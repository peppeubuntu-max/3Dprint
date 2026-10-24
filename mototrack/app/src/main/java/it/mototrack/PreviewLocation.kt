package it.mototrack

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// UI-only positioning is independent of the recording service and never saves a ride.
@Composable
fun rememberPreviewLocation(permissionGranted: Boolean, enabled: Boolean): Location? {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var fix by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(context, owner, permissionGranted, enabled) {
        val manager = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { location ->
            val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            if (age in 0..30_000_000_000L) fix = location
        }
        fun stop() { manager.removeUpdates(listener) }
        fun start() {
            stop()
            if (!permissionGranted || !enabled || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).forEach { provider ->
                try {
                    if (manager.isProviderEnabled(provider)) {
                        manager.getLastKnownLocation(provider)?.let { old ->
                            if (SystemClock.elapsedRealtimeNanos() - old.elapsedRealtimeNanos in 0..30_000_000_000L &&
                                (fix == null || old.elapsedRealtimeNanos > fix!!.elapsedRealtimeNanos)) fix = old
                        }
                        manager.requestLocationUpdates(provider, 2000L, 2f, listener, Looper.getMainLooper())
                    }
                } catch (_: SecurityException) { fix = null }
                catch (_: IllegalArgumentException) { /* Provider absent on this device. */ }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) start()
            if (event == Lifecycle.Event.ON_PAUSE) stop()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose { stop(); owner.lifecycle.removeObserver(observer) }
    }
    return fix
}
