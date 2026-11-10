package com.handsfree.probes

import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ExportRecoveryTest : ProbeTest() {
    private lateinit var downloads: TestDownloads
    private val day = LocalDate.of(2026, 11, 10)

    @Before fun setUp() {
        downloads = TestDownloads().also {
            it.attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, it)
        }
    }

    private suspend fun existingPair(): ExportedFiles {
        controller.repo.emit("T12", "test", "last_complete", "complete snapshot")
        return ProbeExporter(app, controller.repo, testDispatcher, today = { day }).export()
    }

    private fun assertPreviousPairVisible(files: ExportedFiles, content: List<String>) {
        assertEquals(setOf(files.csv, files.json), downloads.rows.keys)
        listOf(files.csv, files.json).forEachIndexed { index, uri ->
            app.contentResolver.query(uri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null)!!.use {
                assertTrue("Daily file must remain visible", it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            assertEquals("Previous complete bytes must survive", content[index], downloads.text(uri))
        }
    }

    @Test fun cancellationAfterWritingReplacementCsvKeepsPreviousCompletePair(): Unit = runTest {
        val exporter = ProbeExporter(app, controller.repo, testDispatcher, today = { day })
        val previous = existingPair()
        val content = listOf(downloads.text(previous.csv), downloads.text(previous.json))
        controller.repo.emit("T12", "test", "replacement")
        val rewrite = async(testDispatcher, start = CoroutineStart.LAZY) { exporter.export() }
        downloads.beforeWrite = { uri ->
            // Cancellation during the synchronous stream operation is observed by ensureActive.
            downloads.rows.getValue(uri).file.writeText("partial")
            rewrite.cancel()
        }
        rewrite.start()
        rewrite.join()
        assertTrue(rewrite.isCancelled)
        assertPreviousPairVisible(previous, content)
    }

    @Test fun exceptionDuringReplacementJsonKeepsPreviousCompletePair(): Unit = runTest {
        val previous = existingPair()
        val content = listOf(downloads.text(previous.csv), downloads.text(previous.json))
        controller.repo.emit("T12", "test", "replacement")
        var writes = 0
        downloads.beforeWrite = { uri ->
            if (++writes == 2) {
                downloads.rows.getValue(uri).file.writeText("{incomplete")
                throw IOException("disk write interrupted")
            }
        }
        try {
            ProbeExporter(app, controller.repo, testDispatcher, today = { day }).export()
            fail("Expected write failure")
        } catch (_: IOException) { /* expected */ }
        assertEquals(2, writes)
        assertPreviousPairVisible(previous, content)
    }

    @Test fun failurePublishingSecondReplacementRemovesNewItemsAndKeepsPreviousPair(): Unit = runTest {
        val previous = existingPair()
        val content = listOf(downloads.text(previous.csv), downloads.text(previous.json))
        var publications = 0
        downloads.beforeUpdate = { _: Uri, values: ContentValues? ->
            if (values?.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0 && ++publications == 2) {
                throw IOException("publication interrupted")
            }
        }
        try {
            ProbeExporter(app, controller.repo, testDispatcher, today = { day }).export()
            fail("Expected publication failure")
        } catch (_: IOException) { /* expected */ }
        assertEquals(2, publications)
        assertPreviousPairVisible(previous, content)
    }

    private suspend fun interruptedReplacementRecoveredOnLaterDay(afterDelete: Boolean, atStartup: Boolean) {
        val previous = existingPair()
        controller.repo.emit("T12", "test", "newest_complete_snapshot")
        if (afterDelete) {
            downloads.beforeUpdate = { _, values ->
                if (values?.containsKey(MediaStore.Downloads.DISPLAY_NAME) == true) {
                    throw IOException("process stopped between delete and rename")
                }
            }
        } else {
            downloads.beforeDelete = { throw IOException("process stopped between publish and delete") }
        }
        try {
            ProbeExporter(app, controller.repo, testDispatcher, today = { day }).export()
            fail("Expected interrupted replacement")
        } catch (_: IOException) { /* Published complete items survive the failed retirement. */ }
        finally {
            downloads.beforeDelete = null
            downloads.beforeUpdate = null
        }
        val oldUris = setOf(previous.csv, previous.json)
        assertEquals(if (afterDelete) 2 else 4, downloads.rows.size)
        val replacement = downloads.rows.filterKeys { it !in oldUris }.keys
        assertEquals(2, replacement.size)
        val content = replacement.associateWith(downloads::text)
        replacement.forEach {
            assertEquals(0, downloads.rows.getValue(it).values.getAsInteger(MediaStore.Downloads.IS_PENDING))
            assertTrue(downloads.rows.getValue(it).values.getAsString(MediaStore.Downloads.DISPLAY_NAME).contains("-pending-"))
            assertTrue(content.getValue(it).contains("newest_complete_snapshot"))
        }
        fun assertRecovered() {
            assertTrue(oldUris.none(downloads.rows::containsKey))
            val stem = ProbeExporter.dailyStem(android.os.Build.MODEL, day)
            val historical = downloads.rows.filterValues {
                it.values.getAsString(MediaStore.Downloads.DISPLAY_NAME).startsWith(stem)
            }
            assertEquals(replacement, historical.keys)
            assertEquals(setOf("$stem.csv", "$stem.json"), historical.values.map {
                it.values.getAsString(MediaStore.Downloads.DISPLAY_NAME)
            }.toSet())
            replacement.forEach {
                assertEquals(content.getValue(it), downloads.text(it))
                assertEquals(0, downloads.rows.getValue(it).values.getAsInteger(MediaStore.Downloads.IS_PENDING))
            }
        }
        val laterDay = day.plusDays(1)
        val writesBeforeRecovery = downloads.writes.get()
        if (atStartup) {
            // A fresh coordinator, no export of the old date and no content rewrite.
            ProbeExports(app, controller.repo, today = { laterDay }, dispatcher = testDispatcher).recoverOnStartup().join()
            assertRecovered()
            assertEquals(2, downloads.rows.size)
            assertEquals(writesBeforeRecovery, downloads.writes.get())
        } else {
            // Historical reconciliation must finish before creating the next day's files.
            downloads.beforeInsert = { assertRecovered() }
            ProbeExporter(app, controller.repo, testDispatcher, today = { laterDay }).export(emptyList())
            downloads.beforeInsert = null
            assertRecovered()
            assertEquals(4, downloads.rows.size)
        }
        val names = downloads.rows.mapValues { it.value.values.getAsString(MediaStore.Downloads.DISPLAY_NAME) }
        ProbeExports(app, controller.repo, today = { laterDay.plusDays(1) }, dispatcher = testDispatcher).recoverOnStartup().join()
        assertRecovered()
        assertEquals("Repeated recovery must be idempotent", names,
            downloads.rows.mapValues { it.value.values.getAsString(MediaStore.Downloads.DISPLAY_NAME) })
    }

    @Test fun startupOnLaterDayRecoversCrashBetweenPublishAndDelete(): Unit = runTest {
        interruptedReplacementRecoveredOnLaterDay(afterDelete = false, atStartup = true)
    }

    @Test fun startupOnLaterDayRecoversCrashBetweenDeleteAndRename(): Unit = runTest {
        interruptedReplacementRecoveredOnLaterDay(afterDelete = true, atStartup = true)
    }

    @Test fun exportOnLaterDayRecoversCrashBetweenPublishAndDelete(): Unit = runTest {
        interruptedReplacementRecoveredOnLaterDay(afterDelete = false, atStartup = false)
    }

    @Test fun exportOnLaterDayRecoversCrashBetweenDeleteAndRename(): Unit = runTest {
        interruptedReplacementRecoveredOnLaterDay(afterDelete = true, atStartup = false)
    }

    @Test fun recoverySelectsNewestPublishedGenerationPerExtensionAndPreservesUnrelatedFiles(): Unit = runTest {
        val stem = ProbeExporter.dailyStem(android.os.Build.MODEL, day.minusDays(10))
        val path = "${android.os.Environment.DIRECTORY_DOWNLOADS}/probes/"
        fun seed(name: String, generation: Long, pending: Boolean = false,
                 owner: String = app.packageName, relativePath: String = path): Uri {
            val uri = downloads.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, if (pending) 1 else 0)
            })
            downloads.rows.getValue(uri).apply {
                values.put(MediaStore.Downloads.OWNER_PACKAGE_NAME, owner)
                values.put(MediaStore.Downloads.GENERATION_ADDED, generation)
                file.writeText("complete bytes for $uri")
            }
            return uri
        }
        // Generation order deliberately differs from ID/cursor order. JSON needs restoration,
        // while CSV already has its newest content under the daily name.
        val json = seed("$stem-pending-aaaaaaaa.json", 20)
        val oldJson = seed("$stem.json", 10)
        val incomplete = seed("$stem-pending-bbbbbbbb.json", 30, pending = true)
        val csv = seed("$stem.csv", 40)
        val oldCsv = seed("$stem-pending-cccccccc.csv", 35)
        val anotherStem = ProbeExporter.dailyStem("other-model", day.minusMonths(2))
        val anotherDate = seed("$anotherStem-pending-dddddddd.csv", 50)
        val unrelated = listOf(
            seed("$stem.json", 60, owner = "other.app"),
            seed("$stem.csv", 70, relativePath = "Download/elsewhere/"),
            seed("unrelated.json", 80),
        )
        val retained = listOf(json, csv, anotherDate) + unrelated
        val originalBytes = retained.associateWith(downloads::text)
        val unrelatedValues = unrelated.associateWith { ContentValues(downloads.rows.getValue(it).values) }
        ProbeExports(app, controller.repo, today = { day.plusDays(1) }).recoverOnStartup().join()
        assertEquals(retained.toSet(), downloads.rows.keys)
        listOf(oldJson, incomplete, oldCsv).forEach { assertFalse(downloads.rows.containsKey(it)) }
        assertEquals("$stem.json", downloads.rows.getValue(json).values.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals("$stem.csv", downloads.rows.getValue(csv).values.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals("$anotherStem.csv", downloads.rows.getValue(anotherDate).values.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        retained.forEach {
            assertEquals(originalBytes.getValue(it), downloads.text(it))
            assertEquals(0, downloads.rows.getValue(it).values.getAsInteger(MediaStore.Downloads.IS_PENDING))
        }
        unrelated.forEach { assertEquals(unrelatedValues.getValue(it), downloads.rows.getValue(it).values) }
        assertEquals(0, downloads.writes.get())
    }
}
