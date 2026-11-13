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
import com.gpstelemetry.core.GpxImport
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

    /** Selettore di file per importare un tracciato GPX. */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importTrack(it) }
        }

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
        view.findViewById<ImageButton>(R.id.btnImport).setOnClickListener {
            // I GPX arrivano spesso con tipi MIME diversi (o generici): si
            // accetta qualunque file e si verifica il contenuto.
            importLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream", "*/*"))
        }
        view.findViewById<AvatarView>(R.id.btnProfile).setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }
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

    private fun refresh() {
        val view = view ?: return
        val ctx = requireContext().applicationContext
        view.findViewById<AvatarView>(R.id.btnProfile).set(Profile.avatar(ctx), Profile.account(ctx)?.nickname)
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
        // Le azioni dipendono dallo stato di pubblicazione del tracciato.
        val imported = track.remoteAuthor != null
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.action_timer) to { startTimer(track) },
            getString(R.string.action_edit) to {
                startActivity(
                    Intent(ctx, TrackEditorActivity::class.java)
                        .putExtra(TrackEditorActivity.EXTRA_TRACK_ID, track.id)
                )
            },
        )
        if (!imported) {
            actions += if (track.published) {
                getString(R.string.action_unpublish) to { confirmUnpublish(track) }
            } else {
                getString(R.string.action_publish) to { publish(track) }
            }
        }
        actions += getString(R.string.action_export_gpx) to {
            exportContent = Gpx.track(track)
            exportLauncher.launch(MainActivity.safeName(track.name) + ".gpx")
        }
        actions += getString(R.string.action_delete) to {
            MaterialAlertDialogBuilder(ctx)
                .setMessage(getString(R.string.confirm_delete_track, track.name))
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    TrackStore.deleteTrack(ctx, track.id)
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(track.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /* --------------------------- Importazione ---------------------------- */

    /** Legge il GPX in background, crea il tracciato e propone l'editor. */
    private fun importTrack(uri: android.net.Uri) {
        val ctx = requireContext().applicationContext
        Toast.makeText(ctx, R.string.import_reading, Toast.LENGTH_SHORT).show()
        thread(name = "gpx-import") {
            val result: Result<Track> = runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val limit = 20L * 1024 * 1024
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw GpxImport.ImportException(ctx.getString(R.string.import_too_big))
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                } ?: throw GpxImport.ImportException("file non leggibile")
                GpxImport.parse(bytes, TrackStore.newId(), System.currentTimeMillis()).getOrThrow()
                    .also { TrackStore.saveTrack(ctx, it) }
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                result.onSuccess { t ->
                    refresh()
                    showImported(t)
                }.onFailure { e ->
                    val msg = (e as? GpxImport.ImportException)?.message ?: "formato non riconosciuto"
                    Toast.makeText(ctx, getString(R.string.import_failed, msg), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showImported(t: Track) {
        val ctx = requireContext()
        val type = getString(if (t.type == TrackType.CIRCULAR) R.string.track_type_circular else R.string.track_type_open)
        val len = if (t.path.size > 1) Fmt.dist(Geo.pathLength(t.path)) else "—"
        val sectors = resources.getQuantityString(R.plurals.editor_status_splits, t.splits.size, t.splits.size)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.import_done_title)
            .setMessage(getString(R.string.import_done_text, t.name, "$type · $len", sectors))
            .setPositiveButton(R.string.import_open_editor) { _, _ ->
                startActivity(
                    Intent(ctx, TrackEditorActivity::class.java).putExtra(TrackEditorActivity.EXTRA_TRACK_ID, t.id)
                )
            }
            .setNegativeButton(R.string.ok, null)
            .show()
    }

    /* --------------------------- Pubblicazione --------------------------- */

    /** Serve un nickname registrato: altrimenti propone il profilo. */
    private fun requireAccount(): com.gpstelemetry.core.UserAccount? {
        val acc = Profile.account(requireContext())
        if (acc == null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.need_nickname_title)
                .setMessage(R.string.need_nickname_text)
                .setPositiveButton(R.string.go_to_profile) { _, _ ->
                    startActivity(Intent(requireContext(), ProfileActivity::class.java))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        return acc
    }

    private fun publish(track: Track) {
        val acc = requireAccount() ?: return
        val ctx = requireContext().applicationContext
        Toast.makeText(ctx, R.string.publishing, Toast.LENGTH_SHORT).show()
        Community.api.publishTrack(acc, track) { result ->
            activity?.runOnUiThread {
                result.onSuccess { p ->
                    TrackStore.saveTrack(ctx, track.copy(published = true, remoteId = p.id))
                    Toast.makeText(ctx, R.string.track_published, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(ctx, getString(R.string.server_error, it.message ?: ""), Toast.LENGTH_LONG).show()
                }
                if (isAdded) refresh()
            }
        }
    }

    private fun confirmUnpublish(track: Track) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.action_unpublish)
            .setMessage(R.string.confirm_unpublish)
            .setPositiveButton(R.string.action_unpublish) { _, _ -> unpublish(track) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun unpublish(track: Track) {
        val acc = requireAccount() ?: return
        val id = track.remoteId ?: return
        val ctx = requireContext().applicationContext
        Community.api.unpublishTrack(acc, id) { result ->
            activity?.runOnUiThread {
                result.onSuccess {
                    TrackStore.saveTrack(ctx, track.copy(published = false, remoteId = null))
                    Toast.makeText(ctx, R.string.track_unpublished, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(ctx, getString(R.string.server_error, it.message ?: ""), Toast.LENGTH_LONG).show()
                }
                if (isAdded) refresh()
            }
        }
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
            val community: TextView = view.findViewById(R.id.trackCommunity)
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
            val community = when {
                t.remoteAuthor != null -> getString(R.string.track_imported_fmt, t.remoteAuthor)
                t.published -> getString(R.string.track_public)
                else -> null
            }
            holder.community.visibility = if (community != null) View.VISIBLE else View.GONE
            holder.community.text = community
            holder.itemView.setOnClickListener { onClick(t) }
            holder.play.setOnClickListener { onPlay(t) }
        }
    }
}
