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
    private var boardReply: CompletableDeferred<JSONObject>? = null

    private fun clearBoardSession() {
        _boardInfo.value = null
        boardDecoder.reset()
        synchronized(this) { boardReply?.cancel(); boardReply = null; boardReplyId = null }
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
            synchronized(this) { boardReply?.cancel(); boardReply = null; boardReplyId = null }
            _boardBusy.value = false
        }
    }

    private fun receiveBoardData(connection: BluetoothGatt, bytes: ByteArray) {
        if (connection !== gatt) return
        for (line in boardDecoder.feed(bytes)) {
            try {
                val json = JSONObject(line)
                synchronized(this) {
                    if (json.optString("type") == "board_reply" && json.optString("request_id") == boardReplyId)
                        boardReply?.complete(json)
                }
            } catch (_: Exception) { }
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
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEVICE_NAME_HINT = "DisplayConnect"
        private const val REQUESTED_MTU = 512
        private const val DEFAULT_CHUNK = 180
        private const val MAX_PAYLOAD_BYTES = 8192
        private const val WRITE_TIMEOUT_MS = 2000L
        private const val TAG = "DisplayConnectBLE"
        private const val HEARTBEAT_INTERVAL_SEC = 15L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val SCAN_DURATION_MS = 10_000L
    }
}
