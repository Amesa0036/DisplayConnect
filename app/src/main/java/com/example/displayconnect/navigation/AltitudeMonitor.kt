package com.example.displayconnect.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AltitudeMonitor(context: Context) : SensorEventListener {
    data class State(val pressureHpa: Float? = null, val pressureMs: Long = 0, val samples: Int = 0,
        val ready: Boolean = false, val message: String = "Comprobando barometro; GPS aprox. como alternativa",
        val reference: String = "Sin lectura", val source: String = "GPS aprox.", val pressureMessage: String = message)
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State(message = if (sensor == null) "Sin barometro; usando GPS aprox. si hay altura valida" else "Barometro detectado; pendiente de comprobar lecturas"))
    val state = _state.asStateFlow()
    private var users = 0
    private var unreliable = false
    private var previous: Float? = null
    private val staleCheck = object : Runnable { override fun run() {
        val s = _state.value
        if (s.pressureMs == 0L || SystemClock.elapsedRealtime()-s.pressureMs > 3000) {
            _state.update { current -> current.copy(ready=false, samples=0, pressureHpa=null, message="Barometro sin datos recientes; usando GPS aprox.",pressureMessage="Barometro sin datos recientes; usando GPS aprox.") }
        }
        handler.postDelayed(this,1000)
    }}
    @Synchronized fun start() {
        if (++users != 1) return
        previous=null;unreliable=false
        _state.value=_state.value.copy(ready=false,samples=0,pressureHpa=null,pressureMs=0)
        if (sensor == null) return
        val registered = manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL,handler)
        if (!registered) _state.update { it.copy(message="No se puede leer el barometro; usando GPS aprox.",pressureMessage="No se puede leer el barometro; usando GPS aprox.") }
        else handler.postDelayed(staleCheck,3000)
    }
    @Synchronized fun stop() {
        if (users<=0 || --users != 0) return
        manager.unregisterListener(this);handler.removeCallbacks(staleCheck)
        _state.value=_state.value.copy(ready=false,samples=0,pressureHpa=null,pressureMs=0)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        unreliable=accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE
        if(unreliable) _state.update { it.copy(ready=false,samples=0,message="Barometro no fiable; usando GPS aprox.",pressureMessage="Barometro no fiable; usando GPS aprox.") }
    }
    override fun onSensorChanged(event: SensorEvent) {
        val p=event.values.firstOrNull() ?: return
        val now=SystemClock.elapsedRealtime();val ms=event.timestamp/1_000_000
        val good = p.isFinite() && p in 300f..1100f && now-ms in 0..3000 && !unreliable &&
            event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE && (previous==null || kotlin.math.abs(p-previous!!)<2f)
        previous = p.takeIf { it.isFinite() }
        val n=if(good) (_state.value.samples+1).coerceAtMost(100) else 0
        _state.update { current -> current.copy(pressureHpa=p.takeIf { good },pressureMs=ms,samples=n,ready=good&&n>=5,
            pressureMessage=if(good) "Barometro con lecturas; falta calibracion para altura absoluta" else "Barometro no fiable; usando GPS aprox.",
            message=if(!good) "Barometro no fiable; usando GPS aprox." else if(_state.value.pressureMs==0L) "Barometro con lecturas; usando GPS hasta calibrar" else current.message) }
    }
    fun publish(reading: AltitudeEstimator.Reading) {
        _state.update { it.copy(reference=if(reading.reference=="msl") "Sobre el nivel del mar" else "WGS84 (no es nivel del mar)",
            source=if(reading.source=="gps_baro") "GPS + barometro" else "GPS aprox.", message=reading.notice) }
    }
}
