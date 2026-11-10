package com.handsfree.probes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-owned work: stopping a run or destroying its Activity cannot cancel a save. */
class ProbeExports(private val context: Context, private val repo: ProbeRepository) {
    private val exporter = ProbeExporter(context, repo)
    private val mutex = Mutex()
    private val preferences = context.getSharedPreferences("probe_exports", Context.MODE_PRIVATE)
    private var savedThrough = preferences.getLong("saved_through", 0L)

    fun automatically(reason: String, experiment: String, finishing: List<Job> = emptyList(), onlyIfChanged: Boolean = false): Job = repo.scope.launch {
        finishing.joinAll()
        save(reason, experiment, automatic = true, onlyIfChanged = onlyIfChanged)
    }

    suspend fun save(reason: String, experiment: String, automatic: Boolean = false, onlyIfChanged: Boolean = false): ExportedFiles? = mutex.withLock {
        val events = repo.snapshot() // FIFO barrier: includes every event queued before the request.
        if (onlyIfChanged && (events.lastOrNull()?.id ?: 0) <= savedThrough) return@withLock null
        try {
            listOf("com.google.android.googlequicksearchbox", "com.google.android.inputmethod.latin", "com.whatsapp").forEach(repo::packageVersion)
            val files = exporter.export(events)
            repo.emit(experiment, "export", if (automatic) "auto_export" else "export",
                "reason=$reason;rows=${files.rows};csv=${files.csv};json=${files.json}", "SUCCESS")
            // Include the successful export outcome itself without creating another dirty event.
            val withOutcome = repo.snapshot()
            val complete = exporter.export(withOutcome)
            savedThrough = withOutcome.lastOrNull()?.id ?: 0
            preferences.edit().putLong("saved_through", savedThrough).apply()
            complete
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            repo.emit(experiment, "export", if (automatic) "auto_export" else "export",
                "reason=$reason;error=${error.javaClass.simpleName}", "FAILED")
            null // Leave the watermark unchanged so the next trigger retries.
        }
    }

    fun shareIntent(files: ExportedFiles): Intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*" // CSV and JSON have different top-level MIME types.
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(files.csv, files.json))
        clipData = ClipData.newUri(context.contentResolver, "Probe results", files.csv).apply {
            addItem(ClipData.Item(files.json))
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
