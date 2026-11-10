package com.handsfree.probes

import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.*
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
        return ProbeExporter(app, controller.repo, today = { day }).export()
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

    @Test fun cancellationAfterWritingReplacementCsvKeepsPreviousCompletePair(): Unit = runBlocking {
        val exporter = ProbeExporter(app, controller.repo, today = { day })
        val previous = existingPair()
        val content = listOf(downloads.text(previous.csv), downloads.text(previous.json))
        controller.repo.emit("T12", "test", "replacement")
        val rewrite = async(Dispatchers.IO, start = CoroutineStart.LAZY) { exporter.export() }
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

    @Test fun exceptionDuringReplacementJsonKeepsPreviousCompletePair(): Unit = runBlocking {
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
            ProbeExporter(app, controller.repo, today = { day }).export()
            fail("Expected write failure")
        } catch (_: IOException) { /* expected */ }
        assertEquals(2, writes)
        assertPreviousPairVisible(previous, content)
    }

    @Test fun failurePublishingSecondReplacementRemovesNewItemsAndKeepsPreviousPair(): Unit = runBlocking {
        val previous = existingPair()
        val content = listOf(downloads.text(previous.csv), downloads.text(previous.json))
        var publications = 0
        downloads.beforeUpdate = { _: Uri, values: ContentValues? ->
            if (values?.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0 && ++publications == 2) {
                throw IOException("publication interrupted")
            }
        }
        try {
            ProbeExporter(app, controller.repo, today = { day }).export()
            fail("Expected publication failure")
        } catch (_: IOException) { /* expected */ }
        assertEquals(2, publications)
        assertPreviousPairVisible(previous, content)
    }
}
