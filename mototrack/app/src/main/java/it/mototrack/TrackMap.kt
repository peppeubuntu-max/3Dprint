package it.mototrack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline as OsmPolyline

// Draw the same vector at device density for both map providers.
private fun mapPinBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = (32 * density).roundToInt().coerceAtLeast(1)
    val height = (40 * density).roundToInt().coerceAtLeast(1)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val pin = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_map_pin))
        pin.setBounds(0, 0, width, height)
        pin.draw(Canvas(bitmap))
    }
}

data class MapPosition(val lat: Double, val lon: Double)
private fun Sample.position() = MapPosition(lat, lon)
private fun MapPosition.latLng() = LatLng(lat, lon)
private fun MapPosition.geoPoint() = GeoPoint(lat, lon)
private fun pathColor(point: Sample, metric: TraceMetric) = traceHue(point, metric)?.let {
    android.graphics.Color.HSVToColor(floatArrayOf(it, 0.85f, 0.9f))
} ?: android.graphics.Color.GRAY

@Composable
fun TrackMap(points: List<Sample>, follow: Boolean, modifier: Modifier,
             current: MapPosition? = null, selected: MapPosition? = null, metric: TraceMetric = TraceMetric.SPEED) {
    var useGoogle by remember { mutableStateOf(BuildConfig.GOOGLE_MAPS_ENABLED) }
    Box(modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))) {
        if (useGoogle) GoogleTrackMap(points, follow, current, selected, metric)
        else OsmTrackMap(points, follow, current, selected, metric)
        if (BuildConfig.GOOGLE_MAPS_ENABLED) TextButton(
            onClick = { useGoogle = !useGoogle },
            modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
        ) { Text(if (useGoogle) "Mappa alternativa" else "Google Maps") }
    }
}

@Composable
private fun GoogleTrackMap(points: List<Sample>, follow: Boolean, current: MapPosition?, selected: MapPosition?, metric: TraceMetric) {
    val context = LocalContext.current
    val pinBitmap = remember(context) { mapPinBitmap(context) }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(42.5, 12.5), 5f)
    }
    var loaded by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    val pinIcon = remember(loaded, pinBitmap) { if (loaded) BitmapDescriptorFactory.fromBitmap(pinBitmap) else null }
    val target = if (follow) current ?: points.lastOrNull()?.position() else selected
    LaunchedEffect(loaded, target) {
        if (loaded && target != null) camera.animate(CameraUpdateFactory.newLatLngZoom(target.latLng(), 16f))
    }
    LaunchedEffect(loaded, points.isNotEmpty()) {
        if (loaded && !follow && target == null && points.isNotEmpty()) {
            if (points.map { it.position() }.distinct().size == 1) camera.move(CameraUpdateFactory.newLatLngZoom(points.first().position().latLng(), 16f))
            else {
                val bounds = LatLngBounds.builder().apply { points.forEach { include(it.position().latLng()) } }.build()
                camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 70))
            }
        }
    }
    LaunchedEffect(Unit) { delay(15000); slow = true }
    Box(Modifier.fillMaxSize()) {
        GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = camera,
            onMapLoaded = { loaded = true }, uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false)) {
            points.zipWithNext().forEach { (a, b) ->
                if (a.segment == b.segment) Polyline(points = listOf(a.position().latLng(), b.position().latLng()), color = Color(pathColor(b, metric)), width = 10f)
            }
            if (pinIcon != null) target?.let {
                Marker(state = remember(it) { MarkerState(position = it.latLng()) }, icon = pinIcon,
                    anchor = Offset(0.5f, 0.95f), title = if (follow) "Posizione attuale" else "Istante selezionato")
            }
        }
        if (!loaded && slow) Text("Mappa non caricata. Verifica la connessione o prova Mappa alternativa.",
            Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(12.dp))
    }
}

@Composable
private fun OsmTrackMap(points: List<Sample>, follow: Boolean, current: MapPosition?, selected: MapPosition?, metric: TraceMetric) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val pinBitmap = remember(context) { mapPinBitmap(context) }
    val map = remember { MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setUseDataConnection(true)
        setMultiTouchControls(true)
        controller.setZoom(6.0); controller.setCenter(GeoPoint(42.5, 12.5))
        minZoomLevel = 3.0; maxZoomLevel = 19.0
    } }
    DisposableEffect(map, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) map.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose { owner.lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    val trackLines = remember(points, metric) {
        points.zipWithNext().filter { (a, b) -> a.segment == b.segment }.map { (a, b) ->
            OsmPolyline(map).apply {
                setPoints(listOf(a.position().geoPoint(), b.position().geoPoint()))
                outlinePaint.strokeWidth = 10f; outlinePaint.color = pathColor(b, metric)
                title = "${number(b.speed?.times(3.6), "km/h")} • ${number(b.lean, "°")} • ${number(b.acceleration, "m/s²")}"
            }
        }
    }
    val target = if (follow) current ?: points.lastOrNull()?.position() else selected
    LaunchedEffect(trackLines, target) {
        map.overlays.clear(); map.overlays.addAll(trackLines)
        target?.let {
            map.overlays.add(Marker(map).apply {
                position = it.geoPoint()
                icon = BitmapDrawable(context.resources, pinBitmap)
                setAnchor(0.5f, 0.95f)
                title = if (follow) "Posizione attuale" else "Istante selezionato"
            })

        }
        map.invalidate()
    }
    LaunchedEffect(target, points.isNotEmpty()) {
        target?.let { map.controller.setZoom(16.0); map.controller.animateTo(it.geoPoint()) }
        if (!follow && target == null && points.isNotEmpty()) map.post {
            if (points.map { it.position() }.distinct().size > 1)
                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points.map { it.position().geoPoint() }), false, 70)
            else { map.controller.setZoom(16.0); map.controller.setCenter(points.first().position().geoPoint()) }
        }
        map.invalidate()
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
        Text("© OpenStreetMap contributors", Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 8.dp)
            .background(Color(0xDD101815)).padding(4.dp), style = MaterialTheme.typography.labelSmall)
    }
}
