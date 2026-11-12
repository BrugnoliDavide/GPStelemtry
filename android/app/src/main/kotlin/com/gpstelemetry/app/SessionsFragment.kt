package com.gpstelemetry.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gpstelemetry.core.Gpx
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.SessionStatus
import com.gpstelemetry.core.SessionSummary
import kotlin.concurrent.thread

/**
 * Scheda SESSIONI: storico dei giri (dal riepilogo leggero, quindi veloce
 * anche con molte sessioni). Il giro record di ogni tracciato è in viola.
 * Toccando una sessione: immagine da condividere, esportazioni, eliminazione;
 * la sessione completa (telemetria) viene letta solo quando serve.
 */
class SessionsFragment : Fragment(R.layout.fragment_sessions), TabFragment {

    private lateinit var adapter: SessionAdapter
    private var list: RecyclerView? = null
    private var exportContent: String? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val content = exportContent ?: return@registerForActivityResult
            uri?.let {
                requireContext().contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
                Toast.makeText(requireContext(), R.string.exported, Toast.LENGTH_SHORT).show()
            }
            exportContent = null
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SessionAdapter { showSessionActions(it) }
        list = view.findViewById<RecyclerView>(R.id.sessionList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SessionsFragment.adapter
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
        val ctx = context?.applicationContext ?: return
        thread(name = "sessions") {
            val sums = TrackStore.listSummaries(ctx)
            // Giro record per tracciato.
            val recordIds = sums
                .filter { it.status == SessionStatus.FINISHED && it.result?.elapsedMs != null }
                .groupBy { it.trackId }
                .mapNotNull { (_, l) -> l.minByOrNull { it.result!!.elapsedMs!! }?.id }
                .toSet()
            activity?.runOnUiThread {
                val v = view ?: return@runOnUiThread
                adapter.submit(sums, recordIds)
                v.findViewById<TextView>(R.id.emptyText).visibility =
                    if (sums.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showSessionActions(summary: SessionSummary) {
        val ctx = requireContext()
        val actions = arrayOf(
            getString(R.string.export_image),
            getString(R.string.export_gpx),
            getString(R.string.export_csv_gps),
            getString(R.string.export_csv_motion),
            getString(R.string.export_csv_hr),
            getString(R.string.export_json),
            getString(R.string.action_delete),
        )
        val base = MainActivity.safeName(summary.name)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(summary.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(ctx, ShareResultActivity::class.java)
                            .putExtra(ShareResultActivity.EXTRA_SESSION_ID, summary.id)
                    )
                    1 -> withSession(summary) { export("$base.gpx", Gpx.session(it)) }
                    2 -> withSession(summary) { export("${base}_gps.csv", Gpx.gpsCsv(it)) }
                    3 -> withSession(summary) { export("${base}_motion.csv", Gpx.motionCsv(it)) }
                    4 -> withSession(summary) { export("${base}_hr.csv", Gpx.hrCsv(it)) }
                    5 -> withSession(summary) { export("$base.json", TrackStore.sessionJson(it)) }
                    6 -> MaterialAlertDialogBuilder(ctx)
                        .setMessage(R.string.confirm_delete_session)
                        .setPositiveButton(R.string.action_delete) { _, _ ->
                            TrackStore.deleteSession(ctx, summary.id)
                            refresh()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    /** Legge la sessione completa in background e poi esegue [action]. */
    private fun withSession(summary: SessionSummary, action: (Session) -> Unit) {
        val ctx = requireContext().applicationContext
        thread(name = "session-load") {
            val s = TrackStore.getSession(ctx, summary.id)
            activity?.runOnUiThread { if (s != null && isAdded) action(s) }
        }
    }

    private fun export(filename: String, content: String) {
        exportContent = content
        exportLauncher.launch(filename)
    }

    /* ------------------------------ Adapter ------------------------------ */

    private inner class SessionAdapter(
        private val onClick: (SessionSummary) -> Unit,
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        private var items: List<SessionSummary> = emptyList()
        private var records: Set<String> = emptySet()

        fun submit(list: List<SessionSummary>, recordIds: Set<String>) {
            items = list
            records = recordIds
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.sessionName)
            val time: TextView = view.findViewById(R.id.sessionTime)
            val info: TextView = view.findViewById(R.id.sessionInfo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            val r = s.result
            val ctx = holder.itemView.context
            holder.name.text = s.name
            val isRecord = s.id in records
            if (s.status == SessionStatus.FINISHED) {
                holder.time.text = Fmt.time(r?.elapsedMs) + if (isRecord) "  ·  ${getString(R.string.record)}" else ""
                holder.time.setTextColor(
                    ContextCompat.getColor(ctx, if (isRecord) R.color.sector_best else R.color.text_primary)
                )
            } else {
                holder.time.text = getString(R.string.session_aborted)
                holder.time.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            val off = getString(
                R.string.session_offtrack_fmt,
                (r?.offTrackMs ?: 0) / 1000.0,
                r?.offTrackEpisodes ?: 0,
            )
            val splits = r?.splits?.joinToString(" · ") { "${it.name}: ${Fmt.time(it.elapsed)}" }
            holder.info.text = listOfNotNull(splits?.ifBlank { null }, off).joinToString("\n")
            holder.itemView.setOnClickListener { onClick(s) }
        }
    }
}
