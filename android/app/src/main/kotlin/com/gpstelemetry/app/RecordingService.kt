package com.gpstelemetry.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.gpstelemetry.core.GpsSample
import com.gpstelemetry.core.HrSample
import com.gpstelemetry.core.MotionSample
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.SessionResult
import com.gpstelemetry.core.SessionStatus
import com.gpstelemetry.core.SplitTime
import com.gpstelemetry.core.TimingEngine
import com.gpstelemetry.core.Track
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Servizio in foreground che registra la telemetria di una sessione:
 *  - posizioni GPS (FusedLocationProvider, alta precisione, ~2 Hz);
 *  - accelerometro (~10 Hz) e inclinazione pitch/roll (rotation vector);
 *  - frequenza cardiaca inviata dall'app Wear OS tramite Data Layer
 *    (MessageClient, percorso /gpstelemetry/hr, payload "timestamp;bpm");
 * alimenta il motore di cronometraggio e salva la sessione alla fine.
 */
class RecordingService : Service(), SensorEventListener, MessageClient.OnMessageReceivedListener {

    companion object {
        const val EXTRA_TRACK_ID = "trackId"
        const val HR_PATH = "/gpstelemetry/hr"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val MOTION_PERIOD_MS = 100L

        fun start(ctx: Context, trackId: String) {
            val i = Intent(ctx, RecordingService::class.java).putExtra(EXTRA_TRACK_ID, trackId)
            ctx.startForegroundService(i)
        }
    }

    /** Callback per l'interfaccia (TimerActivity). Invocate sul main thread. */
    interface Listener {
        fun onFix(fix: GpsSample, engine: TimingEngine)
        fun onRaceEvent(type: String, detail: SplitTime?)
        fun onHr(bpm: Int)
        fun onSessionSaved(finished: Boolean)
    }

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()
    var listener: Listener? = null

    var track: Track? = null
        private set
    var engine: TimingEngine? = null
        private set
    var session: Session? = null
        private set
    var lastHrBpm: Int? = null
        private set
    var lastMotion: MotionSample? = null
        private set

    private lateinit var fused: FusedLocationProviderClient
    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastMotionT = 0L
    private var pitch: Float? = null
    private var roll: Float? = null
    private var running = false

    /** Vero mentre una sessione è in registrazione. */
    val isRecording: Boolean get() = running

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    @SuppressLint("MissingPermission") // i permessi sono verificati dall'activity chiamante
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        val trackId = intent?.getStringExtra(EXTRA_TRACK_ID) ?: return stopSelfResult(startId).let { START_NOT_STICKY }
        val t = TrackStore.getTrack(this, trackId) ?: return stopSelfResult(startId).let { START_NOT_STICKY }
        running = true
        track = t

        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date())
        session = Session(
            id = TrackStore.newId(),
            trackId = t.id,
            name = "${t.name} — $dateStr",
            startedAt = System.currentTimeMillis(),
            trackType = t.type,
            trackStart = t.start,
            trackFinish = t.finish,
            trackSplits = t.splits.toList(),
        )
        engine = TimingEngine(t, engineListener)

        startForeground(NOTIF_ID, buildNotification())

        // Wake lock parziale: la CPU resta attiva anche a schermo spento.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpstelemetry:recording")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        // GPS ad alta precisione.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateIntervalMillis(250L)
            .build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())

        // Sensori di moto.
        sensorManager = (getSystemService(SENSOR_SERVICE) as SensorManager).also { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        // Frequenza cardiaca dallo smartwatch.
        Wearable.getMessageClient(this).addListener(this)

        return START_STICKY
    }

    /* ------------------------------- GPS -------------------------------- */

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val sess = session ?: return
            val eng = engine ?: return
            for (loc in result.locations) {
                val fix = GpsSample(
                    t = if (loc.time > 0) loc.time else System.currentTimeMillis(),
                    lat = loc.latitude,
                    lon = loc.longitude,
                    ele = if (loc.hasAltitude()) loc.altitude else null,
                    acc = if (loc.hasAccuracy()) loc.accuracy else null,
                    speed = if (loc.hasSpeed()) loc.speed else null,
                    heading = if (loc.hasBearing()) loc.bearing else null,
                )
                sess.samples.add(fix)
                eng.feed(fix)
                listener?.onFix(fix, eng)
            }
        }
    }

    /* ------------------------- Eventi di gara --------------------------- */

    private val engineListener = object : TimingEngine.Listener {
        override fun onStart(t: Long) {
            session?.status = SessionStatus.RUNNING
            listener?.onRaceEvent("start", null)
        }

        override fun onSplit(split: SplitTime, index: Int) {
            listener?.onRaceEvent("split", split)
        }

        override fun onFinish(t: Long, elapsedMs: Long) {
            listener?.onRaceEvent("finish", null)
            finalize(finished = true)
        }

        override fun onOffTrackEnter(t: Long, episodes: Int) {
            listener?.onRaceEvent("offtrack-enter", null)
        }

        override fun onOffTrackExit(t: Long, totalMs: Long) {
            listener?.onRaceEvent("offtrack-exit", null)
        }
    }

    /* ------------------------------ Sensori ----------------------------- */

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, event.values)
                val o = FloatArray(3)
                SensorManager.getOrientation(r, o)
                pitch = Math.round(o[1] * 180f / PI.toFloat() * 10f) / 10f
                roll = Math.round(o[2] * 180f / PI.toFloat() * 10f) / 10f
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val now = System.currentTimeMillis()
                if (now - lastMotionT < MOTION_PERIOD_MS) return
                lastMotionT = now
                val m = MotionSample(
                    t = now,
                    ax = event.values[0],
                    ay = event.values[1],
                    az = event.values[2],
                    pitch = pitch,
                    roll = roll,
                )
                lastMotion = m
                session?.motion?.add(m)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /* ----------------------- Frequenza cardiaca ------------------------- */

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != HR_PATH) return
        val parts = String(event.data, Charsets.UTF_8).split(";")
        if (parts.size < 2) return
        val t = parts[0].toLongOrNull() ?: System.currentTimeMillis()
        val bpm = parts[1].toDoubleOrNull()?.roundToInt() ?: return
        if (bpm <= 0) return
        lastHrBpm = bpm
        session?.hr?.add(HrSample(t, bpm))
        listener?.onHr(bpm)
    }

    /* --------------------------- Conclusione ---------------------------- */

    /** Interruzione manuale dall'interfaccia. */
    fun stopSession() {
        val eng = engine ?: return
        finalize(finished = eng.state == TimingEngine.State.FINISHED)
    }

    private fun finalize(finished: Boolean) {
        val sess = session ?: return
        val eng = engine ?: return
        if (!running) return
        running = false

        val result: SessionResult = eng.summary()
        sess.result = result
        sess.status = if (finished) SessionStatus.FINISHED else SessionStatus.ABORTED
        TrackStore.saveSession(this, sess)
        listener?.onSessionSaved(finished)

        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        runCatching { fused.removeLocationUpdates(locationCallback) }
        sensorManager?.unregisterListener(this)
        sensorManager = null
        runCatching { Wearable.getMessageClient(this).removeListener(this) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        if (running) {
            running = false
            // arresto imprevisto: salva comunque la sessione come interrotta
            session?.let { sess ->
                engine?.let { eng ->
                    sess.result = eng.summary()
                    sess.status = SessionStatus.ABORTED
                    TrackStore.saveSession(this, sess)
                }
            }
            teardown()
        }
        super.onDestroy()
    }

    /* ---------------------------- Notifica ------------------------------ */

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, TimerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_recording))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
