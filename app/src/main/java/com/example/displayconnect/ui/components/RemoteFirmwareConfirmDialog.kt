package com.example.displayconnect.ui.components
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@Composable
fun RemoteFirmwareConfirmDialog(version:String,size:Int,boardName:String,diagnostic:Boolean,installed:String,onCancel:()->Unit,onConfirm:()->Unit){
 AlertDialog(onDismissRequest=onCancel,title={Text(if(diagnostic)"Instalar prueba Hecate" else "Aceptar actualizacion")},
 text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("Destino: $boardName\nInstalada: $installed\nOfrecida: $version\nTamano: $size bytes")
  if(diagnostic)Text("SOLO DIAGNOSTICO. No sirve para navegar. Prueba de posicion acotada: equipo en parado, una sola sesion de hasta tres minutos y exportar el registro al terminar. No usar en carretera. No repetir ni apagar por rutina.")
  else Text("Canal de uso. La version ofrecida puede ser distinta, no necesariamente mas reciente. Compruebala antes de continuar.")
  Text("La descarga esta comprobada, pero todavia no se ha enviado a la placa. Al aceptar se escribira la particion inactiva y la placa se reiniciara. No la apagues. Reconecta y comprueba la version despues; la transferencia no confirma el arranque.")
 }},confirmButton={TextButton(onClick=onConfirm){Text(if(diagnostic)"Aceptar y enviar prueba" else "Aceptar y enviar")}},dismissButton={TextButton(onClick=onCancel){Text("Cancelar")}})
}
