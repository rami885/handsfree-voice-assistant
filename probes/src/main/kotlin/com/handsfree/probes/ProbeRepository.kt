package com.handsfree.probes

import android.content.Context
import android.os.Build
import androidx.room.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject

@Entity(tableName = "probe_events")
data class ProbeEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long = System.currentTimeMillis(),
    val experimentId: String,
    val device: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val step: String,
    val event: String,
    val value: String,
    val outcome: String,
)
@Dao
interface ProbeDao {
    @Insert suspend fun insert(event: ProbeEvent): Long
    @Query("SELECT * FROM probe_events ORDER BY id DESC LIMIT 200") fun observe(): Flow<List<ProbeEvent>>
    @Query("SELECT * FROM probe_events ORDER BY id") suspend fun all(): List<ProbeEvent>
    @Query("SELECT COUNT(*) FROM probe_events WHERE experimentId = :experiment") suspend fun count(experiment: String): Int
}
@Database(entities = [ProbeEvent::class], version = 1, exportSchema = true)
abstract class ProbeDatabase : RoomDatabase() { abstract fun events(): ProbeDao }

@Singleton
class ProbeRepository internal constructor(
    private val context: Context,
    val db: ProbeDatabase,
    private val eventDao: ProbeDao = db.events(),
    writerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context, Room.databaseBuilder(context, ProbeDatabase::class.java, "probes.db").build(),
    )
    private val observedVersions = java.util.concurrent.ConcurrentHashMap<String, String>()
    val scope = CoroutineScope(SupervisorJob() + writerDispatcher)
    private sealed interface Write {
        data class Event(val event: ProbeEvent) : Write
        data class Snapshot(val result: CompletableDeferred<List<ProbeEvent>>) : Write
    }
    private val queue = Channel<Write>(Channel.UNLIMITED)
    private val mutableLines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = mutableLines.asStateFlow()
    val spoken = MutableStateFlow("")
    private val writer = scope.launch {
        for (write in queue) when (write) {
            is Write.Event -> eventDao.insert(write.event)
            is Write.Snapshot -> try { write.result.complete(eventDao.all()) }
                catch (error: Exception) { write.result.completeExceptionally(error) }
        }
    }
    init { writer.invokeOnCompletion { cause -> if (cause != null) queue.cancel(CancellationException("Event writer stopped", cause)) } }
    suspend fun snapshot(): List<ProbeEvent> {
        val result = CompletableDeferred<List<ProbeEvent>>()
        val completion = writer.invokeOnCompletion { cause ->
            if (cause != null) result.completeExceptionally(cause)
        }
        try {
            queue.send(Write.Snapshot(result))
            return result.await()
        } finally { completion.dispose() }
    }
    @Synchronized
    fun display(text: String) { mutableLines.value = (mutableLines.value + text).takeLast(100) }
    fun emit(id: String, step: String, event: String, value: String = "", outcome: String = "OBSERVED") {
        display("$id / $step / $event: $value [$outcome]")
        check(queue.trySend(Write.Event(ProbeEvent(experimentId = id, step = step, event = event, value = value, outcome = outcome))).isSuccess)
    }
    fun deviceInfo(): String = JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
        .put("sdkInt", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE).put("securityPatch", Build.VERSION.SECURITY_PATCH)
        .put("fingerprint", Build.FINGERPRINT).put("probesVersion", packageVersion(context.packageName)).put("appVersions", JSONObject(observedVersions.toMap())).toString()
    fun packageVersion(packageName: String): String {
        val version = try { context.packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { "not visible or installed" }
        observedVersions[packageName] = version
        return version
    }
}
