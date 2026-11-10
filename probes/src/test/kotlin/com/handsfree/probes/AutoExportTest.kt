package com.handsfree.probes

import android.Manifest
import android.app.KeyguardManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.CancellationSignal
import android.app.role.RoleManager
import androidx.core.content.FileProvider
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
    val queries = AtomicLong()
    @Volatile var beforeInsert: (() -> Unit)? = null
    @Volatile var beforeWrite: ((Uri) -> Unit)? = null
    @Volatile var beforeUpdate: ((Uri, ContentValues?) -> Unit)? = null
    private val ids = AtomicLong()
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        beforeInsert?.invoke()
        val id = ids.incrementAndGet()
        val result = ContentUris.withAppendedId(uri, id)
        rows[result] = Row(ContentValues(values).apply {
            put(MediaStore.Downloads._ID, id)
            put(MediaStore.Downloads.OWNER_PACKAGE_NAME, context!!.packageName)
        }, File(context!!.cacheDir, "download-$id"))
        return result
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        return queryRows(uri, projection, selectionArgs, includePending = false)
    }
    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): MatrixCursor {
        return queryRows(uri, projection, queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
            queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE)
    }
    private fun queryRows(uri: Uri, projection: Array<out String>?, selectionArgs: Array<out String>?, includePending: Boolean): MatrixCursor {
        queries.incrementAndGet()
        val columns = projection ?: arrayOf(MediaStore.Downloads._ID)
        val result = MatrixCursor(columns)
        rows.filter { (key, row) ->
            (includePending || row.values.getAsInteger(MediaStore.Downloads.IS_PENDING) != 1) &&
            (if (uri != MediaStore.Downloads.EXTERNAL_CONTENT_URI) key == uri
            else selectionArgs == null || (when (selectionArgs.size) {
                2 -> row.values.getAsString(MediaStore.Downloads.RELATIVE_PATH) == selectionArgs[0] &&
                    row.values.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME) == selectionArgs[1]
                else -> row.values.getAsString(MediaStore.Downloads.DISPLAY_NAME) == selectionArgs[0] &&
                    row.values.getAsString(MediaStore.Downloads.RELATIVE_PATH) == selectionArgs[1] &&
                    row.values.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME) == selectionArgs[2]
            }))
        }.values.forEach { row -> result.addRow(columns.map { row.values.get(it) }) }
        return result
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failWrites && mode != "r") throw java.io.FileNotFoundException("storage unavailable")
        if (mode != "r") { beforeWrite?.invoke(uri); writes.incrementAndGet() }
        return ParcelFileDescriptor.open(rows.getValue(uri).file, ParcelFileDescriptor.parseMode(mode))
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        beforeUpdate?.invoke(uri, values)
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
        it.values.getAsInteger(MediaStore.Downloads.IS_PENDING) != 1 &&
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
    private lateinit var mainDispatcher: TestDispatcher
    @Before fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
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
                mainDispatcher.scheduler.runCurrent()
                shadowOf(android.os.Looper.getMainLooper()).idle()
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
            mainDispatcher.scheduler.runCurrent()
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

    @Test fun dailyFilesAreReplacedAcrossExporterInstancesAndConcurrentRequests() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        val exporter = ProbeExporter(app, controller.repo, today = { day })
        controller.repo.emit("T12", "manual", "first", "x".repeat(500))
        val first = exporter.export()
        controller.repo.emit("T12", "manual", "second")
        val second = ProbeExporter(app, controller.repo, today = { day }).export()
        assertNotEquals(first.csv, second.csv)
        assertNotEquals(first.json, second.json)
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
        // A shorter replacement must not append or retain stale JSON/CSV bytes.
        val shorter = exporter.export(emptyList())
        assertEquals(0, JSONObject(downloads.text(shorter.json)).getJSONArray("events").length())
        assertFalse(downloads.text(shorter.csv).contains("first"))
        ProbeExporter(app, controller.repo, today = { day.plusDays(1) }).export()
        assertEquals(4, downloads.rows.size)
    }

    @Test fun dailyNameIsSafeAndLocaleIndependent() {
        assertEquals("probes-pixel-9-pro-20261110", ProbeExporter.dailyStem("Pixel 9 / Pro", LocalDate.of(2026, 11, 10)))
        assertEquals("probes-device-20260102", ProbeExporter.dailyStem(" / ", LocalDate.of(2026, 1, 2)))
    }

    @Test fun consentCheckboxAutomaticallyExportsItsAnswerWithoutAnotherTrigger() {
        val activity = Robolectric.buildActivity(ProbeActivity::class.java, Intent(app, ProbeActivity::class.java).putExtra("experiment", "T3")).setup()
        try {
            val checkbox = allViews(activity.get().window.decorView).filterIsInstance<CheckBox>().first { it.text.contains("RECORD_AUDIO") }
            checkbox.performClick() // Grant has no revocation/interrupt export to hide a missing consent trigger.
            awaitEvent("auto_export", "consent_answer")
            assertTrue(downloads.exportedEvents().any { it.getString("event") == "consent_answer" && it.getString("value") == "capability=${Manifest.permission.RECORD_AUDIO};granted=true" && it.getString("experimentId") == "T3" && it.getString("outcome") == "GRANTED" })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun runtimePermissionCallbackAutomaticallyExportsGrantAndDenialWithoutAnotherTrigger() {
        val activity = Robolectric.buildActivity(ProbeActivity::class.java, Intent(app, ProbeActivity::class.java).putExtra("experiment", "T3")).setup()
        try {
            activity.get().onRequestPermissionsResult(100, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE), intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED))
            awaitEvent("auto_export", "permission_answer")
            // Wait for both callbacks' own saves; never request a helper export.
            runBlocking { withTimeout(15_000) { while (runCatching { downloads.exportedEvents().none { it.getString("event") == "permission_answer" && it.getString("value") == "capability=${Manifest.permission.READ_PHONE_STATE};granted=true" } }.getOrDefault(true)) delay(10) } }
            val events = downloads.exportedEvents()
            assertTrue(events.any { it.getString("event") == "permission_answer" && it.getString("value") == "capability=${Manifest.permission.RECORD_AUDIO};granted=false" && it.getString("outcome") == "DENIED" && it.getString("experimentId") == "T3" })
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
                        mainDispatcher.scheduler.runCurrent()
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
            assertTrue(app.contentResolver.getType(uris[0]) in setOf("text/csv", "text/comma-separated-values"))
            assertEquals("application/json", app.contentResolver.getType(uris[1]))
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(0, send.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            assertEquals(uris, (0 until send.clipData!!.itemCount).map { send.clipData!!.getItemAt(it).uri })
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The receiver deliberately waits until the daily files have been overwritten.
            controller.repo.emit("T12", "manual", "later_private_marker")
            runBlocking { controller.exports.automatically("after_share", "T12").join() }
            assertTrue(downloads.exportedEvents().any { it.getString("event") == "later_private_marker" })
            uris.forEach { uri ->
                val text = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                assertTrue(text.contains("share_marker"))
                assertFalse(text.contains("later_private_marker"))
            }
            assertTrue(uris.all { it.scheme == "content" && it.authority == "${app.packageName}.probe-shares" })
            val second = runBlocking { controller.exports.save("second_share", "T12", forSharing = true)!! }
            assertFalse(uris.contains(second.csv))
            assertFalse(uris.contains(second.json))
            assertTrue(app.contentResolver.openInputStream(second.json)!!.bufferedReader().use { it.readText() }.contains("later_private_marker"))
            assertEquals(2, downloads.rows.size)
            assertTrue(allViews(activity.get().window.decorView).filterIsInstance<Button>().any { it.text == "Export CSV and JSON" })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun microphoneDenialAutomaticallyExportsWithoutAnotherTrigger() {
        val service = Robolectric.buildService(MicrophoneService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, MicrophoneService::class.java).putExtra("experiment", "T8"), 0, 1)
            awaitEvent("auto_export", "microphone_denied")
            assertTrue(downloads.exportedEvents().any { it.getString("event") == "permission_or_consent_denied" && it.getString("experimentId") == "T8" })
        } finally { service.destroy() }
    }

    @Test @Config(shadows = [UnsupportedBufferAudioRecordShadow::class])
    fun microphoneWorkerFinalizerAutomaticallyExportsWithoutAnotherTrigger() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        controller.consents.set(Manifest.permission.RECORD_AUDIO, true)
        UnsupportedBufferAudioRecordShadow.minimum = 0
        val service = Robolectric.buildService(MicrophoneService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, MicrophoneService::class.java).putExtra("experiment", "T3"), 0, 1)
            awaitEvent("auto_export", "microphone_end")
            assertTrue(downloads.exportedEvents().any { it.getString("event") == "format_unavailable" })
        } finally { service.destroy() }
    }

    @Test @Config(shadows = [RejectedForegroundServiceShadow::class])
    fun microphoneForegroundFailureAutomaticallyExportsWithoutAnotherTrigger() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        controller.consents.set(Manifest.permission.RECORD_AUDIO, true)
        val service = Robolectric.buildService(MicrophoneService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, MicrophoneService::class.java).putExtra("experiment", "T3"), 0, 1)
            awaitEvent("auto_export", "microphone_failed")
            assertTrue(downloads.exportedEvents().any { it.getString("event") == "start_rejected" })
        } finally { service.destroy() }
    }

    @Test fun microphoneDestructionAutomaticallyExportsWithoutAnotherTrigger() {
        val service = Robolectric.buildService(MicrophoneService::class.java).create()
        controller.repo.emit("T3", "test", "before_microphone_destroy")
        service.destroy()
        awaitEvent("auto_export", "microphone_destroyed")
        assertTrue(downloads.exportedEvents().any { it.getString("event") == "before_microphone_destroy" })
    }

    @Test fun voiceTaskFinalizerAutomaticallyExportsWithoutAnotherTrigger() {
        val session = ProbeSession(app)
        controller.sessionShown("T2", session) // Its own permission-denied terminal path.
        awaitEvent("auto_export", "session_voice_end")
        assertTrue(downloads.exportedEvents().any { it.getString("step") == "session_voice" && it.getString("event") == "permission_or_consent_denied" })
    }

    @Test fun sessionActionFinalizerAutomaticallyExportsWithoutAnotherTrigger() {
        val session = ProbeSession(app)
        controller.session = session
        controller.launchFromSession("unknown", session)
        awaitEvent("auto_export", "action_end")
        assertTrue(downloads.exportedEvents().any { it.getString("step") == "intent" && it.getString("event") == "unavailable" })
    }

    @Test fun ringingTaskFinalizerAutomaticallyExportsWithoutAnotherTrigger() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.RECORD_AUDIO)
        listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.RECORD_AUDIO).forEach { controller.consents.set(it, true) }
        controller.start("T1b", ProbeConfig())
        mainDispatcher.scheduler.runCurrent()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(controller.repo.lines.value.joinToString("\n"), controller.screeningEnabled)
        controller.screened(null)
        mainDispatcher.scheduler.advanceTimeBy(2_001)
        mainDispatcher.scheduler.runCurrent()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2_001, java.util.concurrent.TimeUnit.MILLISECONDS)
        awaitEvent("auto_export", "ringing_end")
        assertTrue(downloads.exportedEvents().any { it.getString("event") == "show_timeout" })
    }

    @Test fun interruptionWaitsForABlockedCancellationFinalizerBeforeTakingAnySnapshot() = runTest {
        // This test has no Activity; run the injected cancellation task immediately.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val database = androidx.room.Room.inMemoryDatabaseBuilder(app, ProbeDatabase::class.java).build()
        val delegate = database.events()
        val snapshots = AtomicLong()
        val stored = mutableListOf<ProbeEvent>()
        val dao = object : ProbeDao by delegate {
            // Keep the writer fully on the test scheduler: a Room insert suspended on IO
            // would otherwise hide an early snapshot request when the join is removed.
            override suspend fun insert(event: ProbeEvent): Long {
                val id = stored.size.toLong() + 1
                stored += event.copy(id = id)
                return id
            }
            override suspend fun all(): List<ProbeEvent> { snapshots.incrementAndGet(); return stored.toList() }
        }
        val repo = ProbeRepository(app, database, dao, dispatcher)
        val local = ProbeController(app, repo)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finishing = local.scope.launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { entered.complete(Unit); release.await(); repo.emit("T12", "test", "delayed_finalizer") } }
        }
        ProbeController::class.java.getDeclaredField("actionJob").apply { isAccessible = true }.set(local, finishing)
        try {
            local.interrupt("gate")
            entered.await()
            runCurrent()
            assertEquals("No snapshot before cancellation finalizers finish", 0, snapshots.get())
            assertEquals(0, downloads.writes.get())
            release.complete(Unit)
            // Drive the controlled writer while the exporter performs its IO on the real dispatcher.
            withContext(Dispatchers.IO) {
                withTimeout(15_000) {
                    while (runCatching { downloads.exportedEvents().none { it.getString("event") == "auto_export" && it.getString("value").contains("reason=interrupt:gate;") } }.getOrDefault(true)) {
                        testScheduler.runCurrent()
                        delay(10)
                    }
                }
            }
            assertTrue(downloads.exportedEvents().any { it.getString("event") == "delayed_finalizer" })
        } finally {
            release.complete(Unit)
            local.scope.cancel()
            repo.scope.cancel()
            runCurrent()
            database.close()
        }
    }

    @Test fun shareProviderIsPrivateAndRejectsFilesOutsideTheSnapshotDirectory() {
        val provider = app.packageManager.resolveContentProvider("${app.packageName}.probe-shares", PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val unrelated = File(app.cacheDir, "unrelated.txt").apply { writeText("private") }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(app, provider.authority, unrelated)
        }
    }

    @Test fun startupDeletesIncompleteOwnedDailyItemsFromAnyDayWithoutPublishingThem(): Unit = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        val stem = ProbeExporter.dailyStem(android.os.Build.MODEL, day)
        fun abandoned(name: String, path: String = "${android.os.Environment.DIRECTORY_DOWNLOADS}/probes/"): Uri = downloads.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, path)
            put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith("json")) "application/json" else "text/csv")
            put(MediaStore.Downloads.IS_PENDING, 1)
        })
        // A previous exporter/process died after insertion. It never reached publication or cleanup.
        val csv = abandoned("$stem.csv")
        val json = abandoned("$stem.json")
        val staging = abandoned("$stem-pending-${java.util.UUID.randomUUID()}.json")
        val older = abandoned("probes-old-device-20260101.csv")
        val foreign = abandoned("$stem.json").also { downloads.rows.getValue(it).values.put(MediaStore.Downloads.OWNER_PACKAGE_NAME, "other.app") }
        val elsewhere = abandoned("$stem.csv", "Download/elsewhere/")
        val otherName = abandoned("unrelated.json")
        // Exercise the same recovery entry point used by Application.onCreate, with no export.
        ProbeExports(app, controller.repo).recoverOnStartup().join()
        listOf(csv, json, staging, older).forEach { assertFalse(downloads.rows.containsKey(it)) }
        assertEquals(3, downloads.rows.size)
        listOf(foreign, elsewhere, otherName).forEach { assertEquals(1, downloads.rows.getValue(it).values.getAsInteger(MediaStore.Downloads.IS_PENDING)) }
        assertEquals(0, downloads.writes.get())
    }

    @Test fun saveCrossingMidnightKeepsTheOutcomeAndReturnedUrisInTheSamePair() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        var currentDay = day
        downloads.beforeWrite = { currentDay = day.plusDays(1) }
        controller.repo.emit("T12", "test", "midnight_marker")
        val files = ProbeExports(app, controller.repo, today = { currentDay }).save("midnight", "T12")!!
        assertEquals(2, downloads.rows.size)
        val events = downloads.exportedEvents()
        val outcome = events.single { it.getString("event") == "export" && it.getString("value").contains("reason=midnight;") }
        val stem = ProbeExporter.dailyStem(android.os.Build.MODEL, day)
        assertTrue(outcome.getString("value").contains("csv=$stem.csv;json=$stem.json"))
        assertTrue(downloads.rows.values.all { it.values.getAsString(MediaStore.Downloads.DISPLAY_NAME).contains("20261110") })
        assertTrue(downloads.text(files.csv).contains("midnight_marker"))
        assertTrue(downloads.text(files.csv).contains("reason=midnight;"))
    }

    @Test fun returningFromSettingsAfterRecreationRecordsTheOriginalExperimentOnce() {
        for ((experiment, button, capability) in listOf(
            Triple("T5", "Notification access settings", ProbeNotificationListener.ACCESS),
            Triple("T12", "Assistant system settings", RoleManager.ROLE_ASSISTANT),
            Triple("T1b", "Request call screening role", RoleManager.ROLE_CALL_SCREENING),
        )) {
            shadowOf(app.getSystemService(RoleManager::class.java)).addAvailableRole(RoleManager.ROLE_CALL_SCREENING)
            val activity = Robolectric.buildActivity(ProbeActivity::class.java).setup()
            activity.get().show(experiment) // Navigation differs from the Activity's launch intent.
            allViews(activity.get().window.decorView).filterIsInstance<Button>().single { it.text == button }.performClick()
            val state = Bundle()
            activity.pause().saveInstanceState(state).stop().destroy()
            if (capability == ProbeNotificationListener.ACCESS) {
                shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationListenerAccessGranted(ComponentName(app, ProbeNotificationListener::class.java), true)
            } else {
                shadowOf(app.getSystemService(RoleManager::class.java)).addAvailableRole(capability)
                shadowOf(app.getSystemService(RoleManager::class.java)).addHeldRole(capability)
            }
            val restored = Robolectric.buildActivity(ProbeActivity::class.java).create(state).start()
            try {
                assertTrue(allViews(restored.get().window.decorView).filterIsInstance<Button>().any { it.text == button })
                restored.get().show("T3") // Attribute the answer to the experiment that opened settings.
                restored.resume()
                awaitEvent("auto_export", "permission_answer")
                val answers = runBlocking { controller.repo.snapshot() }.filter { it.event == "permission_answer" && it.value.startsWith("capability=$capability;") }
                assertEquals(1, answers.size)
                assertEquals(experiment, answers.single().experimentId)
                assertEquals("GRANTED", answers.single().outcome)
                restored.pause().resume()
                assertEquals(1, runBlocking { controller.repo.snapshot() }.count { it.event == "permission_answer" && it.value.startsWith("capability=$capability;") })
            } finally { restored.pause().stop().destroy() }
        }
    }

    @Test fun concurrentFirstExportsWaitForTheFirstInsertionAndCreateOnlyOnePair() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val firstInsert = java.util.concurrent.atomic.AtomicBoolean(true)
        downloads.beforeInsert = {
            if (firstInsert.getAndSet(false)) {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val day = LocalDate.of(2026, 11, 10)
        val first = async(Dispatchers.IO) { ProbeExporter(app, controller.repo, today = { day }).export() }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val queriesWhileFirstIsBlocked = downloads.queries.get()
            val second = async(start = CoroutineStart.UNDISPATCHED) { ProbeExporter(app, controller.repo, Dispatchers.Unconfined, today = { day }).export(emptyList(), day) }
            assertFalse(second.isCompleted)
            assertEquals(queriesWhileFirstIsBlocked, downloads.queries.get())
            assertEquals(0, downloads.rows.size)
            release.countDown()
            val firstFiles = first.await()
            val secondFiles = second.await()
            assertNotEquals(firstFiles.csv, secondFiles.csv)
            assertNotEquals(firstFiles.json, secondFiles.json)
            assertEquals(2, downloads.rows.size)
        } finally { release.countDown(); first.join() }
    }

    @Test fun mergedManifestDeclaresNoInternetPermission() {
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(Manifest.permission.INTERNET))
    }

    private fun allViews(view: android.view.View): List<android.view.View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
}

@org.robolectric.annotation.Implements(android.app.Service::class)
class RejectedForegroundServiceShadow : org.robolectric.shadows.ShadowService() {
    @org.robolectric.annotation.Implementation
    override fun startForeground(id: Int, notification: android.app.Notification) {
        throw SecurityException("foreground start rejected")
    }
}
