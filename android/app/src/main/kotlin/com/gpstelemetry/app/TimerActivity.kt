package com.gpstelemetry.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.GpsSample
import com.gpstelemetry.core.SplitTime
import com.gpstelemetry.core.TimingEngine
import java.util.Locale
import kotlin.math.sqrt

/**
 * Schermata di cronometraggio: avvia il RecordingService sul tracciato
 * selezionato e mostra in tempo reale cronometro, stato, telemetria
 * (velocità, quota, inclinazione, accelerazione, frequenza cardiaca),
 * tempo fuori tolleranza e parziali.
 */
class TimerActivity : AppCompatActivity(), RecordingService.Listener {

    companion object {
        const val EXTRA_TRACK_ID = "trackId"
    }

    private lateinit var tmState: TextView
    private lateinit var tmClock: TextView
    private lateinit var tmOffTrack: TextView
    private lateinit var tmSplits: LinearLayout
    private lateinit var grid: GridLayout

    private val statViews = mutableMapOf<Int, TextView>()
    private var service: RecordingService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as RecordingService.LocalBinder).service
            service = svc
            svc.listener = this@TimerActivity
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timer)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tmState = findViewById(R.id.tmState)
        tmClock = findViewById(R.id.tmClock)
        tmOffTrack = findViewById(R.id.tmOffTrack)
        tmSplits = findViewById(R.id.tmSplits)
        grid = findViewById(R.id.tmGrid)

        buildStatsGrid()

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            service?.stopSession()
        }

        // Avvia il servizio solo se chiamata con un tracciato (non al rientro).
        intent.getStringExtra(EXTRA_TRACK_ID)?.let { trackId ->
            RecordingService.start(this, trackId)
        }

        handler.post(clockTick)
    }

    private val statLabels = listOf(
        R.string.speed_label, R.string.ele_label, R.string.pitch_label, R.string.g_label,
        R.string.hr_label, R.string.offtime_label, R.string.offcount_label, R.string.acc_label,
    )

    private fun buildStatsGrid() {
        grid.removeAllViews()
        statViews.clear()
        for (labelRes in statLabels) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(4, 10, 4, 10)
            }
            val label = TextView(this).apply {
                text = getString(labelRes)
                textSize = 11f
                gravity = Gravity.CENTER
            }
            val value = TextView(this).apply {
                text = "—"
                textSize = 17f
                gravity = Gravity.CENTER
            }
            cell.addView(label)
            cell.addView(value)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            grid.addView(cell, lp)
            statViews[labelRes] = value
        }
    }

    private fun setStat(labelRes: Int, value: String) {
        statViews[labelRes]?.text = value
    }

    /* --------------------------- Aggiornamenti --------------------------- */

    private val clockTick = object : Runnable {
        override fun run() {
            val eng = service?.engine
            if (eng != null && eng.startTime != null) {
                tmClock.text = Fmt.time(eng.elapsedMs())
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onFix(fix: GpsSample, engine: TimingEngine) {
        runOnUiThread {
            setStat(R.string.speed_label, fix.speed?.let {
                String.format(Locale.US, "%.1f km/h", it * 3.6f)
            } ?: "—")
            setStat(R.string.ele_label, fix.ele?.let {
                String.format(Locale.US, "%.0f m", it)
            } ?: "—")
            setStat(R.string.acc_label, fix.acc?.let {
                String.format(Locale.US, "%.0f m", it)
            } ?: "—")
            service?.lastMotion?.let { m ->
                setStat(R.string.pitch_label, m.pitch?.let { "$it°" } ?: "—")
                val g = sqrt(m.ax * m.ax + m.ay * m.ay + m.az * m.az) / 9.81f
                setStat(R.string.g_label, String.format(Locale.US, "%.2f g", g))
            }
            val sum = engine.summary()
            setStat(R.string.offtime_label, String.format(Locale.US, "%.1f s", sum.offTrackMs / 1000.0))
            setStat(R.string.offcount_label, sum.offTrackEpisodes.toString())

            if (engine.state == TimingEngine.State.ARMED) {
                val tr = service?.track
                if (tr != null) {
                    val d = Geo.haversine(fix.lat, fix.lon, tr.start.lat, tr.start.lon)
                    tmState.text = getString(R.string.state_armed, d.toInt())
                    tmState.setTextColor(ContextCompat.getColor(this, R.color.warn))
                }
            }
        }
    }

    override fun onRaceEvent(type: String, detail: SplitTime?) {
        runOnUiThread {
            when (type) {
                "start" -> {
                    tmState.text = getString(R.string.state_running)
                    tmState.setTextColor(ContextCompat.getColor(this, R.color.ok))
                    vibrate(longArrayOf(0, 200))
                }
                "split" -> {
                    detail?.let {
                        val row = TextView(this).apply {
                            text = "${it.name}: ${Fmt.time(it.elapsed)}"
                            textSize = 17f
                            typeface = android.graphics.Typeface.MONOSPACE
                            setPadding(8, 8, 8, 8)
                        }
                        tmSplits.addView(row)
                    }
                    vibrate(longArrayOf(0, 100, 50, 100))
                }
                "offtrack-enter" -> {
                    tmOffTrack.visibility = View.VISIBLE
                    vibrate(longArrayOf(0, 300, 100, 300))
                }
                "offtrack-exit" -> tmOffTrack.visibility = View.GONE
                "finish" -> {
                    val eng = service?.engine
                    tmState.text = getString(R.string.state_finished, Fmt.time(eng?.elapsedMs()))
                    tmState.setTextColor(ContextCompat.getColor(this, R.color.primary))
                    tmOffTrack.visibility = View.GONE
                    vibrate(longArrayOf(0, 400, 100, 400))
                }
            }
        }
    }

    override fun onHr(bpm: Int) {
        runOnUiThread { setStat(R.string.hr_label, "$bpm bpm") }
    }

    override fun onSessionSaved(finished: Boolean) {
        runOnUiThread {
            android.widget.Toast.makeText(this, R.string.session_saved, android.widget.Toast.LENGTH_LONG).show()
            if (!finished) finish()
        }
    }

    private fun vibrate(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (v.hasVibrator()) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    /* ----------------------------- Ciclo vita ---------------------------- */

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        service?.listener = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockTick)
        super.onDestroy()
    }
}
