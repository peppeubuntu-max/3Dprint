package it.mototrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun CalibrationDialog(state: TrackingState, onConfirm: () -> Unit, onRestart: () -> Unit, onClose: () -> Unit) {
    val step = state.calibrationStep
    val done = step == CalibrationStep.DONE && !state.calibrationActive
    val degrees = if (done) state.lean else state.calibrationDegrees
    val title = when (step) {
        CalibrationStep.UPRIGHT -> "1 · Tieni la moto verticale"
        CalibrationStep.RIGHT -> "2 · Inclina verso destra"
        CalibrationStep.RETURN -> "3 · Torna allo zero"
        CalibrationStep.DONE -> "Calibrazione completata"
    }
    val instruction = when (step) {
        CalibrationStep.UPRIGHT -> "Da fermo, manubrio dritto. Questa posizione diventerà lo zero della moto."
        CalibrationStep.RIGHT -> "Inclina solo verso destra, tra 5° e 30°. Mantieni la moto ferma, aiutandoti se necessario."
        CalibrationStep.RETURN -> "Riporta la moto verticale. Conferma quando leggi un valore vicino a 0°."
        CalibrationStep.DONE -> "Piega e accelerazione sono applicate al giro. Muovendo la moto, i gradi qui sotto si aggiornano."
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Zero", "Destra", "Verifica").forEachIndexed { index, label ->
                        Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.small,
                            color = if (index <= step.ordinal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Text("${index + 1} $label", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(12.dp))
                    val displayAngle = if (step == CalibrationStep.UPRIGHT) 0f else (degrees ?: 0.0).toFloat().coerceIn(-40f, 40f)
                    MotorcycleLevel(displayAngle, step == CalibrationStep.RIGHT)
                    Text(if (step == CalibrationStep.UPRIGHT) "Assetto del telefono · prima dello zero" else "Inclinazione rispetto allo zero",
                        style = MaterialTheme.typography.labelMedium)
                    Text(number(degrees, "°"), fontSize = 42.sp, color = MaterialTheme.colorScheme.primary)
                    if (step == CalibrationStep.UPRIGHT) Text("Il valore iniziale dipende dal montaggio.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(instruction, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    if (!done) {
                        LinearProgressIndicator(progress = { state.calibrationProgress }, modifier = Modifier.fillMaxWidth()
                            .semantics { contentDescription = "Stabilità della posizione" })
                        Spacer(Modifier.height(8.dp))
                        Text(state.calibrationHint, style = MaterialTheme.typography.bodySmall)
                        Text("Il giro è in pausa durante la calibrazione.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Zero impostato: 0,0°", style = MaterialTheme.typography.titleMedium)
                        Text(if (state.mode == Mode.RECORDING) "Registrazione ripresa" else "Giro in pausa", color = MaterialTheme.colorScheme.primary)
                        Text("Accelerazione: ${number(state.accelerationG, "G")}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = if (done) onClose else onConfirm, enabled = done || state.calibrationCanConfirm,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(when (step) {
                        CalibrationStep.UPRIGHT -> "Imposta questa posizione a 0°"
                        CalibrationStep.RIGHT -> "Conferma inclinazione"
                        CalibrationStep.RETURN -> "Verifica e applica lo zero"
                        CalibrationStep.DONE -> "Torna al giro"
                    })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (step != CalibrationStep.UPRIGHT) TextButton(onClick = onRestart) { Text("Ricomincia") }
                    if (!done) TextButton(onClick = onClose) { Text("Annulla") }
                }
            }
        }
    }
}

@Composable
private fun MotorcycleLevel(degrees: Float, rightStep: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(130.dp).semantics { contentDescription = "Moto vista da dietro, inclinazione ${degrees.toInt()} gradi" }) {
            val ground = size.height * 0.86f
            val pivot = Offset(size.width / 2, ground)
            drawLine(muted, Offset(size.width * 0.15f, ground), Offset(size.width * 0.85f, ground), 2.dp.toPx())
            drawLine(muted.copy(alpha = 0.4f), pivot, Offset(pivot.x, size.height * 0.08f), 1.dp.toPx())
            rotate(degrees, pivot) {
                drawLine(accent, pivot.copy(y = ground - 3.dp.toPx()), pivot.copy(y = ground - 27.dp.toPx()), 13.dp.toPx(), StrokeCap.Round)
                drawLine(accent, pivot.copy(y = ground - 25.dp.toPx()), pivot.copy(y = ground - 75.dp.toPx()), 23.dp.toPx(), StrokeCap.Round)
                drawLine(accent, Offset(pivot.x - 30.dp.toPx(), ground - 75.dp.toPx()), Offset(pivot.x + 30.dp.toPx(), ground - 75.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
                drawCircle(Color(0xFFEA4335), 4.dp.toPx(), Offset(pivot.x, ground - 49.dp.toPx()))
            }
        }
        Text(if (rightStep) "Vista da dietro · Destra →" else "Vista da dietro", style = MaterialTheme.typography.labelSmall)
    }
}
