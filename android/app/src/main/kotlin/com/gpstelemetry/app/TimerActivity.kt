package com.gpstelemetry.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.GpsSample
import com.gpstelemetry.core.LapStats
import com.gpstelemetry.core.LatLon
import com.gpstelemetry.core.Sectors
import com.gpstelemetry.core.SessionStatus
import com.gpstelemetry.core.SplitTime
import com.gpstelemetry.core.TimingEngine
import com.gpstelemetry.core.Track
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dashboard di cronometraggio.
 *
 * Si apre in attesa: il tasto arancione START TRACKING avvia il
 * RecordingService (telemetria + motore di cronometraggio); il tempo parte
 * all'attraversamento della linea di partenza.
 *
 * In orizzontale (rotazione del telefono o tasto dedicato) diventa una
 * dashboard: barra dei settori in alto, tempo enorme al centro. Ogni settore
 * concluso si colora rispetto allo storico del tracciato: viola = record,
 * verde = meglio della media, rosso = peggio della media.
 *
 * La rotazione è gestita senza ricreare l'activity (configChanges): lo stato
 * resta in memoria e il layout viene semplicemente ridisegnato.
 */
class TimerActivity : AppCompatActivity(), RecordingService.Listener {

    companion object {
        const val EXTRA_TRACK_ID = "trackId"
    }

    private enum class Phase { IDLE, WAITING_GPS, ARMED, RUNNING, FINISHED, STOPPED }

    /* ------------------------------- Stato ------------------------------- */

    private var trackId: String? = null
    private var track: Track? = null
    private var benchmark = Sectors.Benchmark.empty(1)
    private var phase = Phase.IDLE
    private val splits = mutableListOf<SplitTime>()
    private var finalMs: Long? = null
    private var runStartedAt = 0L
    private var distToStart: Int? = null
    private var offTrack = false
    private var lastFix: GpsSample? = null
    private var lastSessionId: String? = null
    private var forceLandscape = false
    /** Resoconto del giro concluso (mostrato nella dashboard a fine giro). */
    private var lapStats: LapStats? = null
    private var showingSummary = false
    private val stats = mutableMapOf<Int, String>()

    /* ------------------------------- Viste ------------------------------- */

    private lateinit var tmState: TextView
    private lateinit var tmName: TextView
    private lateinit var tmClock: TextView
    private lateinit var tmDelta: TextView
    private lateinit var tmOffTrack: TextView
    private lateinit var tmSectors: SectorBarView
    private lateinit var tmShape: TrackShapeView
    private lateinit var grid: GridLayout
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnShare: MaterialButton
    private val statViews = mutableMapOf<Int, TextView>()

