package com.example.displayconnect.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.example.displayconnect.models.ConnectionState
import com.example.displayconnect.protocol.NavMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE UART (Nordic NUS) client for JSON navigation updates to the ESP32 CYD.
 * Framing: each JSON message ends with '\n'; large payloads are chunked to fit MTU.
 */
@SuppressLint("MissingPermission")
class BleNavClient(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var writeChunkSize = DEFAULT_CHUNK
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var scanJob: Job? = null
    private val shouldReconnect = AtomicBoolean(false)
    private val writeMutex = Mutex()
    private val writeAck = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private data class OutgoingFrame(
        val connection: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
        val bytes: ByteArray
    )
    private val outgoing = Channel<OutgoingFrame>(Channel.CONFLATED)
    @Volatile private var lastQueuedAt = 0L

    init {
        scope.launch {
            for (frame in outgoing) {
                writeMutex.withLock {
                    writeInChunks(frame.connection, frame.characteristic, frame.bytes)
                }
            }
        }
    }

    @Volatile private var targetAddress: String = ""
    private val _boardInfo = MutableStateFlow<BoardInfo?>(null)
    val boardInfo: StateFlow<BoardInfo?> = _boardInfo.asStateFlow()
    private val _boardBusy = MutableStateFlow(false)
    val boardBusy: StateFlow<Boolean> = _boardBusy.asStateFlow()
    private val boardMutex = Mutex()
    private val boardDecoder = BoardReplyDecoder()
    private var boardReplyId: String? = null
    private val otaActive = AtomicBoolean(false)
    private val otaLines = Channel<String>(capacity = Channel.BUFFERED)
    private val readAck = AtomicReference<CompletableDeferred<Result<ByteArray>>?>(null)
    private var boardReply: CompletableDeferred<JSONObject>? = null
    private var imuReply: CompletableDeferred<JSONObject>? = null
    private var imuReplyId: String? = null

    private fun clearBoardSession() {
        _boardInfo.value = null
        boardDecoder.reset()
        synchronized(this) { boardReply?.cancel(); boardReply = null; boardReplyId = null; imuReply?.cancel(); imuReply = null; imuReplyId = null }
    }

    /** A command never enters the conflated navigation channel. One request at a time. */
    suspend fun boardCommand(type: String, newName: String? = null): Result<BoardInfo> = boardMutex.withLock {
        _boardBusy.value = true
        try {
            val connection = gatt ?: error("La placa no esta conectada")
            val characteristic = rxCharacteristic ?: error("La placa no esta lista")
            check(_connectionState.value == ConnectionState.CONNECTED) { "La placa no esta conectada" }
            val address = targetAddress
            val id = UUID.randomUUID().toString()
            val reply = CompletableDeferred<JSONObject>()
            synchronized(this) { boardReplyId = id; boardReply = reply }
            val request = JSONObject().put("type", type).put("request_id", id)
            newName?.let { request.put("name", it) }
            if (type != "board_info") {
                val identity = _boardInfo.value ?: error("Lee primero la identidad de la placa")
                check(identity.address == address) { "La identidad corresponde a otra placa" }
                request.put("board_id", identity.id)
            }
            val written = writeMutex.withLock {
                check(connection === gatt && address == targetAddress) { "La conexion ha cambiado" }
                writeInChunks(connection, characteristic, ("\n" + request + "\n").toByteArray(Charsets.UTF_8))
            }
            check(written) { "No se pudo enviar el cambio a la placa" }
            val response = withTimeoutOrNull(5000) { reply.await() }
                ?: error("La placa no confirma. Comprueba que tiene firmware v18 o posterior")
            check(connection === gatt && address == targetAddress) { "La conexion ha cambiado" }
            check(response.optBoolean("ok", false)) { response.optString("error", "La placa rechazo la peticion") }
            val boardId = response.optString("board_id")
            val boardName = response.optString("name")
            check(boardId.matches(Regex("[0-9A-F]{12}")) && boardName.isNotBlank()) { "Identidad de placa no valida" }
            check(newName == null || boardName == newName) { "La placa no confirma el nombre solicitado" }
            val previousIdentity = _boardInfo.value
            check(previousIdentity == null || previousIdentity.id == boardId) { "La identidad de la placa ha cambiado" }
            val info = BoardInfo(boardId, boardName, address)
            _boardInfo.value = info
            Result.success(info)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            synchronized(this) { boardReply?.cancel(); boardReply = null; boardReplyId = null; imuReply?.cancel(); imuReply = null; imuReplyId = null }
            _boardBusy.value = false
        }
    }

    /** Passive capture commands never enter the conflated navigation queue. */
    suspend fun imuCommand(op: String, session: Long = 0, offset: Int = 0): Result<JSONObject> = boardMutex.withLock {
        _boardBusy.value = true
        try {
            check(!otaActive.get()) { "Espera a que termine la actualizacion" }
            val connection = gatt ?: error("La placa no esta conectada")
            val characteristic = rxCharacteristic ?: error("La placa no esta lista")
            val address = targetAddress
            val identity = _boardInfo.value ?: error("Lee primero la identidad de la placa")
            check(identity.address == address) { "La identidad corresponde a otra placa" }
            val id = UUID.randomUUID().toString()
            val reply = CompletableDeferred<JSONObject>()
            synchronized(this) { imuReplyId = id; imuReply = reply }
            val request = JSONObject().put("type", "imu_capture").put("request_id", id)
                .put("board_id", identity.id).put("op", op).put("session", session).put("offset", offset)
                .put("started_utc", java.text.SimpleDateFormat("yyyy-MM-dd\'T\'HH:mm:ss.SSS\'Z\'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
            val written = writeMutex.withLock {
                check(connection === gatt && address == targetAddress) { "La conexion ha cambiado" }
                writeInChunks(connection, characteristic, ("\n" + request + "\n").toByteArray(Charsets.UTF_8))
            }
            check(written) { "No se pudo enviar la orden" }
            val response = withTimeoutOrNull(5000) { reply.await() } ?: error("La placa no confirma la captura")
            check(connection === gatt && address == targetAddress) { "La conexion ha cambiado" }
            check(response.optBoolean("ok")) { response.optString("error", "Captura rechazada") }
            Result.success(response)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Result.failure(e)
        } finally {
            synchronized(this) { imuReply?.cancel(); imuReply = null; imuReplyId = null }
            _boardBusy.value = false
        }
    }

    /** Freeze first, then pull retryable pages. Lost notifications never advance the offset. */
    data class ImuDownload(val csv: String, val boardId: String, val session: Long, val count: Int)

    suspend fun downloadImuCsv(): Result<ImuDownload> = runCatching {
        val expectedBoard = _boardInfo.value ?: error("Lee primero la identidad de la placa")
        val expectedAddress = targetAddress
        val state = imuCommand("status").getOrThrow()
        val session = state.getLong("session")
        check(session != 0L) { "No hay captura" }
        imuCommand("stop", session).getOrThrow()
        val frozen = imuCommand("status").getOrThrow()
        check(frozen.getLong("session") == session && !frozen.getBoolean("running")) { "Captura cambiada" }
        val count = frozen.getInt("count")
        val capacity = frozen.getInt("capacity")
        val stopReason = frozen.getString("stop_reason")
        // Only a full, cleanly finished window is exportable; partial/error windows are not saved as valid CSV.
        check(stopReason == "fifo_window_full" && count == capacity) { "Captura incompleta ($stopReason: $count/$capacity); repite la captura" }
        val metadata = JSONObject().put("protocol", frozen.getInt("protocol"))
            .put("board_id", expectedBoard.id).put("session", session).put("count", count)
            .put("capacity", frozen.getInt("capacity")).put("firmware", frozen.getString("firmware"))
            .put("started_utc_phone", frozen.getString("started_utc")).put("stop_reason", frozen.getString("stop_reason"))
            .put("no_data", frozen.getLong("no_data")).put("duplicates", frozen.getLong("duplicates"))
            .put("errors", frozen.getLong("errors")).put("late_reads", frozen.getLong("late_reads"))
            .put("odr_hz_nominal", frozen.getInt("odr_hz_nominal")).put("fs_g_nominal", frozen.getInt("fs_g_nominal"))
            .put("period_ms_nominal", frozen.getInt("period_ms_nominal")).put("window_ms_measured", frozen.getLong("window_end_ms") - frozen.getLong("window_start_ms"))
            .put("restore_failed", frozen.getBoolean("restore_failed"))
            .put("app_version", "3.0-passive-dev")
        val csv = StringBuilder("# " + metadata.toString() + "\n")
        csv.append("sample_ms_nominal,sensor_tick_none,status,ax_raw,ay_raw,az_raw,read_us_unused,fifo_status_prefreeze\n")
        var offset = 0
        var written = 0
        while (offset < count) {
            check(targetAddress == expectedAddress && _boardInfo.value?.id == expectedBoard.id) { "La placa ha cambiado" }
            var page: JSONObject? = null
            repeat(3) { if (page == null) page = imuCommand("read", session, offset).getOrNull() }
            val reply = page ?: error("No se pudo descargar el bloque $offset; la placa conserva la captura")
            check(reply.getLong("session") == session && reply.getInt("count") == count && reply.getInt("offset") == offset) { "Bloque de otra captura" }
            val rows = reply.getJSONArray("rows")
            check(rows.length() == minOf(5, count - offset)) { "Bloque incompleto" }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                check(row.length() == 8) { "Fila no valida" }
                csv.append((0 until 8).joinToString(",") { row.getLong(it).toString() }).append('\n')
            }
            written += rows.length()
            offset += rows.length()
        }
        check(written == count) { "Descarga incompleta" }
        check(targetAddress == expectedAddress && _boardInfo.value?.id == expectedBoard.id) { "La placa ha cambiado" }
        ImuDownload(csv.toString(), expectedBoard.id, session, count)
    }

    private fun receiveBoardData(connection: BluetoothGatt, bytes: ByteArray) {
        if (connection !== gatt) return
        // OTA replies may arrive without a trailing newline; accept a bare OTA: chunk as a line.
        if (otaActive.get()) {
            val raw = String(bytes, Charsets.UTF_8)
            if (raw.startsWith("OTA:") && !raw.contains("\n") && !raw.contains("\r")) {
                boardDecoder.reset()
                otaLines.trySend(raw)
                return
            }
        }
        for (line in boardDecoder.feed(bytes)) {
            if (otaActive.get() && line.startsWith("OTA:")) { otaLines.trySend(line); continue }
            try {
                val json = JSONObject(line)
                synchronized(this) {
                    if (json.optString("type") == "imu_reply" && json.optString("request_id") == imuReplyId)
                        imuReply?.complete(json)
                    if (json.optString("type") == "board_reply" && json.optString("request_id") == boardReplyId)
                        boardReply?.complete(json)
                }
            } catch (_: Exception) { }
        }
    }


    /** Lee la version del firmware por la caracteristica de version (v20+). */
    suspend fun boardFirmwareVersion(): Result<String> {
        val connection = gatt ?: return Result.failure(Exception("La placa no esta conectada"))
        val chr = connection.getService(SERVICE_UUID)?.getCharacteristic(VERSION_UUID)
            ?: return Result.failure(Exception("Este firmware no informa su version (v19 o anterior)"))
        val deferred = CompletableDeferred<Result<ByteArray>>()
        readAck.set(deferred)
        if (!connection.readCharacteristic(chr)) {
            readAck.compareAndSet(deferred, null)
            return Result.failure(Exception("No se pudo leer la version"))
        }
        val res = withTimeoutOrNull(5000) { deferred.await() }
            ?: return Result.failure(Exception("La placa no responde"))
        return res.map { String(it, Charsets.UTF_8).trim() }
    }

    /**
     * OTA por BLE: OTA:BEGIN, ventanas de 4096 con OTA:ACK, y OTA:OK final.
     * La placa verifica SHA-256, instala en la otra particion y reinicia;
     * si algo falla sigue arrancando el firmware anterior.
     */
    suspend fun otaUpdate(
        bin: ByteArray,
        sha256Hex: String,
        onProgress: (sent: Int, total: Int) -> Unit
    ): Result<Unit> {
        val connection = gatt ?: return Result.failure(Exception("La placa no esta conectada"))
        val characteristic = rxCharacteristic ?: return Result.failure(Exception("La placa no esta lista"))
        if (_connectionState.value != ConnectionState.CONNECTED)
            return Result.failure(Exception("La placa no esta conectada"))
        if (!otaActive.compareAndSet(false, true))
            return Result.failure(Exception("Ya hay una actualizacion en curso"))
        _boardBusy.value = true
        while (otaLines.tryReceive().isSuccess) { }
        try {
            val ready = otaExchange(connection, characteristic,
                "OTA:BEGIN ${bin.size} $sha256Hex", 120_000)
            if (ready == "OTA:ERR timeout_app")
                return Result.failure(Exception("Timeout esperando OTA:READY (la placa tarda en borrar la flash)"))
            if (!ready.startsWith("OTA:READY")) return otaFailure(ready)
            var sent = 0
            while (sent < bin.size) {
                if (connection !== gatt)
                    return Result.failure(Exception("Conexion perdida durante la actualizacion"))
                val end = minOf(sent + OTA_WINDOW_BYTES, bin.size)
                val ok = writeMutex.withLock {
                    writeInChunks(connection, characteristic, bin.copyOfRange(sent, end))
                }
                if (!ok) {
                    sendOtaAbort(connection, characteristic)
                    return Result.failure(Exception("Fallo de escritura BLE"))
                }
                sent = end
                onProgress(sent, bin.size)
                if (sent < bin.size) {
                    val ack = nextOtaLine(30_000)
                    if (ack == "OTA:ERR timeout_app")
                        return Result.failure(Exception("Timeout esperando OTA:ACK (enviados $sent de ${bin.size} bytes)"))
                    if (!ack.startsWith("OTA:ACK")) return otaFailure(ack)
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val line = nextOtaLine(deadline - SystemClock.elapsedRealtime())
                if (line.startsWith("OTA:OK")) return Result.success(Unit)
                if (line == "OTA:ERR timeout_app")
                    return Result.failure(Exception("Timeout esperando OTA:OK tras la verificacion"))
                if (line.startsWith("OTA:ERR")) return otaFailure(line)
            }
            return Result.failure(Exception("La placa no confirma la instalacion"))
        } finally {
            otaActive.set(false)
            _boardBusy.value = false
        }
    }

    private suspend fun otaExchange(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
        text: String, timeoutMs: Long
    ): String {
        val ok = writeMutex.withLock {
            writeInChunks(gatt, characteristic, ("\n" + text + "\n").toByteArray(Charsets.UTF_8))
        }
        if (!ok) return "OTA:ERR escritura"
        return nextOtaLine(timeoutMs)
    }

    private suspend fun nextOtaLine(timeoutMs: Long): String =
        withTimeoutOrNull(timeoutMs) { otaLines.receive() } ?: "OTA:ERR timeout_app"

    private fun otaFailure(line: String): Result<Unit> =
        Result.failure(Exception(line.removePrefix("OTA:").replace('_', ' ')))

    private fun sendOtaAbort(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        scope.launch {
            writeMutex.withLock {
                runCatching {
                    writeInChunks(gatt, characteristic, "\nOTA:ABORT\n".toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDeviceItem>>(emptyList())
    val scannedDevices: StateFlow<List<BleDeviceItem>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, BleDeviceItem>()

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun startScan(durationMs: Long = SCAN_DURATION_MS) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !isBluetoothEnabled()) {
            _isScanning.value = false
            return
        }

        stopScanInternal()
        deviceMap.clear()
        _scannedDevices.value = emptyList()
        _isScanning.value = true

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (_: SecurityException) {
            try {
                scanner.startScan(null, settings, scanCallback)
            } catch (_: SecurityException) {
                _isScanning.value = false
                return
            }
        }

        scanJob = scope.launch {
            delay(durationMs)
            stopScanInternal()
        }
    }

    fun stopScan() {
        stopScanInternal()
    }

    private fun stopScanInternal() {
        scanJob?.cancel()
        scanJob = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // ignore
        }
        _isScanning.value = false
    }

    fun connect(address: String, name: String = "") {
        disconnect(manual = false)
        targetAddress = address
        shouldReconnect.set(true)
        openGatt()
    }

    fun disconnect(manual: Boolean = true) {
        clearBoardSession()
        if (manual) {
            shouldReconnect.set(false)
        }
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        stopScanInternal()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
            // ignore
        }
        gatt = null
        rxCharacteristic = null
        writeChunkSize = DEFAULT_CHUNK
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendNavMessage(json: String): Boolean {
        if (otaActive.get()) return false
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        val characteristic = rxCharacteristic ?: return false
        val gattLocal = gatt ?: return false
        val payload = ("\n" + json.trimEnd() + "\n").toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_PAYLOAD_BYTES) {
            Log.w(TAG, "Navigation frame exceeds CYD buffer: ${payload.size}")
            return false
        }
        lastQueuedAt = SystemClock.elapsedRealtime()
        return outgoing.trySend(OutgoingFrame(gattLocal, characteristic, payload)).isSuccess
    }

    fun release() {
        disconnect(manual = true)
        outgoing.close()
        scope.cancel()
    }

    private fun openGatt() {
        if (targetAddress.isBlank()) return
        val device = try {
            adapter?.getRemoteDevice(targetAddress)
        } catch (_: IllegalArgumentException) {
            null
        } ?: run {
            _connectionState.value = ConnectionState.ERROR
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.RECONNECTING
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (shouldReconnect.get() && isActive) {
                openGatt()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                delay(HEARTBEAT_INTERVAL_SEC * 1000)
                if (SystemClock.elapsedRealtime() - lastQueuedAt >= HEARTBEAT_INTERVAL_SEC * 1000) {
                    sendNavMessage(NavMessage.heartbeat())
                }
            }
        }
    }

    private suspend fun writeInChunks(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ): Boolean {
        var offset = 0
        while (offset < payload.size) {
            if (gatt !== this.gatt) return false
            val end = minOf(offset + writeChunkSize, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            val deferred = CompletableDeferred<Boolean>()
            writeAck.set(deferred)

            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    characteristic.value = chunk
                    gatt.writeCharacteristic(characteristic)
                }
            }
            if (!queued) {
                writeAck.compareAndSet(deferred, null)
                return false
            }

            val acked = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() } == true
            writeAck.compareAndSet(deferred, null)
            if (!acked) return false
            offset = end
        }
        return true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val name = result.scanRecord?.deviceName
                ?: device.name
                ?: ""
            val hasNus = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            val nameMatch = name.contains(DEVICE_NAME_HINT, ignoreCase = true)
            if (!hasNus && !nameMatch && name.isNotBlank()) {
                return
            }
            if (!hasNus && !nameMatch && name.isBlank()) {
                // Service filter scan already matched; keep unnamed devices
            }
            val displayName = name.ifBlank { "CYD $address" }
            deviceMap[address] = BleDeviceItem(address, displayName, result.rssi)
            _scannedDevices.value = deviceMap.values.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@BleNavClient.gatt) { gatt.close(); return }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.CONNECTING
                // Request MTU first; discover services in onMtuChanged (or fallback).
                if (!gatt.requestMtu(REQUESTED_MTU)) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearBoardSession()
                heartbeatJob?.cancel()
                rxCharacteristic = null
                writeAck.getAndSet(null)?.complete(false)
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                this@BleNavClient.gatt = null
                if (shouldReconnect.get()) {
                    scheduleReconnect()
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@BleNavClient.gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeChunkSize = (mtu - 3).coerceIn(20, 500)
            }
            gatt.discoverServices()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== this@BleNavClient.gatt) return
            writeAck.getAndSet(null)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@BleNavClient.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.ERROR
                scheduleReconnect()
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            if (rx == null || tx == null) {
                _connectionState.value = ConnectionState.ERROR
                scheduleReconnect()
                return
            }
            rxCharacteristic = rx

            if (!gatt.setCharacteristicNotification(tx, true)) {
                _connectionState.value = ConnectionState.ERROR
                return
            }
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothGatt.GATT_SUCCESS)
                        _connectionState.value = ConnectionState.ERROR
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (!gatt.writeDescriptor(cccd)) _connectionState.value = ConnectionState.ERROR
                    }
                }
            }

            // Wait for CCCD acknowledgement before enabling commands/navigation.
            if (cccd == null) {
                _connectionState.value = ConnectionState.ERROR
                return
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== this@BleNavClient.gatt || descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.ERROR
                return
            }
            _connectionState.value = ConnectionState.CONNECTED
            startHeartbeat()
            scope.launch { boardCommand("board_info") }
        }

        @Deprecated("Android legacy callback")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != VERSION_UUID) return
            val ack = readAck.getAndSet(null) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) ack.complete(Result.success(characteristic.value ?: ByteArray(0)))
            else ack.complete(Result.failure(Exception("Lectura fallida ($status)")))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (characteristic.uuid != VERSION_UUID) return
            val ack = readAck.getAndSet(null) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) ack.complete(Result.success(value))
            else ack.complete(Result.failure(Exception("Lectura fallida ($status)")))
        }

        @Deprecated("Android legacy callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == TX_UUID)
                receiveBoardData(gatt, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX_UUID) receiveBoardData(gatt, value)
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val VERSION_UUID: UUID = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEVICE_NAME_HINT = "DisplayConnect"
        private const val REQUESTED_MTU = 512
        private const val DEFAULT_CHUNK = 180
        private const val MAX_PAYLOAD_BYTES = 8192
        private const val OTA_WINDOW_BYTES = 4096
        private const val WRITE_TIMEOUT_MS = 2000L
        private const val TAG = "DisplayConnectBLE"
        private const val HEARTBEAT_INTERVAL_SEC = 15L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val SCAN_DURATION_MS = 10_000L
    }
}
