package com.example.displayconnect.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Geometria para la reincorporacion a una traza GPX.
 * Punto de enganche = PROYECCION sobre el segmento mas cercano (decision de
 * producto: "hasta el punto mas cercano para seguir"), con candidatos
 * ambiguos (ramas ida/vuelta coincidentes) desambiguados por rumbo en el
 * motor o elegidos expresamente por el usuario en la app. Candidatos que son
 * el mismo lugar y el mismo progreso (artefacto de trazas densas) se fusionan
 * por cluster; ramas genuinas a kilometros de progreso nunca se fusionan.
 * Misma aproximacion equirectangular que RouteProgressCalculator.
 * Funciones puras, sin estado.
 */
object TrackRejoin {

    data class JoinCandidate(
        val segmentIndex: Int,
        val fraction: Double,
        val distanceM: Double,
        val point: LatLon,
        val bearing: Float,
        val progressM: Double
    )

    /**
     * Proyecta pos sobre cada segmento y devuelve los candidatos a distancia
     * <= mejor + marginM, ordenados por distancia. Mas de un candidato =
     * punto ambiguo (ramas coincidentes dentro de la precision GPS).
     */
    fun nearestJoinCandidates(points: List<LatLon>, pos: LatLon, marginM: Double): List<JoinCandidate> {
        if (points.size < 2 || !pos.lat.isFinite() || !pos.lon.isFinite()) return emptyList()
        val cosLat = cos(Math.toRadians(pos.lat))
        // Progreso acumulado por vertice: necesario para distinguir "mismo punto
        // de enganche" de "ramas distintas" (ida/vuelta o cierre de ruta estan a
        // kilometros de progreso aunque coincidan en el mapa).
        val cum = DoubleArray(points.size)
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            cum[i + 1] = cum[i] + hypot(
                Math.toRadians(b.lon - a.lon) * EARTH_RADIUS * cos(Math.toRadians((a.lat + b.lat) / 2)),
                Math.toRadians(b.lat - a.lat) * EARTH_RADIUS
            )
        }
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
            val segLen = cum[i + 1] - cum[i]
            all.add(
                JoinCandidate(
                    segmentIndex = i,
                    fraction = fraction,
                    distanceM = hypot(ax + fraction * dx, ay + fraction * dy),
                    point = LatLon(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction),
                    bearing = segmentBearing(a, b),
                    progressM = cum[i] + fraction * segLen
                )
            )
        }
        val best = all.minOf { it.distanceM }
        val near = all.filter { it.distanceM <= best + marginM }.sortedBy { it.distanceM }
        // Fusion por clusters: dos candidatos son EL MISMO punto de enganche si
        // sus puntos proyectados estan muy cerca en el mapa Y en progreso de
        // traza. Con trazas densas (un punto cada pocos metros), una posicion
        // fuera de la traza proyecta en los extremos clavados de varios
        // segmentos consecutivos de la misma curva: sin esta fusion, cada curva
        // generaba una "ambigüedad" falsa. Las ramas genuinas (ida/vuelta,
        // cierre circular) estan a kilometros de progreso entre si y NUNCA se
        // fusionan. Caso residual conocido: un micro-bucle de <100 m de
        // progreso y <40 m en el mapa se fusionaria (raro en rutas de moto).
        val merged = ArrayList<JoinCandidate>(near.size)
        for (cand in near) {
            val dupIndex = merged.indexOfFirst { e ->
                kotlin.math.abs(e.progressM - cand.progressM) <= SAME_JOIN_PROGRESS_M &&
                    planarDistanceM(e.point, cand.point) <= SAME_JOIN_GEO_M
            }
            if (dupIndex < 0) merged.add(cand)
        }
        return merged
    }

    private fun planarDistanceM(a: LatLon, b: LatLon): Double {
        val cosLat = cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot(
            Math.toRadians(a.lon - b.lon) * EARTH_RADIUS * cosLat,
            Math.toRadians(a.lat - b.lat) * EARTH_RADIUS
        )
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
    private const val SAME_JOIN_GEO_M = 40.0
    private const val SAME_JOIN_PROGRESS_M = 100.0
}