    private var service: RecordingService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())

    private val statLabels = listOf(
        R.string.speed_label, R.string.g_label, R.string.hr_label, R.string.pitch_label,
        R.string.ele_label, R.string.offtime_label, R.string.offcount_label, R.string.acc_label,
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) startTracking()
            else Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? RecordingService.LocalBinder)?.service ?: return
            service = svc
            svc.listener = this@TimerActivity
            bound = true
            syncWithService(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    /* ----------------------------- Ciclo vita ---------------------------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        trackId = savedInstanceState?.getString(EXTRA_TRACK_ID) ?: intent.getStringExtra(EXTRA_TRACK_ID)
        trackId?.let { id ->
            track = TrackStore.getTrack(this, id)
            if (track == null) {
                finish()
                return
            }
        }
        loadBenchmark()
        buildUi()
        handler.post(clockTick)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_TRACK_ID, trackId)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
    }

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
        service = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockTick)
        super.onDestroy()
    }

    /* ------------------------------ Layout ------------------------------- */

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun buildUi() {
        setContentView(R.layout.activity_timer)
        tmState = findViewById(R.id.tmState)
        tmName = findViewById(R.id.tmTrackName)
        tmClock = findViewById(R.id.tmClock)
        tmDelta = findViewById(R.id.tmDelta)
        tmOffTrack = findViewById(R.id.tmOffTrack)
        tmSectors = findViewById(R.id.tmSectors)
        tmShape = findViewById(R.id.tmShape)
        grid = findViewById(R.id.tmGrid)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnShare = findViewById(R.id.btnShare)

        val land = isLandscape()
        tmSectors.barHeightDp = if (land) 18f else 14f
        tmSectors.timeTextSp = if (land) 20f else 15f

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnRotate).setOnClickListener { toggleOrientation() }
        btnStart.setOnClickListener { onStartPressed() }
        btnStop.setOnClickListener { service?.stopSession() }
        btnShare.setOnClickListener { openShare() }

        buildStatsGrid(land)
        showingSummary = false
        applySystemBars(land)
        tmShape.lineWidthDp = 2.5f
        render()
    }

    private fun buildStatsGrid(land: Boolean) {
        grid.removeAllViews()
        statViews.clear()
        // In orizzontale bastano le metriche principali su una riga.
        val labels = if (land) statLabels.take(6) else statLabels
        grid.columnCount = if (land) labels.size else 4
        for (labelRes in labels) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(4, if (land) 6 else 12, 4, if (land) 6 else 12)
            }
            val label = TextView(this).apply {
                text = getString(labelRes)
                setTextAppearance(R.style.Text_Label)
                textSize = 9.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val value = TextView(this).apply {
                text = stats[labelRes] ?: "—"
                textSize = if (land) 17f else 18f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                fontFeatureSettings = "tnum"
                setTextColor(ContextCompat.getColor(this@TimerActivity, R.color.text_primary))
                gravity = Gravity.CENTER
                maxLines = 1
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

    /** In orizzontale la dashboard occupa tutto lo schermo. */
    private fun applySystemBars(land: Boolean) {
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        if (land) {
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleOrientation() {
        forceLandscape = !forceLandscape
        requestedOrientation = if (forceLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /* ------------------------------ Rendering ---------------------------- */

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun render() {
        val t = track
        tmName.text = t?.name ?: ""
        tmShape.setTrack(t)
        tmShape.setMarker(lastFix?.let { LatLon(it.lat, it.lon) })

        // Stato.
        val (stateText, stateColor) = when (phase) {
            Phase.IDLE -> getString(R.string.state_idle) to R.color.text_secondary
            Phase.WAITING_GPS -> getString(R.string.waiting_gps) to R.color.warn
            Phase.ARMED -> (distToStart?.let { getString(R.string.state_armed, it) }
                ?: getString(R.string.waiting_gps)) to R.color.warn
            Phase.RUNNING -> getString(R.string.state_running) to R.color.ok
            Phase.FINISHED -> getString(R.string.state_finished, Fmt.time(finalMs)) to R.color.accent
            Phase.STOPPED -> getString(R.string.state_stopped) to R.color.text_secondary
        }
        tmState.text = stateText
        tmState.setTextColor(color(stateColor))

        renderSectors()
        renderClock()

        // In corsa: banner "fuori tracciato". A fine giro: avviso con il numero di uscite.
        val summary = lapStats.takeIf { phase == Phase.FINISHED }
        if (summary != null) {
            Summary.bindAlert(tmOffTrack, summary)
            tmOffTrack.visibility = View.VISIBLE
        } else {
            tmOffTrack.setText(R.string.off_track)
            tmOffTrack.setBackgroundResource(R.drawable.bg_offtrack)
            tmOffTrack.setTextColor(color(android.R.color.white))
            tmOffTrack.visibility = if (offTrack && phase == Phase.RUNNING) View.VISIBLE else View.GONE
        }

        val active = phase == Phase.WAITING_GPS || phase == Phase.ARMED || phase == Phase.RUNNING
        btnStart.visibility = if (active) View.GONE else View.VISIBLE
        btnStop.visibility = if (active) View.VISIBLE else View.GONE
        btnShare.visibility =
            if (phase == Phase.FINISHED && lastSessionId != null) View.VISIBLE else View.GONE

        // A fine giro la griglia mostra il resoconto invece della telemetria live.
        if (summary != null) {
            if (!showingSummary) {
                val land = isLandscape()
                val tiles = Summary.tiles(this, summary, compact = land)
                Summary.fill(grid, tiles, columns = if (land) tiles.size else 4, valueSp = if (land) 16f else 17f)
                statViews.clear()
                showingSummary = true
            }
        } else {
            if (showingSummary) {
                buildStatsGrid(isLandscape())
                showingSummary = false
            }
            statViews.forEach { (k, v) -> v.text = stats[k] ?: "—" }
        }
    }

    private fun sectorCount() = Sectors.count(track?.splits?.size ?: 0)

    private fun completedDurations(): List<Long> =
        Sectors.durations(splits, if (phase == Phase.FINISHED) finalMs else null)

    private fun renderSectors() {
        val n = sectorCount()
        val done = completedDurations()
        val names = track?.splits?.map { it.name } ?: emptyList()
        val list = List(n) { i ->
            val d = done.getOrNull(i)
            SectorBarView.Sector(
                label = sectorLabel(i, n, names),
                timeMs = d,
                rating = d?.let { Sectors.rate(it, benchmark.sectors.getOrNull(i)) },
            )
        }
        tmSectors.setSectors(list)
        if (phase == Phase.RUNNING) updateCurrentSectorProgress() else tmSectors.setCurrent(-1, 0f)
    }

    private fun sectorLabel(i: Int, n: Int, names: List<String>): String = when {
        n == 1 -> getString(R.string.sector_lap)
        // Nome del parziale che chiude il settore, se personalizzato.
        i < names.size && names[i].isNotBlank() && !names[i].startsWith("Parziale") -> "S${i + 1} · ${names[i]}"
        else -> "S${i + 1}"
    }

    private fun updateCurrentSectorProgress() {
        val cur = splits.size
        val eng = service?.engine ?: return tmSectors.setCurrent(cur, 0f)
        val inSector = eng.elapsedMs() - (splits.lastOrNull()?.elapsed ?: 0L)
        val avg = benchmark.sectors.getOrNull(cur)?.avg
        val progress = if (avg != null && avg > 0) inSector.toFloat() / avg else 0f
        tmSectors.setCurrent(cur, progress)
    }

    private fun renderClock() {
        when (phase) {
            Phase.FINISHED -> {
                val ms = finalMs ?: 0
                val rating = Sectors.rate(ms, benchmark.lap)
                tmClock.text = Fmt.time(ms)
                tmClock.setTextColor(TrackShape.ratingColor(this, rating))
                tmDelta.text = lapDeltaText(ms)
                tmDelta.setTextColor(TrackShape.ratingColor(this, rating))
            }
            Phase.RUNNING -> {
                tmClock.setTextColor(color(R.color.text_primary))
                // Ultimo settore concluso rispetto al suo record.
                val done = completedDurations()
                val i = done.size - 1
                if (i >= 0) {
                    val ref = benchmark.sectors.getOrNull(i)
                    val d = done[i]
                    tmDelta.text = "S${i + 1}  " + (Sectors.deltaToBest(d, ref)?.let { signed(it) }
                        ?: getString(R.string.record))
                    tmDelta.setTextColor(TrackShape.ratingColor(this, Sectors.rate(d, ref)))
                } else {
                    tmDelta.text = benchmark.lap?.let { getString(R.string.best_fmt, Fmt.time(it.best)) } ?: ""
                    tmDelta.setTextColor(color(R.color.text_secondary))
                }
            }
            else -> {
                tmClock.text = Fmt.time(if (phase == Phase.STOPPED) finalMs ?: 0 else 0)
                tmClock.setTextColor(color(R.color.text_primary))
                tmDelta.text = benchmark.lap?.let { getString(R.string.best_fmt, Fmt.time(it.best)) } ?: ""
                tmDelta.setTextColor(color(R.color.sector_best))
            }
        }
    }

    private fun lapDeltaText(ms: Long): String {
        val ref = benchmark.lap ?: return getString(R.string.first_record)
        val delta = ms - ref.best
        return if (delta < 0) "${getString(R.string.record)}  ${signed(delta)}"
        else getString(R.string.delta_best_fmt, signed(delta))
    }

    private fun signed(ms: Long): String {
        val s = String.format(Locale.US, "%.2f", abs(ms) / 1000.0)
        return if (ms < 0) "−$s" else "+$s"
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (phase == Phase.RUNNING) {
                service?.engine?.let { eng ->
                    if (eng.startTime != null) tmClock.text = Fmt.time(eng.elapsedMs())
                    updateCurrentSectorProgress()
                }
            }
            handler.postDelayed(this, 50)
        }
    }

    /* ------------------------------ Comandi ------------------------------ */

    private fun onStartPressed() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) {
            startTracking()
        } else {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun startTracking() {
        val id = trackId ?: return
        splits.clear()
        finalMs = null
        offTrack = false
        lastSessionId = null
        lapStats = null
        distToStart = null
        runStartedAt = System.currentTimeMillis()
        phase = Phase.WAITING_GPS
        loadBenchmark() // include i giri appena conclusi
        RecordingService.start(this, id)
        vibrate(longArrayOf(0, 60))
        render()
    }

    private fun openShare() {
        val id = lastSessionId ?: return
        startActivity(Intent(this, ShareResultActivity::class.java).putExtra(ShareResultActivity.EXTRA_SESSION_ID, id))
    }

    /** Medie e record del tracciato (in background). */
    private fun loadBenchmark() {
        val t = track ?: return
        benchmark = Sectors.Benchmark.empty(Sectors.count(t.splits.size))
        val appCtx = applicationContext
        thread(name = "benchmark") {
            val b = TrackStore.benchmark(appCtx, t)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                // Durante un giro i riferimenti restano quelli di inizio giro.
                if (phase != Phase.FINISHED) {
                    benchmark = b
                    render()
                }
            }
        }
    }

    /* ----------------------- Sincronizzazione servizio ------------------- */

    private fun syncWithService(svc: RecordingService) {
        if (svc.isRecording) {
            val st = svc.track ?: return
            if (track?.id != st.id) {
                track = st
                trackId = st.id
                loadBenchmark()
            }
            val eng = svc.engine ?: return
            splits.clear()
            splits.addAll(eng.splitTimes)
            offTrack = eng.isOffTrack
            phase = when (eng.state) {
                TimingEngine.State.ARMED -> if (svc.session?.samples.isNullOrEmpty()) Phase.WAITING_GPS else Phase.ARMED
                TimingEngine.State.RUNNING -> Phase.RUNNING
                TimingEngine.State.FINISHED -> Phase.FINISHED
            }
            render()
        } else if (phase == Phase.WAITING_GPS || phase == Phase.ARMED || phase == Phase.RUNNING) {
            // La sessione si è conclusa mentre la schermata non era visibile.
            if (runStartedAt == 0L) return
            recoverFromStore()
        } else if (track == null) {
            finish() // aperta dalla notifica senza sessione in corso
        }
    }

    private fun recoverFromStore() {
        val id = trackId ?: return
        val appCtx = applicationContext
        val since = runStartedAt
        thread(name = "recover") {
            val last = TrackStore.listSummaries(appCtx)
                .firstOrNull { it.trackId == id && it.startedAt >= since }
            val stats = last?.takeIf { it.status == SessionStatus.FINISHED }
                ?.let { TrackStore.getSession(appCtx, it.id) }?.let { LapStats.compute(it) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val r = last?.result
                if (last != null && last.status == SessionStatus.FINISHED && r != null) {
                    splits.clear()
                    splits.addAll(r.splits)
                    finalMs = r.elapsedMs
                    lastSessionId = last.id
                    lapStats = stats
                    phase = Phase.FINISHED
                } else {
                    phase = Phase.STOPPED
                }
                offTrack = false
                render()
            }
        }
    }

    /* --------------------------- Eventi servizio ------------------------- */

    override fun onFix(fix: GpsSample, engine: TimingEngine) {
        runOnUiThread {
            lastFix = fix
            stats[R.string.speed_label] = fix.speed?.let { Units.speedMs(it.toDouble()) } ?: "—"
            stats[R.string.ele_label] = fix.ele?.let { Units.length(it) } ?: "—"
            stats[R.string.acc_label] = fix.acc?.let { "±" + Units.length(it.toDouble()) } ?: "—"
            service?.lastMotion?.let { m ->
                stats[R.string.pitch_label] = m.pitch?.let { String.format(Locale.US, "%.0f°", it) } ?: "—"
                val g = sqrt(m.ax * m.ax + m.ay * m.ay + m.az * m.az) / 9.81f
                stats[R.string.g_label] = String.format(Locale.US, "%.2f g", g)
            }
            val sum = engine.summary()
            stats[R.string.offtime_label] = String.format(Locale.US, "%.1f s", sum.offTrackMs / 1000.0)
            stats[R.string.offcount_label] = sum.offTrackEpisodes.toString()

            if (engine.state == TimingEngine.State.ARMED) {
                if (phase == Phase.WAITING_GPS) phase = Phase.ARMED
                track?.let { tr ->
                    distToStart = Geo.haversine(fix.lat, fix.lon, tr.start.lat, tr.start.lon).toInt()
                }
            }
            render()
        }
    }

    override fun onRaceEvent(type: String, detail: SplitTime?) {
        runOnUiThread {
            when (type) {
                "start" -> {
                    phase = Phase.RUNNING
                    splits.clear()
                    vibrate(longArrayOf(0, 200))
                }
                "split" -> {
                    detail?.let { splits.add(it) }
                    vibrate(longArrayOf(0, 100, 50, 100))
                }
                "offtrack-enter" -> {
                    offTrack = true
                    vibrate(longArrayOf(0, 300, 100, 300))
                }
                "offtrack-exit" -> offTrack = false
                "finish" -> {
                    val svc = service
                    finalMs = svc?.engine?.elapsedMs()
                    svc?.engine?.splitTimes?.let { splits.clear(); splits.addAll(it) }
                    lastSessionId = svc?.session?.id
                    phase = Phase.FINISHED
                    offTrack = false
                    vibrate(longArrayOf(0, 400, 100, 400))
                }
            }
            render()
        }
    }

    override fun onHr(bpm: Int) {
        runOnUiThread {
            stats[R.string.hr_label] = "$bpm"
            statViews[R.string.hr_label]?.text = "$bpm"
        }
    }

    override fun onSessionSaved(finished: Boolean) {
        runOnUiThread {
            Toast.makeText(this, R.string.session_saved, Toast.LENGTH_SHORT).show()
            if (finished) {
                // Il risultato è completo solo ora: calcola il resoconto del giro.
                service?.session?.let { lapStats = LapStats.compute(it) }
                render()
            } else {
                finalMs = service?.engine?.takeIf { it.startTime != null }?.elapsedMs()
                phase = Phase.STOPPED
                offTrack = false
                render()
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (v?.hasVibrator() == true) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
