package com.example.displayconnect.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.displayconnect.navigation.AltitudeMonitor
@Composable fun AltitudeStatusCard(state: AltitudeMonitor.State) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Altitud",style=MaterialTheme.typography.titleMedium)
        Text(state.message)
        Text("${state.source} · ${state.reference}",style=MaterialTheme.typography.bodySmall)
        state.pressureHpa?.let { Text("Presion: ${java.lang.String.format(java.util.Locale.US,"%.1f",it)} hPa · ${if(state.ready) "lecturas disponibles" else "comprobando"}") }
        Text("Sin barometro fiable o sin calibrar se usa GPS aproximado. La referencia se indica en la placa; sin altura valida aparecen guiones. La calibracion con GPS tampoco garantiza una precision fija.",style=MaterialTheme.typography.bodySmall)
    }}
}
