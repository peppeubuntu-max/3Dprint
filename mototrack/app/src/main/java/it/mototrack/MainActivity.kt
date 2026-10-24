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
        Configuration.getInstance().userAgentValue = "it.mototrack/0.1 Android"
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF80E4B0), background = Color(0xFF101815), surface = Color(0xFF19251F))) {
                Surface(Modifier.fillMaxSize()) { MotoTrack() }
            }
        }
    }
}
private fun number(v: Double?, suffix: String) = v?.let { String.format(Locale.ITALY, if (suffix == "G") "%.2f %s" else "%.1f %s", it, suffix) } ?: "—"
private fun date(id: Long) = DateTimeFormatter.ofPattern("dd MMM yyyy • HH:mm", Locale.ITALY).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(id))
private fun duration(ms: Long): String = "%02d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

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
            notice = null; command(TrackingService.START)
        } else notice = "Serve la posizione precisa. Abilitala nelle impostazioni dei permessi dell’app."
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
            TrackMap(state.ride?.points.orEmpty(), true, Modifier.weight(1f).fillMaxWidth())
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("● 0   🟡 60   🔴 120+ km/h · grigio: dato assente", style = MaterialTheme.typography.labelSmall)
                Text(notice ?: state.message, color = MaterialTheme.colorScheme.primary)
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
                OutlinedButton(enabled = state.mode != Mode.IDLE, modifier = Modifier.fillMaxWidth(), onClick = { command(TrackingService.ZERO) }) {
                    Text(if (state.calibrated) "Ricalibra piega e asse G" else "Imposta zero piega e asse G")
                }
                Text(if (state.mode == Mode.IDLE) "Avvia e calibra per misurare l’accelerazione in G." else if (!state.sensorAvailable) "Sensore lineare non disponibile: stima GPS in G." else if (!state.calibrated) "Calibra a moto ferma per attivare i G del sensore." else "G dal sensore; stima GPS se il dato manca.", style = MaterialTheme.typography.labelSmall)
                Text("A moto ferma e verticale, azzera il telefono fissato al supporto. Piega stimata; +G accelerazione, −G frenata. Schermo verso il pilota, allineato alla moto.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val detail = selected
            if (detail != null) {
                TextButton(onClick = { selected = null }) { Text("← Tutti i giri") }
                Text(date(detail.id), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                TrackMap(detail.points, false, Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    Text("Tocca un punto sulla mappa per i dati tecnici. Colori: verde 0, giallo 60, rosso ≥120 km/h.", style = MaterialTheme.typography.bodySmall)
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
@Composable
private fun TrackMap(points: List<Sample>, follow: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val map = remember { MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
        controller.setZoom(6.0); controller.setCenter(GeoPoint(42.5, 12.5))
        minZoomLevel = 3.0; maxZoomLevel = 19.0
    } }
    DisposableEffect(map, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) map.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(observer); map.onResume()
        onDispose { owner.lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    LaunchedEffect(points, follow) {
        map.overlays.clear()
        points.zipWithNext().forEach { (a, b) ->
            if (a.segment == b.segment) map.overlays.add(Polyline(map).apply {
                setPoints(listOf(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)))
                outlinePaint.strokeWidth = 10f
                outlinePaint.color = b.speed?.let { android.graphics.Color.HSVToColor(floatArrayOf(speedHue(it), 0.85f, 0.9f)) } ?: android.graphics.Color.GRAY
                title = "${number(b.speed?.times(3.6), "km/h")} • piega ${number(b.lean, "°")} • ${number(b.acceleration?.div(STANDARD_GRAVITY), "G")}"
                snippet = "${if (b.accelerationSource == "sensor") "Sensore" else "Stima GPS"} • ${date(b.time)} • precisione ±${b.accuracy.toInt()} m"
            })
        }
        points.lastOrNull()?.let { p ->
            map.overlays.add(Marker(map).apply { position = GeoPoint(p.lat, p.lon); title = if (follow) "Ultima posizione GPS" else "Arrivo" })
            if (follow) { map.controller.setZoom(16.0); map.controller.animateTo(GeoPoint(p.lat, p.lon)) }
            else map.post {
                if (points.size > 1) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) }), false, 70)
                else { map.controller.setZoom(16.0); map.controller.setCenter(GeoPoint(p.lat, p.lon)) }
            }
        }
        map.invalidate()
    }
    Box(modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
        Text("© OpenStreetMap contributors", Modifier.background(Color(0xDD101815)).padding(4.dp), style = MaterialTheme.typography.labelSmall)
    }
}
