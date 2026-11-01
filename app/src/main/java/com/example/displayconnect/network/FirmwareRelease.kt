package com.example.displayconnect.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Latest board firmware from the GitHub release: a "firmware.json" manifest
 * ({"version","size","sha256","bin_url"}) plus the signed .bin it describes.
 */
object FirmwareRelease {

    data class Manifest(val version: String, val size: Int, val sha256: String, val binUrl: String)

    private const val RELEASE_URL =
        "https://api.github.com/repos/Amesa0036/DisplayConnect/releases/latest"
    private const val MANIFEST_NAME = "firmware.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLatest(): Result<Manifest> = withContext(Dispatchers.IO) {
        runCatching {
            val release = getJson(RELEASE_URL)
            val assets = release.optJSONArray("assets")
                ?: error("La release no tiene archivos")
            var manifestUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == MANIFEST_NAME) {
                    manifestUrl = asset.getString("browser_download_url")
                }
            }
            checkNotNull(manifestUrl) { "La release no trae $MANIFEST_NAME" }
            val m = getJson(manifestUrl)
            Manifest(
                version = m.getString("version"),
                size = m.getInt("size"),
                sha256 = m.getString("sha256").lowercase(),
                binUrl = m.getString("bin_url")
            )
        }
    }

    suspend fun download(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "DisplayConnect").build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Descarga fallida (${resp.code})" }
                resp.body?.bytes() ?: error("Respuesta vacia")
            }
        }
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url).header("User-Agent", "DisplayConnect").build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Error ${resp.code} consultando la release" }
            return JSONObject(resp.body?.string() ?: error("Respuesta vacia"))
        }
    }
}
