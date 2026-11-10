package com.handsfree.probes

import android.Manifest
import android.app.KeyguardManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** A queryable Downloads provider with real truncating streams, not a canned exporter response. */
class TestDownloads : ContentProvider() {
    data class Row(val values: ContentValues, val file: File)
    val rows = ConcurrentHashMap<Uri, Row>()
    val writes = AtomicLong()
    var failWrites = false
    private val ids = AtomicLong()
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = ids.incrementAndGet()
        val result = ContentUris.withAppendedId(uri, id)
        rows[result] = Row(ContentValues(values).apply {
            put(MediaStore.Downloads._ID, id)
            put(MediaStore.Downloads.OWNER_PACKAGE_NAME, context!!.packageName)
        }, File(context!!.cacheDir, "download-$id"))
        return result
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        val columns = projection ?: arrayOf(MediaStore.Downloads._ID)
        val result = MatrixCursor(columns)
        rows.filter { (key, row) ->
            if (uri != MediaStore.Downloads.EXTERNAL_CONTENT_URI) key == uri
            else selectionArgs == null || (
                row.values.getAsString(MediaStore.Downloads.DISPLAY_NAME) == selectionArgs[0] &&
                row.values.getAsString(MediaStore.Downloads.RELATIVE_PATH) == selectionArgs[1] &&
                row.values.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME) == selectionArgs[2])
        }.values.forEach { row -> result.addRow(columns.map { row.values.get(it) }) }
        return result
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failWrites && mode != "r") throw java.io.FileNotFoundException("storage unavailable")
        if (mode != "r") writes.incrementAndGet()
        return ParcelFileDescriptor.open(rows.getValue(uri).file, ParcelFileDescriptor.parseMode(mode))
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        rows.getValue(uri).values.putAll(values)
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        rows.remove(uri)?.file?.delete()
        return 1
    }
    override fun getType(uri: Uri): String? = rows[uri]?.values?.getAsString(MediaStore.Downloads.MIME_TYPE)
    fun text(uri: Uri) = rows.getValue(uri).file.readText()
    fun exportedEvents(): List<JSONObject> = rows.values.filter {
        it.values.getAsString(MediaStore.Downloads.MIME_TYPE) == "application/json"
    }.flatMap { row ->
        val array = JSONObject(row.file.readText()).getJSONArray("events")
        (0 until array.length()).map(array::getJSONObject)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class AutoExportTest : ProbeTest() {
    private lateinit var downloads: TestDownloads
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        downloads = TestDownloads().also {
            it.attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, it)
        }
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }
    @After fun resetMain() { Dispatchers.resetMain() }

    private fun awaitEvent(event: String, reason: String? = null): JSONObject = runBlocking {
        withTimeout(15_000) {
            var found: JSONObject? = null
            while (found == null) {
                found = runCatching { downloads.exportedEvents().lastOrNull {
                    it.getString("event") == event && (reason == null || it.getString("value").contains("reason=$reason;"))
                } }.getOrNull()
                if (found == null) delay(10)
            }
            found
        }
    }

    @Test fun successfulRunExportsQueuedResultsAndTheAutoExportOutcome() {
        controller.start("T12", ProbeConfig())
        assertEquals("SUCCESS", awaitEvent("auto_export", "run_end").getString("outcome"))
        assertTrue(downloads.exportedEvents().any { it.getString("step") == "snapshot" })
        assertEquals(2, downloads.rows.size)
    }

    @Test fun deniedStartIsAResultAndIsAutomaticallyExported() {
        controller.start("T1a", ProbeConfig())
        awaitEvent("auto_export", "run_end")
        assertTrue(downloads.exportedEvents().any { it.getString("outcome") == "CONSENT_DENIED" })
    }

    @Test fun unsupportedRunIsAutomaticallyExported() {
        controller.start("T13", ProbeConfig())
        awaitEvent("auto_export", "run_end")
        assertTrue(downloads.exportedEvents().any { it.getString("event") == "api_33_required" })
    }

    @Test fun stopCancelInterruptAndRevocationExportAfterCancellationFinalizers() {
        for (reason in listOf("user", "cancel", "call_state", "consent_revoked")) {
            controller.repo.emit("T12", "test", "before_$reason")
            if (reason == "call_state") controller.interrupt(reason) else controller.stop(reason)
            awaitEvent("auto_export", "interrupt:$reason")
            assertTrue(downloads.exportedEvents().any { it.getString("event") == "before_$reason" })
        }
    }

    @Test fun cancellingInterruptingAndRevokingConsentExportActiveRunFinalizers() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        for (reason in listOf("user", "call_state", "consent_revoked")) {
            controller.setConsent(Manifest.permission.RECORD_AUDIO, true, "T3")
            controller.start("T3", ProbeConfig()) // Waiting for the five-second background trial.
            when (reason) {
                "consent_revoked" -> controller.setConsent(Manifest.permission.RECORD_AUDIO, false, "T3")
                "call_state" -> controller.interrupt(reason)
                else -> controller.stop(reason)
            }
            awaitEvent("auto_export", "interrupt:$reason")
            val cancelled = downloads.exportedEvents().count { it.getString("event") == "cancelled" && it.getString("experimentId") == "T3" }
            assertEquals(listOf("user", "call_state", "consent_revoked").indexOf(reason) + 1, cancelled)
        }
    }

    @Test fun activityStopExportsNewEventsButAnUnchangedStopDoesNoIo() {
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).setup()
        try {
            controller.repo.emit("T12", "manual", "stop_marker")
            activity.pause().stop()
            awaitEvent("auto_export", "activity_stop")
            assertTrue(downloads.exportedEvents().any { it.getString("event") == "stop_marker" })
            // Join an explicit unchanged stop after the lifecycle-triggered save has completed.
            runBlocking { controller.exports.automatically("unchanged", "T12", onlyIfChanged = true).join() }
            val writes = downloads.writes.get()
            activity.restart().start().resume().pause().stop()
            runBlocking { controller.exports.automatically("unchanged_again", "T12", onlyIfChanged = true).join() }
            assertEquals(writes, downloads.writes.get())
            assertFalse(downloads.exportedEvents().any { it.getString("value").contains("reason=unchanged;") })
        } finally { activity.destroy() }
    }

    @Test fun unchangedBackgroundAfterRecreatingTheExporterDoesNotRewriteFiles() = runBlocking {
        controller.repo.emit("T12", "manual", "restart_marker")
        controller.exports.automatically("first_save", "T12").join()
        val writes = downloads.writes.get()
        ProbeExports(app, controller.repo).automatically("activity_stop", "T12", onlyIfChanged = true).join()
        assertEquals(writes, downloads.writes.get())
    }

    @Test fun lockedBackgroundStillSavesWithoutPrompting() {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        controller.repo.emit("T12", "manual", "locked_marker")
        runBlocking { controller.exports.automatically("activity_stop", "T12").join() }
        assertEquals("SUCCESS", awaitEvent("auto_export", "activity_stop").getString("outcome"))
    }

    @Test fun storageFailureLogsFailedOutcomeAndNextStopRetries() = runBlocking {
        downloads.failWrites = true
        controller.repo.emit("T12", "manual", "retry_marker")
        controller.exports.automatically("failure", "T12").join()
        assertTrue(controller.repo.snapshot().any { it.event == "auto_export" && it.outcome == "FAILED" })
        downloads.failWrites = false
        controller.exports.automatically("retry", "T12").join()
        assertEquals("SUCCESS", awaitEvent("auto_export", "retry").getString("outcome"))
        assertTrue(downloads.exportedEvents().any { it.getString("event") == "retry_marker" })
        assertEquals(2, downloads.rows.size)
    }

    @Test fun dailyFilesAreUpdatedInPlaceAcrossExporterInstancesAndConcurrentRequests() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        val exporter = ProbeExporter(app, controller.repo, today = { day })
        controller.repo.emit("T12", "manual", "first", "x".repeat(500))
        val first = exporter.export()
        controller.repo.emit("T12", "manual", "second")
        val second = ProbeExporter(app, controller.repo, today = { day }).export()
        assertEquals(first.csv, second.csv)
        assertEquals(first.json, second.json)
        assertTrue(downloads.text(second.csv).contains("second"))
        assertEquals(2, downloads.rows.size)
        coroutineScope { repeat(5) { launch(Dispatchers.IO) { ProbeExporter(app, controller.repo, today = { day }).export() } } }
        assertEquals(2, downloads.rows.size)
        val stem = ProbeExporter.dailyStem(android.os.Build.MODEL, day)
        assertEquals(setOf("$stem.csv", "$stem.json"), downloads.rows.values.map { it.values.getAsString(MediaStore.Downloads.DISPLAY_NAME) }.toSet())
        downloads.rows.values.forEach {
            assertEquals("${android.os.Environment.DIRECTORY_DOWNLOADS}/probes/", it.values.getAsString(MediaStore.Downloads.RELATIVE_PATH))
            assertEquals(0, it.values.getAsInteger(MediaStore.Downloads.IS_PENDING))
        }
        // A shorter replacement must truncate, not append or retain stale JSON/CSV bytes.
        exporter.export(emptyList())
        assertEquals(0, JSONObject(downloads.text(first.json)).getJSONArray("events").length())
        assertFalse(downloads.text(first.csv).contains("first"))
        ProbeExporter(app, controller.repo, today = { day.plusDays(1) }).export()
        assertEquals(4, downloads.rows.size)
    }

    @Test fun dailyNameIsSafeAndLocaleIndependent() {
        assertEquals("probes-pixel-9-pro-20261110", ProbeExporter.dailyStem("Pixel 9 / Pro", LocalDate.of(2026, 11, 10)))
        assertEquals("probes-device-20260102", ProbeExporter.dailyStem(" / ", LocalDate.of(2026, 1, 2)))
    }

    @Test fun consentCheckboxAndSystemPermissionAnswersAreExportedWithCapabilityAndAnswer() {
        val activity = Robolectric.buildActivity(ProbeActivity::class.java, Intent(app, ProbeActivity::class.java).putExtra("experiment", "T3")).setup()
        try {
            val checkbox = allViews(activity.get().window.decorView).filterIsInstance<CheckBox>().first { it.text.contains("RECORD_AUDIO") }
            checkbox.performClick()
            checkbox.performClick()
            activity.get().onRequestPermissionsResult(100, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE), intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED))
            runBlocking { controller.exports.automatically("answers_complete", "T3").join() }
            val events = downloads.exportedEvents()
            for ((event, capability) in listOf("consent_answer" to Manifest.permission.RECORD_AUDIO, "permission_answer" to Manifest.permission.RECORD_AUDIO)) {
                assertTrue(events.any { it.getString("event") == event && it.getString("value") == "capability=$capability;granted=false" && it.getString("outcome") == "DENIED" && it.getString("experimentId") == "T3" })
            }
            assertTrue(events.any { it.getString("event") == "consent_answer" && it.getString("value").endsWith("granted=true") && it.getString("outcome") == "GRANTED" })
            assertTrue(events.any { it.getString("event") == "permission_answer" && it.getString("value") == "capability=${Manifest.permission.READ_PHONE_STATE};granted=true" && it.getString("outcome") == "GRANTED" })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun shareButtonFlushesResultsAndOpensChooserWithTwoReadableUris() {
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).setup()
        try {
            controller.repo.emit("T12", "manual", "share_marker")
            allViews(activity.get().window.decorView).filterIsInstance<Button>().single { it.text == "Share all results" }.performClick()
            var chooser: Intent? = null
            runBlocking {
                withTimeout(15_000) {
                    while (chooser == null) {
                        shadowOf(android.os.Looper.getMainLooper()).idle()
                        chooser = shadowOf(activity.get()).nextStartedActivity
                        if (chooser == null) delay(10)
                    }
                }
            }
            assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
            assertEquals("*/*", send.type)
            val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(2, uris.size)
            assertEquals(listOf("text/csv", "application/json"), uris.map { app.contentResolver.getType(it) })
            assertTrue(uris.all { it.scheme == "content" && it.authority == MediaStore.AUTHORITY })
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(uris, (0 until send.clipData!!.itemCount).map { send.clipData!!.getItemAt(it).uri })
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uris.forEach { uri ->
                assertTrue(app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }.contains("share_marker"))
            }
            assertEquals(2, downloads.rows.size)
            assertTrue(allViews(activity.get().window.decorView).filterIsInstance<Button>().any { it.text == "Export CSV and JSON" })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun mergedManifestDeclaresNoInternetPermission() {
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(Manifest.permission.INTERNET))
    }

    private fun allViews(view: android.view.View): List<android.view.View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
}
