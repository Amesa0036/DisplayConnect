package com.example.displayconnect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.displayconnect.R
import com.example.displayconnect.ui.components.StatsCard
import com.example.displayconnect.ui.navigation.DataTopBar
import com.example.displayconnect.models.ConnectionState
import com.example.displayconnect.viewmodel.MainViewModel
import com.example.displayconnect.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    section: String = "preferences",
    viewModel: SettingsViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel()
) {
    val mainUiState by mainViewModel.uiState.collectAsState()
    val effectiveGpsSource by com.example.displayconnect.utils.TransmissionHub.effectiveGpsSource.collectAsState()
    val traceActive by mainViewModel.navigationTraceActive.collectAsState()
    val rawNotice by mainViewModel.rawCaptureNotice.collectAsState()
    val traceStatus by mainViewModel.navigationTraceStatus.collectAsState()
    val traceMessage by mainViewModel.navigationTraceMessage.collectAsState()
    val traceBusy by mainViewModel.navigationTraceBusy.collectAsState()
    val traceExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) {
        mainViewModel.exportNavigationTrace(it)
    }
    var confirmTraceDelete by remember { mutableStateOf(false) }
    if (confirmTraceDelete) AlertDialog(
        onDismissRequest = { confirmTraceDelete = false },
        title = { Text("Borrar registro privado") },
        text = { Text("Se borra el registro de navegacion de esta app, no las copias exportadas.") },
        confirmButton = { TextButton(onClick = { confirmTraceDelete = false; mainViewModel.clearNavigationTrace() }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { confirmTraceDelete = false }) { Text("Cancelar") } }
    )

    val boardInfo by mainViewModel.boardInfo.collectAsState()
    val otaBusy by mainViewModel.otaBusy.collectAsState()
    val otaProgress by mainViewModel.otaProgress.collectAsState()
    val otaMessage by mainViewModel.otaMessage.collectAsState()
    val remoteFirmwarePreview by mainViewModel.remoteFirmwarePreview.collectAsState()
    remoteFirmwarePreview?.let { preview ->
        com.example.displayconnect.ui.components.RemoteFirmwareConfirmDialog(preview.version,preview.size,preview.boardName,preview.diagnostic,preview.installed,
            onCancel={mainViewModel.dismissRemoteFirmware()},onConfirm={mainViewModel.confirmRemoteFirmwareUpdate()})
    }
    val localFirmwarePreview by mainViewModel.localFirmwarePreview.collectAsState()
    val localFirmwarePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->mainViewModel.prepareLocalFirmware(uri)}
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { mainViewModel.dismissLocalFirmware() } }
    localFirmwarePreview?.let { preview ->
        com.example.displayconnect.ui.components.LocalFirmwareConfirmDialog(preview.version,preview.size,preview.sha256,preview.boardName,
            onCancel={mainViewModel.dismissLocalFirmware()},onConfirm={mainViewModel.confirmLocalFirmwareUpdate()})
    }
    val imuMessage by mainViewModel.imuMessage.collectAsState()
    val imuBusy by mainViewModel.imuBusy.collectAsState()
    val imuRunning by mainViewModel.imuRunning.collectAsState()
    val imuSaved by mainViewModel.imuSaved.collectAsState()
    val shadowMessage by mainViewModel.shadowMessage.collectAsState()
    val shadowBusy by mainViewModel.shadowBusy.collectAsState()
    val shadowSaved by mainViewModel.shadowSaved.collectAsState()
    var confirmShadow by remember { mutableStateOf(false) }
    val shadowExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> mainViewModel.exportShadow(uri) }
    if (confirmShadow) AlertDialog(
        onDismissRequest = { confirmShadow = false },
        title = { Text("Preparacion de diagnostico") },
        text = { Text("Solo prueba estacionaria y atendida. No es permiso para usarlo en carretera ni probar motor. No controla la pantalla. Con navegacion activa evalua GPS; sin GPS no declara reposo. Guarda el CSV antes de apagar o reiniciar.") },
        confirmButton = { TextButton(onClick = { confirmShadow = false; mainViewModel.shadowCommand("start") }) { Text("Iniciar diagnostico") } },
        dismissButton = { TextButton(onClick = { confirmShadow = false }) { Text("Cancelar") } }
    )
    var imuAction by remember { mutableStateOf<String?>(null) }
    val imuExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        mainViewModel.exportImu(uri)
    }
    if (imuAction != null) {
        AlertDialog(
            onDismissRequest = { imuAction = null },
            title = { Text("Solo para prueba en mesa") },
            text = { Text("Confirma que la placa esta sobre la mesa y no estas navegando. Esta version solo registra datos; no controla la pantalla.") },
            confirmButton = { TextButton(onClick = {
                val action = imuAction; imuAction = null
                when (action) {
                    "start" -> mainViewModel.startImu()
                    "stop" -> mainViewModel.stopImu()
                    "download" -> mainViewModel.downloadImu()
                }
            }) { Text("Esta en la mesa") } },
            dismissButton = { TextButton(onClick = { imuAction = null }) { Text("Cancelar") } }
        )
    }
    val settings by viewModel.settings.collectAsState()
    val gnssProbeMessage by mainViewModel.gnssProbeMessage.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val language by viewModel.language.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DataTopBar(when (section) { "board" -> "Extras de la placa"; "advanced" -> "Capturas y diagnóstico"; else -> "Ajustes" }, onNavigateBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            if (section == "preferences") {
            SectionCard(title = stringResource(R.string.setting_language)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = language == "es", onClick = { viewModel.selectLanguage("es") }, label = { Text("Español") })
                    FilterChip(selected = language == "en", onClick = { viewModel.selectLanguage("en") }, label = { Text("English") })
                    FilterChip(selected = language == "pt-BR", onClick = { viewModel.selectLanguage("pt-BR") }, label = { Text("Português") })
                }
            }

            SectionCard(title = stringResource(R.string.setting_gps_source)) {
                Text("Eleccion para la siguiente sesion. Al cambiar durante una ruta por destino, primero se para y se espera al cierre del GPS y su copia.",style=MaterialTheme.typography.bodySmall)
                val effective=effectiveGpsSource
                Text(if(mainUiState.isNavigating && effective!=null)
                    "Fuente en uso: "+if(effective==com.example.displayconnect.models.GpsSource.HECATE) "GPS de Hecate" else "GPS del movil"
                    else "GPS de navegacion parado.",style=MaterialTheme.typography.bodySmall)
                com.example.displayconnect.ui.components.RawCaptureStatus(rawNotice)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.gpsSource == com.example.displayconnect.models.GpsSource.PHONE,
                        onClick = { viewModel.updateSettings { it.copy(gpsSource = com.example.displayconnect.models.GpsSource.PHONE) } },
                        label = { Text(stringResource(R.string.gps_source_phone)) })
                    FilterChip(
                        selected = settings.gpsSource == com.example.displayconnect.models.GpsSource.HECATE,
                        onClick = {
                            mainViewModel.probeAndSelectHecateGnss {
                                viewModel.updateSettings { it.copy(gpsSource = com.example.displayconnect.models.GpsSource.HECATE) }
                            }
                        },
                        label = { Text(stringResource(R.string.gps_source_hecate)) })
                }
                gnssProbeMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard(title = stringResource(R.string.setting_nav_hz, settings.navUpdateHz)) {
                Slider(
                    value = settings.navUpdateHz.toFloat(),
                    onValueChange = { value ->
                        viewModel.updateSettings { it.copy(navUpdateHz = value.toInt()) }
                    },
                    valueRange = 1f..5f,
                    steps = 3
                )
                Text(
                    "Cuántas veces por segundo se manda la posición a la placa. Se aplica al iniciar la navegación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(title = stringResource(R.string.setting_map_scale, settings.mapScaleMeters.toInt())) {
                Slider(
                    value = settings.mapScaleMeters.toFloat(),
                    onValueChange = { value ->
                        viewModel.updateSettings { it.copy(mapScaleMeters = value.toDouble()) }
                    },
                    valueRange = 150f..800f,
                    steps = 12
                )
                Text(
                    text = stringResource(R.string.setting_map_scale_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            }
            if (section == "board") {
            SectionCard(title = "Extras de la placa") {
                ExtraSwitch("Hora en la placa", settings.boardShowClock) { on ->
                    viewModel.updateSettings { it.copy(boardShowClock = on) }
                }
                ExtraSwitch("Brillo automatico dia/noche", settings.boardAutoBright) { on ->
                    viewModel.updateSettings { it.copy(boardAutoBright = on) }
                }
                ExtraSwitch("Aviso discreto si falla el GPS", settings.boardGpsIndicator) { on ->
                    viewModel.updateSettings { it.copy(boardGpsIndicator = on) }
                }
                ExtraSwitch("Limite de velocidad manual", settings.manualSpeedLimitEnabled) { on ->
                    viewModel.updateSettings { it.copy(manualSpeedLimitEnabled = on) }
                }
                if (settings.manualSpeedLimitEnabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(90, 100, 110, 120).forEach { kmh ->
                            FilterChip(
                                selected = settings.manualSpeedLimitKmh == kmh,
                                onClick = { viewModel.updateSettings { it.copy(manualSpeedLimitKmh = kmh) } },
                                label = { Text("$kmh km/h") }
                            )
                        }
                    }
                }
                Text(
                    "Se mandan a la placa al conectar por Bluetooth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            }
            if (section == "advanced") {
            SectionCard(title = "Registro privado de navegacion") {
                Text("Con GPS de Hecate guarda posiciones originales, ruta, decisiones de avance y recalculos en este movil. Sin envio automatico ni copia de seguridad. Conserva hasta tres archivos de unos 2 MB; lo mas antiguo se reemplaza.", style = MaterialTheme.typography.bodySmall)
                Text("La copia contiene ubicaciones. Elige donde guardarla y con quien compartirla; guardar no la envia.", style = MaterialTheme.typography.bodySmall)
                Text(com.example.displayconnect.navigation.RawCapturePresentation.EXPLANATION, style = MaterialTheme.typography.bodySmall)
                com.example.displayconnect.ui.components.RawCaptureStatus(rawNotice)
                Text(traceStatus, style = MaterialTheme.typography.bodySmall)
                if (traceMessage.isNotBlank()) Text(traceMessage, style = MaterialTheme.typography.bodySmall)
                Button(enabled = !mainUiState.isNavigating && traceActive == 0 && !rawNotice.copying && !traceBusy,
                    onClick = { traceExport.launch("registro-navegacion.jsonl") }) { Text("Guardar copia del registro") }
                Button(enabled = !mainUiState.isNavigating && traceActive == 0 && !rawNotice.copying && !traceBusy,
                    onClick = { confirmTraceDelete = true }) { Text("Borrar registro privado") }
                if (mainUiState.isNavigating || traceActive > 0) Text("Finaliza la navegacion y espera al cierre del GPS antes de guardar o borrar.")
            }

            SectionCard(title = "Diagnostico sombra (preparacion)") {
                val ready = mainUiState.connectionState == ConnectionState.CONNECTED && !otaBusy && !imuBusy && !shadowBusy && !mainViewModel.boardBusy.collectAsState().value
                Text("Solo registra posibles decisiones y motivos. Nunca apaga ni enciende la pantalla. Desactivado hasta iniciar. Hasta 3 minutos; RAM se pierde al apagar.")
                Text("GPS solo durante navegacion activa. IMU tranquila y GPS lento no demuestran moto parada. Esta preparacion no autoriza pruebas en carretera ni motor.", style = MaterialTheme.typography.bodySmall)
                Text(shadowMessage)
                if (shadowBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(enabled = ready, onClick = { mainViewModel.shadowCommand("status") }) { Text("Consultar sombra") }
                Button(enabled = ready, onClick = { confirmShadow = true }) { Text("Iniciar sombra") }
                Button(enabled = ready, onClick = { mainViewModel.shadowCommand("stop") }) { Text("Parar sombra") }
                Button(enabled = ready && !mainUiState.isNavigating, onClick = { mainViewModel.downloadShadow() }) { Text("Parar y descargar sombra") }
                Button(enabled = shadowSaved && ready && !mainUiState.isNavigating, onClick = { shadowExport.launch("diagnostico-sombra.csv") }) { Text("Guardar copia de sombra") }
                Text("Para descargar, finaliza navegacion y aparca. Captura cruda y sombra no pueden compartir sensor. Una sesion por arranque; guarda antes de reiniciar.", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(title = "Prueba pasiva del sensor") {
                val ready = mainUiState.connectionState == ConnectionState.CONNECTED &&
                    !mainUiState.isNavigating && !otaBusy && !imuBusy && !shadowBusy && !mainViewModel.boardBusy.collectAsState().value
                Text("Prueba solo en mesa, sin navegacion. No controla la pantalla. RAM: se pierde al apagar o reiniciar la placa.")
                Text("Firmware r3: hasta 3 minutos y 12.000 filas, acelerometro a 62,5 Hz nominales. Prueba solo en mesa, sin navegacion. No apaga la pantalla. Guarda datos crudos y contadores de huecos; no mide toda la vibracion. Firmware anterior: solo 0,5 s.", style = MaterialTheme.typography.bodySmall)
                Text(imuMessage, style = MaterialTheme.typography.bodyMedium)
                if (imuBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(enabled = ready, onClick = { mainViewModel.refreshImu() }) { Text("Consultar estado") }
                Button(enabled = ready && !imuRunning, onClick = { imuAction = "start" }) { Text("Iniciar captura") }
                Button(enabled = ready, onClick = { imuAction = "stop" }) { Text("Parar captura") }
                Button(enabled = ready, onClick = { imuAction = "download" }) { Text("Parar y descargar CSV") }
                Button(enabled = imuSaved && !imuBusy && !mainUiState.isNavigating, onClick = { imuExport.launch("captura-sensor.csv") }) { Text("Guardar copia del ultimo CSV") }
                if (mainUiState.isNavigating) Text("Finaliza la navegacion, aparca y vuelve aqui para descargar.")
                Text("Descargar guarda una copia interna antes de elegir carpeta. No desinstales la app antes de guardar copia. Para otra captura: guarda y comprueba el CSV, luego reinicia la placa.", style = MaterialTheme.typography.bodySmall)
            }

            StatsCard(stats = stats.copy(resolution = settings.resolutionLabel))
            }
            if (section == "preferences") {
            com.example.displayconnect.ui.components.FirmwareUpdateCard(
                connected = mainUiState.connectionState == ConnectionState.CONNECTED,
                canUpdate = remoteFirmwarePreview == null && localFirmwarePreview == null && boardInfo != null && !mainUiState.isNavigating && !imuBusy && !imuRunning && !shadowBusy && !mainViewModel.boardBusy.collectAsState().value,
                busy = otaBusy,
                progress = otaProgress,
                message = otaMessage,
                needsUsb = boardInfo?.hardwareModel == com.example.displayconnect.network.HardwareUpdatePolicy.G && boardInfo?.otaEnabled != true,
                allowLocalFile = mainViewModel.localFirmwareToolsEnabled() && boardInfo?.hardwareModel == com.example.displayconnect.network.HardwareUpdatePolicy.G,
                onLocalFile = { localFirmwarePicker.launch(arrayOf("application/octet-stream","*/*")) },
                onUpdate = { mainViewModel.startFirmwareUpdate() },
                allowTrial = boardInfo?.hardwareModel == com.example.displayconnect.network.HardwareUpdatePolicy.G,
                onTrial = { mainViewModel.startFirmwareUpdate(trial=true) }
            )

            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ExtraSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}
