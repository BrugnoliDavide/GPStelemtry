package com.gpstelemetry.app

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
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.PublishedTrack

/**
 * Scheda ESPLORA: tracciati pubblicati dagli utenti con le classifiche.
 *
 * PREDISPOSIZIONE: i dati arrivano da [Community.repository]. Finché è
 * attivo [NotConfiguredExploreRepository] la scheda mostra lo stato
 * "In arrivo"; con un repository reale si attivano ricerca ed elenco
 * (forma del tracciato, autore, numero di piloti e podio).
 */
class ExploreFragment : Fragment(R.layout.fragment_explore), TabFragment {

    private lateinit var adapter: PublishedAdapter
    private var list: RecyclerView? = null
    private var loaded = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = PublishedAdapter {
            Toast.makeText(requireContext(), R.string.explore_open_soon, Toast.LENGTH_SHORT).show()
        }
        list = view.findViewById<RecyclerView>(R.id.exploreList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ExploreFragment.adapter
        }
        buildFeatures(view.findViewById(R.id.exploreFeatures))

        val repo = Community.repository
        val query = view.findViewById<EditText>(R.id.exploreQuery)
        if (repo.isAvailable) {
            view.findViewById<View>(R.id.exploreSearch).alpha = 1f
            query.isEnabled = true
            query.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    load(v.text.toString().trim().ifBlank { null })
                    true
                } else false
            }
        }
        showSoon(!repo.isAvailable)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden && !loaded) load(null)
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden && !loaded) load(null)
    }

    override fun scrollToTop() {
        list?.smoothScrollToPosition(0)
    }

    private fun showSoon(soon: Boolean) {
        val v = view ?: return
        v.findViewById<View>(R.id.exploreSoon).visibility = if (soon) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.exploreList).visibility = if (soon) View.GONE else View.VISIBLE
    }

    private fun load(query: String?) {
        val repo = Community.repository
        if (!repo.isAvailable) return
        loaded = true
        repo.listTracks(query = query) { result ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                adapter.submit(result.getOrDefault(emptyList()))
                showSoon(false)
            }
        }
    }

    /** Elenco delle funzioni in arrivo (icona + testo). */
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
            row.addView(
                TextView(ctx).apply {
                    setText(text)
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    setPadding((14 * density).toInt(), 0, 0, 0)
                },
            )
            container.addView(row)
        }
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

        /** Riga del podio: medaglia, pilota, tempo. */
        private fun boardRow(e: LeaderboardEntry): View {
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
                text = e.driver
                textSize = 15f
                maxLines = 1
                setTextColor(ContextCompat.getColor(ctx, if (e.isMe) R.color.accent else R.color.text_primary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(ctx).apply {
                text = Fmt.time(e.lapMs)
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                fontFeatureSettings = "tnum"
                textSize = 16f
                setTextColor(
                    ContextCompat.getColor(ctx, if (e.rank == 1) R.color.sector_best else R.color.text_primary)
                )
            })
            return row
        }
    }
}
