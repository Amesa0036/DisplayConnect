package com.example.displayconnect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.example.displayconnect.ui.navigation.SettingsTopBar
import com.example.displayconnect.models.ConnectionState
import com.example.displayconnect.viewmodel.MainViewModel
import com.example.displayconnect.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel()
) {
    val mainUiState by mainViewModel.uiState.collectAsState()
    val boardInfo by mainViewModel.boardInfo.collectAsState()
    val otaBusy by mainViewModel.otaBusy.collectAsState()
    val otaProgress by mainViewModel.otaProgress.collectAsState()
    val otaMessage by mainViewModel.otaMessage.collectAsState()
    val imuMessage by mainViewModel.imuMessage.collectAsState()
    val imuBusy by mainViewModel.imuBusy.collectAsState()
    val imuRunning by mainViewModel.imuRunning.collectAsState()
    val imuSaved by mainViewModel.imuSaved.collectAsState()
    var imuAction by remember { mutableStateOf<String?>(null) }
    val imuExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        mainViewModel.exportImu(uri)
    }
    if (imuAction != null) {
        AlertDialog(
            onDismissRequest = { imuAction = null },
            title = { Text("Solo estando aparcado") },
            text = { Text("Confirma que estas aparcado y puedes usar el movil con seguridad. Durante la marcha no hay que tocar nada.") },
            confirmButton = { TextButton(onClick = {
                val action = imuAction; imuAction = null
                when (action) {
                    "start" -> mainViewModel.startImu()
                    "stop" -> mainViewModel.stopImu()
                    "download" -> mainViewModel.downloadImu()
                }
            }) { Text("Estoy aparcado") } },
            dismissButton = { TextButton(onClick = { imuAction = null }) { Text("Cancelar") } }
        )
    }
    val settings by viewModel.settings.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val language by viewModel.language.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsTopBar(onNavigateBack) }
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

            SectionCard(title = stringResource(R.string.setting_language)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = language == "es", onClick = { viewModel.selectLanguage("es") }, label = { Text("Español") })
                    FilterChip(selected = language == "en", onClick = { viewModel.selectLanguage("en") }, label = { Text("English") })
                    FilterChip(selected = language == "pt-BR", onClick = { viewModel.selectLanguage("pt-BR") }, label = { Text("Português") })
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            SectionCard(title = "Prueba pasiva del sensor") {
                val ready = mainUiState.connectionState == ConnectionState.CONNECTED &&
                    !mainUiState.isNavigating && !otaBusy && !imuBusy && !mainViewModel.boardBusy.collectAsState().value
                Text("No controla la pantalla. Inicia aparcado, navega sin tocar nada y descarga al terminar. RAM: se pierde al apagar o reiniciar la placa.")
                Text("Hasta 12.000 intentos, unos 10 minutos. No es una medida de toda la vibracion. Requiere firmware de prueba compatible.", style = MaterialTheme.typography.bodySmall)
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

            SectionCard(title = "Firmware de la placa") {
                val connected = mainUiState.connectionState == ConnectionState.CONNECTED
                Text(
                    "Busca la ultima version en GitHub y la instala por Bluetooth. La placa se reinicia sola al terminar; si algo falla, arranca con su firmware anterior.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { mainViewModel.startFirmwareUpdate() },
                    enabled = connected && boardInfo != null && !mainUiState.isNavigating && !otaBusy && !imuBusy && !imuRunning
                ) {
                    Text(if (otaBusy) "Actualizando..." else "Buscar actualizacion")
                }
                if (!connected) {
                    Text("Conecta la placa primero.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (otaBusy && otaProgress >= 0) {
                    LinearProgressIndicator(
                        progress = { otaProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("$otaProgress%", style = MaterialTheme.typography.bodySmall)
                } else if (otaBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                otaMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            StatsCard(stats = stats.copy(resolution = settings.resolutionLabel))
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
