package com.example.displayconnect.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.displayconnect.models.ConnectionState
import com.example.displayconnect.models.MainUiState
import com.example.displayconnect.routing.PlaceSearchResult
import com.example.displayconnect.routing.RouteProfile
import com.example.displayconnect.ui.components.TripProgressCard
import com.example.displayconnect.ui.theme.Alert
import com.example.displayconnect.ui.theme.Ok
import com.example.displayconnect.ui.theme.Warn
import com.example.displayconnect.utils.TransmissionHub
import com.example.displayconnect.viewmodel.MainViewModel
import org.json.JSONArray
import org.json.JSONObject

// ---------- Destinos recientes (guardados en el movil, sin tocar el ViewModel) ----------

data class RecentDestination(val label: String, val lat: Double, val lon: Double)

class RecentDestinations(context: Context) {
    private val prefs = context.getSharedPreferences("recent_destinations", Context.MODE_PRIVATE)

    fun load(): List<RecentDestination> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RecentDestination(o.getString("label"), o.getDouble("lat"), o.getDouble("lon"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(item: RecentDestination) {
        val list = (listOf(item) + load().filter { it.label != item.label }).take(6)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("label", it.label).put("lat", it.lat).put("lon", it.lon))
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}

// ---------- Pantalla Home ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onOpenClassic: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val altitudeStatus by viewModel.altitudeStatus.collectAsState()
    val tripProgress by viewModel.tripProgress.collectAsState()
    val gpxJoinOptions by viewModel.gpxJoinOptions.collectAsState()
    val liveNav by TransmissionHub.liveNav.collectAsState()
    val view = LocalView.current
    DisposableEffect(uiState.isNavigating) {
        view.keepScreenOn = uiState.isNavigating
        onDispose { view.keepScreenOn = false }
    }
    val context = LocalContext.current
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onGpxFileSelected(it) }
    }
    val recents = remember { RecentDestinations(context.applicationContext) }
    var recentList by remember { mutableStateOf(recents.load()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    val hasDestination = uiState.destLabel.isNotBlank()
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .then(if (uiState.isNavigating) Modifier else Modifier.verticalScroll(scrollState)),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Barra superior: chip Bluetooth + ajustes
            if (!uiState.isNavigating) Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoardChip(
                    state = uiState.connectionState,
                    hasSavedDevice = uiState.bleDeviceAddress.isNotBlank(),
                    onClick = {
                        when (uiState.connectionState) {
                            ConnectionState.DISCONNECTED, ConnectionState.ERROR ->
                                if (uiState.bleDeviceAddress.isNotBlank()) {
                                    viewModel.connectSavedDevice(onRequestBluetoothPermission)
                                } else {
                                    onOpenClassic()
                                }
                            else -> Unit
                        }
                    }
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Ajustes",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!uiState.isNavigating) OutlinedButton(onClick = onOpenClassic) {
                Text("Elegir o configurar mi placa")
            }

            if (uiState.isNavigating) {
                Text(altitudeStatus.message, style = MaterialTheme.typography.bodySmall)
                // ---------- Navegando: espejo de la placa ----------
                NavMirror(
                    nav = liveNav,
                    trip = tripProgress,
                    destLabel = uiState.destLabel,
                    onStop = { viewModel.stopNavigation() },
                    joinOptions = gpxJoinOptions,
                    onSelectJoin = { viewModel.selectGpxJoinCandidate(it) }
                )
            } else {
                // ---------- Buscar destino ----------
                Text(
                    "¿A dónde?",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp)
                )

                OutlinedTextField(
                    value = uiState.destQuery,
                    onValueChange = viewModel::updateDestQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Calle, ciudad o lugar") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else if (uiState.destQuery.isNotBlank()) {
                            IconButton(onClick = {
                                viewModel.searchDestination()
                                keyboard?.hide()
                            }) {
                                Icon(Icons.Default.Search, contentDescription = "Buscar",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        viewModel.searchDestination()
                        keyboard?.hide()
                    })
                )

                // Resultados de la busqueda
                uiState.searchResults.forEach { result ->
                    val lines = result.displayName.lines()
                    PlaceCard(
                        title = lines.firstOrNull().orEmpty(),
                        subtitle = lines.drop(1).joinToString(" ").ifBlank { result.source },
                        warning = if (result.houseNumberUnconfirmed) "Número sin confirmar" else null,
                        onClick = { viewModel.selectSearchResult(result) }
                    )
                }

                // Destino elegido: resumen + Empezar
                if (hasDestination) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Destino", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Text(uiState.destLabel, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    RouteProfile.MOTORCYCLE to "Moto",
                                    RouteProfile.CAR to "Coche",
                                    RouteProfile.BIKE to "Bici"
                                ).forEach { (profile, label) ->
                                    FilterChip(
                                        selected = uiState.routeProfile == profile,
                                        onClick = { viewModel.updateRouteProfile(profile) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            if (uiState.connectionState != ConnectionState.CONNECTED) {
                                Text(
                                    "La placa no está conectada. Toca el chip de arriba.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Warn
                                )
                            }
                            Button(
                                onClick = {
                                    val lat = uiState.destLat.toDoubleOrNull()
                                    val lon = uiState.destLon.toDoubleOrNull()
                                    if (lat != null && lon != null) {
                                        recents.add(RecentDestination(uiState.destLabel, lat, lon))
                                        recentList = recents.load()
                                    }
                                    viewModel.startNavigation(onRequestLocationPermission)
                                },
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text("Empezar", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = { viewModel.updateDestQuery("") },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) { Text("Cambiar destino") }
                        }
                    }
                }

                // Recientes (solo si no hay nada elegido ni resultados)
                if (!hasDestination && uiState.searchResults.isEmpty() && recentList.isNotEmpty()) {
                    Text("Recientes", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    recentList.forEach { item ->
                        val parts = item.label.split(",", limit = 2)
                        PlaceCard(
                            title = parts.firstOrNull().orEmpty().trim(),
                            subtitle = parts.getOrNull(1)?.trim().orEmpty(),
                            warning = null,
                            onClick = {
                                viewModel.selectSearchResult(
                                    PlaceSearchResult(displayName = item.label, lat = item.lat, lon = item.lon)
                                )
                            }
                        )
                    }
                }
                // ---------- Ruta GPX ----------
                GpxSection(
                    uiState = uiState,
                    onPick = { gpxLauncher.launch(arrayOf("*/*")) },
                    onPrepare = { viewModel.prepareGpxRoute() },
                    onStart = { viewModel.startGpxNavigation(onRequestLocationPermission) },
                    onClear = { viewModel.clearGpx() }
                )
            }

            if (!uiState.isNavigating) {
                TextButton(
                    onClick = onOpenClassic,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Modo clásico (Bluetooth, coordenadas)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun GpxSection(
    uiState: MainUiState,
    onPick: () -> Unit,
    onPrepare: () -> Unit,
    onStart: () -> Unit,
    onClear: () -> Unit
) {
    if (uiState.gpxStatus == "none") {
        OutlinedButton(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("Cargar ruta GPX", fontSize = 16.sp)
        }
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ruta GPX", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(uiState.gpxName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            if (uiState.gpxInfo.isNotBlank()) {
                Text(uiState.gpxInfo, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (uiState.gpxStatus) {
                "loaded" -> {
                    Button(
                        onClick = onPrepare,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) { Text("Preparar ruta", fontSize = 16.sp) }
                    Text("Necesita internet para generar las indicaciones de giro.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "preparing" -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Preparando indicaciones… ${uiState.gpxProgress}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                "ready" -> {
                    if (!uiState.gpxArrowsComplete) {
                        Text("Hay tramos sin indicaciones; el mapa y la distancia funcionan igual.",
                            style = MaterialTheme.typography.bodySmall, color = Warn)
                    }
                    if (uiState.connectionState != ConnectionState.CONNECTED) {
                        Text("La placa no está conectada. Toca el chip de arriba.",
                            style = MaterialTheme.typography.bodySmall, color = Warn)
                    }
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) { Text("Empezar", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
                "error" -> Text("No se pudo usar ese archivo.",
                    style = MaterialTheme.typography.bodyMedium, color = Alert)
            }
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Quitar")
            }
        }
    }
}

@Composable
private fun BoardChip(state: ConnectionState, hasSavedDevice: Boolean, onClick: () -> Unit) {
    val (color, text) = when (state) {
        ConnectionState.CONNECTED -> Ok to "Placa conectada"
        ConnectionState.CONNECTING -> Warn to "Conectando…"
        ConnectionState.RECONNECTING -> Warn to "Reconectando…"
        ConnectionState.ERROR -> Alert to "Error · toca para reintentar"
        ConnectionState.DISCONNECTED ->
            Color(0xFF8A94A3) to (if (hasSavedDevice) "Sin conexión · toca para conectar" else "Vincular placa")
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PlaceCard(title: String, subtitle: String, warning: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (warning != null) {
                Text(warning, style = MaterialTheme.typography.labelSmall, color = Warn)
            }
        }
    }
}
