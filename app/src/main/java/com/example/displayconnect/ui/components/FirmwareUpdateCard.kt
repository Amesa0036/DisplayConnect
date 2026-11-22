package com.example.displayconnect.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Production Settings card. Bench tests inject presentation state, never a BLE board. */
@Composable
fun FirmwareUpdateCard(
    connected: Boolean,
    canUpdate: Boolean,
    busy: Boolean,
    progress: Int,
    message: String?,
    needsUsb: Boolean,
    allowLocalFile: Boolean = false,
    onLocalFile: () -> Unit = {},
    allowTrial:Boolean=false,
    onTrial:()->Unit={},
    onUpdate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Firmware de la placa", style = MaterialTheme.typography.titleMedium)
            Text("Busca y descarga una imagen compatible. Antes de enviarla veras su version y tendras que aceptarla. No apagues la placa durante la transferencia. Una transferencia aceptada no confirma que el nuevo firmware haya arrancado.", style = MaterialTheme.typography.bodySmall)
            if (needsUsb) Text("Esta Hecate necesita primero el receptor OTA por USB. No se usara firmware de otra placa.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onUpdate, enabled = connected && canUpdate && !busy && !needsUsb) {
                Text(if (busy) "Actualizando..." else "Buscar actualizacion")
            }
            if(allowTrial) {
                OutlinedButton(onClick=onTrial,enabled=connected&&canUpdate&&!busy&&!needsUsb){Text("Buscar prueba Hecate")}
                Text("Canal separado de diagnostico. No es una actualizacion de uso ni se instala al conectar.",style=MaterialTheme.typography.bodySmall)
            }
            if (allowLocalFile) {
                OutlinedButton(onClick=onLocalFile,enabled=connected && canUpdate && !busy && !needsUsb) { Text("Elegir archivo firmado (prueba)") }
                Text("Prueba local: no publica firmware ni cambia el canal normal. Elegir un archivo no lo envia.",style=MaterialTheme.typography.bodySmall)
            }
            if (!connected) Text("Conecta la placa primero.", style = MaterialTheme.typography.bodySmall)
            if (busy && progress >= 0) {
                LinearProgressIndicator(progress = { progress.coerceIn(0,100) / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${progress.coerceIn(0,100)}%", style = MaterialTheme.typography.bodySmall)
            } else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
