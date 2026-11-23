package com.example.displayconnect.network

import java.io.File
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** One private, bounded bench record. No automatic upload and no firmware/GPS bytes. */
class OtaTrialLog(private val file: File, private val clock: () -> Long,
                  private val deviceState: () -> Map<String, Any?> = { emptyMap() }) {
    private val writer = ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(256))
    @Volatile private var failed = false
    @Volatile private var active = false
    @Volatile private var accepting = false
    @Volatile private var exported = false
    fun begin(fields: Map<String, Any?>) {
        val opening=writer.submit {
            check(!file.exists() || exported) { "Guarda el registro OTA anterior antes de otra prueba" }
            file.parentFile?.mkdirs(); file.writeText("")
            failed = false; active = true; accepting = true; exported = false
            append("session_start", fields)
            check(!failed) { "No se puede abrir el registro OTA" }
        }
        try {opening.get(5,TimeUnit.SECONDS)} catch(e:Exception) {
            failed=true;accepting=false;opening.cancel(true)
            try {writer.submit {failed=true;accepting=false;active=false}} catch (_:Exception) {active=false}
            throw e
        }
    }
    fun record(event: String, fields: Map<String, Any?> = emptyMap()) {
        if (!accepting) return
        val time = clock()
        val state = try {deviceState()} catch (_:Exception) {failed=true;emptyMap()}
        try {writer.submit { append(event, fields + state, time) }} catch (_:Exception) {failed=true}
    }
    private fun append(event: String, fields: Map<String, Any?>, time: Long = clock()) {
        if (failed) return
        try {
            val row = JSONObject().put("schema", 1).put("event", event).put("elapsed_ms", time)
            fields.forEach { (k, v) -> row.put(k, v ?: JSONObject.NULL) }
            val line = row.toString() + "\n"
            check(file.length() + line.toByteArray().size <= 1_000_000) { "Registro OTA lleno" }
            file.appendText(line)
        } catch (_: Exception) { failed = true }
    }
    fun end() {
        if (!accepting) return
        accepting = false
        try {writer.submit {append("session_end",emptyMap());active=false}} catch (_:Exception) {failed=true;active=false}
    }
    fun available(): Boolean = file.isFile
    fun snapshot(): ByteArray = writer.submit<ByteArray> {
        check(!active) { "Espera al cierre OTA" }
        check(file.isFile) { "No hay registro OTA" }
        val lines=file.readLines()
        val ended=runCatching{JSONObject(lines.last()).optString("event")=="session_end"}.getOrDefault(false)
        val tail = JSONObject().put("event", "export_status").put("complete", !failed && ended)
            .put("process_active", false).put("note", "Un registro interrumpido no confirma arranque")
        file.readBytes() + (tail.toString() + "\n").toByteArray()
    }.get(5, TimeUnit.SECONDS)
    fun exported() { exported = true }
}
