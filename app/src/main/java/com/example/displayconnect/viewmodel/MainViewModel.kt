package com.example.displayconnect.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.displayconnect.DisplayConnectApp
import com.example.displayconnect.R
import com.example.displayconnect.maps.MapsBrowserActivity
import com.example.displayconnect.models.MainUiState
import com.example.displayconnect.navigation.NavigationForegroundService
import com.example.displayconnect.network.FirmwareRelease
import com.example.displayconnect.network.BleDeviceItem
import com.example.displayconnect.routing.GpxRouteBuilder
import com.example.displayconnect.routing.GpxParser
import com.example.displayconnect.routing.NominatimGeocoder
import com.example.displayconnect.routing.PendingRouteHolder
import com.example.displayconnect.routing.PlaceSearchResult
import com.example.displayconnect.routing.RouteData
import com.example.displayconnect.routing.RouteProfile
import com.example.displayconnect.utils.TransmissionHub
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DisplayConnectApp
    private val navClient = app.navClient
    val altitudeMonitor = app.altitudeMonitor
    val altitudeStatus = altitudeMonitor.state
    val boardInfo = navClient.boardInfo
    val boardBusy = navClient.boardBusy
    private val _boardMessage = MutableStateFlow<String?>(null)
    val boardMessage: StateFlow<String?> = _boardMessage.asStateFlow()

    private val _otaMessage = MutableStateFlow<String?>(null)
    val otaMessage: StateFlow<String?> = _otaMessage.asStateFlow()
    private val _otaBusy = MutableStateFlow(false)
    val otaBusy: StateFlow<Boolean> = _otaBusy.asStateFlow()
    private val _otaProgress = MutableStateFlow(-1)
    val otaProgress: StateFlow<Int> = _otaProgress.asStateFlow()

    private val _imuMessage = MutableStateFlow("Sin consultar. Captura solo con firmware de prueba compatible.")
    val imuMessage: StateFlow<String> = _imuMessage.asStateFlow()
    private val _imuBusy = MutableStateFlow(false)
    val imuBusy: StateFlow<Boolean> = _imuBusy.asStateFlow()
    private val _imuRunning = MutableStateFlow(false)
    val imuRunning: StateFlow<Boolean> = _imuRunning.asStateFlow()
    private val _imuSaved = MutableStateFlow(java.io.File(application.filesDir, "imu-captures").listFiles()?.any { it.extension == "csv" } == true)
    val imuSaved: StateFlow<Boolean> = _imuSaved.asStateFlow()
    private var imuSession = 0L
    private fun lastImuFile(): java.io.File? = java.io.File(getApplication<Application>().filesDir, "imu-captures")
        .listFiles()?.filter { it.extension == "csv" }?.maxByOrNull { it.lastModified() }
    private fun imuStopLabel(reason: String): String = when(reason) {
        "fifo_window_full" -> "ventana completa"
        "fifo_read_error" -> "ERROR DE LECTURA FIFO"
        "fifo_fill_timeout" -> "llenado incompleto"
        "user" -> "parada solicitada"
        "sensor_errors" -> "ERROR DEL SENSOR"
        "sensor_init_error" -> "ERROR AL INICIAR SENSOR"
        "never_started" -> "sin iniciar"
        else -> reason
    }

    fun refreshImu() = runImu("status")
    fun startImu() = runImu("start")
    fun stopImu() = runImu("stop")

    private fun runImu(op: String) {
        if (_imuBusy.value || _otaBusy.value || navClient.boardBusy.value || _uiState.value.isNavigating) return
        _imuBusy.value = true
        viewModelScope.launch {
            try {
                if (navClient.boardInfo.value == null) navClient.boardCommand("board_info").getOrThrow()
                // Refresh session before a stop, including after reconnect or app restart.
                if (op == "stop") imuSession = navClient.imuCommand("status").getOrThrow().getLong("session")
                // Firmware guard: only start when the board reports fifo IMU support (protocol 2).
                if (op == "start") {
                    val st = navClient.imuCommand("status").getOrThrow()
                    check(st.optInt("protocol", 0) == 2) { "Este firmware no soporta captura IMU" }
                }
                val reply = navClient.imuCommand(op, if (op == "stop") imuSession else 0).getOrThrow()
                imuSession = reply.getLong("session")
                _imuRunning.value = reply.getBoolean("running")
                _imuMessage.value = if (_imuRunning.value) "Grabando una ventana de unos 0,5 s. No toques el movil en marcha."
                    else "Detenida (${imuStopLabel(reply.getString("stop_reason"))}): ${reply.getInt("count")}/${reply.getInt("capacity")}. Descarga antes de apagar la placa."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                _imuMessage.value = "No confirmado: ${e.message}. Consulta el estado antes de repetir."
            } finally { _imuBusy.value = false }
        }
    }

    fun downloadImu() {
        if (_imuBusy.value || _otaBusy.value || navClient.boardBusy.value || _uiState.value.isNavigating) return
        _imuBusy.value = true
        _imuMessage.value = "Parando y descargando. Espera; no apagues la placa."
        viewModelScope.launch {
            try {
                if (navClient.boardInfo.value == null) navClient.boardCommand("board_info").getOrThrow()
                val download = navClient.downloadImuCsv().getOrThrow()
                val csv = download.csv
                withContext(Dispatchers.IO) {
                    val directory = java.io.File(getApplication<Application>().filesDir, "imu-captures")
                    check(directory.isDirectory || directory.mkdirs()) { "No se pudo crear carpeta privada" }
                    // Preserve every successful download, even after a reboot reuses a random session id.
                    val destination = java.io.File(directory, "${download.boardId}-${download.session}-${java.util.UUID.randomUUID()}.csv")
                    val storage = android.util.AtomicFile(destination)
                    val stream = storage.startWrite()
                    try { stream.write(csv.toByteArray(Charsets.UTF_8)); storage.finishWrite(stream)
                    } catch (e: Exception) { storage.failWrite(stream); throw e }
                    check(storage.readFully().contentEquals(csv.toByteArray(Charsets.UTF_8))) { "No se pudo verificar el archivo local" }
                }
                // Only acknowledge after durable local write + readback, with exact frozen session/count.
                val ack = navClient.imuCommand("ack_saved", download.session, download.count)
                _imuRunning.value = false
                _imuSaved.value = true
                _imuMessage.value = "CSV guardado y verificado dentro de la app. Usa Guardar copia para elegir carpeta. " + if (ack.isSuccess) "Placa informada del guardado." else "La placa NO confirma el guardado: OTA seguira bloqueada; reintenta descarga."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { _imuMessage.value = "Descarga no completada: ${e.message}. No apagues la placa; puedes reintentar."
            } finally { _imuBusy.value = false }
        }
    }

    fun exportImu(uri: Uri?) {
        if (uri == null || _imuBusy.value) return
        _imuBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val bytes = android.util.AtomicFile(lastImuFile() ?: error("No hay archivo guardado")).readFully()
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("No se pudo abrir destino")
                    output.use { it.write(bytes); it.flush() }
                    val verified = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    check(verified != null && verified.contentEquals(bytes)) { "No se pudo verificar la copia; conserva el archivo interno" }
                }
                _imuMessage.value = "Copia CSV guardada y verificada en la carpeta elegida."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { _imuMessage.value = "Copia no confirmada: ${e.message}. El CSV interno sigue guardado."
            } finally { _imuBusy.value = false }
        }
    }

    fun startFirmwareUpdate() {
        if (_uiState.value.isNavigating || navClient.boardBusy.value || _otaBusy.value || _imuBusy.value || _imuRunning.value) return
        if (_uiState.value.connectionState != com.example.displayconnect.models.ConnectionState.CONNECTED) {
            _otaMessage.value = "Conecta la placa primero."
            return
        }
        viewModelScope.launch {
            _otaBusy.value = true
            _otaProgress.value = -1
            _otaMessage.value = "Buscando actualizaciones..."
            try {
                // Query live capture state on every attempt, including app restart/reconnect.
                val version = navClient.boardFirmwareVersion().getOrNull()
                if (version?.contains("passive") == true) {
                    if (navClient.boardInfo.value == null) navClient.boardCommand("board_info").getOrThrow()
                    val capture = navClient.imuCommand("status").getOrThrow()
                    _imuRunning.value = capture.getBoolean("running")
                    check(!capture.getBoolean("running") && !capture.getBoolean("unsaved")) { "Para y descarga la captura antes de actualizar" }
                }
                val manifest = FirmwareRelease.fetchLatest().getOrElse {
                    _otaMessage.value = "No se pudo consultar la actualizacion: ${it.message}"
                    return@launch
                }
                val boardVersion = navClient.boardFirmwareVersion().getOrNull()
                if (boardVersion == manifest.version) {
                    _otaMessage.value = "La placa ya tiene ${manifest.version}."
                    return@launch
                }
                _otaMessage.value = "Descargando firmware ${manifest.version}..."
                val bin = FirmwareRelease.download(manifest.binUrl).getOrElse {
                    _otaMessage.value = "No se pudo descargar el firmware: ${it.message}"
                    return@launch
                }
                if (bin.size != manifest.size || sha256Hex(bin) != manifest.sha256) {
                    _otaMessage.value = "La descarga no es valida. Intentalo de nuevo."
                    return@launch
                }
                _otaMessage.value = "Actualizando la placa... no la apagues"
                val result = navClient.otaUpdate(bin, manifest.sha256) { sent, total ->
                    _otaProgress.value = (sent * 100) / total
                }
                _otaMessage.value = result.fold(
                    onSuccess = { "Actualizacion ${manifest.version} instalada. La placa se reinicia sola." },
                    onFailure = { "Error actualizando: ${it.message}. La placa sigue con su firmware anterior." }
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { _otaMessage.value = "Actualizacion no iniciada: ${e.message}"
            } finally {
                _otaBusy.value = false
                _otaProgress.value = -1
            }
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun refreshBoardInfo() = runBoardCommand("board_info")
    fun identifyBoard() = runBoardCommand("board_identify")
    fun renameBoard(name: String) {
        if (name.length !in 1..20 || name != name.trim() ||
            !name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " -_" }) {
            _boardMessage.value = "Nombre no valido: usa 1-20 letras sin acentos, numeros, espacios, - o _."
            return
        }
        runBoardCommand("board_rename", name)
    }
    private fun runBoardCommand(type: String, name: String? = null) {
        if (_uiState.value.isNavigating || navClient.boardBusy.value || _imuBusy.value) return
        val expectedAddress = _uiState.value.bleDeviceAddress
        val expectedId = navClient.boardInfo.value?.id
        if (type != "board_info" && expectedId == null) {
            _boardMessage.value = "Lee primero la identidad de la placa conectada."
            return
        }
        viewModelScope.launch {
            _boardMessage.value = null
            val result = navClient.boardCommand(type, name)
            result.onSuccess { info ->
                if (info.address != expectedAddress || (expectedId != null && info.id != expectedId)) {
                    _boardMessage.value = "La identidad ha cambiado. Comprueba la placa antes de continuar."
                    return@onSuccess
                }
                // Only update the saved label after the board confirms NVS commit.
                if (type == "board_rename") settingsRepository.updateBleDevice(info.address, info.name)
                _boardMessage.value = when(type) {
                    "board_rename" -> "Nombre guardado en la placa. Se anunciara al desconectar."
                    "board_identify" -> "Identidad mostrada en la pantalla durante 8 segundos."
                    else -> "Identidad leida de la placa conectada."
                }
            }.onFailure { _boardMessage.value = it.message ?: "No se pudo confirmar la operacion" }
        }
    }

    private val settingsRepository = app.settingsRepository
    private val geocoder = NominatimGeocoder()
    private var searchJob: Job? = null
    val tripProgress = TransmissionHub.tripProgress
    val gpxJoinOptions = TransmissionHub.gpxJoinOptions
    val offlineMap = TransmissionHub.offlineMap
    val gps = TransmissionHub.gps

    fun retryOfflineDownload() = NavigationForegroundService.retryDownload(getApplication())

    private var pendingMapsBrowser = false
    private var pendingGpxStart = false
    private var pendingBleAction: (() -> Unit)? = null

    private val gpxBuilder = GpxRouteBuilder()
    private var gpxTrack: GpxParser.GpxTrack? = null
    private var gpxRoute: RouteData? = null
    private var gpxJob: Job? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(com.example.displayconnect.utils.AppLanguage.language, navClient.connectionState,
                settingsRepository.settings) { lang, connection, settings -> Triple(lang, connection, settings) }
                .collect { (lang, connection, settings) ->
                    if (connection == com.example.displayconnect.models.ConnectionState.CONNECTED) {
                        navClient.sendNavMessage(org.json.JSONObject().put("type", "config").put("lang", lang).toString())
                        // 0.23: extras de la placa (checks de Ajustes) via boardcfg.
                        navClient.sendNavMessage(org.json.JSONObject()
                            .put("type", "boardcfg")
                            .put("show_clock", settings.boardShowClock)
                            .put("auto_bright", settings.boardAutoBright)
                            .put("gps_indicator", settings.boardGpsIndicator)
                            .toString())
                    }
                }
        }
        viewModelScope.launch {
            TransmissionHub.navigationError.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(errorMessage = error) }
                    TransmissionHub.clearNavigationError()
                }
            }
        }
        viewModelScope.launch {
            combine(
                settingsRepository.settings,
                navClient.connectionState,
                TransmissionHub.isNavigating,
                TransmissionHub.stats
            ) { settings, connection, navigating, stats ->
                MainUiState(
                    bleDeviceAddress = settings.bleDeviceAddress,
                    bleDeviceName = settings.bleDeviceName,
                    destQuery = settings.destQuery,
                    destLabel = settings.destLabel,
                    destLat = settings.destLat,
                    destLon = settings.destLon,
                    routeProfile = settings.routeProfile,
                    connectionState = connection,
                    isNavigating = navigating,
                    stats = stats.copy(resolution = settings.resolutionLabel)
                )
            }.collect { fromSettings ->
                _uiState.update { current ->
                    fromSettings.copy(
                        scannedDevices = current.scannedDevices,
                        isScanning = current.isScanning,
                        searchResults = current.searchResults,
                        isSearching = current.isSearching,
                        searchCity = current.searchCity,
                        errorMessage = current.errorMessage,
                        gpxStatus = current.gpxStatus,
                        gpxName = current.gpxName,
                        gpxInfo = current.gpxInfo,
                        gpxProgress = current.gpxProgress,
                        gpxArrowsComplete = current.gpxArrowsComplete
                    )
                }
            }
        }

        viewModelScope.launch {
            navClient.scannedDevices.collect { devices ->
                _uiState.update { it.copy(scannedDevices = devices) }
            }
        }

        viewModelScope.launch {
            navClient.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }
    }

    fun updateDestQuery(value: String) {
        searchJob?.cancel()
        _uiState.update { it.copy(destQuery = value, destLabel = "", destLat = "", destLon = "",
            searchResults = emptyList(), isSearching = false, errorMessage = null) }
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(destQuery = value, destLabel = "", destLat = "", destLon = "") }
        }
    }

    fun updateSearchCity(value: String) {
        _uiState.update { it.copy(searchCity = value) }
        updateDestQuery(_uiState.value.destQuery)
    }

    fun updateDestLat(value: String) {
        _uiState.update { it.copy(destLat = value) }
        persistDestinationCoords(value, _uiState.value.destLon)
    }

    fun updateDestLon(value: String) {
        _uiState.update { it.copy(destLon = value) }
        persistDestinationCoords(_uiState.value.destLat, value)
    }

    fun updateRouteProfile(profile: RouteProfile) {
        _uiState.update { it.copy(routeProfile = profile) }
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(routeProfile = profile) }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun lastKnownLocation(): Pair<Double, Double>? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        try {
            LocationServices.getFusedLocationProviderClient(getApplication<Application>()).lastLocation
                .addOnSuccessListener { loc -> cont.resume(loc?.let { it.latitude to it.longitude }) { } }
                .addOnFailureListener { cont.resume(null) { } }
        } catch (_: Exception) { cont.resume(null) { } }
    }

    fun searchDestination() {
        searchJob?.cancel()
        val query = _uiState.value.destQuery.trim()
        val city = _uiState.value.searchCity.trim()
        if (query.length < 3) {
            _uiState.update {
                it.copy(errorMessage = com.example.displayconnect.utils.AppLanguage.context(getApplication()).getString(R.string.error_search_too_short))
            }
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchResults = emptyList(), errorMessage = null) }
            val bias = if (hasPreciseLocationPermission()) lastKnownLocation() else null
            geocoder.search(query, city, biasLat = bias?.first, biasLon = bias?.second)
                .onSuccess { results ->
                    ensureActive()
                    _uiState.update { it.copy(searchResults = results, isSearching = false) }
                }
                .onFailure { error ->
                    ensureActive()
                    val appCtx = com.example.displayconnect.utils.AppLanguage.context(getApplication())
                    val message = when (error.message) {
                        "No places found" -> appCtx.getString(R.string.error_place_not_found)
                        "Query too short" -> appCtx.getString(R.string.error_search_too_short)
                        else -> appCtx.getString(R.string.error_search_failed)
                    }
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchResults = emptyList(),
                            errorMessage = message
                        )
                    }
                }
        }
    }

    fun selectSearchResult(result: PlaceSearchResult) {
        val lat = result.lat.toString()
        val lon = result.lon.toString()
        val shortLabel = result.displayName.lineSequence().first().take(80)

        _uiState.update {
            it.copy(
                destLabel = shortLabel,
                destLat = lat,
                destLon = lon,
                searchResults = emptyList()
            )
        }

        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(
                    destQuery = _uiState.value.destQuery,
                    destLabel = shortLabel,
                    destLat = lat,
                    destLon = lon
                )
            }
        }
    }

    private fun persistDestinationCoords(lat: String, lon: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(destLat = lat, destLon = lon) }
        }
    }

    fun startBleScan(onNeedBluetoothPermission: () -> Unit) {
        runWithBluetoothPermission(onNeedBluetoothPermission) {
            if (!navClient.isBluetoothEnabled()) {
                _uiState.update {
                    it.copy(errorMessage = com.example.displayconnect.utils.AppLanguage.context(getApplication()).getString(R.string.error_bluetooth_disabled))
                }
                return@runWithBluetoothPermission
            }
            navClient.startScan()
        }
    }

    fun stopBleScan() {
        navClient.stopScan()
    }

    fun connectToDevice(device: BleDeviceItem, onNeedBluetoothPermission: () -> Unit) {
        runWithBluetoothPermission(onNeedBluetoothPermission) {
            viewModelScope.launch {
                settingsRepository.updateBleDevice(device.address, device.name)
                _uiState.update {
                    it.copy(bleDeviceAddress = device.address, bleDeviceName = device.name)
                }
                navClient.stopScan()
                navClient.connect(device.address, device.name)
            }
        }
    }

    fun connectSavedDevice(onNeedBluetoothPermission: () -> Unit) {
        val address = _uiState.value.bleDeviceAddress
        if (address.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = com.example.displayconnect.utils.AppLanguage.context(getApplication()).getString(R.string.error_no_ble_device))
            }
            return
        }
        runWithBluetoothPermission(onNeedBluetoothPermission) {
            if (!navClient.isBluetoothEnabled()) {
                _uiState.update {
                    it.copy(errorMessage = com.example.displayconnect.utils.AppLanguage.context(getApplication()).getString(R.string.error_bluetooth_disabled))
                }
                return@runWithBluetoothPermission
            }
            navClient.connect(address, _uiState.value.bleDeviceName)
        }
    }

    fun disconnect() {
        stopNavigation()
        navClient.disconnect()
    }

    fun onBluetoothPermissionGranted() {
        pendingBleAction?.invoke()
        pendingBleAction = null
    }

    private fun runWithBluetoothPermission(onNeedPermission: () -> Unit, action: () -> Unit) {
        if (hasBluetoothPermissions()) {
            action()
        } else {
            pendingBleAction = action
            onNeedPermission()
        }
    }

    fun startNavigation(onNeedLocationPermission: () -> Unit) {
        if (!ensureConnectedAndDestination()) return
        val (lat, lon) = resolveDestination() ?: return

        if (hasLocationPermission()) {
            NavigationForegroundService.start(getApplication(), lat, lon)
        } else {
            onNeedLocationPermission()
        }
    }

    fun startMapsBrowserNavigation(context: Context, onNeedLocationPermission: () -> Unit) {
        if (!ensureConnectedAndDestination()) return
        val (lat, lon) = resolveDestination() ?: return

        if (hasLocationPermission()) {
            launchMapsBrowser(context, lat, lon)
        } else {
            pendingMapsBrowser = true
            onNeedLocationPermission()
        }
    }

    fun onLocationPermissionGranted() {
        if (pendingGpxStart) {
            pendingGpxStart = false
            startGpxNavigation { }
            return
        }
        if (!hasPreciseLocationPermission()) {
            _uiState.update { it.copy(errorMessage = com.example.displayconnect.utils.AppLanguage.context(getApplication()).getString(R.string.error_precise_location)) }
            return
        }
        val coords = resolveDestination() ?: return
        val (lat, lon) = coords
        if (pendingMapsBrowser) {
            pendingMapsBrowser = false
            launchMapsBrowser(getApplication(), lat, lon)
        } else {
            NavigationForegroundService.start(getApplication(), lat, lon)
        }
    }

    private fun ensureConnectedAndDestination(): Boolean {
        val state = _uiState.value
        if (state.connectionState != com.example.displayconnect.models.ConnectionState.CONNECTED) {
            _uiState.update { it.copy(errorMessage = com.example.displayconnect.utils.AppLanguage.context(getApplication()).getString(R.string.error_not_connected)) }
            return false
        }
        if (resolveDestination() == null) {
            _uiState.update { it.copy(errorMessage = com.example.displayconnect.utils.AppLanguage.context(getApplication()).getString(R.string.error_invalid_destination)) }
            return false
        }
        return true
    }

    private fun resolveDestination(): Pair<Double, Double>? {
        val state = _uiState.value
        val lat = state.destLat.toDoubleOrNull()
        val lon = state.destLon.toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return null
        }
        return lat to lon
    }

    private fun launchMapsBrowser(context: Context, lat: Double, lon: Double) {
        NavigationForegroundService.start(context, lat, lon)
        val intent = Intent(context, MapsBrowserActivity::class.java).apply {
            putExtra(MapsBrowserActivity.EXTRA_DEST_LAT, lat)
            putExtra(MapsBrowserActivity.EXTRA_DEST_LON, lon)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    private fun hasLocationPermission(): Boolean {
        return hasPreciseLocationPermission()
    }

    private fun hasPreciseLocationPermission(): Boolean {
        val appCtx = getApplication<Application>()
        return ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothPermissions(): Boolean {
        val appCtx = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission()
        }
    }

    // ---------- Importacion GPX ----------

    fun onGpxFileSelected(uri: Uri) {
        viewModelScope.launch {
            val appCtx = getApplication<Application>()
            val (text, fileName) = withContext(Dispatchers.IO) {
                val name = queryDisplayName(appCtx, uri)
                val content = appCtx.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                (content ?: "") to name
            }
            if (text.isBlank()) {
                _uiState.update { it.copy(gpxStatus = "error", errorMessage = "No se pudo leer el archivo GPX") }
                return@launch
            }
            try {
                val track = GpxParser.parse(text, fileName)
                if (track.points.size < 2) {
                    _uiState.update { it.copy(gpxStatus = "error", errorMessage = "Ese GPX no tiene puntos de ruta") }
                    return@launch
                }
                gpxTrack = track
                gpxRoute = null
                _uiState.update {
                    it.copy(
                        gpxStatus = "loaded",
                        gpxName = track.name,
                        gpxInfo = "${GpxParser.formatKm(track.distanceM)} km · ${track.points.size} puntos",
                        gpxProgress = "",
                        gpxArrowsComplete = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(gpxStatus = "error", errorMessage = "Ese archivo no es un GPX valido") }
            }
        }
    }

    fun prepareGpxRoute() {
        val track = gpxTrack ?: return
        gpxJob?.cancel()
        gpxJob = viewModelScope.launch {
            _uiState.update { it.copy(gpxStatus = "preparing", gpxProgress = "") }
            try {
                val outcome = gpxBuilder.build(track.points, _uiState.value.routeProfile) { done, total ->
                    _uiState.update { s -> s.copy(gpxProgress = "$done/$total") }
                }
                ensureActive()
                gpxRoute = outcome.route
                _uiState.update {
                    it.copy(
                        gpxStatus = "ready",
                        gpxInfo = "${GpxParser.formatKm(outcome.route.distanceM)} km · ${outcome.route.steps.size} indicaciones",
                        gpxProgress = "",
                        gpxArrowsComplete = outcome.arrowsComplete
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(gpxStatus = "loaded", gpxProgress = "",
                        errorMessage = "No se pudo preparar la ruta. Revisa internet e intentalo de nuevo.")
                }
            }
        }
    }

    fun startGpxNavigation(onNeedLocationPermission: () -> Unit) {
        val route = gpxRoute ?: return
        if (_uiState.value.connectionState != com.example.displayconnect.models.ConnectionState.CONNECTED) {
            _uiState.update {
                it.copy(errorMessage = com.example.displayconnect.utils.AppLanguage.context(getApplication()).getString(R.string.error_not_connected))
            }
            return
        }
        if (!hasLocationPermission()) {
            pendingGpxStart = true
            onNeedLocationPermission()
            return
        }
        val name = gpxTrack?.name ?: "Ruta GPX"
        PendingRouteHolder.put(route, name)
        val last = route.coordinates.last()
        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(destLabel = name, destLat = last.lat.toString(), destLon = last.lon.toString())
            }
        }
        NavigationForegroundService.startPreparedRoute(getApplication())
    }

    fun clearGpx() {
        gpxJob?.cancel()
        gpxTrack = null
        gpxRoute = null
        PendingRouteHolder.clear()
        _uiState.update {
            it.copy(gpxStatus = "none", gpxName = "", gpxInfo = "", gpxProgress = "", gpxArrowsComplete = true)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
                } ?: ""
        }.getOrDefault("").ifBlank { "Ruta GPX" }
    }

    /** Eleccion expresa del usuario entre las ramas de enganche GPX ambiguas. */
    fun selectGpxJoinCandidate(index: Int) =
        NavigationForegroundService.selectGpxJoin(getApplication(), index)

    fun stopNavigation() {
        pendingMapsBrowser = false
        NavigationForegroundService.stop(getApplication())
        TransmissionHub.setNavigating(false)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
