package com.gpstelemetry.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.PublishedTrack
import com.gpstelemetry.core.TrackQuery

/**
 * Scheda ESPLORA: tracciati pubblicati dagli utenti con le classifiche.
 *
 * Ricerca sul server ([Community.api]):
 *  - VICINO A ME: tracciati entro 50 km dalla posizione attuale;
 *  - NOME: nome del tracciato;
 *  - PILOTA: tracciati pubblicati o corsi da un nickname.
 * Toccando un tracciato: classifica completa e "Aggiungi ai miei tracciati"
 * (copia locale collegata, per cronometrarsi e comparire in classifica).
 */
class ExploreFragment : Fragment(R.layout.fragment_explore), TabFragment {

    private enum class Mode { NEARBY, NAME, DRIVER }

    private lateinit var adapter: PublishedAdapter
    private var list: RecyclerView? = null
    private var mode = Mode.NEARBY
    private var loaded = false
    private var seq = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = PublishedAdapter { showTrack(it) }
        list = view.findViewById<RecyclerView>(R.id.exploreList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ExploreFragment.adapter
        }
        buildFeatures(view.findViewById(R.id.exploreFeatures))

        val api = Community.api
        if (!api.isAvailable) {
            showSoon(true)
            return
        }
        showSoon(false)
        view.findViewById<View>(R.id.exploreSearch).alpha = 1f
        val query = view.findViewById<EditText>(R.id.exploreQuery)
        query.isEnabled = true
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                if (mode == Mode.NEARBY) selectMode(Mode.NAME) else load()
                true
            } else false
        }
        buildModes(view.findViewById(R.id.exploreModes))
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden) load()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden && !loaded) load()
    }

    override fun scrollToTop() {
        list?.smoothScrollToPosition(0)
    }

    private fun showSoon(soon: Boolean) {
        val v = view ?: return
        v.findViewById<View>(R.id.exploreSoon).visibility = if (soon) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.exploreList).visibility = if (soon) View.GONE else View.VISIBLE
    }

    /* ------------------------------ Ricerca ------------------------------ */

    private fun buildModes(group: ChipGroup) {
        group.visibility = View.VISIBLE
        listOf(
            Mode.NEARBY to R.string.explore_mode_nearby,
            Mode.NAME to R.string.explore_mode_name,
            Mode.DRIVER to R.string.explore_mode_driver,
        ).forEach { (m, label) ->
            group.addView(Chip(requireContext()).glass().apply {
                id = View.generateViewId()
                tag = m
                setText(label)
                isCheckable = true
                isChecked = m == mode
                setOnClickListener { selectMode(m) }
            })
        }
    }

    private fun selectMode(m: Mode) {
        mode = m
        view?.findViewById<ChipGroup>(R.id.exploreModes)?.let { g ->
            for (i in 0 until g.childCount) (g.getChildAt(i) as Chip).isChecked = g.getChildAt(i).tag == m
        }
        load()
    }

    private fun setInfo(text: String?) {
        view?.findViewById<TextView>(R.id.exploreInfo)?.apply {
            visibility = if (text != null) View.VISIBLE else View.GONE
            this.text = text
        }
    }

    @SuppressLint("MissingPermission")
    private fun load() {
        val v = view ?: return
        val api = Community.api
        if (!api.isAvailable) return
        loaded = true
        val text = v.findViewById<EditText>(R.id.exploreQuery).text.toString().trim()
        when (mode) {
            Mode.NAME -> run(TrackQuery.ByName(text), if (text.isEmpty()) null else getString(R.string.explore_info_name, text))
            Mode.DRIVER -> if (text.isEmpty()) {
                adapter.submit(emptyList())
                setInfo(getString(R.string.explore_info_driver_hint))
            } else run(TrackQuery.ByDriver(text), getString(R.string.explore_info_driver, text))
            Mode.NEARBY -> {
                val ctx = requireContext()
                val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    run(TrackQuery.All, getString(R.string.explore_info_no_location))
                    return
                }
                LocationServices.getFusedLocationProviderClient(ctx).lastLocation
                    .addOnSuccessListener { loc ->
                        if (loc != null) run(TrackQuery.Nearby(loc.latitude, loc.longitude), getString(R.string.explore_info_nearby))
                        else run(TrackQuery.All, getString(R.string.explore_info_no_location))
                    }
                    .addOnFailureListener { run(TrackQuery.All, getString(R.string.explore_info_no_location)) }
            }
        }
    }

    private fun run(q: TrackQuery, info: String?) {
        val mySeq = ++seq
        val me = Profile.account(requireContext())
        setInfo(getString(R.string.explore_loading))
        Community.api.searchTracks(q, me) { result ->
            activity?.runOnUiThread {
                if (!isAdded || mySeq != seq) return@runOnUiThread
                result.onSuccess { items ->
                    adapter.submit(items)
                    val mock = if (Community.api.isMock) " · " + getString(R.string.server_mock_badge) else ""
                    setInfo(
                        if (items.isEmpty()) getString(R.string.explore_empty) + mock
                        else (info ?: resources.getQuantityString(R.plurals.explore_count, items.size, items.size)) + mock
                    )
                }.onFailure {
                    setInfo(getString(R.string.server_error, it.message ?: ""))
                }
            }
        }
    }

    /* ---------------------- Dettaglio e importazione --------------------- */

    private fun showTrack(p: PublishedTrack) {
        val ctx = requireContext()
        val me = Profile.account(ctx)
        Community.api.leaderboard(p.id, me) { result ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val board = result.getOrDefault(p.topTimes)
                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (20 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, 0)
                }
                if (board.isEmpty()) {
                    container.addView(TextView(ctx).apply {
                        setText(R.string.explore_no_times)
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    })
                }
                board.take(50).forEach { container.addView(boardRow(it, showVehicle = true)) }
                val owned = TrackStore.listTracks(ctx).any { it.remoteId == p.id }
                val b = MaterialAlertDialogBuilder(ctx)
                    .setTitle(p.track.name)
                    .setView(android.widget.ScrollView(ctx).apply { addView(container) })
                    .setNegativeButton(R.string.close, null)
                if (!owned) b.setPositiveButton(R.string.explore_import) { _, _ -> import(p) }
                b.show()
            }
        }
    }

    private fun import(p: PublishedTrack) {
        val ctx = requireContext()
        val copy = p.track.copy(
            id = TrackStore.newId(),
            createdAt = System.currentTimeMillis(),
            published = false,
            remoteId = p.id,
            remoteAuthor = p.author,
        )
        TrackStore.saveTrack(ctx, copy)
        Toast.makeText(ctx, getString(R.string.explore_imported, p.track.name), Toast.LENGTH_LONG).show()
    }

    /** Elenco delle funzioni (stato "In arrivo" senza server). */
    private fun buildFeatures(container: LinearLayout) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        listOf(
            R.drawable.ic_explore to R.string.explore_feature_tracks,
            R.drawable.ic_trophy to R.string.explore_feature_board,
            R.drawable.ic_timer to R.string.explore_feature_sectors,
        ).forEach { (icon, text) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            }
            row.addView(
                ImageView(ctx).apply {
                    setImageResource(icon)
                    imageTintList = ContextCompat.getColorStateList(ctx, R.color.accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()),
            )
            row.addView(TextView(ctx).apply {
                setText(text)
                textSize = 15f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                setPadding((14 * density).toInt(), 0, 0, 0)
            })
            container.addView(row)
        }
    }

    /** Riga di classifica: posizione (medaglia), pilota, veicolo, tempo. */
    private fun boardRow(e: LeaderboardEntry, showVehicle: Boolean = false): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val medal = when (e.rank) {
            1 -> R.color.medal_gold
            2 -> R.color.medal_silver
            3 -> R.color.medal_bronze
            else -> R.color.text_secondary
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), (5 * density).toInt(), (4 * density).toInt(), (5 * density).toInt())
        }
        row.addView(TextView(ctx).apply {
            text = e.rank.toString()
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, medal))
        }, LinearLayout.LayoutParams((28 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(ctx).apply {
            text = e.vehicleLabel?.takeIf { showVehicle }?.let { "${e.driver}  ·  $it" } ?: e.driver
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(ctx, if (e.isMe) R.color.accent else R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(ctx).apply {
            text = Fmt.time(e.lapMs)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            fontFeatureSettings = "tnum"
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, if (e.rank == 1) R.color.sector_best else R.color.text_primary))
        })
        return row
    }

    /* ------------------------------ Adapter ------------------------------ */

    private inner class PublishedAdapter(
        private val onClick: (PublishedTrack) -> Unit,
    ) : RecyclerView.Adapter<PublishedAdapter.VH>() {

        private var items: List<PublishedTrack> = emptyList()

        fun submit(list: List<PublishedTrack>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val shape: TrackShapeView = view.findViewById(R.id.pubShape)
            val name: TextView = view.findViewById(R.id.pubName)
            val author: TextView = view.findViewById(R.id.pubAuthor)
            val meta: TextView = view.findViewById(R.id.pubMeta)
            val board: LinearLayout = view.findViewById(R.id.pubBoard)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_published_track, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            holder.shape.lineWidthDp = 2f
            holder.shape.setTrack(p.track)
            holder.name.text = p.track.name
            holder.author.text = getString(R.string.explore_author_fmt, p.author)
            val parts = mutableListOf(resources.getQuantityString(R.plurals.explore_drivers_fmt, p.drivers, p.drivers))
            if (p.track.path.size > 1) parts.add(Fmt.dist(Geo.pathLength(p.track.path)))
            p.distanceM?.let { parts.add("a " + Fmt.dist(it)) }
            holder.meta.text = parts.joinToString(" · ")
            holder.board.removeAllViews()
            p.topTimes.take(3).forEach { holder.board.addView(boardRow(it)) }
            holder.itemView.setOnClickListener { onClick(p) }
        }
    }
}
