package com.example.displayconnect.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.displayconnect.network.BoardInfo

/** Separate from the navigation UI. Renaming requires the connected board's reply. */
@Composable
fun BoardSettingsCard(
    info: BoardInfo?, busy: Boolean, navigating: Boolean, message: String?,
    onIdentify: () -> Unit, onRename: (String) -> Unit, onRefresh: () -> Unit
) {
    var name by remember(info?.id, info?.name) { mutableStateOf(info?.name ?: "") }
    var confirm by remember { mutableStateOf(false) }
    val valid = name.length in 1..20 && name == name.trim() &&
        name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " -_" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tu placa", style = MaterialTheme.typography.titleMedium)
            if (info == null) {
                Text("Conecta tu placa y comprueba su identidad. El cambio de nombre necesita firmware v18 o posterior.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onRefresh, enabled = !busy && !navigating) { Text("Leer identidad") }
            } else {
                Text(info.name, style = MaterialTheme.typography.titleLarge)
                Text("ID ${info.id}\n${info.address}", style = MaterialTheme.typography.bodySmall)
                Text("Este movil recuerda esta direccion. Si tu placa no esta disponible, no elige otra por su nombre.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onIdentify, enabled = !busy && !navigating) { Text("Identificar en la pantalla") }
                OutlinedTextField(value = name, onValueChange = { if (it.length <= 20) name = it },
                    label = { Text("Nombre de la placa") }, singleLine = true,
                    supportingText = { Text("1-20 letras sin acentos, numeros, espacios, - o _.") },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy && !navigating)
                Button(onClick = { confirm = true }, enabled = valid && name != info.name && !busy && !navigating) {
                    Text("Guardar en esta placa")
                }
                Text("El nombre no es un bloqueo de propietario. Otra persona puede seleccionarla si esta libre.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (navigating) Text("Para la navegacion y espera 5 s antes de identificar o cambiar el nombre.")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (confirm && info != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Cambiar nombre") },
        text = { Text("Guardar \"$name\" en la placa ${info.id}? Usa Identificar antes si tienes varias juntas. El nombre del anuncio se actualizara al desconectar.") },
        confirmButton = { TextButton(onClick = { confirm = false; onRename(name) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } }
    )
}
