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
    val calibrationActive: Boolean = false, val calibrationStep: CalibrationStep = CalibrationStep.UPRIGHT,
    val calibrationDegrees: Double? = null, val calibrationProgress: Float = 0f,
    val calibrationCanConfirm: Boolean = false, val orientationAvailable: Boolean = false,
    val awaitingRightLean: Boolean = false, val calibrationHint: String = "Calibra a moto ferma: prima verticale, poi leggermente a destra.",
    val sensorAvailable: Boolean = false, val calibrated: Boolean = false, val message: String = "Pronto per un nuovo giro", val lastFixElapsed: Long = 0
)
class TrackingService : Service(), LocationListener, SensorEventListener {
    companion object {
        private val mutable = MutableStateFlow(TrackingState())
        val state = mutable.asStateFlow()
        const val START = "start"; const val PAUSE = "pause"; const val RESUME = "resume"
        const val BEGIN_CALIBRATION = "begin_calibration"; const val CONFIRM_CALIBRATION = "confirm_calibration"; const val CANCEL_CALIBRATION = "cancel_calibration"
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
    private val telemetry = CalibratedTelemetry()
    private var guide: CalibrationGuide? = null
    private var resumeAfterCalibration = false
    private var rotationType = Sensor.TYPE_ROTATION_VECTOR
    private var lastSensor = 0L
    private var lastUpNanos = 0L
    private var lastKnownSpeed = 0.0
    private var lastKnownSpeedNanos = 0L
    private fun moving() = SystemClock.elapsedRealtimeNanos() - lastKnownSpeedNanos < 3_000_000_000L && lastKnownSpeed > 2.0
    private fun publishCalibration() {
        val session = guide ?: return
        val reading = session.reading(SystemClock.elapsedRealtimeNanos(), moving())
        val hint = when {
            !mutable.value.orientationAvailable -> "Sensore di orientamento non disponibile sul telefono."
            !reading.fresh -> "Attendo i dati del sensore…"
            moving() -> "Ferma la moto prima di calibrare."
            reading.progress < 1f -> "Mantieni la posizione: riempimento della barra di stabilità."
            !reading.canConfirm && session.step == CalibrationStep.RIGHT -> "Inclina verso destra tra 5° e 30°."
            !reading.canConfirm && session.step == CalibrationStep.RETURN -> "Riporta la moto verticale: l’angolo deve essere vicino a 0°."
            else -> "Posizione stabile: puoi confermare."
        }
        mutable.value = mutable.value.copy(calibrationStep = session.step, calibrationDegrees = reading.degrees,
            calibrationProgress = reading.progress, calibrationCanConfirm = reading.canConfirm,
            awaitingRightLean = session.step == CalibrationStep.RIGHT, calibrationHint = hint)
    }
    private fun pauseRecording(keepLocation: Boolean = false) {
        accumulated = elapsed(); if (!keepLocation) locations.removeUpdates(this); resetAcceleration()
        ride = ride?.copy(activeMillis = accumulated)
        mutable.value = mutable.value.copy(mode = Mode.PAUSED, ride = ride, message = "In pausa")
        checkpoint(); notifyState()
    }
    private fun resumeRecording() {
        resetAcceleration(); segment++; activeStart = SystemClock.elapsedRealtime(); lastLocationNanos = 0
        mutable.value = mutable.value.copy(mode = Mode.RECORDING, message = "Attendo il GPS", lastFixElapsed = 0)
        requestLocations(); notifyState()
    }
    private fun endCalibration() {
        guide = null
        mutable.value = mutable.value.copy(calibrationActive = false, calibrated = telemetry.ready, awaitingRightLean = false)
        resetAcceleration()
        if (resumeAfterCalibration && mutable.value.mode == Mode.PAUSED) resumeRecording()
        else locations.removeUpdates(this)
        resumeAfterCalibration = false
    }
    private var up: DoubleArray? = null
    private var lastInertialNanos = 0L
    private var lastInertialStored = 0L
    private var latestG: Double? = null
    private fun resetAcceleration() {
        telemetry.resetStream(); latestG = null; lastInertialNanos = 0; lastInertialStored = 0
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
            if (SystemClock.elapsedRealtimeNanos() - lastUpNanos > 1_000_000_000L) mutable.value = mutable.value.copy(lean = null)
            publishCalibration()
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
            PAUSE -> if (mutable.value.mode == Mode.RECORDING) pauseRecording()
            RESUME -> if (mutable.value.mode == Mode.PAUSED && guide == null) resumeRecording()
            BEGIN_CALIBRATION -> if (ride != null) {
                if (guide == null) {
                    resumeAfterCalibration = mutable.value.mode == Mode.RECORDING
                    if (resumeAfterCalibration) pauseRecording(keepLocation = true)
                }
                guide = CalibrationGuide()
                requestLocations()
                mutable.value = mutable.value.copy(calibrationActive = true, calibrationStep = CalibrationStep.UPRIGHT,
                    calibrationDegrees = null, calibrationCanConfirm = false, calibrationProgress = 0f)
                publishCalibration()
            }
            CONFIRM_CALIBRATION -> {
                val result = guide?.confirm(SystemClock.elapsedRealtimeNanos(), moving())
                if (result != null) {
                    telemetry.apply(result)
                    mutable.value = mutable.value.copy(calibrated = true, calibrationStep = CalibrationStep.DONE,
                        calibrationDegrees = 0.0, lean = up?.let { telemetry.lean(it) }, calibrationHint = "Zero verificato. Calibrazione applicata alla registrazione.")
                    endCalibration()
                } else publishCalibration()
            }
            CANCEL_CALIBRATION -> if (guide != null) endCalibration()
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
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val orientationRegistered = sensor != null && sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        rotationType = sensor?.type ?: Sensor.TYPE_ROTATION_VECTOR
        val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val available = linear != null && orientationRegistered && sensors.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
        mutable.value = mutable.value.copy(sensorAvailable = available, orientationAvailable = orientationRegistered)
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
        val speedAge = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (speedAge in 0..3_000_000_000L && location.hasSpeed() &&
            (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 1.5f)) {
            lastKnownSpeed = location.speed.toDouble(); lastKnownSpeedNanos = location.elapsedRealtimeNanos
        }
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
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            if (SystemClock.elapsedRealtimeNanos() - event.timestamp !in 0..500_000_000L) return
            val linear = DoubleArray(3) { event.values[it].toDouble() }
            guide?.acceleration(linear, event.timestamp)
            if (mutable.value.mode != Mode.RECORDING || saving || guide != null) return
            if (event.timestamp - lastUpNanos !in -100_000_000L..1_000_000_000L) return
            val g = telemetry.acceleration(linear, event.timestamp) ?: return
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
        if (event.sensor.type != rotationType) return
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        up = doubleArrayOf(matrix[6].toDouble(), matrix[7].toDouble(), matrix[8].toDouble())
        val direction = up!!
        lastUpNanos = event.timestamp
        guide?.orientation(direction, event.timestamp)
        val now = SystemClock.elapsedRealtime()
        if (now - lastSensor < 100) return
        lastSensor = now
        mutable.value = mutable.value.copy(lean = telemetry.lean(direction))
        publishCalibration()
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
        guide = null; resumeAfterCalibration = false
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
