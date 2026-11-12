package com.gpstelemetry.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.Gpx
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackType
import kotlin.concurrent.thread

/**
 * Scheda CRONOMETRA: tracciati personali in schede di vetro (forma del
 * tracciato, nome, tipologie, record e tasto play arancione), creazione,
 * modifica ed esportazione.
 */
class TracksFragment : Fragment(R.layout.fragment_tracks), TabFragment {

    private lateinit var adapter: TrackAdapter
    private var list: RecyclerView? = null
    private var exportContent: String? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            val content = exportContent ?: return@registerForActivityResult
            uri?.let {
                requireContext().contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
                Toast.makeText(requireContext(), R.string.exported, Toast.LENGTH_SHORT).show()
            }
            exportContent = null
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = TrackAdapter(
            onClick = { showTrackActions(it) },
            onPlay = { startTimer(it) },
        )
        list = view.findViewById<RecyclerView>(R.id.trackList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@TracksFragment.adapter
        }
        view.findViewById<FloatingActionButton>(R.id.fabNew).setOnClickListener {
            startActivity(Intent(requireContext(), TrackEditorActivity::class.java))
        }
        view.findViewById<ImageButton>(R.id.btnTheme).setOnClickListener { cycleTheme() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden && isResumed) refresh()
    }

    override fun scrollToTop() {
        list?.smoothScrollToPosition(0)
    }

    /** Scuro (nero vero) → Chiaro → Sistema. */
    private fun cycleTheme() {
        val ctx = requireContext()
        val next = when (ThemePrefs.get(ctx)) {
            ThemePrefs.Mode.DARK -> ThemePrefs.Mode.LIGHT
            ThemePrefs.Mode.LIGHT -> ThemePrefs.Mode.SYSTEM
            ThemePrefs.Mode.SYSTEM -> ThemePrefs.Mode.DARK
        }
        val label = when (next) {
            ThemePrefs.Mode.DARK -> R.string.theme_dark
            ThemePrefs.Mode.LIGHT -> R.string.theme_light
            ThemePrefs.Mode.SYSTEM -> R.string.theme_system
        }
        Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
        ThemePrefs.set(ctx, next)
    }

    private fun refresh() {
        val view = view ?: return
        val ctx = requireContext().applicationContext
        val tracks = TrackStore.listTracks(ctx).sortedByDescending { it.createdAt }
        adapter.submit(tracks)
        view.findViewById<TextView>(R.id.emptyText).visibility =
            if (tracks.isEmpty()) View.VISIBLE else View.GONE
        // Migliori tempi in background (lettura dell'indice delle sessioni).
        thread(name = "best-times") {
            val summaries = TrackStore.listSummaries(ctx)
            val best = tracks.associate { t ->
                t.id to TrackStore.finishedResults(summaries, t.id).mapNotNull { it.elapsedMs }.minOrNull()
            }
            activity?.runOnUiThread { if (isAdded) adapter.submitBest(best) }
        }
    }

    private fun startTimer(track: Track) {
        startActivity(
            Intent(requireContext(), TimerActivity::class.java)
                .putExtra(TimerActivity.EXTRA_TRACK_ID, track.id)
        )
    }

    private fun showTrackActions(track: Track) {
        val ctx = requireContext()
        val actions = arrayOf(
            getString(R.string.action_timer),
            getString(R.string.action_edit),
            getString(R.string.action_export_gpx),
            getString(R.string.action_delete),
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(track.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startTimer(track)
                    1 -> startActivity(
                        Intent(ctx, TrackEditorActivity::class.java)
                            .putExtra(TrackEditorActivity.EXTRA_TRACK_ID, track.id)
                    )
                    2 -> {
                        exportContent = Gpx.track(track)
                        exportLauncher.launch(MainActivity.safeName(track.name) + ".gpx")
                    }
                    3 -> MaterialAlertDialogBuilder(ctx)
                        .setMessage(getString(R.string.confirm_delete_track, track.name))
                        .setPositiveButton(R.string.action_delete) { _, _ ->
                            TrackStore.deleteTrack(ctx, track.id)
                            refresh()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    /* ------------------------------ Adapter ----------------------------- */

    private inner class TrackAdapter(
        private val onClick: (Track) -> Unit,
        private val onPlay: (Track) -> Unit,
    ) : RecyclerView.Adapter<TrackAdapter.VH>() {

        private var items: List<Track> = emptyList()
        private var best: Map<String, Long?> = emptyMap()

        fun submit(list: List<Track>) {
            items = list
            notifyDataSetChanged()
        }

        fun submitBest(map: Map<String, Long?>) {
            best = map
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val shape: TrackShapeView = view.findViewById(R.id.trackShape)
            val name: TextView = view.findViewById(R.id.trackName)
            val type: TextView = view.findViewById(R.id.trackType)
            val length: TextView = view.findViewById(R.id.trackLength)
            val info: TextView = view.findViewById(R.id.trackInfo)
            val best: TextView = view.findViewById(R.id.trackBest)
            val play: ImageButton = view.findViewById(R.id.btnPlay)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            holder.shape.lineWidthDp = 2f
            holder.shape.setTrack(t)
            holder.name.text = t.name
            holder.type.text = getString(
                if (t.type == TrackType.CIRCULAR) R.string.track_type_circular else R.string.track_type_open
            )
            holder.length.text = if (t.path.size > 1) Fmt.dist(Geo.pathLength(t.path)) else "—"
            holder.info.text = resources.getQuantityString(
                R.plurals.track_info_fmt, t.splits.size + 1, t.tolerance.toInt(), t.splits.size + 1,
            )
            val b = best[t.id]
            holder.best.visibility = if (b != null) View.VISIBLE else View.GONE
            holder.best.text = getString(R.string.best_fmt, Fmt.time(b))
            holder.itemView.setOnClickListener { onClick(t) }
            holder.play.setOnClickListener { onPlay(t) }
        }
    }
}
