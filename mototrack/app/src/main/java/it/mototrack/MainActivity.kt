package it.mototrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.roundToLong
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("map", MODE_PRIVATE))
            userAgentValue = "it.mototrack/0.4 Android"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MotoTrack() {
    val context = LocalContext.current
    val state by TrackingService.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var rides by remember { mutableStateOf(emptyList<Ride>()) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = rides.firstOrNull { it.id == selectedId }
    var loadingRides by remember { mutableStateOf(true) }
    var archiveError by remember { mutableStateOf<String?>(null) }
    var refreshArchive by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(tab, state.mode, refreshArchive) {
        if (tab == 1) {
            loadingRides = true; archiveError = null
            val result = withContext(Dispatchers.IO) { runCatching { RideStore(context).list() } }
            result.onSuccess { rides = it }.onFailure { archiveError = "Impossibile leggere i giri" }
            loadingRides = false
        }
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var showCalibration by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.mode) { if (state.mode == Mode.IDLE) showCalibration = false }
    BackHandler(enabled = drawer.isOpen || selected != null || tab != 0) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            selected != null -> selectedId = null
            else -> tab = 0
        }
    }
    fun navigate(destination: Int) {
        tab = destination; selectedId = null
        scope.launch { drawer.close() }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet {
            Text("MotoTrack", Modifier.padding(28.dp), style = MaterialTheme.typography.headlineSmall)
            NavigationDrawerItem(label = { Text("Registra") }, selected = tab == 0,
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                onClick = { navigate(0) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            NavigationDrawerItem(label = { Text("Giri") }, selected = tab == 1,
                icon = { Icon(Icons.Default.List, contentDescription = null) },
                onClick = { navigate(1) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
        }
    }) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(if (selected != null) "Dettaglio giro" else if (tab == 0) "Registra" else "Giri") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selected != null) selectedId = null else scope.launch { drawer.open() }
                        }) {
                            Icon(if (selected != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                                contentDescription = if (selected != null) "Torna ai giri" else "Apri menu di navigazione")
                        }
                    },
                    actions = {
                        if (tab == 1 && state.mode != Mode.IDLE) TextButton(onClick = { navigate(0) }) {
                            Text(if (state.mode == Mode.RECORDING) "In registrazione" else "In pausa")
                        }
                    })
            },
            bottomBar = {
                if (tab == 0) Surface(tonalElevation = 3.dp) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.widthIn(max = 840.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state.mode == Mode.IDLE) Button(modifier = Modifier.weight(1f).heightIn(min = 56.dp), onClick = {
                                startAfterPermission = true
                                val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
                                permissions.launch(required.toTypedArray())
                            }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Avvia giro") }
                            else {
                                val saving = state.message == "Salvataggio…"
                                Button(enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 56.dp), onClick = {
                                    command(if (state.mode == Mode.RECORDING) TrackingService.PAUSE else TrackingService.RESUME)
                                }) { Text(if (state.mode == Mode.RECORDING) "Pausa" else "Riprendi") }
                                FilledTonalButton(enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                    onClick = { command(TrackingService.STOP) }) { Text(if (saving) "Salvataggio…" else "Stop e salva") }
                            }
                        }
                        if (state.mode != Mode.IDLE) TextButton(enabled = state.message != "Salvataggio…", onClick = { showCalibration = true }) {
                            Text(if (state.calibrated) "Sensori calibrati · Ricalibra" else "Calibra sensori")
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                if (tab == 0) {
                    Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                        val status = notice ?: when {
                            state.mode == Mode.PAUSED -> state.message
                            state.mode == Mode.RECORDING -> state.message
                            preview != null -> "GPS pronto"
                            else -> "Ricerca posizione…"
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = if (notice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!locationGranted) TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                            }) { Text("Permessi") }
                        }
                        TrackMap(state.ride?.points.orEmpty(), true, Modifier.weight(1f).fillMaxWidth(),
                            current = preview?.let { MapPosition(it.latitude, it.longitude) })
                        val fresh = state.mode == Mode.RECORDING && state.lastFixElapsed > 0 && SystemClock.elapsedRealtime() - state.lastFixElapsed < 5000
                        val p = state.ride?.points?.lastOrNull().takeIf { fresh }
                        ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                TelemetryRow(p?.speed, state.lean,
                                    state.accelerationG.takeIf { state.mode == Mode.RECORDING && (state.accelerationSource == "sensor" || fresh) },
                                    state.accelerationSource)
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(duration(state.ride?.activeMillis ?: 0), style = MaterialTheme.typography.titleMedium)
                                    Text(number((state.ride?.distanceMeters ?: 0.0) / 1000, "km"), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                } else {
                    val detail = selected
                    if (detail != null) {
                        var second by rememberSaveable(detail.id) { mutableLongStateOf(0L) }
                        val lastSecond = remember(detail) { ((detail.timelineEnd() - detail.id + 999) / 1000).coerceAtLeast(0) }
                        val playback = remember(detail, second) { detail.playback(second) }
                        LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { Text(date(detail.id), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium) }
                            item { TrackMap(detail.points, false, Modifier.fillMaxWidth().height(280.dp),
                                selected = playback.point?.let { MapPosition(it.lat, it.lon) }) }
                            item {
                                ElevatedCard(Modifier.padding(horizontal = 16.dp)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Dati del percorso", style = MaterialTheme.typography.titleMedium)
                                        Text("${duration(second * 1000)} / ${duration(lastSecond * 1000)}", style = MaterialTheme.typography.bodyMedium)
                                        Slider(value = second.toFloat(), onValueChange = { second = it.roundToLong().coerceIn(0, lastSecond) },
                                            modifier = Modifier.semantics { contentDescription = "Istante del giro in secondi" },
                                            valueRange = 0f..lastSecond.coerceAtLeast(1).toFloat(), enabled = lastSecond > 0)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            TextButton(onClick = { second = (second - 1).coerceAtLeast(0) }, enabled = second > 0,
                                                modifier = Modifier.semantics { contentDescription = "Indietro di un secondo" }) { Text("−1 s") }
                                            TextButton(onClick = { second = (second + 1).coerceAtMost(lastSecond) }, enabled = second < lastSecond,
                                                modifier = Modifier.semantics { contentDescription = "Avanti di un secondo" }) { Text("+1 s") }
                                        }
                                        TelemetryRow(playback.point?.speed, playback.point?.lean, playback.accelerationG, playback.source)
                                        if (playback.point == null && playback.accelerationG == null)
                                            Text("Nessun dato in questo istante", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            item {
                                OutlinedCard(Modifier.padding(horizontal = 16.dp)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("Riepilogo", style = MaterialTheme.typography.titleMedium)
                                        if (detail.ended == null) Text("Registrazione interrotta", color = MaterialTheme.colorScheme.error)
                                        SummaryRow("Distanza", number(detail.distanceMeters / 1000, "km"))
                                        SummaryRow("Tempo attivo", duration(detail.activeMillis))
                                        SummaryRow("Velocità massima", number(detail.maxSpeed?.times(3.6), "km/h"))
                                        SummaryRow("Velocità media", number(if (detail.activeMillis > 0) detail.distanceMeters / detail.activeMillis * 3600 else null, "km/h"))
                                        SummaryRow("Piega massima", number(detail.maxLean, "°"))
                                        SummaryRow("Accelerazione", number(detail.maxAcceleration?.div(STANDARD_GRAVITY), "G"))
                                        SummaryRow("Frenata", number(detail.maxBraking?.div(STANDARD_GRAVITY), "G"))
                                    }
                                }
                            }
                            item {
                                Button(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 56.dp), onClick = {
                                    export = detail.gpx(); save.launch("MotoTrack-${detail.id}.gpx")
                                }) { Text("Esporta GPX") }
                            }
                        }
                    } else {
                        val visibleRides = rides.filter { it.id != state.ride?.id }
                        if (loadingRides) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else if (archiveError != null) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(archiveError!!)
                            TextButton(onClick = { refreshArchive++ }) { Text("Riprova") }
                        }
                        else if (visibleRides.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.List, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Nessun giro salvato", style = MaterialTheme.typography.titleLarge)
                            Button(onClick = { navigate(0) }) { Text("Vai a Registra") }
                        } else LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(visibleRides, key = { it.id }) { r ->
                                Card(onClick = { selectedId = r.id }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(date(r.id), style = MaterialTheme.typography.titleMedium)
                                        Text("${number(r.distanceMeters / 1000, "km")}  ·  ${duration(r.activeMillis)}",
                                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (r.ended == null) Text("Registrazione interrotta", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showCalibration && state.mode != Mode.IDLE) AlertDialog(
        onDismissRequest = { showCalibration = false },
        title = { Text(if (state.calibrated) "Sensori calibrati" else if (state.awaitingRightLean) "2 di 2 · Inclina a destra" else "1 di 2 · Moto verticale") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.calibrationHint)
            if (!state.calibrated && !state.awaitingRightLean) Text("Moto ferma, manubrio dritto. Mantieni la posizione per un secondo.")
            if (state.awaitingRightLean) TextButton(onClick = { command(TrackingService.ZERO) }) { Text("Ripeti zero a moto verticale") }
        } },
        confirmButton = { TextButton(onClick = {
            command(if (state.awaitingRightLean) TrackingService.CONFIRM_RIGHT else TrackingService.ZERO)
        }) { Text(if (state.calibrated) "Ricalibra" else if (state.awaitingRightLean) "Conferma" else "Imposta zero") } },
        dismissButton = { TextButton(onClick = { showCalibration = false }) { Text("Chiudi") } })
}

@Composable
private fun TelemetryRow(speed: Double?, lean: Double?, acceleration: Double?, source: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric("Velocità", number(speed?.times(3.6), "km/h"), Modifier.weight(1f))
        Metric("Piega ≈", number(lean, "°"), Modifier.weight(1f))
        Metric(if (source == "gps") "Accel. GPS ≈" else "Accel. sensore", number(acceleration, "G"), Modifier.weight(1f))
    }
}
@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
