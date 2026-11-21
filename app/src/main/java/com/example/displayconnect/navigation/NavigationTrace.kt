package com.example.displayconnect.navigation

import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

fun interface NavigationTraceSink {
    fun record(event: String, fields: Map<String, Any?>)
    companion object { val NONE = NavigationTraceSink { _, _ -> } }
}

/** Bounded, best-effort local diagnostic. Caller supplies an app no-backup directory.
 * No network/logcat output, no permissions, no external storage without manual export.
 * Disk failures/drop counts are visible, but never interrupt the GPS session. */
class FileNavigationTrace(private val directory: File, private val fileLimit: Long = 2_000_000,
    private val fileCount: Int = 3) {
    private val queue = ArrayBlockingQueue<() -> Unit>(256)
    private val dropped = AtomicInteger()
    private val _rawNotice = kotlinx.coroutines.flow.MutableStateFlow(RawCaptureNotice())
    val rawNotice: kotlinx.coroutines.flow.StateFlow<RawCaptureNotice> = _rawNotice
    private val activeSessions = AtomicInteger()
    private val _active = kotlinx.coroutines.flow.MutableStateFlow(0)
    val activeFlow: kotlinx.coroutines.flow.StateFlow<Int> = _active
    private val totalDropped = AtomicInteger()
    private val writeErrors = AtomicInteger()
    private var rotations = 0
    private val _status = kotlinx.coroutines.flow.MutableStateFlow("Registro local preparado")
    val statusFlow: kotlinx.coroutines.flow.StateFlow<String> = _status
    @Volatile var status: String = "Registro local preparado"
        private set(value) { field = value; _status.value = value }
    private var fileIndex = 0
    private var current: File? = null
    init {
        require(fileLimit >= 1024 && fileCount in 1..5)
        Thread({
            while (true) {
                val job = queue.take()
                try { job() } catch (_: Exception) { writeErrors.incrementAndGet(); status = "Fallo de escritura; registro incompleto" }
            }
        }, "navigation-private-trace").apply { isDaemon = true; start() }
    }
    fun newSession(): NavigationTraceSink {
        val session = UUID.randomUUID().toString()
        return NavigationTraceSink { event, fields ->
            try {
                _rawNotice.value = RawCapturePresentation.update(_rawNotice.value,event,fields)
                if(event == "session_start") _active.value = activeSessions.incrementAndGet()
                if(event == "session_end") _active.value = activeSessions.updateAndGet { (it-1).coerceAtLeast(0) }
                val row = JSONObject()
                row.put("schema",1).put("session",session).put("event",event)
                    .put("wall_ms",System.currentTimeMillis()).put("monotonic_ns",System.nanoTime())
                for ((key,value) in fields) row.put(key,safe(value))
                val line = row.toString()+"\n"
                if (line.length > 64_000 || !queue.offer { append(line) }) {
                    dropped.incrementAndGet(); totalDropped.incrementAndGet(); status = "Registro incompleto: cola llena o evento demasiado grande"
                }
            } catch (_: Exception) { dropped.incrementAndGet(); totalDropped.incrementAndGet(); status = "Registro incompleto: evento no guardado" }
        }
    }
    private fun safe(value: Any?): Any = when(value) {
        null -> JSONObject.NULL
        is Double -> if(value.isFinite())value else JSONObject.NULL
        is Float -> if(value.isFinite())value else JSONObject.NULL
        is String, is Boolean, is Number -> value
        else -> value.toString()
    }
    private fun files() = directory.listFiles()?.filter { it.name.matches(Regex("trace-[0-9]+\\.jsonl")) }
        ?.sortedBy { it.lastModified() } ?: emptyList()
    private fun append(line: String) {
        directory.mkdirs()
        val lost = dropped.getAndSet(0)
        val note = if(lost>0) JSONObject().put("schema",1).put("event","dropped_events").put("count",lost).toString()+"\n" else ""
        val payload = (note+line).toByteArray(Charsets.UTF_8)
        if(payload.size>fileLimit) {
            totalDropped.incrementAndGet(); status="Registro incompleto: evento supera limite"; return
        }
        var f = current
        if (f == null || !f.exists() || f.length()+payload.size > fileLimit) {
            val all = files()
            if (current == null) fileIndex = (all.mapNotNull { it.name.removePrefix("trace-").removeSuffix(".jsonl").toIntOrNull() }.maxOrNull() ?: -1)+1
            f = File(directory,"trace-${fileIndex++}.jsonl")
            while (files().size >= fileCount) {
                files().first().let { check(it.delete()) }; rotations++
            }
            f.writeText(""); current = f
        }
        f.appendBytes(payload)
        status = if(totalDropped.get()>0 || writeErrors.get()>0) "Registro incompleto: revisa estado al exportar" else "Registro privado guardado en la app"
    }
    /** Call on IO, never UI. Queue barrier includes all earlier events or fails loudly. */
    fun snapshot(): ByteArray = barrier {
        check(activeSessions.get()==0) { "Finaliza la navegacion y espera al cierre del GPS" }
        val all=files(); check(all.isNotEmpty()) { "No hay registro guardado" }
        val bytes=all.fold(ByteArray(0)) { out,f -> out+f.readBytes() }
        val tail=JSONObject().put("schema",1).put("event","export_status")
            .put("pending_dropped",dropped.get()).put("total_dropped",totalDropped.get())
            .put("write_errors",writeErrors.get()).put("old_files_replaced",rotations)
            .put("counter_scope","current_process").put("retention_bounded",true).put("status",status).toString()+"\n"
        bytes+tail.toByteArray(Charsets.UTF_8)
    }
    fun clear() = barrier {
        check(activeSessions.get()==0) { "Finaliza la navegacion y espera al cierre del GPS" }
        files().forEach { check(it.delete()) }; current=null; dropped.set(0); totalDropped.set(0); writeErrors.set(0); rotations=0
        status="Registro privado borrado"; Unit
    }
    private fun <T> barrier(work: () -> T): T {
        val result=CompletableFuture<T>()
        val job = { try { result.complete(work()) } catch(e:Exception) { result.completeExceptionally(e) }; Unit }
        check(queue.offer(job,5,TimeUnit.SECONDS)) { "Registro ocupado; vuelve a intentarlo" }
        return result.get(10,TimeUnit.SECONDS)
    }
}
