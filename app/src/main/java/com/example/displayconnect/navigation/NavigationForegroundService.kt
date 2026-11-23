package com.example.displayconnect.navigation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.displayconnect.DisplayConnectApp
import com.example.displayconnect.MainActivity
import com.example.displayconnect.R
import com.example.displayconnect.routing.OsrmRouteProvider
import com.example.displayconnect.routing.PendingRouteHolder
import com.example.displayconnect.routing.OverpassStreetProvider
import com.example.displayconnect.offline.OfflineMapRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NavigationForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var navigationEngine: NavigationEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val traceOwner = (application as DisplayConnectApp).navigationTrace
        if(intent?.action in listOf(ACTION_START,ACTION_START_ROUTE) &&
            (sourceSwitching || traceOwner.activeFlow.value>0 || traceOwner.rawNotice.value.copying)) {
            com.example.displayconnect.utils.TransmissionHub.navigationFailed("Espera al cierre del GPS y la copia del registro antes de empezar otra ruta.")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_START_ROUTE -> handleStartRoute()
            ACTION_STOP -> stopNavigationService()
            ACTION_RETRY_DOWNLOAD -> navigationEngine?.retryOfflineDownload()
            ACTION_SELECT_INITIAL -> navigationEngine?.selectInitialRoute(
                intent.getStringExtra(EXTRA_INITIAL_TOKEN) ?: "", intent.getIntExtra(EXTRA_JOIN_INDEX,-1))
            ACTION_SELECT_GPX_JOIN ->
                navigationEngine?.selectGpxJoinCandidate(intent.getIntExtra(EXTRA_JOIN_INDEX, -1))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopNavigationService()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        serviceScope.launch { handleStartAndAwait(intent) }
    }

    /** Also used directly by source handoff so cancellation covers preflight and
     * engine creation, not only the previous owner's closure. */
    private suspend fun handleStartAndAwait(intent: Intent) {
        val currentTrace = (application as DisplayConnectApp).navigationTrace
        if(currentTrace.activeFlow.value>0 || currentTrace.rawNotice.value.copying) {
            com.example.displayconnect.utils.TransmissionHub.navigationFailed("Espera al cierre del GPS y la copia del registro antes de empezar otra ruta.")
            return
        }
        val destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN)
        val destLon = intent.getDoubleExtra(EXTRA_DEST_LON, Double.NaN)
        if (destLat.isNaN() || destLon.isNaN()) {
            stopSelf()
            return
        }

        NavigationWakeLock.acquire(this)
        createNotificationChannel()
        startForegroundWithNotification(buildNotification())

        val app = application as DisplayConnectApp
        navigationEngine?.stopNavigation()
        run {
            val trace = app.navigationTrace.newSession()
            val source = selectPositionSource(app, trace) ?: return
            val engine = NavigationEngine(
                scope = serviceScope,
                locationTracker = source,
                routeProvider = OsrmRouteProvider(),
                offlineMap = OfflineMapRepository(File(filesDir, "offline-map-v1"), OverpassStreetProvider()),
                navClient = app.navClient,
                settingsProvider = { app.settingsRepository.settings.first() },
                onNavigationEnded = { stopNavigationService() },
                trace = if (source is HecateGnssSource) trace else NavigationTraceSink.NONE,
                boardGnss = source is HecateGnssSource
            )
            engine.altitudeMonitor=app.altitudeMonitor
            navigationEngine = engine
            activeGpsSource = if (source is HecateGnssSource) com.example.displayconnect.models.GpsSource.HECATE
                else com.example.displayconnect.models.GpsSource.PHONE
            engine.startNavigation(destLat, destLon)
            com.example.displayconnect.utils.TransmissionHub.updateEffectiveGpsSource(activeGpsSource)
            watchGpsSourceSwitch(app, intent)
        }
    }

    /** Arranca con la ruta GPX preparada que dejo el ViewModel en PendingRouteHolder. */
    private fun handleStartRoute() {
        val prepared = PendingRouteHolder.take()
        if (prepared == null || prepared.coordinates.size < 2) {
            stopSelf()
            return
        }
        val last = prepared.coordinates.last()

        NavigationWakeLock.acquire(this)
        createNotificationChannel()
        startForegroundWithNotification(buildNotification())

        val app = application as DisplayConnectApp
        navigationEngine?.stopNavigation()
        serviceScope.launch {
            val trace = app.navigationTrace.newSession()
            val source = selectPositionSource(app, trace) ?: return@launch
            val engine = NavigationEngine(
                scope = serviceScope,
                locationTracker = source,
                routeProvider = OsrmRouteProvider(),
                offlineMap = OfflineMapRepository(File(filesDir, "offline-map-v1"), OverpassStreetProvider()),
                navClient = app.navClient,
                settingsProvider = { app.settingsRepository.settings.first() },
                onNavigationEnded = { stopNavigationService() },
                trace = if (source is HecateGnssSource) trace else NavigationTraceSink.NONE,
                boardGnss = source is HecateGnssSource
            )
            engine.altitudeMonitor=app.altitudeMonitor
            navigationEngine = engine
            activeGpsSource = if (source is HecateGnssSource) com.example.displayconnect.models.GpsSource.HECATE
                else com.example.displayconnect.models.GpsSource.PHONE
            engine.startNavigationWithRoute(prepared, last.lat, last.lon)
            com.example.displayconnect.utils.TransmissionHub.updateEffectiveGpsSource(activeGpsSource)
            // Con ruta GPX el cambio de fuente se aplica al volver a iniciar la
            // navegacion (rehacer la traza en curso seria destructivo).
        }
    }

    /** Elige la fuente de posicion segun ajustes. Con HECATE no hay repliegue
     *  silencioso: si la placa no ofrece gnss_diag se avisa y no se navega. */
    private suspend fun selectPositionSource(app: DisplayConnectApp, trace: NavigationTraceSink): PositionSource? {
        val (_,source)=latestPreparedSource(
            latestSelection={ app.settingsRepository.settings.first().gpsSource },
            prepare={ choice ->
                if(choice==com.example.displayconnect.models.GpsSource.PHONE) LocationTracker(this) as PositionSource?
                else {
                    if(app.navClient.boardInfo.value==null) app.navClient.boardCommand("board_info")
                    val reply=app.navClient.gnssRawCommand("status").getOrNull()
                    if(reply != null && com.example.displayconnect.navigation.HecateNavigationPolicy.supported(reply)) HecateGnssSource(app.navClient,trace)
                    else null
                }
            })
        if(source!=null) return source
        com.example.displayconnect.utils.TransmissionHub.navigationFailed(
            when (com.example.displayconnect.utils.AppLanguage.language.value) {
                "en" -> "This board does not offer its own GPS. Select phone GPS or update the board firmware."
                "es" -> "GPS propio no disponible para navegar. Comprueba la conexion y que el firmware no sea solo de diagnostico. No se ha cambiado al GPS del movil."
                else -> "Esta placa nao oferece GPS proprio. Escolha o GPS do telefone nas definicoes ou atualize a placa."
            })
        stopSelf()
        return null
    }

    private var activeGpsSource: com.example.displayconnect.models.GpsSource = com.example.displayconnect.models.GpsSource.PHONE
    private var sourceSwitching=false
    private var sourceWatchJob: kotlinx.coroutines.Job? = null

    /** Cambio manual de fuente en ajustes durante la navegacion: reinicio
     *  explicito con la fuente elegida (lo pidio el usuario; no es un fallback
     *  silencioso). Espera STOP, copia y cierre, y entonces lee la ultima eleccion.
     *  Solo rutas por destino; las GPX no se rehacen solas. */
    private fun watchGpsSourceSwitch(app: DisplayConnectApp, intent: Intent) {
        sourceWatchJob?.cancel()
        sourceWatchJob = serviceScope.launch {
            app.settingsRepository.settings.collect { s ->
                if (s.gpsSource != activeGpsSource && navigationEngine != null) {
                    val previous=navigationEngine ?: return@collect
                    sourceSwitching=true
                    // Stop the effective source before suspending. UI no longer claims a
                    // route is running while the new choice waits for old receiver closure.
                    navigationEngine=null
                    try {
                        val restarted=sourceHandoff(
                            close={ previous.stopNavigationAndAwaitClosure() },
                            latestSelection={ app.settingsRepository.settings.first().gpsSource },
                            restart={ _ -> handleStartAndAwait(intent) }
                        )
                        if(!restarted) {
                            com.example.displayconnect.utils.TransmissionHub.navigationFailed(
                                "Navegacion parada. No se reinicio con otra fuente: no se pudo confirmar STOP o completar la copia del GPS. Revisa la placa y el registro antes de volver a empezar.")
                            stopNavigationService()
                        }
                    } finally { sourceSwitching=false }
                }
            }
        }
    }

    private fun stopNavigationService() {
        sourceWatchJob?.cancel()
        sourceSwitching=false
        navigationEngine?.stopNavigation()
        navigationEngine = null
        NavigationWakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, NavigationForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(com.example.displayconnect.utils.AppLanguage.context(this).getString(R.string.notification_title))
            .setContentText(com.example.displayconnect.utils.AppLanguage.context(this).getString(R.string.notification_nav_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, com.example.displayconnect.utils.AppLanguage.context(this).getString(R.string.stop_navigation), stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            com.example.displayconnect.utils.AppLanguage.context(this).getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.example.displayconnect.action.START_NAV"
        const val ACTION_START_ROUTE = "com.example.displayconnect.action.START_PREPARED_ROUTE"
        const val ACTION_STOP = "com.example.displayconnect.action.STOP_NAV"
        const val ACTION_RETRY_DOWNLOAD = "com.example.displayconnect.action.RETRY_MAP_DOWNLOAD"
        const val ACTION_SELECT_INITIAL = "com.example.displayconnect.action.SELECT_INITIAL"
        const val EXTRA_INITIAL_TOKEN = "extra_initial_token"
        const val ACTION_SELECT_GPX_JOIN = "com.example.displayconnect.action.SELECT_GPX_JOIN"
        const val EXTRA_JOIN_INDEX = "extra_join_index"
        const val EXTRA_DEST_LAT = "extra_dest_lat"
        const val EXTRA_DEST_LON = "extra_dest_lon"
        private const val CHANNEL_ID = "display_connect_nav"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context, destLat: Double, destLon: Double) {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEST_LAT, destLat)
                putExtra(EXTRA_DEST_LON, destLon)
            }
            context.startForegroundService(intent)
        }

        fun startPreparedRoute(context: Context) {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_START_ROUTE
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun retryDownload(context: Context) {
            context.startService(Intent(context, NavigationForegroundService::class.java).apply { action = ACTION_RETRY_DOWNLOAD })
        }

        fun selectInitialRoute(context: Context, token: String, segment: Int) {
            context.startService(Intent(context, NavigationForegroundService::class.java).apply {
                action=ACTION_SELECT_INITIAL; putExtra(EXTRA_INITIAL_TOKEN,token); putExtra(EXTRA_JOIN_INDEX,segment)
            })
        }

        /** Eleccion expresa del usuario entre las ramas de enganche GPX ambiguas. */
        fun selectGpxJoin(context: Context, index: Int) {
            context.startService(Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_SELECT_GPX_JOIN
                putExtra(EXTRA_JOIN_INDEX, index)
            })
        }
    }
}
