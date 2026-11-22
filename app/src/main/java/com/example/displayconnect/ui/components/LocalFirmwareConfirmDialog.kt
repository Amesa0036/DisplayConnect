package com.example.displayconnect.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Explicit user confirmation, never shown as proof that a board has been updated. */
@Composable
fun LocalFirmwareConfirmDialog(version:String,size:Int,sha256:String,boardName:String,onCancel:()->Unit,onConfirm:()->Unit) {
    AlertDialog(
        onDismissRequest=onCancel,
        title={Text("Enviar archivo de prueba")},
        text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Destino: $boardName (Hecate G)")
            Text("Version: $version\nTamano: $size bytes")
            Text("Firma Hecate verificada. SHA-256:\n${sha256.chunked(32).joinToString("\n")}",style=MaterialTheme.typography.bodySmall)
            Text("Al confirmar se escribira en la particion inactiva y la placa se reiniciara. No la apagues. Despues hay que reconectar y comprobar la version; la transferencia por si sola no confirma el arranque.")
            Text("No publica firmware ni modifica el canal normal. Si no quieres escribir ahora, pulsa Cancelar.",style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(onClick=onConfirm){Text("Enviar por Bluetooth")}},
        dismissButton={TextButton(onClick=onCancel){Text("Cancelar")}}
    )
}
