package com.example.displayconnect.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayconnect.protocol.NavMessage
import com.example.displayconnect.routing.TripProgress
import com.example.displayconnect.utils.TransmissionHub
import com.example.displayconnect.ui.theme.Alert
import com.example.displayconnect.ui.theme.Blue
import com.example.displayconnect.ui.theme.Outline
import com.example.displayconnect.ui.theme.Warn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Espejo de la placa: lo que muestra la pantalla redonda, pero en el movil.
 * Solo lee datos (NavMessage del ultimo frame + TripProgress); no envia nada.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NavMirror(
    nav: NavMessage?,
    trip: TripProgress,
    destLabel: String,
    onStop: () -> Unit,
    joinOptions: List<TransmissionHub.GpxJoinOption> = emptyList(),
    onSelectJoin: (Int) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Velocidad y limite
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val speed = nav?.speedKmh
            val limit = nav?.speedLimitKmh
            val over = speed != null && limit != null && speed > limit
            Column {
                Text(
                    text = speed?.toString() ?: "--",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (over) Alert else Color.White
                )
                Text("km/h", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            if (limit != null) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(5.dp, Alert, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(limit.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }

        // Circulo principal
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val d = if (maxWidth < maxHeight) maxWidth else maxHeight
            val diameter = if (d > 360.dp) 360.dp else d
            Box(
                modifier = Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(Color(0xFF05080C))
                    .border(3.dp, Outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (nav == null) {
                    Text(
                        "Calculando ruta…",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        ManeuverArrow(
                            code = nav.maneuver,
                            modifier = Modifier.size(diameter * 0.46f)
                        )
                        if (nav.maneuver == "roundabout" && nav.maneuverExit != null) {
                            Text(
                                "${nav.maneuverExit}ª salida",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatDistance(nav.distanceM),
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Calle / aviso
        val warning = when {
            nav?.offRoute == true || trip.offRoute -> nav?.instruction?.takeIf { it.isNotBlank() } ?: "Fuera de ruta"
            nav?.gpsWeak == true -> "GPS débil"
            else -> null
        }
        Text(
            text = warning ?: nav?.street?.takeIf { it.isNotBlank() } ?: destLabel,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (warning != null) Warn else Color.White,
            modifier = Modifier.fillMaxWidth()
        )

        // Ramas de enganche cuando la traza es ambigua: el usuario elige
        // expresamente por cual empezar (nunca se infiere por cercania).
        if (joinOptions.isNotEmpty()) {
            // Tope de altura con scroll: con 4+ ramas o pantalla pequeña el
            // circulo y el boton de parar siguen visibles.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 170.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (joinOptions.any { it.autoRemainingMs != null }) "Reincorporación automática" else "Elige dónde incorporarte:",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                joinOptions.firstOrNull()?.accessStatus?.let {
                    Text(it, fontSize = 12.sp, color = Warn)
                }
                joinOptions.forEach { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .drawBehind {
                                if (opt.autoRemainingMs != null) drawRect(
                                    color = Color(0xFF245DA8),
                                    size = Size(size.width * opt.autoFraction.coerceIn(0f, 1f), size.height))
                            }
                            .border(1.dp, Outline, RoundedCornerShape(14.dp))
                            .clickable { onSelectJoin(opt.index) }
                            .padding(vertical = 10.dp, horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (opt.isRouteStart) "Empezar desde el inicio"
                                else "Unirme en el km ${"%.1f".format(opt.progressM / 1000)}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                opt.roadDistanceM?.let { "${it.toInt()} m por carretera" }
                                    ?: "${opt.distanceM.toInt()} m en línea recta",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                            opt.autoRemainingMs?.let {
                                Text(if (it > 0) "Auto en ${(it + 999) / 1000} s" else "Verificando acceso...", fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // Barra inferior: km, minutos, llegada
        val remM = trip.remainingDistanceM ?: nav?.remainingDistanceM
        val remS = trip.remainingDurationS ?: nav?.remainingDurationS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(vertical = 14.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Stat(remM?.let { formatDistance(it) } ?: "—", "restan")
            Stat(remS?.let { formatMinutes(it) } ?: "—", "tiempo")
            Stat(remS?.let { formatArrival(it) } ?: "—", "llegada")
        }

        // Parar (pulsacion larga)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Alert)
                .combinedClickable(onClick = {}, onLongClick = onStop),
            contentAlignment = Alignment.Center
        ) {
            Text("Mantén pulsado para parar", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Blue)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatDistance(m: Int): String =
    if (m < 1000) "$m m" else String.format(Locale.getDefault(), "%.1f km", m / 1000.0)

private fun formatMinutes(s: Int): String {
    val min = (s + 59) / 60
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

private fun formatArrival(s: Int): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + s * 1000L))

// ---------- Flecha ----------

@Composable
private fun ManeuverArrow(code: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.drawBehind { drawManeuver(code) })
}

private fun DrawScope.drawManeuver(code: String) {
    val w = size.width
    val stroke = Stroke(width = w * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val white = Color.White

    fun p(x: Float, y: Float) = Offset(w * x, w * y)

    fun head(tip: Offset, angleDeg: Float) {
        // angulo 0 = hacia arriba, positivo = hacia la derecha
        val a = angleDeg * PI.toFloat() / 180f
        val len = w * 0.26f
        fun dir(extra: Float): Offset {
            val b = a + extra
            return Offset(sin(b), -cos(b))
        }
        // dos alas hacia atras
        val back1 = dir(PI.toFloat() - 0.7f)
        val back2 = dir(PI.toFloat() + 0.7f)
        val path = Path().apply {
            moveTo(tip.x + back1.x * len, tip.y + back1.y * len)
            lineTo(tip.x, tip.y)
            lineTo(tip.x + back2.x * len, tip.y + back2.y * len)
        }
        drawPath(path, white, style = stroke)
    }

    fun bent(angleDeg: Float) {
        // tallo recto desde abajo hasta el centro y tramo final con el angulo del giro
        val start = p(0.5f, 0.92f)
        val knee = p(0.5f, 0.56f)
        val a = angleDeg * PI.toFloat() / 180f
        val l = w * 0.46f
        val end = Offset(knee.x + sin(a) * l, knee.y - cos(a) * l)
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(knee.x, knee.y)
            lineTo(end.x, end.y)
        }
        drawPath(path, white, style = stroke)
        head(end, angleDeg)
    }

    when (code) {
        "straight" -> {
            drawLine(white, p(0.5f, 0.92f), p(0.5f, 0.12f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            head(p(0.5f, 0.1f), 0f)
        }
        "right" -> bent(90f)
        "left" -> bent(-90f)
        "slight_right" -> bent(45f)
        "slight_left" -> bent(-45f)
        "sharp_right" -> bent(135f)
        "sharp_left" -> bent(-135f)
        "uturn" -> {
            val path = Path().apply {
                moveTo(w * 0.68f, w * 0.92f)
                lineTo(w * 0.68f, w * 0.38f)
                arcTo(
                    androidx.compose.ui.geometry.Rect(w * 0.26f, w * 0.1f, w * 0.68f, w * 0.52f),
                    0f, -180f, false
                )
                lineTo(w * 0.26f, w * 0.62f)
            }
            drawPath(path, white, style = stroke)
            head(p(0.26f, 0.66f), 180f)
        }
        "roundabout" -> {
            drawCircle(white, radius = w * 0.2f, center = p(0.5f, 0.6f), style = stroke)
            drawLine(white, p(0.5f, 0.95f), p(0.5f, 0.8f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(white, p(0.5f, 0.4f), p(0.5f, 0.12f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            head(p(0.5f, 0.1f), 0f)
        }
        "arrive" -> {
            drawCircle(white, radius = w * 0.34f, center = p(0.5f, 0.5f), style = stroke)
            drawCircle(Blue, radius = w * 0.12f, center = p(0.5f, 0.5f))
        }
        else -> {
            // sin dato: flecha recta tenue
            drawLine(Color(0xFF55606E), p(0.5f, 0.92f), p(0.5f, 0.12f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            head(p(0.5f, 0.1f), 0f)
        }
    }
}
