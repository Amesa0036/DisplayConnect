package com.example.displayconnect.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Geometria para la reincorporacion a una traza GPX.
 * Punto de enganche = PROYECCION sobre el segmento mas cercano (decision de
 * producto: "hasta el punto mas cercano para seguir"), con candidatos
 * ambiguos (ramas ida/vuelta coincidentes) desambiguados por rumbo en el
 * motor. Misma aproximacion equirectangular que RouteProgressCalculator.
 * Funciones puras, sin estado.
 */
object TrackRejoin {

    data class JoinCandidate(
        val segmentIndex: Int,
        val fraction: Double,
        val distanceM: Double,
        val point: LatLon,
        val bearing: Float
    )

    /**
     * Proyecta pos sobre cada segmento y devuelve los candidatos a distancia
     * <= mejor + marginM, ordenados por distancia. Mas de un candidato =
     * punto ambiguo (ramas coincidentes dentro de la precision GPS).
     */
    fun nearestJoinCandidates(points: List<LatLon>, pos: LatLon, marginM: Double): List<JoinCandidate> {
        if (points.size < 2 || !pos.lat.isFinite() || !pos.lon.isFinite()) return emptyList()
        val cosLat = cos(Math.toRadians(pos.lat))
        val all = ArrayList<JoinCandidate>(points.size - 1)
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val ax = Math.toRadians(a.lon - pos.lon) * EARTH_RADIUS * cosLat
            val ay = Math.toRadians(a.lat - pos.lat) * EARTH_RADIUS
            val dx = Math.toRadians(b.lon - a.lon) * EARTH_RADIUS * cosLat
            val dy = Math.toRadians(b.lat - a.lat) * EARTH_RADIUS
            val lengthSq = dx * dx + dy * dy
            val fraction = if (lengthSq > 0.0) (-(ax * dx + ay * dy) / lengthSq).coerceIn(0.0, 1.0) else 0.0
            all.add(
                JoinCandidate(
                    segmentIndex = i,
                    fraction = fraction,
                    distanceM = hypot(ax + fraction * dx, ay + fraction * dy),
                    point = LatLon(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction),
                    bearing = segmentBearing(a, b)
                )
            )
        }
        val best = all.minOf { it.distanceM }
        val near = all.filter { it.distanceM <= best + marginM }.sortedBy { it.distanceM }
        // Dedupe SOLO del mismo punto de progreso: vertice compartido entre
        // segmentos adyacentes (uno termina en el, el siguiente empieza). Es el
        // mismo lugar Y el mismo progreso de traza, asi que no se pierde nada.
        // Ramas coincidentes NO adyacentes (ida/vuelta, cierre de ruta circular)
        // se conservan como candidatos distintos: las desambigua el rumbo en el
        // motor y, sin rumbo, se tratan como ambiguas (sin eleccion silenciosa).
        val merged = ArrayList<JoinCandidate>(near.size)
        for (cand in near) {
            val dupIndex = merged.indexOfFirst { e ->
                kotlin.math.abs(e.segmentIndex - cand.segmentIndex) == 1 &&
                    ((e.segmentIndex < cand.segmentIndex && e.fraction >= 1.0 - 1e-6 && cand.fraction <= 1e-6) ||
                     (cand.segmentIndex < e.segmentIndex && cand.fraction >= 1.0 - 1e-6 && e.fraction <= 1e-6))
            }
            if (dupIndex < 0) merged.add(cand)
        }
        return merged
    }

    /** Distancia minima a la polilinea (proyeccion por segmentos), en metros. */
    fun distanceToPolyline(points: List<LatLon>, pos: LatLon): Double {
        if (points.size < 2 || !pos.lat.isFinite() || !pos.lon.isFinite()) return Double.POSITIVE_INFINITY
        val cosLat = cos(Math.toRadians(pos.lat))
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until points.size - 1) {
            val ax = Math.toRadians(points[i].lon - pos.lon) * EARTH_RADIUS * cosLat
            val ay = Math.toRadians(points[i].lat - pos.lat) * EARTH_RADIUS
            val dx = Math.toRadians(points[i + 1].lon - points[i].lon) * EARTH_RADIUS * cosLat
            val dy = Math.toRadians(points[i + 1].lat - points[i].lat) * EARTH_RADIUS
            val lengthSq = dx * dx + dy * dy
            val fraction = if (lengthSq > 0.0) (-(ax * dx + ay * dy) / lengthSq).coerceIn(0.0, 1.0) else 0.0
            val distance = hypot(ax + fraction * dx, ay + fraction * dy)
            if (distance < best) best = distance
        }
        return best
    }

    /**
     * (distancia, duracion) de traza restante desde (segmentIndex, fraction),
     * con anotaciones OSRM si son coherentes y geometria si no. Replica la
     * logica de pesos de RouteProgressCalculator (duplicacion aceptada para
     * no tocar esa clase).
     */
    fun remainingFrom(route: RouteData, segmentIndex: Int, fraction: Double): Pair<Double, Double> {
        val points = route.coordinates
        if (points.size < 2 || segmentIndex !in 0 until points.size - 1) return 0.0 to 0.0
        val geometry = points.zipWithNext { a, b ->
            val y = Math.toRadians(b.lat - a.lat) * EARTH_RADIUS
            val x = Math.toRadians(b.lon - a.lon) * EARTH_RADIUS * cos(Math.toRadians((a.lat + b.lat) / 2))
            hypot(x, y)
        }
        val distances = weights(route.segmentDistancesM, route.distanceM, geometry)
        val durations = weights(route.segmentDurationsS, route.durationS, distances)
        var dist = 0.0
        var dur = 0.0
        for (i in segmentIndex until distances.size) {
            val factor = if (i == segmentIndex) (1.0 - fraction) else 1.0
            dist += distances[i] * factor
            dur += durations[i] * factor
        }
        return dist to dur
    }

    /** Diferencia angular absoluta 0..180 entre dos rumbos en grados. */
    fun bearingDiff(a: Float, b: Float): Double {
        val diff = ((a - b + 540) % 360) - 180
        return kotlin.math.abs(diff.toDouble())
    }

    private fun segmentBearing(a: LatLon, b: LatLon): Float {
        val cosLat = cos(Math.toRadians((a.lat + b.lat) / 2))
        return ((Math.toDegrees(atan2((b.lon - a.lon) * cosLat, b.lat - a.lat)) + 360) % 360).toFloat()
    }

    private fun weights(annotations: List<Double>, total: Double, fallback: List<Double>): List<Double> {
        val usable = annotations.size == fallback.size && annotations.all { it.isFinite() && it >= 0 } && annotations.sum() > 0
        val values = if (usable) annotations else fallback
        val sum = values.sum()
        val target = if (total.isFinite() && total > 0) total else sum
        return values.map { if (sum > 0) it * target / sum else 0.0 }
    }

    private const val EARTH_RADIUS = 6_371_000.0
}
