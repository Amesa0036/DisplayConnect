package com.example.displayconnect.offline

import com.example.displayconnect.routing.*
import kotlinx.coroutines.*
import java.io.File

class OfflineMapRepository(root: File, private val source: StreetSource, private val pauseMs: Long = 1200,
    private val viewportMinIntervalMs: Long = 60_000) {
    private val disk = OfflineDiskStore(root)

    suspend fun findRoute(origin: LatLon, destination: LatLon, profile: RouteProfile): RouteData? =
        withContext(Dispatchers.IO) { disk.findRoute(origin, destination, profile) }

    suspend fun saveRoute(route: RouteData, destination: LatLon, profile: RouteProfile) =
        withContext(Dispatchers.IO) { disk.saveRoute(route, destination, profile) }

    suspend fun prepare(route: RouteData, scale: Double, onProgress: suspend (OfflineMapState) -> Unit) =
        withContext(Dispatchers.IO) {
            var state = OfflineMapState(downloading = true, radiusM = RouteCoverage.radiusMeters(scale).toInt())
            onProgress(state)
            try {
                val tiles = RouteCoverage.plan(route.coordinates, scale)
                disk.trim(tiles.toSet())
                val missing = tiles.filter { currentCoroutineContext().ensureActive(); disk.load(it) == null }
                state = state.copy(total = tiles.size, downloaded = tiles.size - missing.size)
                onProgress(state)
                for ((index, batch) in missing.chunked(4).withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (index > 0) delay(pauseMs)
                    val ways = source.fetch(batch.map { it.bounds() })
                    currentCoroutineContext().ensureActive()
                    for (tile in batch) {
                        disk.save(tile, ways.filter { it.bounds.intersects(tile.bounds()) })
                        state = state.copy(downloaded = state.downloaded + 1)
                        onProgress(state)
                    }
                }
                state = state.copy(downloading = false)
                onProgress(state)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                onProgress(state.copy(downloading = false,
                    error = e.message ?: "Falha ao baixar as ruas. Confira a internet e tente novamente."))
            }
        }

    private var lastViewportFetchMs = 0L
    private var viewportFailures = 0

    /**
     * Rolling top-up while riding: downloads only the missing tiles around the live
     * viewport (after a mid-ride deviation or a destination change), preserving every
     * tile already cached. Never deletes. At most one network pass per interval, with
     * exponential backoff after failures (1x, 2x, 4x, 8x cap) and a per-pass tile cap
     * so riding through a large uncovered area cannot burst the public API. Failures
     * stay silent and are retried on a later fix; needs mobile data - outside coverage
     * the viewport simply stays uncovered and outsideCoverage keeps showing it.
     * Returns true when the viewport is fully covered.
     */
    suspend fun prepareViewport(center: LatLon, scale: Double, nowMs: Long = System.currentTimeMillis()): Boolean =
        withContext(Dispatchers.IO) {
            val tiles = RouteCoverage.tiles(RouteCoverage.around(center, RouteCoverage.radiusMeters(scale)))
            val missing = tiles.filter { currentCoroutineContext().ensureActive(); disk.load(it) == null }
            if (missing.isEmpty()) { viewportFailures = 0; return@withContext true }
            val interval = viewportMinIntervalMs * (1L shl viewportFailures.coerceAtMost(3))
            if (nowMs - lastViewportFetchMs < interval) return@withContext false
            lastViewportFetchMs = nowMs
            var fetched = 0
            for ((index, batch) in missing.chunked(4).withIndex()) {
                if (fetched + batch.size > VIEWPORT_MAX_TILES_PER_PASS) break
                currentCoroutineContext().ensureActive()
                if (index > 0) delay(pauseMs)
                try {
                    val ways = source.fetch(batch.map { it.bounds() })
                    currentCoroutineContext().ensureActive()
                    for (tile in batch) disk.save(tile, ways.filter { it.bounds.intersects(tile.bounds()) })
                    fetched += batch.size
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { viewportFailures++; return@withContext false }
            }
            viewportFailures = 0
            fetched >= missing.size
        }

    private companion object { const val VIEWPORT_MAX_TILES_PER_PASS = 8 }

    /** No network here. Only read saved tiles needed to cover this complete viewport. */
    suspend fun visibleWays(center: LatLon, scale: Double): Pair<List<List<LatLon>>, Boolean> =
        streetWays(center, scale).let { (ways, complete) -> ways.map { it.points } to complete }

    /** Tags and geometry from the same complete cached viewport, no per-fix network call. */
    suspend fun streetWays(center: LatLon, scale: Double): Pair<List<StreetWay>, Boolean> =
        withContext(Dispatchers.IO) {
            val tiles = RouteCoverage.tiles(RouteCoverage.around(center, RouteCoverage.radiusMeters(scale)))
            val ways = linkedMapOf<Long, StreetWay>()
            var complete = true
            for (tile in tiles) {
                currentCoroutineContext().ensureActive()
                val cached = disk.load(tile)
                if (cached == null) complete = false else cached.forEach { ways[it.id] = it }
            }
            ways.values.toList() to complete
        }
}
