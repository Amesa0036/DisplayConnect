package com.example.displayconnect.navigation

import com.example.displayconnect.map.MapProjector
import com.example.displayconnect.map.StreetContextProjector
import com.example.displayconnect.maps.MapsHtmlHolder
import com.example.displayconnect.models.AppSettings
import com.example.displayconnect.network.BleNavClient
import com.example.displayconnect.protocol.NavMessage
import com.example.displayconnect.routing.LatLon
import com.example.displayconnect.offline.OfflineMapRepository
import com.example.displayconnect.routing.OsrmRouteProvider
import com.example.displayconnect.routing.RouteData
import com.example.displayconnect.routing.RouteStep
import com.example.displayconnect.routing.RouteProgressCalculator
import com.example.displayconnect.routing.TrackRejoin
import com.example.displayconnect.utils.StatsTracker
import com.example.displayconnect.utils.TransmissionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import com.example.displayconnect.routing.ReroutePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.os.SystemClock
import android.util.Log
import com.example.displayconnect.utils.AppLanguage
import com.example.displayconnect.routing.NavigationText
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NavigationEngine(
    private val scope: CoroutineScope,
    private val locationTracker: PositionSource,
    private val boardGnss: Boolean = false,
    private val routeProvider: OsrmRouteProvider,
    private val offlineMap: OfflineMapRepository,
    private val navClient: BleNavClient,
    private val settingsProvider: suspend () -> AppSettings,
    private val onNavigationEnded: () -> Unit = {},
    private val trace: NavigationTraceSink = NavigationTraceSink.NONE,
    private val rejoinRoute: suspend (LatLon, LatLon, com.example.displayconnect.routing.RouteProfile, Float?) -> Result<RouteData> = routeProvider::fetchRoute
) {

    private var route: RouteData? = null
    private var progressCalculator: RouteProgressCalculator? = null
    private var destination: LatLon? = null
    private var locationJob: Job? = null
    private var downloadJob: Job? = null
    private var viewportTopUpJob: Job? = null
    private var rerouteJob: Deferred<Result<RouteData>>? = null
    private val reroutePolicy = ReroutePolicy()
    private var tripStartMs = 0L
    private var tripDistanceM = 0.0
    private var tripMaxSpeedKmh = 0
    private var tripLastLat = Double.NaN
    private var tripLastLon = Double.NaN
    private val altitudeEstimator = AltitudeEstimator()
    private val altitudeFilter get() = altitudeEstimator.filter
    var altitudeMonitor: AltitudeMonitor? = null
    private var rerouteFailed = false
    // Reincorporacion GPX: traza original apartada mientras sigue un conector.
    private var gpxTrack: RouteData? = null
    private var gpxRejoinActive = false
    private var gpxRejoinStrikes = 0
    private var rerouteIsGpxConnector = false
    private var gpxRejoinTrackRemainingM = 0.0
    private var gpxRejoinTrackRemainingS = 0.0
    private var gpxRejoinAttempts = 0
    private var gpxRejoinAmbiguous = false
    // Candidatos de enganche publicados mientras la traza es ambigua: la app
    // los muestra; la salida confirmada admite temporizador con accesos comparados.
    private var gpxJoinCandidates: List<TrackRejoin.JoinCandidate> = emptyList()
    private data class JoinQuote(val candidate: TrackRejoin.JoinCandidate, val road: RouteData?)
    private var joinQuotes: Deferred<List<JoinQuote>>? = null
    private var joinQuoteOrigin: LatLon? = null
    private var joinQuoteStartedMs = 0L
    private var joinQuoteResults: List<JoinQuote>? = null
    private val joinCountdown = com.example.displayconnect.routing.RejoinCountdown()
    private var joinEpoch = 0
    private var gpxResumeSeed: Int? = null
    private var joinRetryAfterMs = 0L
    private var quoteCompletedMs = 0L
    private var quotedDeparture: Float? = null
    private val weakJoinCancellation = java.util.concurrent.atomic.AtomicBoolean(false)
    private var hasJoinedTrack = false
    private val pendingJoinSelection = java.util.concurrent.atomic.AtomicInteger(-1)
    private var autoSelection = false
    private var lastReliableDeparture: Float? = null
    private var lastRawPosition: LatLon? = null
    // Primer enganche de una traza GPX aun no resuelto: si hay ramas ambiguas
    // no se elige en silencio; el usuario elige en la app (selectGpxJoinCandidate).
    private var gpxInitialJoinPending = false
    private var gpxMode = false
    private var activeSettings: AppSettings? = null
    private var gpsFilter: GpsFilter? = null
    private var gpsWatchdog: Job? = null
    private var lastAcceptedMs = 0L
    private val initialGate = InitialRouteGate()
    @Volatile private var initialToken = java.util.UUID.randomUUID().toString()
    private data class InitialSelection(val token: String,val segment: Int,val requestedMs: Long)
    private val initialSelection = java.util.concurrent.atomic.AtomicReference<InitialSelection?>(null)
    private var initialOverrideSegment: Int? = null
    fun selectInitialRoute(token: String, segment: Int) {
        initialSelection.set(InitialSelection(token,segment,SystemClock.elapsedRealtime()))
    }
    private val advanceGuard = RouteAdvanceGuard()
    private var guardedProgress: com.example.displayconnect.routing.TripProgress? = null
    private var traceRouteEpoch = 0
    private var motionEvidenceAtMs = 0L

    private fun traceRoute(value: RouteData, reason: String) {
        traceRouteEpoch++
        initialGate.reset(); initialToken=java.util.UUID.randomUUID().toString(); initialSelection.set(null);initialOverrideSegment=null
        TransmissionHub.updateInitialRoute(null)
        advanceGuard.reset(); guardedProgress = null; motionEvidenceAtMs = 0L
        val geometryId=java.security.MessageDigest.getInstance("SHA-256").digest(
            value.coordinates.joinToString(";") { "${it.lat},${it.lon}" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        trace.record("route", mapOf("epoch" to traceRouteEpoch,"geometry_id" to geometryId, "reason" to reason, "distance_m" to value.distanceM,
            "duration_s" to value.durationS, "point_count" to value.coordinates.size, "gpx" to gpxMode))
        value.snappedWaypoints.forEachIndexed { i, p ->
            trace.record("route_waypoint",mapOf("epoch" to traceRouteEpoch,"index" to i,"lat" to p.lat,"lon" to p.lon,
                "snap_distance_m" to value.waypointDistancesM.getOrNull(i)))
        }
        value.coordinates.chunked(128).forEachIndexed { i, chunk ->
            trace.record("route_geometry", mapOf("epoch" to traceRouteEpoch,"chunk" to i,
                "coordinates_lat_lon" to chunk.joinToString(";") { "${it.lat},${it.lon}" }))
        }
        value.segmentDistancesM.chunked(128).forEachIndexed { i, chunk ->
            trace.record("route_segment_distances", mapOf("epoch" to traceRouteEpoch,"chunk" to i,"metres" to chunk.joinToString(",")))
        }
        value.steps.forEachIndexed { i, step ->
            trace.record("route_step",mapOf("epoch" to traceRouteEpoch,"index" to i,"street" to step.street,
                "maneuver" to step.maneuverType,"at_m" to step.maneuverAtM))
        }
    }
    @Volatile private var lastMessage: NavMessage? = null
    private val statsTracker = StatsTracker()
    private val speedLimitMatcher = com.example.displayconnect.routing.SpeedLimitMatcher()

    fun startNavigation(destLat: Double, destLon: Double) {
        beginNavigation(LatLon(destLat, destLon), prepared = null)
    }

    /** Navega una ruta ya preparada (importada de GPX), sin llamar al router. */
    fun startNavigationWithRoute(prepared: RouteData, destLat: Double, destLon: Double) {
        beginNavigation(LatLon(destLat, destLon), prepared = prepared)
    }

    private fun beginNavigation(dest: LatLon, prepared: RouteData?) {
        stopNavigation()
        tripStartMs = SystemClock.elapsedRealtime()
        tripDistanceM = 0.0
        tripMaxSpeedKmh = 0
        tripLastLat = Double.NaN
        tripLastLon = Double.NaN
        MapsHtmlHolder.clear()
        TransmissionHub.clearNavigationError()
        destination = dest
        gpxMode = prepared != null
        gpxInitialJoinPending = prepared != null
        TransmissionHub.setNavigating(true)
        locationJob = scope.launch {
            var stage = NavigationStage.POSITION
            trace.record("session_start", mapOf("source" to if(boardGnss) "hecate" else "phone", "dest_lat" to dest.lat, "dest_lon" to dest.lon,"guard_policy" to "hecate-normal-v1"))
            try {
                val settings = settingsProvider()
                activeSettings = settings
                trace.record("settings",mapOf("profile" to settings.routeProfile.storageKey,"nav_hz" to settings.navUpdateHz))
                gpsFilter = GpsFilter(when (settings.routeProfile) {
                    com.example.displayconnect.routing.RouteProfile.WALKING -> 8.0
                    com.example.displayconnect.routing.RouteProfile.BIKE -> 25.0
                    else -> 70.0
                })
                val intervalMs = 1000L / settings.navUpdateHz.coerceIn(1, 5)
                if (navClient.connectionState.value == com.example.displayconnect.models.ConnectionState.CONNECTED) {
                    navClient.sendNavMessage(NavMessage.loading())
                }
                withPositionSession(locationTracker, intervalMs) { positions ->
                    val originUpdate = awaitFirstPosition(positions, boardGnss) { acceptUpdate(it) }
                    val origin = LatLon(originUpdate.lat, originUpdate.lon)
                    val dest = destination ?: return@withPositionSession
                    // Reopening the same trip near its route works without another routing request.
                    stage = NavigationStage.ROUTE
                    val selected = if (prepared != null && prepared.coordinates.size >= 2) {
                        trace.record("route_selection",mapOf("source" to "gpx","stage" to "selected"))
                        prepared
                    } else {
                        val saved = offlineMap.findRoute(origin, dest, settings.routeProfile)
                        trace.record("route_selection",mapOf("stage" to "cache_check","hit" to (saved!=null),"reason" to if(saved!=null) "compatible_saved_route" else "no_compatible_saved_route",
                            "origin_lat" to origin.lat,"origin_lon" to origin.lon,"profile" to settings.routeProfile.storageKey))
                        if(saved!=null) {
                            trace.record("route_selection",mapOf("source" to "cache","reason" to "same_destination_profile_projectable",
                                "cache_age_ms" to null))
                            saved
                        } else {
                            trace.record("route_selection",mapOf("source" to "network","stage" to "requested"))
                            routeProvider.fetchRoute(origin, dest, settings.routeProfile).getOrElse {
                                trace.record("route_selection",mapOf("source" to "network","stage" to "failed"))
                                error("Não foi possível calcular a rota. Confira a internet e o destino.")
                            }.also { trace.record("route_selection",mapOf("source" to "network","stage" to "selected")) }
                        }
                    }
                    stage = NavigationStage.CACHE
                    offlineMap.saveRoute(selected, dest, settings.routeProfile)
                    route = selected
                    progressCalculator = RouteProgressCalculator(selected)
                    traceRoute(selected, "initial")
                    retryOfflineDownload()
                    gpsWatchdog = scope.launch {
                        while (true) {
                            delay(1500)
                            if (SystemClock.elapsedRealtime() - lastAcceptedMs > 5000) {
                                TransmissionHub.updateGps(TransmissionHub.gps.value.copy(weak = true, hecate = boardGnss))
                                sendWeakGpsFrame()
                            }
                        }
                    }
                    if (SystemClock.elapsedRealtime() - originUpdate.elapsedMs < 3000) publishUpdate(originUpdate, settings)
                    stage = NavigationStage.STREAM
                    positions.collect {
                        if (acceptUpdate(it)) publishUpdate(it, settings) else if (TransmissionHub.gps.value.weak) sendWeakGpsFrame()
                    }
                    error("position_stream_ended")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                downloadJob?.cancel()
                gpsWatchdog?.cancel()
                TransmissionHub.setNavigating(false)
                val failure = navigationFailure(stage, e)
                Log.w("DisplayConnectNav", "navigation_timeout code=${failure.name} stage=${stage.name}")
                trace.record("session_failure",mapOf("stage" to stage.name,"failure" to failure.name))
                TransmissionHub.navigationFailed(navigationFailureMessage(failure, AppLanguage.language.value))
                onNavigationEnded()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                downloadJob?.cancel()
                gpsWatchdog?.cancel()
                TransmissionHub.setNavigating(false)
                val failure = navigationFailure(stage, e)
                Log.w("DisplayConnectNav", "navigation_failed code=${failure.name} stage=${stage.name}")
                trace.record("session_failure",mapOf("stage" to stage.name,"failure" to failure.name))
                TransmissionHub.navigationFailed(navigationFailureMessage(failure, AppLanguage.language.value))
                onNavigationEnded()
            } finally {
                trace.record("session_end", emptyMap())
            }
        }
    }

    fun retryOfflineDownload() {
        if (downloadJob?.isActive == true) return
        val currentRoute = route ?: return
        val settings = activeSettings ?: return
        // A route (re)prepare owns the network now: stop any viewport top-up so the
        // two never overlap, and so a stale viewport is not fetched after a reroute.
        viewportTopUpJob?.cancel()
        viewportTopUpJob = null
        downloadJob = scope.launch {
            offlineMap.prepare(currentRoute, settings.mapScaleMeters) { progress ->
                withContext(Dispatchers.Main) { TransmissionHub.updateOfflineMap(progress) }
            }
        }
    }

    /** Retains the closing job before stopNavigation clears it. Joining also waits
     * for withPositionSession's owner and its non-cancellable STOP/raw retrieval. */
    suspend fun stopNavigationAndAwaitClosure(): Boolean {
        val closing=locationJob
        stopNavigation()
        closing?.join()
        return (locationTracker as? HecateGnssSource)?.closureConfirmed ?: true
    }

    fun stopNavigation() {
        if (locationJob != null) trace.record("session_stop", emptyMap())
        advanceGuard.reset(); guardedProgress = null; motionEvidenceAtMs = 0L
        initialGate.reset(); initialSelection.set(null); initialOverrideSegment=null; TransmissionHub.updateInitialRoute(null)
        cancelTimedJoin()
        hasJoinedTrack = false
        gpxResumeSeed = null
        joinRetryAfterMs = 0L
        quoteCompletedMs = 0L
        weakJoinCancellation.set(false)
        pendingJoinSelection.set(-1)
        rerouteJob?.cancel()
        rerouteJob = null
        reroutePolicy.reset()
        rerouteFailed = false
        locationJob?.cancel()
        locationJob = null
        downloadJob?.cancel()
        downloadJob = null
        viewportTopUpJob?.cancel()
        viewportTopUpJob = null
        gpsWatchdog?.cancel()
        gpsWatchdog = null
        gpsFilter = null
        lastMessage = null
        lastAcceptedMs = 0
        navClient.shadowGpsSource.clear()
        gpxMode = false
        gpxTrack = null
        gpxRejoinActive = false
        gpxRejoinStrikes = 0
        rerouteIsGpxConnector = false
        gpxRejoinTrackRemainingM = 0.0
        gpxRejoinTrackRemainingS = 0.0
        gpxRejoinAttempts = 0
        gpxRejoinAmbiguous = false
        gpxJoinCandidates = emptyList()
        gpxInitialJoinPending = false
        lastRawPosition = null
        // 0.23: resumen de la ruta al terminar (si hubo ruta de verdad).
        val tripDurationS = ((SystemClock.elapsedRealtime() - tripStartMs) / 1000).toInt()
        if (tripDistanceM >= 100 && tripDurationS >= 30) {
            TransmissionHub.publishTripSummary(
                com.example.displayconnect.utils.TransmissionHub.TripSummary(
                    distanceM = tripDistanceM.toInt(),
                    durationS = tripDurationS,
                    maxSpeedKmh = tripMaxSpeedKmh,
                    avgSpeedKmh = if (tripDurationS > 0) (tripDistanceM / tripDurationS * 3.6).toInt() else 0
                )
            )
        }
        tripDistanceM = 0.0
        tripMaxSpeedKmh = 0
        tripLastLat = Double.NaN
        tripLastLon = Double.NaN
        TransmissionHub.updateGpxJoinOptions(emptyList())
        route = null
        progressCalculator = null
        destination = null
        activeSettings = null
        TransmissionHub.setNavigating(false)
        statsTracker.reset()
        altitudeEstimator.reset()
        speedLimitMatcher.reset()
    }

    /**
     * Eleccion EXPRESA del usuario entre las ramas de enganche publicadas en la
     * app ("Ruta ambigua"). Hasta que el usuario elige no hay conector alguno;
     * al elegir se pide la ruta de acceso por carretera a ESA rama (la misma
     * guia de acceso que el conector automatico) y la traza continuara desde
     * ahi. No esta limitada por el tope de 3 conectores automaticos: es una
     * decision del usuario, no un reintento.
     */
    fun selectGpxJoinCandidate(index: Int) { pendingJoinSelection.set(index) }

    private fun applyGpxJoinCandidate(index: Int) {
        val resolved = if (index >= 1_000_000) {
            val offset = index - (1_000_000 + joinEpoch * 100)
            if (offset !in gpxJoinCandidates.indices) return
            offset
        } else {
            // Old screen events cannot select a new timed choice by position.
            if (joinQuoteOrigin != null) return
            index
        }
        val candidate = gpxJoinCandidates.getOrNull(resolved) ?: return
        cancelTimedJoin()
        // Primer enganche ambiguo estando SOBRE la traza: no hace falta conector
        // (ya esta en la carretera de la ruta); se adjunta la rama elegida.
        if (gpxInitialJoinPending && gpxMode) {
            progressCalculator?.seed(candidate.segmentIndex)
            gpxInitialJoinPending = false
            hasJoinedTrack = true
            gpxJoinCandidates = emptyList()
            TransmissionHub.updateGpxJoinOptions(emptyList())
            gpxRejoinAmbiguous = false
            Log.i("DisplayConnectRoute", "GPX initial join: user picked branch seg=${candidate.segmentIndex} (km ${(candidate.progressM / 1000).toInt()})")
            return
        }
        val track = (if (gpxRejoinActive) gpxTrack else route) ?: return
        val origin = lastRawPosition ?: return
        val settings = activeSettings ?: return
        val parent = locationJob ?: return
        if (rerouteJob != null) return
        gpxJoinCandidates = emptyList()
        TransmissionHub.updateGpxJoinOptions(emptyList())
        gpxRejoinAmbiguous = false
        // "Empezar desde el inicio" es literal: si la rama elegida esta en el
        // primer km de traza, el conector va al PUNTO inicial exacto (km 0) y
        // la traza entera queda por delante; no a una proyeccion a 900 m que
        // prometeria un inicio que no es.
        val nearStart = candidate.progressM < 1000.0
        val targetPoint = if (nearStart) track.coordinates.first() else candidate.point
        val (restM, restS) = if (nearStart) TrackRejoin.remainingFrom(track, 0, 0.0)
            else TrackRejoin.remainingFrom(track, candidate.segmentIndex, candidate.fraction)
        gpxRejoinTrackRemainingM = restM
        gpxRejoinTrackRemainingS = restS
        gpxResumeSeed = if (nearStart) 0 else candidate.segmentIndex
        reroutePolicy.requestStarted(SystemClock.elapsedRealtime())
        rerouteFailed = false
        rerouteIsGpxConnector = true
        val departure = if (autoSelection) lastReliableDeparture else null
        rerouteJob = scope.async(Dispatchers.IO + parent) {
            rejoinRoute(origin, targetPoint, settings.routeProfile,
                departure)
        }
        Log.i("DisplayConnectRoute", "GPX rejoin: user picked branch seg=${candidate.segmentIndex} (${candidate.distanceM.toInt()} m, km ${(candidate.progressM / 1000).toInt()}, startSnap=$nearStart)")
    }

    private fun cancelTimedJoin() {
        if (joinQuoteOrigin != null) joinEpoch++
        joinQuotes?.cancel()
        joinQuotes = null
        joinQuoteResults = null
        joinQuoteOrigin = null
        joinCountdown.cancel()
        // Keep manual choices but remove an obsolete countdown immediately.
        TransmissionHub.updateGpxJoinOptions(TransmissionHub.gpxJoinOptions.value.map {
            it.copy(autoRemainingMs = null, autoFraction = 0f)
        })
    }

    private suspend fun updateTimedJoin(
        track: RouteData, candidates: List<TrackRejoin.JoinCandidate>, origin: LatLon,
        raw: LocationUpdate, settings: AppSettings, nowMs: Long
    ) {
        if (gpxInitialJoinPending || rerouteJob != null) return
        if (!joinCountdown.eligible(hasJoinedTrack, gpxInitialJoinPending, true,
                nowMs - raw.elapsedMs, raw.accuracyM)) {
            gpxJoinCandidates = candidates
            TransmissionHub.updateGpxJoinOptions(candidates.mapIndexed { i, c ->
                TransmissionHub.GpxJoinOption(i, c.progressM, c.distanceM, c.bearing, c.progressM < 1000.0)
            })
            return
        }
        val oldOrigin = joinQuoteOrigin
        // Candidate order is geometric and can swap with centimetres of GPS noise.
        // Match each branch to the previous frame by nearby progress, not by its row index.
        val stable = com.example.displayconnect.routing.RejoinBranches.align(gpxJoinCandidates, candidates)
        val changed = stable.size != gpxJoinCandidates.size ||
            stable.zip(gpxJoinCandidates).any { (a, b) ->
                kotlin.math.abs(a.progressM - b.progressM) > 100.0 ||
                    GpsFilter.distanceM(a.point, b.point) > 40.0 ||
                    TrackRejoin.bearingDiff(a.bearing, b.bearing) > 55.0
            }
        val headingChanged = quotedDeparture?.let { old ->
            lastReliableDeparture?.let { TrackRejoin.bearingDiff(old, it) > 55.0 } ?: false
        } == true
        if (oldOrigin != null && (changed || headingChanged)) cancelTimedJoin()
        // Follow a branch smoothly while riding. Network quotes retain their own immutable targets.
        if (joinQuoteOrigin != null) gpxJoinCandidates = stable
        val dueNeedsRefresh = joinCountdown.tick(nowMs)?.due == true &&
            (nowMs - quoteCompletedMs > 2_000 || GpsFilter.distanceM(oldOrigin ?: origin, origin) > 30.0)
        val refresh = oldOrigin != null && joinQuotes == null && nowMs >= joinRetryAfterMs &&
            (GpsFilter.distanceM(oldOrigin, origin) > 150.0 || nowMs - quoteCompletedMs >= 10_000 || dueNeedsRefresh)
        if (joinQuoteOrigin == null || refresh) {
            if (!refresh) { gpxJoinCandidates = stable; joinEpoch++ }
            joinQuoteOrigin = origin
            joinQuoteStartedMs = nowMs
            val departure = lastReliableDeparture
            quotedDeparture = departure
            val snapshot = gpxJoinCandidates.toList()
            val parent = locationJob ?: return
            // Public routing service: bounded sequential requests, never one per GPS frame.
            joinQuotes = scope.async(Dispatchers.IO + parent) {
                if (candidates.size !in 2..4) return@async candidates.map { JoinQuote(it, null) }
                try { withTimeout(12_000) {
                    snapshot.map { c ->
                        val target = if (c.progressM < 1000.0) track.coordinates.first() else c.point
                        val road = rejoinRoute(origin, target, settings.routeProfile, departure)
                            .getOrNull()?.takeIf { q ->
                                q.coordinates.size >= 2 && q.distanceM.isFinite() && q.distanceM >= 0 &&
                                    GpsFilter.distanceM(q.coordinates.first(), origin) <= 30.0 &&
                                    GpsFilter.distanceM(q.coordinates.last(), target) <= 30.0
                            }
                        JoinQuote(c, road)
                    }
                } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    candidates.map { JoinQuote(it, null) }
                }
            }
        }
        val pending = joinQuotes
        if (pending != null && pending.isCompleted) {
            joinQuotes = null
            joinQuoteResults = try { pending.await() }
                catch (e: kotlinx.coroutines.TimeoutCancellationException) { gpxJoinCandidates.map { JoinQuote(it, null) } }
            quoteCompletedMs = nowMs
            val quotes = joinQuoteResults.orEmpty()
            joinRetryAfterMs = if (quotes.any { it.road == null }) nowMs + 30_000 else nowMs
            val winner = joinCountdown.winner(quotes.mapIndexed { i, q ->
                com.example.displayconnect.routing.RejoinCountdown.Quote(i, q.road?.distanceM,
                    lastReliableDeparture?.let { TrackRejoin.bearingDiff(it, q.candidate.bearing) <= 55.0 } == true)
            })
            if (winner == null) joinCountdown.cancel()
            else if (joinCountdown.tick(nowMs)?.id != winner) joinCountdown.start(winner, nowMs)
        }
        // Losing reliable direction during a tie invalidates its winner.
        val results = joinQuoteResults
        if (results != null) {
            val winner = joinCountdown.winner(results.mapIndexed { i, q ->
                com.example.displayconnect.routing.RejoinCountdown.Quote(i, q.road?.distanceM,
                    lastReliableDeparture?.let { TrackRejoin.bearingDiff(it, q.candidate.bearing) <= 55.0 } == true)
            })
            if (joinCountdown.tick(nowMs)?.id != winner) joinCountdown.cancel()
        }
        val tick = joinCountdown.tick(nowMs)
        TransmissionHub.updateGpxJoinOptions(gpxJoinCandidates.mapIndexed { i, c ->
            TransmissionHub.GpxJoinOption(1_000_000 + joinEpoch * 100 + i,
                c.progressM, c.distanceM, c.bearing, c.progressM < 1000.0,
                results?.getOrNull(i)?.road?.distanceM,
                tick?.takeIf { it.id == i }?.remainingMs,
                tick?.takeIf { it.id == i }?.fraction ?: 0f,
                if (pending != null && !pending.isCompleted) "Calculando acceso..."
                else if (tick == null) "Elección manual: acceso incompleto o empate" else null)
        })
        if (tick?.due == true && joinQuotes == null &&
            nowMs - quoteCompletedMs <= 15_000 &&
            GpsFilter.distanceM(joinQuoteOrigin ?: origin, origin) <= 250.0 &&
            pendingJoinSelection.get() < 0 && nowMs - raw.elapsedMs in 0..3000 && raw.accuracyM in 0f..25f) {
            // Compare fresh quotes first, then fetch the chosen connector from the current fix.
            // Use the exact target that was compared, not a newer nearby projection.
            gpxJoinCandidates = results!!.map { it.candidate }
            autoSelection = true
            try { applyGpxJoinCandidate(1_000_000 + joinEpoch * 100 + tick.id) }
            finally { autoSelection = false }
        }
    }

    private fun acceptUpdate(update: LocationUpdate): Boolean {
        val accepted = gpsFilter?.accept(GpsSample(LatLon(update.lat, update.lon), update.accuracyM,
            update.speedMps, update.bearing, update.elapsedMs), SystemClock.elapsedRealtime()) == true
        if (boardGnss) navClient.shadowGpsSource.clear()
        else navClient.shadowGpsSource.update(com.example.displayconnect.network.ShadowFix(update.elapsedMs, accepted, update.speedMps, update.accuracyM))
        trace.record("position_filter", mapOf("accepted" to accepted,"lat" to update.lat,"lon" to update.lon,
            "accuracy_estimate_m" to update.accuracyM,"speed_mps" to update.speedMps,"fix_elapsed_ms" to update.elapsedMs))
        if (accepted) lastAcceptedMs = update.elapsedMs
        if (accepted || update.elapsedMs > lastAcceptedMs || SystemClock.elapsedRealtime() - lastAcceptedMs > 5000) {
            TransmissionHub.updateGps(GpsStatus(update.accuracyM.takeIf { it.isFinite() }, lastAcceptedMs, !accepted, boardGnss))
        }
        Log.d("DisplayConnectGPS", "accepted=$accepted accuracy=${update.accuracyM} ageMs=${SystemClock.elapsedRealtime() - update.elapsedMs}")
        return accepted
    }

    private suspend fun holdUnconfirmedInitial(raw: LocationUpdate, calc: RouteProgressCalculator?): Boolean {
        currentCoroutineContext().ensureActive()
        if(initialGate.confirmed) return false
        val point=LatLon(raw.lat,raw.lon)
        val candidates=calc?.initialCandidates(point,raw.accuracyM) ?: emptyList()
        val preview=calc?.update(point,commit=false) ?: com.example.displayconnect.routing.TripProgress()
        val reason=initialGate.assess(candidates,preview,raw.accuracyM)
        val fresh=SystemClock.elapsedRealtime()-raw.elapsedMs in 0..3000 && raw.accuracyM in 0f..40f
        val choice=initialSelection.getAndSet(null)
        if(reason!=null) {
            trace.record("initial_match",mapOf("epoch" to traceRouteEpoch,"reason" to reason,
                "accuracy_estimate_m" to raw.accuracyM,"fresh" to fresh,"candidate_count" to candidates.size))
            candidates.forEach { trace.record("initial_candidate",mapOf("epoch" to traceRouteEpoch,
                "segment" to it.segment,"remaining_m" to it.remainingM,"lateral_m" to it.distanceM)) }
            if(choice!=null) {
                val valid=choice.token==initialToken && fresh &&
                    SystemClock.elapsedRealtime()-choice.requestedMs in 0..3000
                trace.record("initial_choice",mapOf("epoch" to traceRouteEpoch,"segment" to choice.segment,
                    "fresh_token" to valid,"kind" to if(choice.segment==-2) "user_confirms_destination" else "user_selects_branch"))
                if(valid && choice.segment==-2) {
                    // User's explicit statement, not a GPS arrival determination.
                    onNavigationEnded(); return true
                }
                if(valid && initialGate.confirmExplicit(choice.segment,candidates)) {
                    initialOverrideSegment=choice.segment
                    advanceGuard.reset();guardedProgress=null;motionEvidenceAtMs=0
                    TransmissionHub.updateInitialRoute(null)
                    return false
                }
            }
            TransmissionHub.updateInitialRoute(TransmissionHub.InitialRouteUi(initialToken,
                candidates.map {
                    val total=route?.distanceM ?: 0.0
                    val label=when {
                        it.remainingM<=maxOf(30.0,total*0.1) -> "Tramo final"
                        it.remainingM>=total*0.9 -> "Inicio de ruta"
                        else -> "Tramo intermedio"
                    }
                    TransmissionHub.InitialRouteChoice(it.segment,it.remainingM,it.distanceM.toInt(),label)
                },fresh))
            TransmissionHub.updateProgressGuard("Inicio sin confirmar. Elige un tramo solo si lo reconoces, o espera y para.")
            val trip=com.example.displayconnect.routing.TripProgress()
            TransmissionHub.updateTripProgress(trip)
            val message=NavMessage(raw.lat,raw.lon,0f,"Inicio sin confirmar",-1,"",emptyList(),
                userScreenPoint=null,remainingDistanceM=null,remainingDurationS=null,
                language=AppLanguage.language.value,gpsWeak=!fresh,maneuver="unknown")
            lastMessage=message;TransmissionHub.updateLiveNav(message)
            trace.record("nav_frame",mapOf("epoch" to traceRouteEpoch,"remaining_m" to null,
                "maneuver" to "unknown","distance_to_maneuver_m" to -1,"initial_unconfirmed" to true))
            navClient.sendNavMessage(message.toJson())
            return true
        }
        trace.record("initial_match",mapOf("epoch" to traceRouteEpoch,"reason" to "unique_nonarrival",
            "candidate_count" to candidates.size))
        TransmissionHub.updateInitialRoute(null)
        return false
    }

    private fun sendWeakGpsFrame() {
        weakJoinCancellation.set(true)
        TransmissionHub.updateGpxJoinOptions(TransmissionHub.gpxJoinOptions.value.map {
            it.copy(autoRemainingMs = null, autoFraction = 0f)
        })
        altitudeEstimator.reset()
        TransmissionHub.initialRoute.value?.let { TransmissionHub.updateInitialRoute(it.copy(fresh=false)) }
        val previous = lastMessage ?: return
        if (previous.gpsWeak) return
        trace.record("nav_weak_frame",mapOf("epoch" to traceRouteEpoch,"initial_unconfirmed" to
            (TransmissionHub.initialRoute.value!=null),"reason" to "position_timeout"))
        previous.copy(altitudeWgs84M = null, altitudeAccuracyM = null, gpsWeak = true, speedKmh = null, speedLimitKmh = null, speedLimitState = "gps_weak").also { lastMessage = it; navClient.sendNavMessage(it.toJson()) }
    }

    private suspend fun publishUpdate(raw: LocationUpdate, settings: AppSettings) = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        if (weakJoinCancellation.getAndSet(false)) { cancelTimedJoin(); pendingJoinSelection.set(-1) }
        val buildStarted = SystemClock.elapsedRealtime()
        var currentRoute = route ?: return@withContext
        val rawPosition = LatLon(raw.lat, raw.lon)
        lastRawPosition = rawPosition
        val nowMs = SystemClock.elapsedRealtime()
        val fresh = nowMs - raw.elapsedMs in 0..3000
        // Rumbo fiable solo en movimiento y con fix razonable: se usa para
        // desambiguar ramas proximas (ida/vuelta) en RouteProgressCalculator.
        val reliableBearing = raw.bearing.takeIf {
            fresh && it.isFinite() && raw.speedMps >= 2.5f && raw.accuracyM <= 30f
        }
        lastReliableDeparture = com.example.displayconnect.routing.ForwardReroute.reliableBearing(
            raw.bearing, raw.speedMps, raw.accuracyM)
        val guardActive = boardGnss && !gpxMode
        val calculator = progressCalculator
        if (guardActive && holdUnconfirmedInitial(raw, calculator)) return@withContext
        val explicitSegment=initialOverrideSegment.also { initialOverrideSegment=null }
        val preview = calculator?.update(rawPosition, reliableBearing, commit = !guardActive,
            explicitInitialSegment=explicitSegment)
            ?: com.example.displayconnect.routing.TripProgress()
        val decision = if (guardActive) advanceGuard.evaluate(RouteAdvanceGuard.Fix(rawPosition,
            raw.accuracyM, raw.speedMps, raw.elapsedMs), preview.remainingDistanceM) else null
        var progress = if (decision == null || decision.accept) {
            if (guardActive) calculator?.update(rawPosition,reliableBearing,explicitInitialSegment=explicitSegment)
            preview.also { guardedProgress = it }
        } else guardedProgress ?: preview
        TransmissionHub.updateProgressGuard(if (decision?.warn == true)
            "Avance retenido: movimiento o tramo sin confirmar. La guia conserva el ultimo avance aceptado." else null)
        trace.record("progress", mapOf("epoch" to traceRouteEpoch,"lat" to raw.lat,"lon" to raw.lon,
            "candidate_segment" to preview.matchedSegment,"shown_segment" to progress.matchedSegment,
            "candidate_remaining_m" to preview.remainingDistanceM,"shown_remaining_m" to progress.remainingDistanceM,
            "route_distance_m" to preview.distanceFromRouteM,"guard_reason" to decision?.reason,
            "guard_accepted" to decision?.accept,"displacement_m" to decision?.displacementM,
            "along_delta_m" to decision?.alongDeltaM,"motion_evidence" to decision?.motionEvidence))
        // Hold ALL branch/rejoin/reroute mutations when the same fix cannot justify progress.
        if (decision?.accept == true && decision.motionEvidence) motionEvidenceAtMs = nowMs
        val allowRouteMutation = decision == null ||
            (decision.reason != "route_jump" && motionEvidenceAtMs > 0 && nowMs - motionEvidenceAtMs <= 5000)
        if (allowRouteMutation || (gpxInitialJoinPending && decision?.reason == "initial")) {
        // Primer enganche de una traza GPX: si varias ramas estan a tiro de GPS
        // y el rumbo no distingue una sola, NO se elige en silencio (ni la mas
        // cercana ni el inicio): se publican las ramas y el usuario elige en la
        // app; el progreso queda suprimido hasta entonces. Si no esta sobre la
        // traza, lo gestiona el reenganche normal (con las mismas opciones).
        if (gpxInitialJoinPending && gpxMode && fresh && raw.accuracyM in 0f..40f) {
            val margin = maxOf(15.0, raw.accuracyM.toDouble() * 2)
            val attachCandidates = TrackRejoin.nearestJoinCandidates(currentRoute.coordinates, rawPosition, margin)
            val onTrack = attachCandidates.isNotEmpty() &&
                attachCandidates[0].distanceM <= maxOf(30.0, raw.accuracyM.toDouble() * 2)
            val unique = when {
                !onTrack -> null
                attachCandidates.size == 1 -> attachCandidates[0]
                else -> reliableBearing?.let { heading ->
                    attachCandidates.filter { TrackRejoin.bearingDiff(heading, it.bearing) <= 55.0 }.singleOrNull()
                }
            }
            when {
                !onTrack -> gpxInitialJoinPending = false
                unique != null -> {
                    if (attachCandidates.size > 1) progressCalculator?.seed(unique.segmentIndex)
                    gpxInitialJoinPending = false
                    hasJoinedTrack = true
                    progress = progressCalculator?.update(rawPosition, reliableBearing) ?: progress
                }
                else -> {
                    gpxJoinCandidates = attachCandidates
                    gpxRejoinAmbiguous = true
                    TransmissionHub.updateGpxJoinOptions(attachCandidates.mapIndexed { i, c ->
                        TransmissionHub.GpxJoinOption(i, c.progressM, c.distanceM, c.bearing, c.progressM < 1000.0)
                    })
                    progress = progress.copy(offRoute = true, remainingDistanceM = null, remainingDurationS = null)
                    Log.i("DisplayConnectRoute", "GPX initial join: ambiguous (${attachCandidates.size} branches), waiting for user pick")
                }
            }
        }
        if (gpxMode && !gpxRejoinActive && !gpxInitialJoinPending && fresh &&
            !progress.offRoute && (progress.distanceFromRouteM ?: Double.MAX_VALUE) <= 30.0)
            hasJoinedTrack = true
        val requested = pendingJoinSelection.getAndSet(-1)
        if (requested >= 0) applyGpxJoinCandidate(requested)
        val away = fresh && reroutePolicy.isAway(progress.distanceFromRouteM, raw.accuracyM)
        if (!away || !fresh || raw.accuracyM !in 0f..25f) {
            cancelTimedJoin()
            if (!away && !gpxInitialJoinPending) {
                gpxJoinCandidates = emptyList()
                gpxRejoinAmbiguous = false
                TransmissionHub.updateGpxJoinOptions(emptyList())
            }
        }
        // A completed network request never writes shared route state itself.
        // Install only here, serially with GPS frames and only if still off the old route.
        val pending = rerouteJob
        if (pending != null && pending.isCompleted) {
            val result = pending.await()
            currentCoroutineContext().ensureActive()
            rerouteJob = null
            reroutePolicy.requestFinished(nowMs)
            val replacement = result.getOrNull()
            val calculator = replacement?.takeIf { it.coordinates.size >= 2 }?.let { RouteProgressCalculator(it) }
            val candidateProgress = calculator?.update(rawPosition)
            val applicable = away && candidateProgress?.distanceFromRouteM?.let {
                it <= maxOf(30.0, raw.accuracyM.toDouble() * 2)
            } == true
            if (replacement != null && calculator != null && applicable) {
                currentCoroutineContext().ensureActive()
                if (rerouteIsGpxConnector) {
                    if (!gpxRejoinActive) gpxTrack = currentRoute
                    gpxRejoinActive = true
                    gpxRejoinStrikes = 0
                    gpxRejoinAttempts++
                }
                trace.record("route_selection",mapOf("source" to "network","stage" to "reroute_selected",
                    "origin_lat" to raw.lat,"origin_lon" to raw.lon))
                route = replacement
                traceRoute(replacement, "reroute_installed")
                progressCalculator = calculator
                currentRoute = replacement
                progress = candidateProgress!!
                reroutePolicy.clearEvidence()
                rerouteFailed = false
                downloadJob?.cancel()
                downloadJob = null
                retryOfflineDownload()
                // Cache persistence is secondary: failure must not stop live navigation.
                destination?.let { dest ->
                    try { offlineMap.saveRoute(replacement, dest, settings.routeProfile) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.w("DisplayConnectRoute", "New route cache failed", e) }
                }
                Log.i("DisplayConnectRoute", "Automatic reroute installed")
            } else if (away) {
                rerouteFailed = true
                Log.w("DisplayConnectRoute", "Reroute failed or no longer near current position")
            }
        }
        // Reincorporacion GPX: con el conector instalado, se vuelve a la traza
        // en cuanto el GPS confirma dos frames seguidos a tiro de rueda de ella.
        if (gpxMode && gpxRejoinActive && fresh) {
            val track = gpxTrack
            if (track != null && raw.accuracyM in 0f..40f &&
                TrackRejoin.distanceToPolyline(track.coordinates, rawPosition) <=
                    maxOf(30.0, raw.accuracyM.toDouble() * 2)) {
                gpxRejoinStrikes++
            } else {
                gpxRejoinStrikes = 0
            }
            if (gpxRejoinStrikes >= 2 && track != null) {
                route = track
                traceRoute(track, "gpx_track_resumed")
                val calculator = RouteProgressCalculator(track)
                gpxResumeSeed?.let { calculator.seed(it) }
                gpxResumeSeed = null
                progressCalculator = calculator
                currentRoute = track
                gpxRejoinActive = false
                gpxRejoinStrikes = 0
                gpxTrack = null
                gpxRejoinAttempts = 0
                gpxRejoinAmbiguous = false
                cancelTimedJoin()
                gpxJoinCandidates = emptyList()
                TransmissionHub.updateGpxJoinOptions(emptyList())
                gpxRejoinTrackRemainingM = 0.0
                gpxRejoinTrackRemainingS = 0.0
                reroutePolicy.clearEvidence()
                progress = calculator.update(rawPosition, reliableBearing)
                retryOfflineDownload()
                Log.i("DisplayConnectRoute", "GPX rejoin: track resumed")
            }
        }
        if (away && joinQuoteOrigin != null && rerouteJob == null && !gpxInitialJoinPending) {
            val track = (if (gpxRejoinActive) gpxTrack else currentRoute)
            if (track != null) updateTimedJoin(track,
                TrackRejoin.nearestJoinCandidates(track.coordinates, rawPosition,
                    maxOf(15.0, raw.accuracyM.toDouble() * 2)),
                rawPosition, raw, settings, nowMs)
        }
        val confirmed = reroutePolicy.observe(progress.distanceFromRouteM, raw.accuracyM,
            raw.elapsedMs, nowMs)
        if (!gpxMode && rerouteJob == null && confirmed) {
            destination?.let { dest ->
                reroutePolicy.requestStarted(nowMs)
                rerouteFailed = false
                rerouteIsGpxConnector = false
                // Child of the whole navigation, not of this frame: BLE continues while waiting.
                // Cancellation cannot install a response into a later trip.
                currentCoroutineContext().ensureActive()
                val navigationParent = locationJob ?: return@withContext
                rerouteJob = scope.async(Dispatchers.IO + navigationParent) {
                    routeProvider.fetchRoute(rawPosition, dest, settings.routeProfile,
                        com.example.displayconnect.routing.ForwardReroute.reliableBearing(
                            raw.bearing, raw.speedMps, raw.accuracyM))
                }
                trace.record("reroute_requested", mapOf("epoch" to traceRouteEpoch,"lat" to raw.lat,"lon" to raw.lon))
                Log.i("DisplayConnectRoute", "Automatic reroute requested")
            }
        } else if (gpxMode && rerouteJob == null && confirmed) {
            // Modo GPX: ruta de acceso al PUNTO de la traza mas cercano (proyeccion
            // en segmento), nunca reruta al destino. Ramas coincidentes (ida/vuelta
            // o cierre circular) solo se resuelven si el rumbo fiable distingue
            // UNA sola; si no distingue, NO se elige indice menor ni nada parecido:
            // es ambiguo, no hay ruta automatica: se dice expresamente y la app
            // ofrece las ramas para que el usuario elija (nunca por cercania). Al
            // enganchar, la traza continua desde ese punto (por delante o por
            // detras, sin monotonia). Tope de 3 conectores sin enganche por
            // sesion: despues se queda en "Fuera de ruta" hasta que se acerque
            // solo (evita bucle si OSRM ajusta el punto lejos de la traza).
            val track = if (gpxRejoinActive) gpxTrack else currentRoute
            if (track != null && gpxRejoinAttempts < 3) {
                val margin = maxOf(15.0, raw.accuracyM.toDouble() * 2)
                val candidates = TrackRejoin.nearestJoinCandidates(track.coordinates, rawPosition, margin)
                val chosen = when {
                    candidates.isEmpty() -> null
                    candidates.size == 1 -> candidates[0]
                    joinQuoteOrigin != null -> null
                    else -> reliableBearing?.let { heading ->
                        candidates.filter { TrackRejoin.bearingDiff(heading, it.bearing) <= 55.0 }
                            .singleOrNull()
                    }
                }
                gpxRejoinAmbiguous = chosen == null && candidates.size > 1
                if (gpxRejoinAmbiguous) {
                    updateTimedJoin(track, candidates, rawPosition, raw, settings, nowMs)
                } else {
                    cancelTimedJoin()
                    gpxJoinCandidates = emptyList()
                    TransmissionHub.updateGpxJoinOptions(emptyList())
                }
                if (chosen != null) {
                    gpxResumeSeed = chosen.segmentIndex
                    val (restM, restS) = TrackRejoin.remainingFrom(track, chosen.segmentIndex, chosen.fraction)
                    gpxRejoinTrackRemainingM = restM
                    gpxRejoinTrackRemainingS = restS
                    reroutePolicy.requestStarted(nowMs)
                    rerouteFailed = false
                    currentCoroutineContext().ensureActive()
                    val navigationParent = locationJob ?: return@withContext
                    rerouteIsGpxConnector = true
                    rerouteJob = scope.async(Dispatchers.IO + navigationParent) {
                        routeProvider.fetchRoute(rawPosition, chosen.point, settings.routeProfile,
                            com.example.displayconnect.routing.ForwardReroute.reliableBearing(
                                raw.bearing, raw.speedMps, raw.accuracyM))
                    }
                    Log.i("DisplayConnectRoute", "GPX rejoin: connector to segment ${chosen.segmentIndex} (${chosen.distanceM.toInt()} m, ${candidates.size} candidates)")
                } else if (candidates.size > 1) {
                    Log.i("DisplayConnectRoute", "GPX rejoin: ambiguous join (${candidates.size} branches), no automatic route")
                }
            } else {
                gpxRejoinAmbiguous = false
                gpxJoinCandidates = emptyList()
                TransmissionHub.updateGpxJoinOptions(emptyList())
            }
        }
        } else { reroutePolicy.clearEvidence() }
        // A replacement is also an unconfirmed initial association, never a free pass.
        if (guardActive && holdUnconfirmedInitial(raw, progressCalculator)) return@withContext
        val deviated = fresh && reroutePolicy.isAway(progress.distanceFromRouteM, raw.accuracyM)
        if (deviated) progress = progress.copy(offRoute = true,
            remainingDistanceM = null, remainingDurationS = null)
        val rerouteInstruction = if (deviated || gpxRejoinAmbiguous) when (AppLanguage.language.value) {
            "es" -> if (rerouteJob != null) "Recalculando ruta..." else if (gpxRejoinAmbiguous)
                "Ruta ambigua - elige en la app" else if (rerouteFailed)
                "Sin ruta nueva - revisa la app" else "Fuera de ruta"
            "en" -> if (rerouteJob != null) "Recalculating route..." else if (gpxRejoinAmbiguous)
                "Ambiguous track - pick a branch in the app" else if (rerouteFailed)
                "No new route - check the app" else "Off route"
            else -> if (rerouteJob != null) "Recalculando rota..." else if (gpxRejoinAmbiguous)
                "Rota ambigua - escolha no app" else if (rerouteFailed)
                "Sem nova rota - confira o app" else "Fora da rota"
        } else null
        val position = GpsFilter.displayPosition(LatLon(raw.lat, raw.lon), progress.matchedPosition,
            progress.distanceFromRouteM, raw.accuracyM, raw.speedMps, raw.bearing, progress.routeBearing)
        val update = raw.copy(lat = position.lat, lon = position.lon)
        // Con conector activo, los totales que ve el usuario (movil y placa)
        // son conector + traza restante; ManeuverSelector sigue usando el
        // progreso relativo al conector (variable progress, intacta).
        val tripForUi = if (gpxRejoinActive && !progress.offRoute) progress.copy(
            remainingDistanceM = progress.remainingDistanceM?.plus(gpxRejoinTrackRemainingM.toInt()),
            remainingDurationS = progress.remainingDurationS?.plus(gpxRejoinTrackRemainingS.toInt())
        ) else progress
        TransmissionHub.updateTripProgress(tripForUi)
        if (navClient.connectionState.value != com.example.displayconnect.models.ConnectionState.CONNECTED) {
            return@withContext
        }

        val next = com.example.displayconnect.routing.ManeuverSelector.next(
            currentRoute, progress.remainingDistanceM, progress.offRoute)
        val step = next?.step
        val distanceM = next?.distanceM ?: 0
        val simplified = MapProjector.simplifyRoute(
            currentRoute.coordinates.map { it.lat to it.lon }
        )
        val screenRoute = MapProjector.projectRoute(
            centerLat = update.lat,
            centerLon = update.lon,
            routePoints = currentRoute.coordinates.map { it.lat to it.lon },
            scaleMeters = settings.mapScaleMeters
        )
        val userPoint = MapProjector.projectPoint(
            centerLat = update.lat,
            centerLon = update.lon,
            lat = update.lat,
            lon = update.lon,
            scaleMeters = settings.mapScaleMeters
        )

        val (streetWays, covered) = offlineMap.streetWays(LatLon(update.lat, update.lon), settings.mapScaleMeters)
        val streets = streetWays.map { it.points }
        val speedKmh = raw.speedMps.takeIf { fresh && raw.accuracyM <= 25f && it.isFinite() && it in 0f..100f }
            ?.let { kotlin.math.round(it * 3.6f).toInt() }
        val limit = speedLimitMatcher.update(rawPosition, raw.accuracyM, raw.speedMps, raw.bearing,
            raw.elapsedMs, streetWays, covered && fresh && !progress.offRoute &&
                settings.routeProfile in listOf(com.example.displayconnect.routing.RouteProfile.CAR,
                    com.example.displayconnect.routing.RouteProfile.MOTORCYCLE), settings.routeProfile)
        // 0.23: limite manual de Ajustes; sustituye al de OSM en la placa.
        val effectiveLimit = if (settings.manualSpeedLimitEnabled) {
            com.example.displayconnect.routing.SpeedLimitReading(kmh = settings.manualSpeedLimitKmh, state = "manual")
        } else limit
        // 0.23: estadisticas de la ruta para el resumen final.
        if (!guardActive || decision?.accept == true) {
        if (!tripLastLat.isNaN() && raw.accuracyM <= 25f) {
            val dLat = Math.toRadians(update.lat - tripLastLat) * 6371000.0
            val dLon = Math.toRadians(update.lon - tripLastLon) * 6371000.0 *
                kotlin.math.cos(Math.toRadians((update.lat + tripLastLat) / 2))
            val stepM = kotlin.math.hypot(dLat, dLon)
            if (stepM in 0.5..200.0) tripDistanceM += stepM
        }
        tripLastLat = update.lat
        tripLastLon = update.lon
        speedKmh?.let { if (it > tripMaxSpeedKmh) tripMaxSpeedKmh = it }
        }
        TransmissionHub.updateMapCoverage(covered)
        if (!covered && downloadJob?.isActive != true && viewportTopUpJob?.isActive != true) {
            // Mid-ride deviation or destination change left the live viewport outside
            // the prepared corridor: fetch only its missing tiles, cache untouched.
            viewportTopUpJob = scope.launch {
                offlineMap.prepareViewport(LatLon(update.lat, update.lon), settings.mapScaleMeters)
            }
        }
        val streetSegments = withContext(Dispatchers.Default) { StreetContextProjector.projectSegments(
            centerLat = update.lat,
            centerLon = update.lon,
            ways = streets,
            scaleMeters = settings.mapScaleMeters,
            // Maneuver arcs first, then whatever keeps the network connected for the
            // active profile (link/major roads motorized, pedestrian-dedicated ways on
            // foot); the rest is sacrificed first under the segment budget.
            priority = { index ->
                streetWays.getOrNull(index)?.tags
                    ?.let { StreetContextProjector.streetTier(it, settings.routeProfile) } ?: 2
            }
        ) }

        // Below ~2.5 m/s GPS bearing is noise (walking or stopped): the arrow follows
        // the route direction, not the physical heading. Same gate as reliableBearing.
        val bearing = if (update.bearing.isFinite() && raw.speedMps >= 2.5f) {
            update.bearing
        } else {
            computeBearingFromRoute(update, simplified)
        }

        val pressure=altitudeMonitor?.state?.value
        val reading=altitudeEstimator.update(raw.altitudeMslM,raw.altitudeWgs84M,
            if(raw.altitudeMslM!=null) raw.altitudeMslAccuracyM else raw.altitudeAccuracyM,
            pressure?.pressureHpa,pressure?.ready==true && nowMs-(pressure.pressureMs) in 0..3000,
            nowMs,raw.elapsedMs,pressure?.pressureMessage ?: "Sin barometro; usando GPS aprox.")
        altitudeMonitor?.publish(reading)
        val altitude=altitudeFilter.update(reading.metres,reading.accuracy,raw.elapsedMs,nowMs)
        val message = NavMessage(
            lat = update.lat,
            lon = update.lon,
            bearing = bearing,
            instruction = rerouteInstruction ?: NavigationText.maneuver(step, AppLanguage.language.value),
            language = AppLanguage.language.value,
            distanceM = distanceM,
            remainingDistanceM = tripForUi.remainingDistanceM,
            remainingDurationS = tripForUi.remainingDurationS,
            routeTotalDistanceM = (if (gpxRejoinActive) currentRoute.distanceM + gpxRejoinTrackRemainingM
                else currentRoute.distanceM).takeIf { it.isFinite() && it > 0 }?.toInt(),
            offRoute = progress.offRoute,
            street = step?.street ?: "",
            routeScreenPoints = screenRoute,
            streetSegments = streetSegments,
            userScreenPoint = userPoint,
            html = MapsHtmlHolder.get(),
            maneuver = step?.let { com.example.displayconnect.routing.ManeuverSelector.code(it) } ?: "unknown",
            maneuverType = step?.maneuverType ?: "",
            maneuverModifier = step?.maneuverModifier ?: "",
            maneuverExit = step?.maneuverExit,
            speedKmh = speedKmh,
            speedLimitKmh = effectiveLimit.kmh,
            speedLimitState = effectiveLimit.state,
            timeHm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
            altitudeWgs84M = altitude,
            altitudeReference = reading.reference, altitudeSource = reading.source,
            altitudeAccuracyM = if (altitude != null) reading.accuracy?.let { kotlin.math.ceil(it.toDouble()).toInt() } else null,
            profile = settings.routeProfile.storageKey
        )

        val json = message.toJson()
        currentCoroutineContext().ensureActive()
        Log.d("DisplayConnectMap", "buildMs=${SystemClock.elapsedRealtime() - buildStarted} segments=${streetSegments.size} bytes=${json.toByteArray().size}")
        if (SystemClock.elapsedRealtime() - raw.elapsedMs > 3000) {
            Log.d("DisplayConnectMap", "Discarded stale map frame")
            return@withContext
        }
        lastMessage = message
        trace.record("nav_frame",mapOf("epoch" to traceRouteEpoch,"remaining_m" to message.remainingDistanceM,
            "maneuver" to message.maneuver,"distance_to_maneuver_m" to message.distanceM,"street" to message.street,
            "speed_kmh" to message.speedKmh,"shown_lat" to message.lat,"shown_lon" to message.lon,
            "guard_message" to TransmissionHub.progressGuardMessage.value))
        TransmissionHub.updateLiveNav(message)
        if (navClient.sendNavMessage(json)) {
            statsTracker.onNavUpdate(json.toByteArray().size)
            TransmissionHub.updateStats(
                statsTracker.currentStats(settings.resolutionLabel)
            )
        }
    }

    private fun computeBearingFromRoute(
        update: LocationUpdate,
        routePoints: List<Pair<Double, Double>>
    ): Float {
        if (routePoints.size < 2) return update.bearing.takeIf { it.isFinite() } ?: 0f
        var bestIndex = 0
        var minDist = Double.MAX_VALUE
        routePoints.forEachIndexed { index, (lat, lon) ->
            val d = haversineMeters(update.lat, update.lon, lat, lon)
            if (d < minDist) {
                minDist = d
                bestIndex = index
            }
        }
        val nextIndex = (bestIndex + 1).coerceAtMost(routePoints.lastIndex)
        val (lat1, lon1) = routePoints[bestIndex]
        val (lat2, lon2) = routePoints[nextIndex]
        return bearingDegrees(lat1, lon1, lat2, lon2)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }
}
