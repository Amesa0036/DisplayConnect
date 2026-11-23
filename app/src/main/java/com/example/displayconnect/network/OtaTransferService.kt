package com.example.displayconnect.network

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.displayconnect.MainActivity
import com.example.displayconnect.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-local, single-use handoff. Never persists firmware or restarts a failed transfer. */
object OtaTransferOwner {
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow(-1)
    val message = MutableStateFlow<String?>(null)
    private var pending: (suspend () -> Unit)? = null
    private val handoffScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var startup: Job? = null
    @Synchronized fun submit(context: Context, task: suspend () -> Unit) {
        check(pending == null && !OtaTransferService.running) { "Ya hay una transferencia" }
        pending = task
        try {
            ContextCompat.startForegroundService(context, Intent(context, OtaTransferService::class.java))
            startup = handoffScope.launch {
                delay(10_000)
                expirePending()
            }
        } catch (e: Exception) { pending = null; busy.value = false; progress.value = -1; throw e }
    }
    @Synchronized private fun expirePending() {
        if (pending != null) {
            pending = null; busy.value = false; progress.value = -1
            message.value = "No se pudo iniciar el servicio de transferencia. No se ha enviado firmware; no hay reintento automatico."
        }
        startup = null
    }
    @Synchronized fun take(): (suspend () -> Unit)? {
        startup?.cancel(); startup = null
        return pending.also { pending = null }
    }
}

/** Owns the approved OTA task while the Activity is stopped or recreated. No automatic retry. */
class OtaTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lock: PowerManager.WakeLock? = null
    private var task: Job? = null
    private var ownsTransfer = false
    private val log get()=(application as com.example.displayconnect.DisplayConnectApp).otaTrialLog
    private var screenReceiver: BroadcastReceiver?=null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val block = OtaTransferOwner.take() ?: run { stopSelf(); return START_NOT_STICKY }
        running = true
        ownsTransfer = true
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Actualizacion de placa", NotificationManager.IMPORTANCE_LOW))
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID, notification())
            lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DisplayConnect::Ota").apply {
                setReferenceCounted(false); acquire(31 * 60 * 1000L)
            }
            screenReceiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){log.record("screen",mapOf("action" to i?.action))}}
            ContextCompat.registerReceiver(this,requireNotNull(screenReceiver),IntentFilter().apply{addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON)},ContextCompat.RECEIVER_NOT_EXPORTED)
            task = scope.launch {
                val updates = launch { OtaTransferOwner.progress.collect { manager.notify(ID, notification()) } }
                try { withTimeout(30 * 60 * 1000L) { block() } }
                catch (e: CancellationException) { OtaTransferOwner.message.value = "Transferencia interrumpida. Reconecta y comprueba la version; no se reanuda automaticamente."; throw e }
                catch (e: Exception) { OtaTransferOwner.message.value = "Transferencia no completada: ${e.message}" }
                finally { updates.cancel() }
            }.also { job -> job.invokeOnCompletion { finish(); stopSelf() } }
        } catch (e: Exception) {
            OtaTransferOwner.message.value = "No se pudo proteger la transferencia: ${e.message}. No se ha enviado firmware."
            finish(); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val p = OtaTransferOwner.progress.value
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Actualizando la placa")
            .setContentText("Puedes apagar la pantalla. No apagues la placa ni fuerces el cierre de la app.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, p.coerceIn(0, 100), p < 0).build()
    }
    private fun finish() {
        if (!ownsTransfer) return
        ownsTransfer = false
        lock?.let { if (it.isHeld) it.release() }; lock = null
        OtaTransferOwner.busy.value = false; OtaTransferOwner.progress.value = -1
        running = false; stopForeground(STOP_FOREGROUND_REMOVE)
        screenReceiver?.let{runCatching{unregisterReceiver(it)}};screenReceiver=null
        log.record("service_released",mapOf("running" to running,"busy" to OtaTransferOwner.busy.value,"lock" to false))
        log.end()
    }
    override fun onDestroy() {
        scope.cancel()
        // Keep ownership until the old task's finally blocks have finished.
        // Otherwise their UI cleanup can clear a replacement transfer's busy state.
        if (task?.isCompleted != false) finish()
        super.onDestroy()
    }
    companion object { @Volatile var running = false; private set
        private const val CHANNEL = "ota-transfer"
        private const val ID = 4501
    }
}
