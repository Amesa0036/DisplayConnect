package com.example.displayconnect.routing

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Searches only on explicit submission, with caching and a global request interval. */
class NominatimGeocoder(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build(),
    private val nominatimUrl: String = "https://nominatim.openstreetmap.org/search",
    private val photonUrl: String = "https://photon.komoot.io/api/",
    private val overpassUrl: String = "https://overpass-api.de/api/interpreter"
) {
    suspend fun search(query: String, city: String = "", limit: Int = 10,
                       biasLat: Double? = null, biasLon: Double? = null): Result<List<PlaceSearchResult>> =
        withContext(Dispatchers.IO) {
            try {
                require(query.trim().length >= 3) { "Query too short" }
                val fullQuery = AddressQuery.withCity(query, city)
                val maxResults = limit.coerceIn(1, 15)
                val biasKey = if (biasLat != null && biasLon != null)
                    "|${Math.round(biasLat * 10)},${Math.round(biasLon * 10)}" else ""
                val key = "$nominatimUrl|$photonUrl|$fullQuery|$maxResults$biasKey".lowercase()
                val results = mutex.withLock {
                    cache[key]?.let { return@withLock it }
                    val number = AddressQuery.requestedHouseNumber(query)
                    var failure: Exception? = null
                    suspend fun attempt(block: suspend () -> List<PlaceSearchResult>): List<PlaceSearchResult> =
                        try { block() } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = e; emptyList() }
                    val norm = { s: String ->
                        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
                            .replace(Regex("\\p{Mn}+"), "")
                    }
                    fun canonTok(t: String) = caToEs[t] ?: t
                    val queryNorm = norm(fullQuery)
                    val variant = queryNorm.split(Regex("[^a-z0-9]+"))
                        .joinToString(" ") { esToCa[it] ?: it }
                    val queries = if (variant != queryNorm) listOf(fullQuery, variant) else listOf(fullQuery)
                    var primary = emptyList<PlaceSearchResult>()
                    var extra = emptyList<PlaceSearchResult>()
                    for (q in queries) {
                        primary = primary + attempt { nominatim(q, maxResults, number, biasLat, biasLon) }
                        extra = extra + attempt { photon(q, maxResults, number, biasLat, biasLon) }
                    }
                    var merged = (primary + extra).distinctBy {
                        Triple(norm(it.displayName.substringBefore(',')),
                            Math.round(it.lat * 1000), Math.round(it.lon * 1000))
                    }
                    // Name relevance first (a plaza named like the query beats any
                    // random street in the comarca), then distance to the user.
                    // Token matching tolerates one typo/variant letter (plaza~placa).
                    val tokens = norm(AddressQuery.withoutHouseNumber(query))
                        .split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.map { canonTok(it) }
                    fun lev(a: String, b: String): Int {
                        if (kotlin.math.abs(a.length - b.length) > 1) return 2
                        val dp = IntArray(b.length + 1) { it }
                        for (i in 1..a.length) {
                            var prev = dp[0]; dp[0] = i
                            for (j in 1..b.length) {
                                val t = dp[j]
                                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                                prev = t
                            }
                        }
                        return dp[b.length]
                    }
                    fun tokenIn(token: String, text: String): Boolean {
                        if (text.contains(token)) return true
                        if (token.length < 4) return false
                        return text.split(Regex("[^a-z0-9]+"))
                            .any { w -> val c = canonTok(w); c == token || (c.length >= 3 && lev(c, token) <= 1) }
                    }
                    fun nameScore(r: PlaceSearchResult): Int {
                        if (tokens.isEmpty()) return 0
                        val name = norm(r.displayName.substringBefore(','))
                        val rest = norm(r.displayName.substringAfter(',', ""))
                        return when {
                            tokens.all { tokenIn(it, name) } -> 0
                            tokens.any { tokenIn(it, name) } -> 1
                            tokens.all { tokenIn(it, rest) } -> 2
                            else -> 3
                        }
                    }
                    // Nearby-name fallback via Overpass: providers rank by importance, so a
                    // small local place (Plaça Catalunya Sabadell) never reaches their top
                    // results. When nothing good nearby came back (or Nominatim failed),
                    // query OSM directly for features named like the query around the user.
                    if (biasLat != null && biasLon != null && city.isBlank() && !query.contains(',')) {
                        fun d2(r: PlaceSearchResult): Double {
                            val dLat = r.lat - biasLat
                            val dLon = (r.lon - biasLon) * Math.cos(Math.toRadians(biasLat))
                            return dLat * dLat + dLon * dLon
                        }
                        // Only a strong match within ~5 km counts as good; a same-named
                        // place farther away (la Llagosta vs Sabadell) must not stop
                        // the fallback from finding the closer one.
                        val goodNearby = merged.any { nameScore(it) == 0 && d2(it) < 0.0025 }
                        val nominatimEmpty = primary.isEmpty()
                        if (!goodNearby || nominatimEmpty) {
                            val nameTokens = tokens.filter { it.length >= 2 }
                            for (take in nameTokens.size downTo 2) {
                                val rx = buildNameRegex(nameTokens.take(take))
                                if (rx.isBlank()) break
                                val ov = attempt { overpassNearby(rx, biasLat, biasLon) }
                                if (ov.isNotEmpty()) {
                                    merged = (merged + ov).distinctBy {
                                        Triple(norm(it.displayName.substringBefore(',')),
                                            Math.round(it.lat * 500), Math.round(it.lon * 500))
                                    }
                                    break
                                }
                            }
                        }
                    }
                    merged = if (biasLat != null && biasLon != null) {
                        merged.sortedWith(compareBy({ nameScore(it) }, {
                            val dLat = it.lat - biasLat
                            val dLon = (it.lon - biasLon) * Math.cos(Math.toRadians(biasLat))
                            dLat * dLat + dLon * dLon
                        }))
                    } else merged.sortedBy { nameScore(it) }
                    val relaxed = AddressQuery.withCity(AddressQuery.withoutHouseNumber(query), city)
                    if (merged.isEmpty() && number != null && relaxed != fullQuery) {
                        merged = attempt { nominatim(relaxed, maxResults, number, biasLat, biasLon) }
                            .map { it.copy(houseNumberUnconfirmed = true) }
                    }
                    if (merged.isEmpty()) {
                        if (failure != null) throw IllegalStateException("Search services unavailable", failure)
                        error("No places found")
                    }
                    merged.take(maxResults).also {
                        cache[key] = it
                        if (cache.size > 50) cache.remove(cache.keys.first())
                    }
                }
                Result.success(results)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }

    private suspend fun nominatim(query: String, limit: Int, number: String?,
                                  biasLat: Double? = null, biasLon: Double? = null): List<PlaceSearchResult> {
        val elapsed = (System.nanoTime() - lastRequestNs) / 1_000_000
        if (elapsed < 1100) delay(1100 - elapsed)
        currentCoroutineContext().ensureActive()
        lastRequestNs = System.nanoTime()
        val builder = nominatimUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", limit.toString()).addQueryParameter("addressdetails", "1")
            .addQueryParameter("accept-language", "es,ca,en")
        if (biasLat != null && biasLon != null) {
            // Bias (not restrict) results toward the user's position: viewbox without bounded=1.
            val d = 1.5
            builder.addQueryParameter("viewbox",
                "${biasLon - d},${biasLat + d},${biasLon + d},${biasLat - d}")
        }
        val url = builder.build()
        val array = JSONArray(request(url))
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val house = item.optJSONObject("address")?.optString("house_number", "").orEmpty()
                val lat = item.optString("lat").toDoubleOrNull() ?: continue
                val lon = item.optString("lon").toDoubleOrNull() ?: continue
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                add(PlaceSearchResult(item.getString("display_name"), lat, lon,
                    number != null && !house.equals(number, true)))
            }
        }
    }

    private suspend fun photon(query: String, limit: Int, number: String?,
                               biasLat: Double? = null, biasLon: Double? = null): List<PlaceSearchResult> {
        val builder = photonUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("limit", limit.toString())
        if (biasLat != null && biasLon != null) {
            builder.addQueryParameter("lat", biasLat.toString())
                .addQueryParameter("lon", biasLon.toString())
        }
        val url = builder.build()
        val features = JSONObject(request(url)).getJSONArray("features")
        return buildList {
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (coordinates.length() < 2) continue
                val props = feature.getJSONObject("properties")
                val house = props.optString("housenumber", "")
                val parts = listOf("name", "street", "housenumber", "district", "city", "state", "postcode", "country")
                    .map { props.optString(it, "") }.filter { it.isNotBlank() }.distinct()
                if (parts.isEmpty()) continue
                val lat = coordinates.getDouble(1)
                val lon = coordinates.getDouble(0)
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                add(PlaceSearchResult(parts.joinToString(", "), lat, lon,
                    number != null && !house.equals(number, true), "OpenStreetMap / Photon"))
            }
        }
    }

    private suspend fun overpassNearby(nameRegex: String, lat: Double, lon: Double): List<PlaceSearchResult> {
        val ql = "[out:json][timeout:10];nwr[\"name\"~\"" + nameRegex + "\",i]" +
            "(around:15000," + lat + "," + lon + ");out center 12;"
        val url = overpassUrl.toHttpUrl().newBuilder().addQueryParameter("data", ql).build()
        val elements = JSONObject(request(url)).getJSONArray("elements")
        return buildList {
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val tags = el.optJSONObject("tags") ?: continue
                val name = tags.optString("name", "")
                if (name.isBlank()) continue
                val center = el.optJSONObject("center")
                val elLat = if (center != null) center.optDouble("lat", Double.NaN)
                    else el.optDouble("lat", Double.NaN)
                val elLon = if (center != null) center.optDouble("lon", Double.NaN)
                    else el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) continue
                if (elLat !in -90.0..90.0 || elLon !in -180.0..180.0) continue
                val cityTag = tags.optString("addr:city", tags.optString("is_in", ""))
                val display = if (cityTag.isBlank()) name else "$name, $cityTag"
                add(PlaceSearchResult(display, elLat, elLon, false, "OpenStreetMap / Overpass"))
            }
        }
    }

    private suspend fun request(url: HttpUrl): String {
        currentCoroutineContext().ensureActive()
        val request = Request.Builder().url(url)
            .header("User-Agent", "DisplayConnect-CYD/2.1 (personal Android navigation)")
            .header("Accept", "application/json").build()
        val body = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Geocoding HTTP " + response.code }
            response.body?.string() ?: error("Empty geocoding response")
        }
        currentCoroutineContext().ensureActive()
        return body
    }

    companion object {
        private val mutex = Mutex()
        private var lastRequestNs = 0L
        private val cache = LinkedHashMap<String, List<PlaceSearchResult>>()

        // es/ca street-type variants: users type "plaza"/"calle" but Catalan
        // places are "plaça"/"carrer"; without the variant providers miss them.
        internal val esToCa = mapOf("plaza" to "placa", "calle" to "carrer",
            "avenida" to "avinguda", "paseo" to "passeig", "camino" to "cami",
            "travesia" to "travessera", "san" to "sant")
        internal val caToEs = mapOf("placa" to "plaza", "carrer" to "calle",
            "avinguda" to "avenida", "passeig" to "paseo", "cami" to "camino",
            "travessera" to "travesia", "sant" to "san")

        // Overpass name regex for the nearby fallback: each normalized query token
        // expands to its es/ca/cedilla variants; "de"/"la" between tokens is optional.
        internal fun buildNameRegex(tokens: List<String>): String {
            val parts = tokens.map { t ->
                val base = linkedSetOf(t, esToCa[t], caToEs[t])
                    .filterNotNull().filter { it.length >= 2 }
                val vars = base.flatMap {
                    listOf(it, it.replace("c", "ç"), it.replace("ç", "c"),
                        it.replace("n", "ñ"), it.replace("ñ", "n"))
                }.distinct()
                if (vars.size == 1) vars.first()
                else vars.joinToString("|", "(", ")")
            }
            return parts.joinToString("(?:\\s+(?:de|la|del|els|les)\\s+|\\s+)")
        }
    }
}
