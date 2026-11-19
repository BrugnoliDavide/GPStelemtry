package com.gpstelemetry.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.gpstelemetry.core.Gate
import com.gpstelemetry.core.GpsSample
import com.gpstelemetry.core.PathPoint
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.SessionResult
import com.gpstelemetry.core.SessionStatus
import com.gpstelemetry.core.SplitGate
import com.gpstelemetry.core.SplitTime
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackType
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rendering delle schermate principali su JVM (Robolectric, grafica nativa):
 * salva i PNG in app/build/screenshots per la verifica visiva senza
 * emulatore, e controlla che le schermate si costruiscano senza errori.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenshotTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val outDir = File("build/screenshots").apply { mkdirs() }

    /* --------------------------- Dati di prova --------------------------- */

    private val lat0 = 45.0
    private val lon0 = 9.0
    private val mLat = 111_320.0
    private val mLon = 111_320.0 * cos(lat0 * PI / 180)

    private fun radius(th: Double) = 300 * (1 + 0.28 * sin(2 * th) + 0.12 * cos(3 * th))
    private fun xy(u: Double): Pair<Double, Double> {
        val th = -u * 2 * PI
        return radius(th) * cos(th) to radius(th) * sin(th)
    }
    private fun ll(u: Double): Pair<Double, Double> {
        val (x, y) = xy(u)
        return lat0 + y / mLat to lon0 + x / mLon
    }
    private fun gate(u: Double): Gate {
        val (ax, ay) = xy(u)
        val (bx, by) = xy(u + 0.001)
        val bearing = (atan2(bx - ax, by - ay) * 180 / PI + 360) % 360
        val (lat, lon) = ll(u)
        return Gate(lat, lon, (bearing + 90) % 360, 60.0)
    }

    private lateinit var track: Track
    private lateinit var live: Session

    @Before
    fun seed() {
        Settings.setTutorialDone(ctx, true) // le schermate principali, non il tutorial
        track = Track(
            id = "demo", name = "Circuito Demo", type = TrackType.CIRCULAR, tolerance = 20.0,
            createdAt = 1, start = gate(0.0), finish = null,
            splits = mutableListOf(SplitGate("Parziale 1", gate(1 / 3.0)), SplitGate("Parziale 2", gate(2 / 3.0))),
            path = MutableList(360) { i -> ll(i / 360.0).let { PathPoint(it.first, it.second) } },
        )
        TrackStore.saveTrack(ctx, track)
        TrackStore.saveTrack(
            ctx,
            Track(
                "open", "Salita del Passo", TrackType.OPEN, 15.0, 0, Gate(45.01, 9.01), Gate(45.02, 9.03),
                path = MutableList(60) { i ->
                    PathPoint(45.01 + i * 0.00017, 9.01 + i * 0.00034 + 0.0006 * sin(i / 4.0))
                },
            ),
        )
        val hist = listOf(
            listOf(15400L, 14800L, 16000L), listOf(15000L, 15200L, 15600L),
            listOf(15800L, 14600L, 16400L), listOf(15200L, 15000L, 15900L),
        )
        hist.forEachIndexed { k, s ->
            TrackStore.saveSession(ctx, session("h$k", 1_700_000_000_000L + k * 86_400_000L, s, withSamples = false))
        }
        live = session("live", 1_800_000_000_000L, listOf(14500L, 15800L, 15700L), withSamples = true)
        live.vehicleType = com.gpstelemetry.core.VehicleType.MOTORCYCLE
        live.vehicle = "Honda CBR1000RR"
        TrackStore.saveSession(ctx, live)
    }

    private fun session(id: String, st: Long, sectors: List<Long>, withSamples: Boolean): Session {
        val e1 = sectors[0]
        val e2 = e1 + sectors[1]
        val tot = e2 + sectors[2]
        val s = Session(
            id = id, trackId = track.id, name = "Circuito Demo — $id", startedAt = st,
            trackType = track.type, trackStart = track.start, trackFinish = null, trackSplits = track.splits,
        )
        if (withSamples) {
            var t = st
            for ((i, ms) in sectors.withIndex()) {
                val steps = (ms / 500).toInt()
                for (k in 0 until steps) {
                    val u = i / 3.0 + (k.toDouble() / steps) / 3.0
                    val (lat, lon) = ll(u)
                    s.samples.add(
                        GpsSample(
                            t, lat, lon, ele = 210.0 + 14.0 * sin(u * 2 * PI), acc = 4f,
                            speed = 40f + 8f * sin(u * 12).toFloat(),
                        )
                    )
                    t += 500
                }
            }
            ll(1.0).let { s.samples.add(GpsSample(st + tot, it.first, it.second, speed = 40f)) }
            // Telefono a riposo prima della partenza, poi pieghe in curva.
            for (k in -20 until (tot / 100).toInt()) {
                val roll = if (k < 0) -3f else -3f + 38f * sin(k / 40.0).toFloat()
                s.motion.add(com.gpstelemetry.core.MotionSample(st + k * 100L, 0f, 0f, 9.8f, pitch = 6f, roll = roll))
            }
            for (k in 0 until 46) s.hr.add(com.gpstelemetry.core.HrSample(st + k * 1000L, 150 + k % 9))
        }
        s.status = SessionStatus.FINISHED
        s.result = SessionResult(
            SessionStatus.FINISHED, st, st + tot, tot,
            listOf(SplitTime("Parziale 1", st + e1, e1), SplitTime("Parziale 2", st + e2, e2)),
            offTrackMs = 1200, offTrackEpisodes = 1,
        )
        return s
    }

    /* ------------------------------ Utilità ------------------------------ */

    private fun idle() {
        Thread.sleep(400) // thread in background (record, medie)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun shot(activity: Activity, name: String) {
        val root: View = activity.window.decorView
        val w = activity.resources.displayMetrics.widthPixels
        val h = activity.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        save(bmp, name)
    }

    /** Fotografa il dialogo aperto per ultimo (finestra separata dall'activity). */
    private fun shotDialog(activity: Activity, name: String) {
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()!!
        val root = d.window!!.decorView
        val w = activity.resources.displayMetrics.widthPixels
        val h = activity.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, w, root.measuredHeight)
        val bmp = Bitmap.createBitmap(w, root.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(android.graphics.Color.DKGRAY) }.also { root.draw(it) }
        save(bmp, name)
    }

    private fun save(bmp: Bitmap, name: String) {
        val f = File(outDir, "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 0)
    }

    private fun TimerActivity.inject(phase: String, splitCount: Int, finalMs: Long?, clock: String? = null) {
        val cls = TimerActivity::class.java
        fun set(name: String, v: Any?) = cls.getDeclaredField(name).apply { isAccessible = true }.set(this, v)
        val phaseCls = Class.forName("com.gpstelemetry.app.TimerActivity\$Phase")
        set("phase", phaseCls.enumConstants!!.first { (it as Enum<*>).name == phase })
        set("benchmark", TrackStore.benchmark(ctx, track, excludeId = "live"))
        @Suppress("UNCHECKED_CAST")
        val splits = cls.getDeclaredField("splits").apply { isAccessible = true }.get(this) as MutableList<SplitTime>
        splits.clear()
        splits.addAll(live.result!!.splits.take(splitCount))
        set("finalMs", finalMs)
        set("lastSessionId", "live")
        set("distToStart", 120)
        set("lapStats", if (phase == "FINISHED") com.gpstelemetry.core.LapStats.compute(TrackStore.getSession(ctx, "live")!!) else null)
        @Suppress("UNCHECKED_CAST")
        val stats = cls.getDeclaredField("stats").apply { isAccessible = true }.get(this) as MutableMap<Int, String>
        stats[R.string.speed_label] = "148 km/h"; stats[R.string.g_label] = "1.32 g"; stats[R.string.hr_label] = "156"
        stats[R.string.pitch_label] = "4°"; stats[R.string.ele_label] = "212 m"; stats[R.string.offtime_label] = "1.2 s"
        stats[R.string.offcount_label] = "1"; stats[R.string.acc_label] = "±3 m"
        cls.getDeclaredMethod("render").apply { isAccessible = true }.invoke(this)
        clock?.let { findViewById<TextView>(R.id.tmClock).text = it }
    }

    private fun timer(): TimerActivity {
        val intent = android.content.Intent(ctx, TimerActivity::class.java).putExtra(TimerActivity.EXTRA_TRACK_ID, track.id)
        return Robolectric.buildActivity(TimerActivity::class.java, intent).setup().get().also { idle() }
    }

    /* ------------------------------- Test -------------------------------- */

    @Test
    fun mainDark() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        shot(a, "01_main_dark")
    }

    @Test
    fun mainLight() {
        // AppCompat ricreerebbe anche le activity rimaste dai test precedenti
        // (registro statico): lo svuotiamo prima di cambiare tema.
        fun forgetOldActivities() {
            val f = androidx.appcompat.app.AppCompatDelegate::class.java.getDeclaredField("sActivityDelegates")
            f.isAccessible = true
            (f.get(null) as MutableCollection<*>).clear()
        }
        forgetOldActivities()
        ThemePrefs.set(ctx, ThemePrefs.Mode.LIGHT)
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            idle()
            shot(a, "02_main_light")
        } finally {
            forgetOldActivities()
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(ThemePrefs.Mode.DARK.nightMode)
        }
    }

    @Test
    fun timerIdlePortrait() {
        val a = timer()
        a.inject("IDLE", 0, null)
        shot(a, "03_timer_idle")
    }

    @Test
    fun timerRunningPortrait() {
        val a = timer()
        a.inject("RUNNING", 1, null, clock = "00:21.4")
        shot(a, "04_timer_running")
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun timerRunningLandscape() {
        val a = timer()
        a.inject("RUNNING", 2, null, clock = "00:37.9")
        shot(a, "05_timer_running_land")
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun timerFinishedLandscape() {
        val a = timer()
        a.inject("FINISHED", 2, 46_000L)
        shot(a, "06_timer_finished_land")
    }

    @Test
    fun timerFinishedPortrait() {
        val a = timer()
        a.inject("FINISHED", 2, 46_000L)
        shot(a, "07_timer_finished")
    }

    @Test
    fun shareCard() {
        val bench = TrackStore.benchmark(ctx, track, excludeId = "live")
        save(ShareCard.render(TrackStore.getSession(ctx, "live")!!, track, bench), "08_share_card")
    }

    @Test
    fun sessionsList() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.selectTab(R.id.nav_sessions)
        idle()
        shot(a, "09_sessions")
    }

    /* --------------------------- Community (mock) ------------------------ */

    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): T {
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Result<T>? = null
        call { out = it; latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return out!!.getOrThrow()
    }

    /** Server simulato senza ritardo, con il tracciato demo pubblicato e alcuni giri. */
    private fun seedCommunity(): com.gpstelemetry.core.UserAccount {
        val api = MockCommunityApi(ctx, latencyMs = 0)
        Community.api = api
        val me = await<com.gpstelemetry.core.UserAccount> { api.registerNickname("davide_rc", null, it) }
        Profile.saveAccount(ctx, me)
        val p = await<com.gpstelemetry.core.PublishedTrack> { api.publishTrack(me, track, it) }
        TrackStore.saveTrack(ctx, track.copy(published = true, remoteId = p.id))
        val anna = await<com.gpstelemetry.core.UserAccount> { api.registerNickname("anna_rc", null, it) }
        val luca = await<com.gpstelemetry.core.UserAccount> { api.registerNickname("luca.b", null, it) }
        fun lap(ms: Long, t: com.gpstelemetry.core.VehicleType, m: String) =
            com.gpstelemetry.core.LapSubmission(p.id, ms, listOf(ms / 3, ms / 3, ms - 2 * (ms / 3)), 0, m, t)
        await<com.gpstelemetry.core.LeaderboardEntry> { api.submitLap(anna, lap(44_120, com.gpstelemetry.core.VehicleType.MOTORCYCLE, "Ducati Panigale V4"), it) }
        await<com.gpstelemetry.core.LeaderboardEntry> { api.submitLap(me, lap(45_800, com.gpstelemetry.core.VehicleType.MOTORCYCLE, "Honda CBR1000RR"), it) }
        await<com.gpstelemetry.core.LeaderboardEntry> { api.submitLap(luca, lap(46_310, com.gpstelemetry.core.VehicleType.CAR, "Porsche 911 GT3"), it) }
        val other = Track(
            "x", "Anello Colline", TrackType.CIRCULAR, 15.0, 0, gate(0.5), null,
            path = MutableList(120) { i -> ll(i / 120.0).let { PathPoint(it.first + 0.01, it.second + 0.01) } },
        )
        await<com.gpstelemetry.core.PublishedTrack> { api.publishTrack(anna, other, it) }
        return me
    }

    @Test
    fun explore() {
        seedCommunity()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.selectTab(R.id.nav_explore)
        idle()
        shot(a, "10_explore")
    }

    @Test
    fun profile() {
        val me = seedCommunity()
        val a = Robolectric.buildActivity(ProfileActivity::class.java).setup().get()
        idle()
        shot(a, "11_profile")
        // Nickname già in uso: esito negativo con suggerimenti.
        a.findViewById<android.widget.EditText>(R.id.edNickname).setText("Marta")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        idle()
        shot(a, "12_profile_taken")
        assertTrue(me.nickname.isNotEmpty())
    }

    @Test
    fun sessionDetail() {
        seedCommunity()
        val s = TrackStore.getSession(ctx, "live")!!.apply {
            vehicleType = com.gpstelemetry.core.VehicleType.MOTORCYCLE
            vehicle = "Honda CBR1000RR"
        }
        TrackStore.saveSession(ctx, s)
        val intent = android.content.Intent(ctx, SessionDetailActivity::class.java)
            .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, "live")
        val a = Robolectric.buildActivity(SessionDetailActivity::class.java, intent).setup().get()
        idle()
        shot(a, "13_session_detail")
    }

    @Test
    fun editor() {
        val intent = android.content.Intent(ctx, TrackEditorActivity::class.java)
            .putExtra(TrackEditorActivity.EXTRA_TRACK_ID, track.id)
        val a = Robolectric.buildActivity(TrackEditorActivity::class.java, intent).setup().get()
        // Lascia il tempo di scaricare le immagini satellitari (se c'è rete).
        repeat(12) { idle() }
        shot(a, "14_editor")
        TrackEditorActivity::class.java.getDeclaredMethod("select", String::class.java)
            .apply { isAccessible = true }.invoke(a, "split:0")
        repeat(12) { idle() }
        shot(a, "15_editor_gate")
    }

    @Test
    fun profileSettings() {
        seedCommunity()
        Profile.setDefaultVehicle(ctx, com.gpstelemetry.core.VehicleType.MOTORCYCLE, "Honda CBR1000RR")
        val a = Robolectric.buildActivity(ProfileActivity::class.java).setup().get()
        idle()
        // Fa scorrere fino alle impostazioni (unità, notifiche, classifiche, dati).
        val scroll = a.findViewById<View>(R.id.unitsGroup).let {
            var v: android.view.View = it
            while (v !is android.widget.ScrollView) v = v.parent as android.view.View
            v
        }
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        a.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        a.window.decorView.layout(0, 0, w, h)
        scroll.scrollTo(0, a.findViewById<View>(R.id.unitsGroup).let { (it.parent as View).top } - 40)
        shot(a, "16_profile_settings")
    }

    @Test
    fun sessionDetailImperial() {
        Settings.setUnits(ctx, UnitSystem.IMPERIAL)
        try {
            val intent = android.content.Intent(ctx, SessionDetailActivity::class.java)
                .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, "live")
            val a = Robolectric.buildActivity(SessionDetailActivity::class.java, intent).setup().get()
            idle()
            shot(a, "17_session_detail_imperial")
            save(ShareCard.render(TrackStore.getSession(ctx, "live")!!, track, TrackStore.benchmark(ctx, track, excludeId = "live")), "18_share_card_imperial")
        } finally {
            Units.system = UnitSystem.METRIC
        }
    }

    /* ------------------------- Neutralizzazioni -------------------------- */

    /**
     * Tracciato con una neutralizzazione tra 0.5 e 0.62 del giro: settori
     * S1, S2, N1 (neutro, 20 s reali) e S3. Tempo effettivo 39 s.
     */
    private fun seedNeutral(): Pair<Track, Session> {
        val t = track.copy(
            id = "neutro", name = "Circuito con Neutralizzazione",
            splits = mutableListOf(
                SplitGate("Parziale 1", gate(0.3)),
                SplitGate("Neutralizzazione 1", gate(0.5), neutral = true),
                SplitGate("Fine neutralizzazione 1", gate(0.62)),
            ),
        )
        TrackStore.saveTrack(ctx, t)
        val st = 1_800_000_500_000L
        val s = Session(
            id = "nlive", trackId = t.id, name = "${t.name} — nlive", startedAt = st,
            trackType = t.type, trackStart = t.start, trackFinish = null, trackSplits = t.splits,
        )
        // Traiettoria: 0.5 s per campione, tempo reale proporzionale al tratto.
        val marks = listOf(0.0 to 0L, 0.3 to 14_000L, 0.5 to 26_000L, 0.62 to 46_000L, 1.0 to 59_000L)
        for ((a, b) in marks.zipWithNext()) {
            val steps = ((b.second - a.second) / 500).toInt()
            for (k in 0 until steps) {
                val u = a.first + (b.first - a.first) * k / steps
                val (lat, lon) = ll(u)
                s.samples.add(GpsSample(st + a.second + k * 500L, lat, lon, ele = 210.0, speed = 40f))
            }
        }
        s.status = SessionStatus.FINISHED
        s.result = SessionResult(
            SessionStatus.FINISHED, st, st + 59_000, 39_000,
            listOf(
                SplitTime("Parziale 1", st + 14_000, 14_000),
                SplitTime("Neutralizzazione 1", st + 26_000, 26_000),
                SplitTime("Fine neutralizzazione 1", st + 46_000, 26_000, neutral = true, neutralMs = 20_000),
            ),
            offTrackMs = 0, offTrackEpisodes = 0, neutralMs = 20_000,
        )
        TrackStore.saveSession(ctx, s)
        return t to s
    }

    private fun TimerActivity.injectNeutral(phase: String, splits: List<SplitTime>, finalMs: Long?, inNeutral: Boolean, clock: String?) {
        val cls = TimerActivity::class.java
        fun set(name: String, v: Any?) = cls.getDeclaredField(name).apply { isAccessible = true }.set(this, v)
        val phaseCls = Class.forName("com.gpstelemetry.app.TimerActivity\$Phase")
        set("phase", phaseCls.enumConstants!!.first { (it as Enum<*>).name == phase })
        set("inNeutral", inNeutral)
        set("finalMs", finalMs)
        set("lastSessionId", "nlive")
        set("lapStats", if (phase == "FINISHED") com.gpstelemetry.core.LapStats.compute(TrackStore.getSession(ctx, "nlive")!!) else null)
        @Suppress("UNCHECKED_CAST")
        val list = cls.getDeclaredField("splits").apply { isAccessible = true }.get(this) as MutableList<SplitTime>
        list.clear()
        list.addAll(splits)
        cls.getDeclaredMethod("render").apply { isAccessible = true }.invoke(this)
        clock?.let { findViewById<TextView>(R.id.tmClock).text = it }
    }

    private fun neutralTimer(): TimerActivity {
        val intent = android.content.Intent(ctx, TimerActivity::class.java).putExtra(TimerActivity.EXTRA_TRACK_ID, "neutro")
        return Robolectric.buildActivity(TimerActivity::class.java, intent).setup().get().also { idle() }
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun timerInNeutralLandscape() {
        val (_, s) = seedNeutral()
        val a = neutralTimer()
        a.injectNeutral("RUNNING", s.result!!.splits.take(2), null, inNeutral = true, clock = "00:26.0")
        shot(a, "30_timer_neutral_land")
    }

    @Test
    fun timerInNeutralPortrait() {
        val (_, s) = seedNeutral()
        val a = neutralTimer()
        a.injectNeutral("RUNNING", s.result!!.splits.take(2), null, inNeutral = true, clock = "00:26.0")
        shot(a, "31_timer_neutral")
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun timerFinishedWithNeutralLandscape() {
        val (_, s) = seedNeutral()
        val a = neutralTimer()
        a.injectNeutral("FINISHED", s.result!!.splits, 39_000L, inNeutral = false, clock = null)
        shot(a, "32_timer_neutral_finished_land")
    }

    @Test
    fun neutralShareDetailAndEditor() {
        val (t, s) = seedNeutral()
        save(ShareCard.render(s, t, TrackStore.benchmark(ctx, t, excludeId = s.id)), "33_share_card_neutral")
        val detail = Robolectric.buildActivity(
            SessionDetailActivity::class.java,
            android.content.Intent(ctx, SessionDetailActivity::class.java).putExtra(SessionDetailActivity.EXTRA_SESSION_ID, s.id),
        ).setup().get()
        idle()
        shot(detail, "34_session_detail_neutral")
        val a = Robolectric.buildActivity(
            TrackEditorActivity::class.java,
            android.content.Intent(ctx, TrackEditorActivity::class.java).putExtra(TrackEditorActivity.EXTRA_TRACK_ID, t.id),
        ).setup().get()
        repeat(12) { idle() }
        TrackEditorActivity::class.java.getDeclaredMethod("select", String::class.java)
            .apply { isAccessible = true }.invoke(a, "split:1")
        repeat(6) { idle() }
        shot(a, "35_editor_neutral")
    }

    @Test
    fun friends() {
        val me = seedCommunity()
        val api = Community.api
        val pid = TrackStore.listTracks(ctx).first { it.remoteId != null }.remoteId!!
        val anna = await<com.gpstelemetry.core.DriverProfile?> { api.findDriver("anna_rc", me, it) }!!
        val luca = await<com.gpstelemetry.core.DriverProfile?> { api.findDriver("luca.b", me, it) }!!
        val marta = await<com.gpstelemetry.core.DriverProfile?> { api.findDriver("marta", me, it) }!!
        listOf(anna, luca, marta).forEach { FriendsStore.add(ctx, com.gpstelemetry.core.Friend(it.userId, it.nickname)) }
        assertTrue(FriendsStore.isFriend(ctx, "ANNA_RC"))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        FriendsUi.showTimes(a, pid, "Pista demo")
        repeat(3) { idle() }
        shotDialog(a, "36_friends_times")
        FriendsUi.manage(a)
        idle()
        shotDialog(a, "37_friends_manage")
    }

    @Test
    fun publishedTrack() {
        val me = seedCommunity()
        val api = Community.api
        // Tracciato con quota e nomi dei settori, giri in categorie diverse.
        val t = track.copy(
            id = "lago", name = "Anello del Lago",
            splits = mutableListOf(SplitGate("Esse del lago", gate(1 / 3.0)), SplitGate("Tornante", gate(2 / 3.0))),
            path = MutableList(360) { i -> ll(i / 360.0).let { PathPoint(it.first, it.second, 210 + 18 * sin(i / 360.0 * 2 * PI)) } },
        )
        val p = await<com.gpstelemetry.core.PublishedTrack> { api.publishTrack(me, t, it) }
        TrackStore.saveTrack(ctx, t.copy(published = true, remoteId = p.id))
        fun lap(s: List<Long>, type: com.gpstelemetry.core.VehicleType, model: String) =
            com.gpstelemetry.core.LapSubmission(p.id, s.sum(), s, 0, model, type)
        val moto = com.gpstelemetry.core.VehicleType.MOTORCYCLE
        val kart = com.gpstelemetry.core.VehicleType.KART
        val others = listOf(
            "anna_rc" to lap(listOf(14_210, 15_020, 14_890), moto, "Ducati Panigale V4"),
            "marco_kart" to lap(listOf(15_400, 14_650, 15_300), kart, "Tony Kart Rotax"),
            "giulia.r" to lap(listOf(15_980, 15_870, 15_120), moto, "Yamaha R6"),
            "teo_99" to lap(listOf(16_300, 16_100, 15_900), kart, "Birel"),
        )
        others.forEach { (nick, l) ->
            val acc = runCatching { await<com.gpstelemetry.core.UserAccount> { api.registerNickname(nick, null, it) } }.getOrNull()
                ?: return@forEach
            await<com.gpstelemetry.core.LeaderboardEntry> { api.submitLap(acc, l, it) }
        }
        await<com.gpstelemetry.core.LeaderboardEntry> { api.submitLap(me, lap(listOf(14_600, 15_100, 14_700), moto, "Honda CBR1000RR"), it) }
        listOf("marco_kart", "giulia.r").forEach { n ->
            val d = await<com.gpstelemetry.core.DriverProfile?> { api.findDriver(n, me, it) }!!
            FriendsStore.add(ctx, com.gpstelemetry.core.Friend(d.userId, d.nickname))
        }

        val a = Robolectric.buildActivity(
            PublishedTrackActivity::class.java,
            android.content.Intent(ctx, PublishedTrackActivity::class.java).putExtra(PublishedTrackActivity.EXTRA_ID, p.id),
        ).setup().get()
        repeat(3) { idle() }
        shot(a, "38_track_top")
        val scroll = a.findViewById<android.widget.ScrollView>(R.id.ptScroll)
        scroll.scrollTo(0, 100_000)
        idle()
        shot(a, "39_track_board_all")
        a.findViewById<android.view.View>(R.id.ptModeCategory).performClick()
        repeat(2) { idle() }
        val chips = a.findViewById<com.google.android.material.chip.ChipGroup>(R.id.ptCategories)
        (0 until chips.childCount).map { chips.getChildAt(it) }.first { it.tag == kart }.performClick()
        repeat(2) { idle() }
        scroll.scrollTo(0, 100_000)
        idle()
        shot(a, "40_track_board_kart")
        a.findViewById<android.view.View>(R.id.ptModeFriends).performClick()
        repeat(2) { idle() }
        scroll.scrollTo(0, 100_000)
        idle()
        shot(a, "41_track_board_friends")
        val board = a.findViewById<android.widget.LinearLayout>(R.id.ptBoard)
        assertTrue("amici in classifica", board.childCount >= 3)
    }
}
