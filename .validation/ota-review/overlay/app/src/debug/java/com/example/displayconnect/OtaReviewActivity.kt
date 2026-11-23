package com.example.displayconnect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.displayconnect.ui.components.FirmwareUpdateCard

/** Render only. No VM, Bluetooth permission, connection, firmware or transfer. */
class OtaReviewActivity:ComponentActivity(){
 var state by mutableStateOf(0)
 var clicks=0
 override fun onCreate(b:Bundle?){super.onCreate(b);WindowCompat.setDecorFitsSystemWindows(window,true)
 setContent{MaterialTheme{Surface{Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)){
 Text("REVISION VISUAL: ESTADO INYECTADO $state",style=MaterialTheme.typography.titleMedium)
 Text("Componente real de Ajustes. No es una transferencia ni una conexion real.")
 val messages=listOf("Preparado. Falta aceptar el envio; no se ha escrito en la placa.","Actualizando la placa... no la apagues", "Transferido. Esperando reconexion y comprobando arranque; no repitas el envio.","Arranque observado. Comprobando estabilidad, espera unos dos minutos.","Transferencia o arranque sin confirmar: version o slot inesperado. No repitas; guarda el registro OTA.","Transferencia y arranque estable observados. Guarda el registro OTA para revisar pantalla apagada.")
 FirmwareUpdateCard(connected=true,canUpdate=true,busy=state in 1..3,progress=if(state==1)37 else -1,message=messages[state],needsUsb=false,allowLocalFile=true,allowTrial=true,onExport={clicks++},exportMessage=if(state==5)"Registro OTA guardado y verificado. No se ha enviado a nadie." else "",onUpdate={})
 }}}}
 }
}
