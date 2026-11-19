package com.gpstelemetry.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.gpstelemetry.core.FriendsBoard
import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.PublishedTrack
import com.gpstelemetry.core.SectorBoard
import com.gpstelemetry.core.TrackFacts
import com.gpstelemetry.core.TrackType
import com.gpstelemetry.core.VehicleType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Scheda di un tracciato pubblicato (da Esplora):
 *  - forma e dati del tracciato (lunghezza, dislivello, quote, settori…);
 *  - migliori settori e giro ideale, con i miei settori a confronto;
 *  - classifica: TUTTI, per CATEGORIA di veicolo, o solo AMICI.
 * I settori seguono la classifica scelta (es. solo Kart, solo amici).
 */
class PublishedTrackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "published_id"

        /** Tracciato già caricato da Esplora: la scheda si apre subito. */
        private val cache = mutableMapOf<String, PublishedTrack>()

        fun open(ctx: Context, p: PublishedTrack) {
            cache[p.id] = p
            ctx.startActivity(Intent(ctx, PublishedTrackActivity::class.java).putExtra(EXTRA_ID, p.id))
        }
    }

    private enum class Mode { ALL, CATEGORY, FRIENDS }

    private lateinit var id: String
    private var track: PublishedTrack? = null
    private var mode = Mode.ALL
    private var category: VehicleType = VehicleType.CAR
    private var seq = 0

    private lateinit var sectors: LinearLayout
    private lateinit var board: LinearLayout
    private lateinit var boardInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_published_track)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        sectors = findViewById(R.id.ptSectors)
        board = findViewById(R.id.ptBoard)
        boardInfo = findViewById(R.id.ptBoardInfo)

        setupModes()
        findViewById<View>(R.id.ptManageFriends).setOnClickListener { FriendsUi.manage(this) { loadBoard() } }

        cache[id]?.let { bindTrack(it); loadBoard() }
        // Dati aggiornati dal server (piloti, podio, eventuali modifiche).
        Community.api.getTrack(id, Profile.account(this)) { r ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { p ->
                    val first = track == null
                    cache[id] = p
                    bindTrack(p)
                    if (first) loadBoard()
                }.onFailure {
                    if (track == null) {
                        Toast.makeText(this, getString(R.string.server_error, it.message ?: ""), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        track?.let { bindImport(it) }
    }

    /* ------------------------------ Tracciato ---------------------------- */

    private fun bindTrack(p: PublishedTrack) {
        track = p
        findViewById<TextView>(R.id.ptName).text = p.track.name
        findViewById<TextView>(R.id.ptAuthor).text = getString(R.string.explore_author_fmt, p.author).uppercase(Locale.ITALY)
        findViewById<TrackShapeView>(R.id.ptShape).apply {
            lineWidthDp = 3f
            setTrack(p.track)
        }
        val meta = mutableListOf(
            getString(R.string.pt_published_on, SimpleDateFormat("d MMM yyyy", Locale.ITALY).format(Date(p.publishedAt))),
        )
        p.distanceM?.let { meta += getString(R.string.pt_distance_from_you, Fmt.dist(it)) }
        findViewById<TextView>(R.id.ptMeta).text = meta.joinToString(" · ")

        val f = TrackFacts.of(p.track)
        val tiles = listOf(
            Summary.Tile(getString(R.string.pt_length), f.lengthM?.let { Fmt.dist(it) } ?: "—"),
            Summary.Tile(getString(R.string.sum_elev), f.elevGainM?.let { Units.elevation(it, f.elevLossM ?: 0.0) } ?: "—"),
            Summary.Tile(
                getString(R.string.pt_altitude),
                if (f.minEleM != null && f.maxEleM != null) {
                    String.format(Locale.US, "%.0f–", Units.lengthValue(f.minEleM!!)) + Units.length(f.maxEleM!!)
                } else "—",
            ),
            Summary.Tile(getString(R.string.pt_type), getString(if (p.track.type == TrackType.CIRCULAR) R.string.pt_type_circuit else R.string.pt_type_open)),
            Summary.Tile(
                getString(R.string.pt_sectors),
                if (f.neutralZones > 0) "${f.timedSectors} + ${f.neutralZones}N" else f.timedSectors.toString(),
            ),
            Summary.Tile(getString(R.string.pt_drivers), p.drivers.toString()),
        )
        Summary.fill(findViewById(R.id.ptFacts), tiles, columns = 3, valueSp = 17f)
        bindImport(p)
    }

    /** "Aggiungi ai miei tracciati", oppure "Cronometrati qui" se c'è già. */
    private fun bindImport(p: PublishedTrack) {
        val btn = findViewById<MaterialButton>(R.id.ptImport)
        val local = TrackStore.listTracks(this).find { it.remoteId == p.id }
        if (local != null) {
            btn.setText(R.string.pt_time_here)
            btn.setOnClickListener {
                startActivity(Intent(this, TimerActivity::class.java).putExtra(TimerActivity.EXTRA_TRACK_ID, local.id))
            }
        } else {
            btn.setText(R.string.explore_import)
            btn.setOnClickListener {
                val copy = p.track.copy(
                    id = TrackStore.newId(),
                    createdAt = System.currentTimeMillis(),
                    published = false,
                    remoteId = p.id,
                    remoteAuthor = p.author,
                )
                TrackStore.saveTrack(this, copy)
                Toast.makeText(this, getString(R.string.explore_imported, p.track.name), Toast.LENGTH_LONG).show()
                bindImport(p)
            }
        }
    }

    /* ------------------------------ Classifica --------------------------- */

    private fun setupModes() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.ptBoardMode)
        group.check(R.id.ptModeAll)
        group.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.ptModeCategory -> Mode.CATEGORY
                R.id.ptModeFriends -> Mode.FRIENDS
                else -> Mode.ALL
            }
            loadBoard()
        }
        // Categoria iniziale: quella del veicolo predefinito, se impostato.
        Profile.defaultVehicle(this)?.first?.let { category = it }
        val chips = findViewById<ChipGroup>(R.id.ptCategories)
        VehicleType.entries.forEach { t ->
            chips.addView(Chip(this).glass().apply {
                id = View.generateViewId()
                tag = t
                text = t.label
                isCheckable = true
                isChecked = t == category
                setOnClickListener {
                    category = t
                    for (i in 0 until chips.childCount) (chips.getChildAt(i) as Chip).isChecked = chips.getChildAt(i).tag == t
                    loadBoard()
                }
            })
        }
    }

    private fun loadBoard() {
        val p = track ?: return
        val mySeq = ++seq
        findViewById<View>(R.id.ptCategoryScroll).visibility = if (mode == Mode.CATEGORY) View.VISIBLE else View.GONE
        findViewById<View>(R.id.ptManageFriends).visibility = if (mode == Mode.FRIENDS) View.VISIBLE else View.GONE
        boardInfo.text = getString(R.string.pt_loading)
        board.removeAllViews()
        val me = Profile.account(this)
        when (mode) {
            Mode.FRIENDS -> {
                val friends = FriendsStore.list(this)
                if (friends.isEmpty()) {
                    boardInfo.setText(R.string.friends_empty)
                    showSectors(emptyList(), getString(R.string.pt_sectors_friends))
                    return
                }
                Community.api.friendsTimes(p.id, friends, me) { r ->
                    runOnUiThread {
                        if (isDestroyed || mySeq != seq) return@runOnUiThread
                        r.onSuccess { fb -> showFriends(fb) }.onFailure { showError(it) }
                    }
                }
            }
            else -> {
                val type = if (mode == Mode.CATEGORY) category else null
                Community.api.leaderboard(p.id, me, type) { r ->
                    runOnUiThread {
                        if (isDestroyed || mySeq != seq) return@runOnUiThread
                        r.onSuccess { list -> showBoard(list, type) }.onFailure { showError(it) }
                    }
                }
            }
        }
    }

    private fun showError(e: Throwable) {
        boardInfo.text = getString(R.string.server_error, e.message ?: "")
    }

    private fun showBoard(list: List<LeaderboardEntry>, type: VehicleType?) {
        board.removeAllViews()
        boardInfo.text = when {
            list.isEmpty() && type != null -> getString(R.string.pt_no_times_category, type.label)
            list.isEmpty() -> getString(R.string.explore_no_times)
            else -> getString(R.string.friends_board_hint)
        }
        val leader = list.firstOrNull()?.lapMs
        list.forEach { e ->
            board.addView(boardRow(e, leader, showVehicle = type == null).apply {
                if (!e.isMe) setOnLongClickListener { FriendsUi.toggle(this@PublishedTrackActivity, e.driver); true }
            })
        }
        showSectors(list, type?.let { getString(R.string.pt_sectors_category, it.label) } ?: getString(R.string.pt_sectors_all))
    }

    private fun showFriends(fb: FriendsBoard) {
        board.removeAllViews()
        boardInfo.text = ""
        FriendsUi.fillTimes(this, board, fb)
        // Nomi cambiati sul server: aggiorna la lista locale.
        FriendsStore.list(this).let { l ->
            com.gpstelemetry.core.Friends.refreshNames(l, fb).takeIf { it != l }?.let { FriendsStore.save(this, it) }
        }
        val entries = fb.friends.mapNotNull { it.entry } + listOfNotNull(fb.me)
        showSectors(entries, getString(R.string.pt_sectors_friends))
    }

    /** Riga di classifica: posizione, pilota (veicolo), distacco dal primo, tempo. */
    private fun boardRow(e: LeaderboardEntry, leaderMs: Long?, showVehicle: Boolean): View {
        val medal = when (e.rank) {
            1 -> R.color.medal_gold
            2 -> R.color.medal_silver
            3 -> R.color.medal_bronze
            else -> R.color.text_secondary
        }
        val row = row()
        row.addView(text(e.rank.toString(), 15f, medal, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        val name = e.vehicleLabel?.takeIf { showVehicle }?.let { "${e.driver}  ·  $it" } ?: e.vehicle?.let { "${e.driver}  ·  $it" } ?: e.driver
        row.addView(text(name, 15f, if (e.isMe) R.color.accent else R.color.text_primary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (leaderMs != null && e.rank > 1) {
            row.addView(text(signed(e.lapMs - leaderMs), 13f, R.color.text_secondary).apply {
                fontFeatureSettings = "tnum"
                setPadding(dp(8), 0, dp(10), 0)
            })
        }
        row.addView(text(Fmt.time(e.lapMs), 16f, if (e.rank == 1) R.color.sector_best else R.color.text_primary,
            Typeface.create("sans-serif-condensed", Typeface.BOLD)).apply { fontFeatureSettings = "tnum" })
        return row
    }

    /* ------------------------------ Settori ------------------------------ */

    private fun showSectors(entries: List<LeaderboardEntry>, caption: String) {
        val p = track ?: return
        sectors.removeAllViews()
        val sb = SectorBoard.of(p.track, entries)
        val hidden = entries.isNotEmpty() && entries.all { it.sectorsMs.isEmpty() }
        sectors.addView(text(
            when {
                hidden -> getString(R.string.pt_sectors_hidden)
                entries.isEmpty() -> getString(R.string.pt_sectors_none)
                else -> caption
            },
            13f, R.color.text_secondary,
        ).apply { setPadding(0, 0, 0, dp(4)) })
        if (hidden) return
        sb.rows.forEach { r ->
            val row = row()
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(listOfNotNull(r.label, r.name).joinToString("  ·  "), 15f, R.color.text_primary).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            r.bestDriver?.let { col.addView(text(it, 12f, R.color.text_secondary).apply { maxLines = 1 }) }
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.gapMs?.let { g ->
                row.addView(text(if (g == 0L) getString(R.string.pt_purple_mine) else signed(g), 13f,
                    if (g == 0L) R.color.sector_best else R.color.text_secondary).apply {
                    fontFeatureSettings = "tnum"
                    setPadding(dp(8), 0, dp(10), 0)
                })
            }
            row.addView(text(Fmt.time(r.bestMs), 16f, if (r.bestMs != null) R.color.sector_best else R.color.text_secondary,
                Typeface.create("sans-serif-condensed", Typeface.BOLD)).apply { fontFeatureSettings = "tnum" })
            sectors.addView(row)
        }
        sb.idealLapMs?.let { ideal ->
            val row = row()
            row.addView(text(getString(R.string.pt_ideal_lap), 15f, R.color.text_primary, Typeface.DEFAULT_BOLD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sb.myLapMs?.let { mine ->
                row.addView(text(signed(mine - ideal), 13f, R.color.text_secondary).apply {
                    fontFeatureSettings = "tnum"
                    setPadding(dp(8), 0, dp(10), 0)
                })
            }
            row.addView(text(Fmt.time(ideal), 16f, R.color.text_primary, Typeface.create("sans-serif-condensed", Typeface.BOLD))
                .apply { fontFeatureSettings = "tnum" })
            sectors.addView(row)
        }
    }

    /* ------------------------------ Viste -------------------------------- */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun text(s: String, sp: Float, color: Int, tf: Typeface? = null) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(ContextCompat.getColor(this@PublishedTrackActivity, color))
        tf?.let { typeface = it }
    }

    private fun signed(ms: Long): String {
        val s = String.format(Locale.US, "%.2f", abs(ms) / 1000.0)
        return when {
            ms < 0 -> "−$s"
            ms > 0 -> "+$s"
            else -> "=$s"
        }
    }
}
