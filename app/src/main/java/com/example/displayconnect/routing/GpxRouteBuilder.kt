package com.example.displayconnect.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Convierte un track GPX en una ruta con indicaciones de giro: muestrea el track
 * en puntos de paso y pide /route al mismo servidor OSRM (FOSSGIS) que ya usa la
 * app. Los servidores publicos limitan /match a 10 coordenadas pero /route acepta
 * muchas mas, asi que el via-point routing es el camino viable.
 *
 * Entre puntos de paso OSRM sigue la carretera mas logica; si el track se sale de
 * la red vial (campo a traves), ese trozo puede quedar suavizado.
 * Si un trozo falla, se usa la geometria original del track ahi y la ruta sigue
 * funcionando sin flechas en ese tramo.
 */
class GpxRouteBuilder(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    data class PrepOutcome(
        val route: RouteData,
        val arrowsComplete: Boolean
    )

    suspend fun build(
        points: List<LatLon>,
        profile: RouteProfile,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): PrepOutcome = withContext(Dispatchers.IO) {
        require(points.size >= 2) { "Track without points" }
        val vias = sampleVias(points)
        val chunks = chunkVias(vias)
        val acc = Acc(profile)
        chunks.forEachIndexed { index, chunkVias ->
            val viaPoints = chunkVias.map { points[it] }
            val parsed = runCatching { fetchRoute(viaPoints, profile) }.getOrNull()
                ?: runCatching { fetchRoute(viaPoints, profile) }.getOrNull()
            if (parsed != null) {
                acc.appendRoute(parsed, isFirst = index == 0, isLast = index == chunks.lastIndex)
            } else {
                // Tramo sin casar: geometria original del track entre esos puntos de paso
                acc.appendRaw(points.subList(chunkVias.first(), chunkVias.last() + 1))
            }
            onProgress(index + 1, chunks.size)
            if (index < chunks.lastIndex) delay(350)
        }
        PrepOutcome(acc.build(), arrowsComplete = !acc.failed)
    }

    /** Indices del track usados como puntos de paso (~150 m de separacion en linea recta). */
    private fun sampleVias(points: List<LatLon>): List<Int> {
        if (points.size <= 2) return points.indices.toList()
        val idx = mutableListOf(0)
        var lastKept = 0
        for (i in 1 until points.lastIndex) {
            if (GpxParser.haversineMeters(points[lastKept], points[i]) >= 150.0) {
                idx.add(i)
                lastKept = i
            }
        }
        idx.add(points.lastIndex)
        return idx
    }

    /** Grupos de como mucho 61 puntos de paso, compartiendo 1 entre grupos. */
    private fun chunkVias(vias: List<Int>): List<List<Int>> {
        if (vias.size <= MAX_VIAS_PER_REQUEST) return listOf(vias)
        val out = mutableListOf<List<Int>>()
        var start = 0
        while (start < vias.size - 1) {
            val end = minOf(start + MAX_VIAS_PER_REQUEST, vias.size)
            out.add(vias.subList(start, end))
            start += MAX_VIAS_PER_REQUEST - 1
        }
        return out
    }

    private fun fetchRoute(viaPoints: List<LatLon>, profile: RouteProfile): JSONObject {
        val coords = viaPoints.joinToString(";") { "${it.lon},${it.lat}" }
        val url = profile.routeBaseUrl + coords +
            "?geometries=geojson&steps=true&overview=full&annotations=distance,duration" +
            "&continue_straight=true"
        val request = Request.Builder().url(url)
            .header("User-Agent", "DisplayConnect-CYD/2.1 (personal navigation)").get().build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        } ?: error("OSRM error")
        val root = JSONObject(body)
        if (root.optString("code") != "Ok") error("OSRM no route")
        val routes = root.getJSONArray("routes")
        if (routes.length() == 0) error("OSRM no route")
        return routes.getJSONObject(0)
    }

    private class Acc(profile: RouteProfile) {
        private val avgSpeedMps = when (profile) {
            RouteProfile.WALKING -> 1.4
            RouteProfile.BIKE -> 4.4
            else -> 13.9
        }
        val coords = mutableListOf<LatLon>()
        val steps = mutableListOf<RouteStep>()
        val segDistances = mutableListOf<Double>()
        val segDurations = mutableListOf<Double>()
        var failed = false
        private var traveled = 0.0

        /**
         * Union entre trozos: true si hay que añadir el primer punto del bloque.
         * Si el primer punto ya esta (punto de paso compartido), las anotaciones
         * del bloque cubren los segmentos siguientes y no hace falta union.
         */
        private fun junction(first: LatLon): Boolean {
            val last = coords.lastOrNull() ?: return true
            val d = GpxParser.haversineMeters(last, first)
            return if (d < 1.0) {
                false
            } else {
                segDistances.add(d)
                segDurations.add(d / avgSpeedMps)
                traveled += d
                true
            }
        }

        fun appendRoute(route: JSONObject, isFirst: Boolean, isLast: Boolean) {
            val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
            for (i in 0 until geometry.length()) {
                val c = geometry.getJSONArray(i)
                val p = LatLon(lat = c.getDouble(1), lon = c.getDouble(0))
                if (i == 0 && !junction(p)) continue
                coords.add(p)
            }
            var local = 0.0
            val legs = route.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val leg = legs.getJSONObject(l)
                val annotation = leg.optJSONObject("annotation")
                annotation?.optJSONArray("distance")?.let { values ->
                    for (i in 0 until values.length()) segDistances.add(values.getDouble(i))
                }
                annotation?.optJSONArray("duration")?.let { values ->
                    for (i in 0 until values.length()) segDurations.add(values.getDouble(i))
                }
                val legSteps = leg.getJSONArray("steps")
                for (s in 0 until legSteps.length()) {
                    val step = legSteps.getJSONObject(s)
                    val maneuver = step.getJSONObject("maneuver")
                    val type = maneuver.optString("type", "continue")
                    val isBoundaryDepart = type == "depart" && !(isFirst && l == 0 && s == 0)
                    val isBoundaryArrive = type == "arrive" && !(isLast && l == legs.length() - 1 && s == legSteps.length() - 1)
                    val distance = step.optDouble("distance", 0.0).coerceAtLeast(0.0)
                    if (isBoundaryDepart || isBoundaryArrive) {
                        local += distance
                        continue
                    }
                    val location = maneuver.getJSONArray("location")
                    val modifier = maneuver.optString("modifier", "")
                    steps.add(
                        RouteStep(
                            instruction = formatInstruction(type, modifier),
                            street = step.optString("name", ""),
                            distanceM = distance.toInt(),
                            endLocation = LatLon(
                                lat = location.getDouble(1),
                                lon = location.getDouble(0)
                            ),
                            maneuverType = type,
                            maneuverModifier = modifier,
                            maneuverExit = maneuver.optInt("exit", 0).takeIf { it > 0 },
                            maneuverAtM = traveled + local
                        )
                    )
                    local += distance
                }
            }
            traveled += route.optDouble("distance", local)
        }

        fun appendRaw(points: List<LatLon>) {
            failed = true
            points.forEachIndexed { i, p ->
                if (i == 0 && !junction(p)) return@forEachIndexed
                if (i > 0) {
                    val d = GpxParser.haversineMeters(points[i - 1], points[i])
                    segDistances.add(d)
                    segDurations.add(d / avgSpeedMps)
                    traveled += d
                }
                coords.add(p)
            }
        }

        fun build(): RouteData {
            val distance = if (segDistances.isNotEmpty()) segDistances.sum() else GpxParser.totalDistance(coords)
            val duration = if (segDurations.isNotEmpty()) segDurations.sum() else distance / avgSpeedMps
            return RouteData(coords, steps, distance, duration, segDistances, segDurations)
        }
    }

    private companion object {
        const val MAX_VIAS_PER_REQUEST = 61

        fun formatInstruction(type: String, modifier: String): String = when {
            type == "arrive" -> "Arrive at destination"
            type == "depart" -> "Start route"
            modifier == "right" -> "Turn right"
            modifier == "left" -> "Turn left"
            modifier == "slight right" -> "Slight right"
            modifier == "slight left" -> "Slight left"
            modifier == "sharp right" -> "Sharp right"
            modifier == "sharp left" -> "Sharp left"
            modifier == "straight" -> "Continue straight"
            modifier.isNotBlank() -> modifier.replaceFirstChar { it.uppercase() }
            else -> type.replaceFirstChar { it.uppercase() }
        }
    }
}
