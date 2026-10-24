package it.mototrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlin.math.roundToLong
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("map", MODE_PRIVATE))
            userAgentValue = "it.mototrack/0.2 Android"
            osmdroidBasePath = java.io.File(filesDir, "osmdroid").apply { mkdirs() }
            osmdroidTileCache = java.io.File(cacheDir, "map-tiles").apply { mkdirs() }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF80E4B0), background = Color(0xFF101815), surface = Color(0xFF19251F))) {
                Surface(Modifier.fillMaxSize()) { MotoTrack() }
            }
        }
    }
}
internal fun number(v: Double?, suffix: String) = v?.let { String.format(Locale.ITALY, if (suffix == "G") "%.2f %s" else "%.1f %s", it, suffix) } ?: "—"
internal fun date(id: Long) = DateTimeFormatter.ofPattern("dd MMM yyyy • HH:mm", Locale.ITALY).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(id))
internal fun duration(ms: Long): String = "%02d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

@Composable
private fun MotoTrack() {
    val context = LocalContext.current
    val state by TrackingService.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var rides by remember { mutableStateOf(emptyList<Ride>()) }
    var selected by remember { mutableStateOf<Ride?>(null) }
    var export by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var locationGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var startAfterPermission by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val preview = rememberPreviewLocation(locationGranted, tab == 0)
    fun command(action: String) {
        val intent = Intent(context, TrackingService::class.java).setAction(action)
        if (action == TrackingService.START) ContextCompat.startForegroundService(context, intent)
        else context.startService(intent)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val content = export
        if (uri != null && content != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val stream = context.contentResolver.openOutputStream(uri) ?: error("File non disponibile")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            } }
            Toast.makeText(context, if (result.isSuccess) "GPX esportato" else "Esportazione fallita", Toast.LENGTH_LONG).show()
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationGranted = true; notice = null
            if (startAfterPermission) command(TrackingService.START)
        } else { locationGranted = false; notice = "Serve la posizione precisa. Abilitala nelle impostazioni dei permessi dell’app." }
        startAfterPermission = false
    }
    LaunchedEffect(Unit) {
        if (!locationGranted) permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(tab, state.mode) {
        if (tab == 1) {
            val result = withContext(Dispatchers.IO) { runCatching { RideStore(context).list() } }
            result.onSuccess { rides = it }.onFailure { notice = "Impossibile leggere l’archivio" }
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("MOTOTRACK", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Text("Ogni curva, il tuo viaggio", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { tab = 1 - tab; selected = null }) { Text(if (tab == 0) "Giri →" else "← Registra") }
        }
        if (tab == 0) {
            TrackMap(state.ride?.points.orEmpty(), true, Modifier.weight(1f).fillMaxWidth(),
                current = preview?.let { MapPosition(it.latitude, it.longitude) })
            Column(Modifier.fillMaxWidth().heightIn(max = 410.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("● 0   🟡 60   🔴 120+ km/h · grigio: dato assente", style = MaterialTheme.typography.labelSmall)
                Text(notice ?: if (state.mode == Mode.IDLE) {
                    if (preview == null) "Attendo la posizione: verifica che il GPS sia attivo" else "Posizione disponibile • pronto per partire"
                } else state.message, color = MaterialTheme.colorScheme.primary)
                if (!locationGranted) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                }) { Text("Abilita posizione nelle impostazioni") }
                val fresh = state.mode == Mode.RECORDING && state.lastFixElapsed > 0 && SystemClock.elapsedRealtime() - state.lastFixElapsed < 5000
                val p = state.ride?.points?.lastOrNull().takeIf { fresh }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("VELOCITÀ", number(p?.speed?.times(3.6), "km/h"))
                    Metric("PIEGA ≈", number(state.lean, "°"))
                    Metric(if (state.accelerationSource == "sensor") "ACCEL. SENSORE" else "ACCEL. GPS ≈", number(state.accelerationG.takeIf { state.mode == Mode.RECORDING && (state.accelerationSource == "sensor" || fresh) }, "G"))
                }
                Text("${duration(state.ride?.activeMillis ?: 0)}  •  ${number((state.ride?.distanceMeters ?: 0.0) / 1000, "km")}")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.mode == Mode.IDLE) Button(modifier = Modifier.weight(1f), onClick = {
                        startAfterPermission = true
                        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
                        permissions.launch(required.toTypedArray())
                    }) { Text("Avvia giro") }
                    else {
                        val saving = state.message == "Salvataggio…"
                        Button(enabled = !saving, modifier = Modifier.weight(1f), onClick = {
                            command(if (state.mode == Mode.RECORDING) TrackingService.PAUSE else TrackingService.RESUME)
                        }) { Text(if (state.mode == Mode.RECORDING) "Pausa" else "Riprendi") }
                        FilledTonalButton(enabled = !saving, modifier = Modifier.weight(1f), onClick = { command(TrackingService.STOP) }) { Text("Stop e salva") }
                    }
                }
                OutlinedButton(enabled = state.mode != Mode.IDLE, modifier = Modifier.fillMaxWidth(), onClick = { command(if (state.awaitingRightLean) TrackingService.CONFIRM_RIGHT else TrackingService.ZERO) }) {
                    Text(if (state.awaitingRightLean) "2/2 Conferma inclinazione a destra" else if (state.calibrated) "Ricalibra montaggio" else "1/2 Moto verticale: imposta zero")
                }
                if (state.awaitingRightLean) TextButton(onClick = { command(TrackingService.ZERO) }) { Text("Ripeti zero a moto verticale") }
                if (state.mode != Mode.IDLE) Text(state.calibrationHint, style = MaterialTheme.typography.bodySmall)
                Text(if (state.mode == Mode.IDLE) "Avvia e calibra per misurare l’accelerazione in G." else if (!state.sensorAvailable) "Sensore lineare non disponibile: stima GPS in G." else if (!state.calibrated) "Calibra a moto ferma per attivare i G del sensore." else "G dal sensore; stima GPS se il dato manca.", style = MaterialTheme.typography.labelSmall)
                Text("Telefono fissato in qualsiasi orientamento. Calibra da fermo, manubrio dritto, in un luogo sicuro e con aiuto se necessario. Piega stimata; +G accelerazione, −G frenata.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val detail = selected
            if (detail != null) {
                TextButton(onClick = { selected = null }) { Text("← Tutti i giri") }
                Text(date(detail.id), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                var second by remember(detail.id) { mutableLongStateOf(0L) }
                val lastSecond = remember(detail) { ((detail.timelineEnd() - detail.id + 999) / 1000).coerceAtLeast(0) }
                val playback = remember(detail, second) { detail.playback(second) }
                TrackMap(detail.points, false, Modifier.weight(1f).fillMaxWidth(),
                    selected = playback.point?.let { MapPosition(it.lat, it.lon) })
                Column(Modifier.fillMaxWidth().heightIn(max = 370.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Esplora il giro • ${duration(second * 1000)} / ${duration(lastSecond * 1000)}")
                    Slider(value = second.toFloat(), onValueChange = { second = it.roundToLong().coerceIn(0, lastSecond) },
                        valueRange = 0f..lastSecond.coerceAtLeast(1).toFloat(), enabled = lastSecond > 0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { second = (second - 1).coerceAtLeast(0) }, enabled = second > 0) { Text("−1 s") }
                        TextButton(onClick = { second = (second + 1).coerceAtMost(lastSecond) }, enabled = second < lastSecond) { Text("+1 s") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("VELOCITÀ", number(playback.point?.speed?.times(3.6), "km/h"))
                        Metric("PIEGA ≈", number(playback.point?.lean, "°"))
                        Metric("ACCEL.", number(playback.accelerationG, "G"))
                    }
                    Text(if (playback.point == null && playback.accelerationG == null) "Pausa o dati non disponibili in questo istante"
                        else "Campione più vicino • G: ${if (playback.source == "sensor") "sensore" else "stima GPS"}", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(if (detail.ended == null) "Traccia recuperata da una registrazione interrotta" else "Giro completato")
                    Text("${number(detail.distanceMeters / 1000, "km")} • ${duration(detail.activeMillis)} attivi")
                    Text("Velocità massima: ${number(detail.maxSpeed?.times(3.6), "km/h")}")
                    Text("Media sul tempo attivo: ${number(if (detail.activeMillis > 0) detail.distanceMeters / detail.activeMillis * 3600 else null, "km/h")}")
                    Text("Piega massima stimata: ${number(detail.maxLean, "°")}")
                    Text("Accelerazione: ${number(detail.maxAcceleration?.div(STANDARD_GRAVITY), "G")} • Frenata: ${number(detail.maxBraking?.div(STANDARD_GRAVITY), "G")}")
                    Text(if (detail.inertial.isNotEmpty()) "Picchi dai sensori • ${detail.inertial.size} campioni inerziali" else "Picchi stimati da GPS", style = MaterialTheme.typography.bodySmall)
                    Text("${detail.points.size} campioni • ${detail.points.map { it.segment }.distinct().size} segmenti", style = MaterialTheme.typography.bodySmall)
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        export = detail.gpx(); save.launch("MotoTrack-${detail.id}.gpx")
                    }) { Text("Scarica GPX con telemetria") }
                    Text("Sposta lo slider per esplorare i dati secondo per secondo. Colori: verde 0, giallo 60, rosso ≥120 km/h.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("I tuoi giri", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium)
                if (rides.isEmpty()) Text("Nessun giro salvato. Avvia una registrazione e premi Stop.", Modifier.padding(16.dp))
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rides.filter { it.id != state.ride?.id }, key = { it.id }) { r ->
                        Card(onClick = { selected = r }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(date(r.id), style = MaterialTheme.typography.titleMedium)
                                Text("${number(r.distanceMeters / 1000, "km")}  •  ${duration(r.activeMillis)}")
                                Text(if (r.ended == null) "Registrazione interrotta • Apri →" else "Apri percorso e dettagli →", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
