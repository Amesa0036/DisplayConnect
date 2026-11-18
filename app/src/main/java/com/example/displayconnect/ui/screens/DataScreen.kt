package com.example.displayconnect.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.displayconnect.ui.navigation.DataTopBar
import com.example.displayconnect.ui.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(onNavigateBack: () -> Unit, onOpenSection: (String) -> Unit) {
    Scaffold(topBar = { DataTopBar("Placa y datos", onNavigateBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onNavigateBack) { Text("Volver a navegar") }
            Text("Todo se conserva. Abre solo el detalle que necesites.",
                style = MaterialTheme.typography.bodyMedium)
            DataEntry("Tu placa", "Conexión, nombre e identificación. Extras de pantalla.") { onOpenSection(Routes.BOARD) }
            DataEntry("Sensores", "Altitud, barómetro y estado del GPS.") { onOpenSection(Routes.SENSORS) }
            DataEntry("Rutas y mapas", "Mapa sin conexión, búsqueda detallada y coordenadas. GPX y favoritos en Navegar.") { onOpenSection(Routes.ROUTES) }
            DataEntry("Ajustes", "Idioma, preferencias y actualizaciones compatibles.") { onOpenSection(Routes.SETTINGS) }
            Spacer(Modifier.height(8.dp))
            Text("Avanzado", style = MaterialTheme.typography.labelLarge)
            DataEntry("Capturas y diagnóstico", "IMU, diagnóstico sombra, archivos y estadísticas.") { onOpenSection(Routes.ADVANCED) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DataEntry(title: String, detail: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.primary)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
