package it.mototrack

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

enum class Mode { IDLE, RECORDING, PAUSED }
data class TrackingState(
    val mode: Mode = Mode.IDLE, val ride: Ride? = null, val lean: Double? = null,
    val accelerationG: Double? = null, val accelerationSource: String = "gps",
    val awaitingRightLean: Boolean = false, val calibrationHint: String = "Calibra a moto ferma: prima verticale, poi leggermente a destra.",
    val sensorAvailable: Boolean = false, val calibrated: Boolean = false, val message: String = "Pronto per un nuovo giro", val lastFixElapsed: Long = 0
)
class TrackingService : Service(), LocationListener, SensorEventListener {
    companion object {
        private val mutable = MutableStateFlow(TrackingState())
        val state = mutable.asStateFlow()
        const val START = "start"; const val PAUSE = "pause"; const val RESUME = "resume"
        const val STOP = "stop"; const val ZERO = "zero"; const val CONFIRM_RIGHT = "confirm_right"
    }
    private lateinit var locations: LocationManager
    private lateinit var sensors: SensorManager
    private lateinit var store: RideStore
    private val diskJob = SupervisorJob()
    private val disk = CoroutineScope(diskJob + Dispatchers.IO.limitedParallelism(1))
    private var saving = false
    private var ride: Ride? = null
    private var segment = 0
    private var activeStart = 0L
    private var accumulated = 0L
    private val mount = MountCalibration()
    private var gyroMagnitude = Double.POSITIVE_INFINITY
    private var lastGyroNanos = 0L
    private var stableSince = 0L
    private var lastUp: DoubleArray? = null
    private var lastUpNanos = 0L
    private fun calibrationIsStationary(): Boolean {
        val now = SystemClock.elapsedRealtimeNanos()
        val orientationFresh = now - lastUpNanos in 0..500_000_000L
        val gyroFresh = now - lastGyroNanos in 0..500_000_000L
        val movingGps = mutable.value.lastFixElapsed > 0 &&
            SystemClock.elapsedRealtime() - mutable.value.lastFixElapsed < 3000 &&
            (ride?.points?.lastOrNull()?.speed ?: 0.0) > 1.0
        return orientationFresh && gyroFresh && gyroMagnitude < 0.05 &&
            stableSince > 0 && now - stableSince >= 1_000_000_000L && !movingGps
    }
    private var lastSensor = 0L
    private val inertialFilter = InertialFilter()
    private var up: DoubleArray? = null
    private var lastInertialNanos = 0L
    private var lastInertialStored = 0L
    private var latestG: Double? = null
    private fun resetAcceleration() {
        inertialFilter.reset(); latestG = null; lastInertialNanos = 0; lastInertialStored = 0
        mutable.value = mutable.value.copy(accelerationG = null)
    }
    private var lastLocationNanos = 0L
    private var lastCheckpoint = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (mutable.value.mode == Mode.RECORDING) {
                ride = ride?.copy(activeMillis = elapsed())
                if (SystemClock.elapsedRealtimeNanos() - lastInertialNanos > 1_000_000_000L && mutable.value.accelerationSource == "sensor") {
                    mutable.value = mutable.value.copy(accelerationG = null)
                }
                mutable.value = mutable.value.copy(ride = ride)
            }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        store = RideStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tracking", "Registrazione giri", NotificationManager.IMPORTANCE_LOW))
        handler.post(ticker)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (saving) return START_NOT_STICKY
        when (intent?.action) {
            START -> if (mutable.value.mode == Mode.IDLE) startRide()
            PAUSE -> if (mutable.value.mode == Mode.RECORDING) {
                accumulated = elapsed(); locations.removeUpdates(this); resetAcceleration()
                ride = ride?.copy(activeMillis = accumulated)
                mutable.value = mutable.value.copy(mode = Mode.PAUSED, ride = ride, message = "In pausa")
                checkpoint(); notifyState()
            }
            RESUME -> if (mutable.value.mode == Mode.PAUSED) {
                resetAcceleration(); segment++; activeStart = SystemClock.elapsedRealtime(); lastLocationNanos = 0
                mutable.value = mutable.value.copy(mode = Mode.RECORDING, message = "Attendo il GPS", lastFixElapsed = 0)
                requestLocations(); notifyState()
            }
            ZERO -> {
                val direction = up
                if (direction != null && calibrationIsStationary() && mount.zero(direction)) {
                    inertialFilter.clear(); resetAcceleration()
                    mutable.value = mutable.value.copy(calibrated = false, awaitingRightLean = true, lean = null,
                        calibrationHint = "1/2 Zero acquisito. Inclina leggermente la moto a destra (5–30°), tienila ferma e conferma.")
                } else mutable.value = mutable.value.copy(calibrationHint = "Moto ferma e verticale: attendi un secondo senza muoverla. Servono orientamento e giroscopio.")
            }
            CONFIRM_RIGHT -> {
                val direction = up
                if (mutable.value.awaitingRightLean && direction != null && calibrationIsStationary() && mount.confirmRight(direction)) {
                    inertialFilter.calibrate(mount.forward!!); resetAcceleration()
                    mutable.value = mutable.value.copy(calibrated = true, awaitingRightLean = false, lean = mount.lean(direction),
                        calibrationHint = "Calibrazione completa. Riporta la moto verticale. Ripeti se sposti il telefono.")
                } else mutable.value = mutable.value.copy(calibrationHint = "Tieni ferma la moto inclinata a destra tra 5° e 30°, poi conferma. Per ripartire usa Ripeti zero.")
            }
            STOP -> stopRide()
        }
        return START_NOT_STICKY
    }
    private fun startRide() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            mutable.value = TrackingState(message = "Consenti la posizione precisa per registrare")
            stopSelf(); return
        }
        ride = Ride(System.currentTimeMillis(), null, 0, emptyList())
        accumulated = 0; segment = 0; activeStart = SystemClock.elapsedRealtime()
        mutable.value = TrackingState(Mode.RECORDING, ride, message = "Attendo il GPS")
        startForeground(1, notification())
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null) sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        else mutable.value = mutable.value.copy(message = "Sensore di orientamento non disponibile")
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val available = linear != null && sensor != null && sensors.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
        mutable.value = mutable.value.copy(sensorAvailable = available)
        requestLocations(); checkpoint()
    }
    private fun requestLocations() {
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, this)
            if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) onProviderDisabled(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            accumulated = elapsed()
            mutable.value = mutable.value.copy(mode = Mode.PAUSED, message = "Permesso posizione revocato")
        } catch (_: IllegalArgumentException) {
            accumulated = elapsed()
            mutable.value = mutable.value.copy(mode = Mode.PAUSED, message = "GPS non disponibile")
        }
    }
    private fun elapsed() = accumulated + (SystemClock.elapsedRealtime() - activeStart).coerceAtLeast(0)
    override fun onLocationChanged(location: Location) {
        if (mutable.value.mode != Mode.RECORDING) return
        if (!location.hasAccuracy() || location.accuracy > 30 || location.accuracy <= 0) return
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (age !in 0..10_000_000_000L || location.elapsedRealtimeNanos <= lastLocationNanos) return
        val r = ride ?: return
        val previous = r.points.lastOrNull()
        if (previous != null && location.time <= previous.time) return
        val gap = lastLocationNanos != 0L && location.elapsedRealtimeNanos - lastLocationNanos > 10_000_000_000L
        if (gap) segment++
        lastLocationNanos = location.elapsedRealtimeNanos
        val speed = if (location.hasSpeed() && (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 3)) location.speed.toDouble().coerceAtLeast(0.0) else null
        val lean = mutable.value.lean?.takeIf { SystemClock.elapsedRealtime() - lastSensor < 2000 }
        val sensorG = latestG?.takeIf { kotlin.math.abs(location.elapsedRealtimeNanos - lastInertialNanos) < 1_000_000_000L }
        val acceleration = sensorG?.times(STANDARD_GRAVITY) ?: longitudinalAcceleration(previous, speed, location.time, segment)
        val source = if (sensorG != null) "sensor" else "gps"
        val p = Sample(location.time, location.latitude, location.longitude,
            if (location.hasAltitude()) location.altitude else null, speed, lean,
            acceleration, location.accuracy, segment, source)
        ride = r.copy(points = r.points + p, activeMillis = elapsed())
        mutable.value = mutable.value.copy(ride = ride, accelerationG = acceleration?.div(STANDARD_GRAVITY), accelerationSource = source, message = "GPS ±${location.accuracy.toInt()} m", lastFixElapsed = SystemClock.elapsedRealtime())
        if (SystemClock.elapsedRealtime() - lastCheckpoint > 5000) checkpoint()
    }
    override fun onProviderDisabled(provider: String) { mutable.value = mutable.value.copy(message = "GPS disattivato: abilita la posizione") }
    override fun onProviderEnabled(provider: String) { mutable.value = mutable.value.copy(message = "Attendo il GPS") }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gyroMagnitude = sqrt(event.values.take(3).sumOf { it.toDouble() * it })
            lastGyroNanos = event.timestamp
            if (gyroMagnitude >= 0.05) stableSince = 0
            return
        }
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            if (mutable.value.mode != Mode.RECORDING || saving) return
            val g = inertialFilter.sample(DoubleArray(3) { event.values[it].toDouble() }, event.timestamp) ?: return
            latestG = g; lastInertialNanos = event.timestamp
            if (event.timestamp - lastInertialStored >= 100_000_000L) {
                lastInertialStored = event.timestamp
                val time = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
                ride = ride?.let { it.copy(inertial = it.inertial + InertialSample(time, g, segment)) }
                mutable.value = mutable.value.copy(ride = ride, accelerationG = g, accelerationSource = "sensor")
                if (SystemClock.elapsedRealtime() - lastCheckpoint > 5000) checkpoint()
            }
            return
        }
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        up = doubleArrayOf(matrix[6].toDouble(), matrix[7].toDouble(), matrix[8].toDouble())
        val direction = up!!
        val previous = lastUp
        val gap = event.timestamp - lastUpNanos
        val drift = previous?.let { acos(dot(it, direction).coerceIn(-1.0, 1.0)) } ?: Double.POSITIVE_INFINITY
        if (gap > 500_000_000L || drift > 0.015 || gyroMagnitude >= 0.05) stableSince = 0
        else if (stableSince == 0L) stableSince = event.timestamp
        lastUp = direction; lastUpNanos = event.timestamp
        val now = SystemClock.elapsedRealtime()
        if (now - lastSensor < 100) return
        lastSensor = now
        mutable.value = mutable.value.copy(lean = mount.lean(direction))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun checkpoint() {
        val snapshot = ride ?: return
        lastCheckpoint = SystemClock.elapsedRealtime()
        disk.launch {
            try { store.save(snapshot) }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "Errore salvataggio: controlla lo spazio disponibile") }
        }
    }
    private fun stopRide() {
        val r = ride ?: return
        saving = true
        val time = if (mutable.value.mode == Mode.RECORDING) elapsed() else accumulated
        accumulated = time
        locations.removeUpdates(this); sensors.unregisterListener(this)
        ride = r.copy(ended = System.currentTimeMillis(), activeMillis = time)
        // Keep controls disabled until the final atomic write has completed.
        mutable.value = mutable.value.copy(mode = Mode.PAUSED, ride = ride, message = "Salvataggio…")
        val finalRide = ride!!
        disk.launch {
            try {
                store.save(finalRide)
                withContext(Dispatchers.Main) {
                    mutable.value = TrackingState(message = "Giro salvato nella sezione Giri")
                    ride = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            } catch (_: Exception) {
                saving = false
                mutable.value = mutable.value.copy(message = "Salvataggio fallito: premi Stop per riprovare")
            }
        }
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("MotoTrack • ${if (mutable.value.mode == Mode.PAUSED) "In pausa" else "Registrazione"}")
            .setContentText("Apri l’app per gestire il giro").setContentIntent(open).setOngoing(true).build()
    }
    private fun notifyState() = getSystemService(NotificationManager::class.java).notify(1, notification())
    override fun onDestroy() {
        handler.removeCallbacks(ticker); locations.removeUpdates(this); sensors.unregisterListener(this)
        if (ride != null) checkpoint()
        // Queued atomic writes must finish even when the service stops.
        diskJob.complete()
        if (mutable.value.mode != Mode.IDLE) mutable.value = TrackingState(message = "Registrazione interrotta: traccia recuperabile in Giri")
        super.onDestroy()
    }
}
