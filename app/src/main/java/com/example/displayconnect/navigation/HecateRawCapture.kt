package com.example.displayconnect.navigation

import java.util.zip.CRC32
import org.json.JSONObject

/** Diagnostic-only, after confirmed STOP. Never issues start/status, writes GNSS,
 * changes navigation, clears the board buffer, or sends data outside the phone.
 * Transfer completion and capture truncation are separate facts in the trace. */
class HecateRawCapture(private val trace: NavigationTraceSink) {
    suspend fun collect(captureId: String, command: suspend (String,String?,Int?) -> Result<JSONObject>) {
        require(captureId.matches(Regex("[a-f0-9]{16,32}")))
        val meta=command("raw_meta",null,null).getOrThrow()
        require(meta.getBoolean("ok") && meta.getInt("raw_version")==1 && meta.getBoolean("frozen"))
        require(meta.getString("capture_id")==captureId)
        val total=meta.getInt("total_bytes")
        require(total in 0..131072 && meta.getInt("chunk_bytes")==256)
        val expected=meta.getString("crc32")
        require(expected.matches(Regex("[a-f0-9]{8}")))
        val dropped=meta.getLong("dropped_records")
        require(dropped>=0 && meta.getLong("dropped_bytes")>=0)
        trace.record("gnss_raw_meta",mapOf("capture_id" to captureId,"meta" to meta.toString()))
        val crc=CRC32()
        var offset=0
        while(offset<total) {
            val reply=command("raw_chunk",captureId,offset).getOrThrow()
            require(reply.getBoolean("ok") && reply.getInt("raw_version")==1 && reply.getBoolean("frozen"))
            require(reply.getString("capture_id")==captureId && reply.getInt("total_bytes")==total && reply.getString("crc32")==expected)
            require(reply.getInt("offset")==offset)
            val count=minOf(256,total-offset)
            require(reply.getInt("bytes")==count)
            val hex=reply.getString("hex")
            require(hex.length==count*2 && hex.matches(Regex("[a-f0-9]+")))
            val bytes=ByteArray(count){i->hex.substring(i*2,i*2+2).toInt(16).toByte()}
            crc.update(bytes)
            trace.record("gnss_raw_chunk",mapOf("capture_id" to captureId,"offset" to offset,"bytes" to count,"hex" to hex))
            offset+=count
        }
        val actual=crc.value.toString(16).padStart(8,'0')
        require(actual==expected) { "Raw capture CRC mismatch" }
        trace.record("gnss_raw_complete",mapOf("capture_id" to captureId,"total_bytes" to total,
            "crc32" to actual,"transfer_complete" to true,"capture_complete" to (dropped==0L),
            "dropped_records" to dropped,"durable_verified" to false))
        // FileNavigationTrace is best-effort. Final export_status must independently show
        // zero queue drops/write errors, and an offline decoder rechecks every chunk + CRC.
    }
}
