package com.gpstelemetry.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gpstelemetry.core.SessionStatus
import com.gpstelemetry.core.SessionSummary
import kotlin.concurrent.thread

/**
 * Scheda SESSIONI: storico dei giri (dal riepilogo leggero, quindi veloce
 * anche con molte sessioni). Il giro record di ogni tracciato è in viola;
 * veicolo e pubblicazione in classifica sono indicati sotto il tempo.
 * Toccando una sessione si apre il dettaglio (veicolo, pubblicazione,
 * immagine, esportazioni).
 */
class SessionsFragment : Fragment(R.layout.fragment_sessions), TabFragment {

    private lateinit var adapter: SessionAdapter
    private var list: RecyclerView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SessionAdapter { open(it) }
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

    private fun open(s: SessionSummary) {
        startActivity(
            Intent(requireContext(), SessionDetailActivity::class.java)
                .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, s.id)
        )
    }

    private fun refresh() {
        val ctx = context?.applicationContext ?: return
        thread(name = "sessions") {
            val sums = TrackStore.listSummaries(ctx)
            val names = TrackStore.listTracks(ctx).associate { it.id to it.name }
            // Giro record per tracciato.
            val recordIds = sums
                .filter { it.status == SessionStatus.FINISHED && it.result?.elapsedMs != null }
                .groupBy { it.trackId }
                .mapNotNull { (_, l) -> l.minByOrNull { it.result!!.elapsedMs!! }?.id }
                .toSet()
            activity?.runOnUiThread {
                val v = view ?: return@runOnUiThread
                adapter.submit(sums, recordIds, names)
                v.findViewById<TextView>(R.id.emptyText).visibility =
                    if (sums.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /* ------------------------------ Adapter ------------------------------ */

    private inner class SessionAdapter(
        private val onClick: (SessionSummary) -> Unit,
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        private var items: List<SessionSummary> = emptyList()
        private var records: Set<String> = emptySet()
        private var trackNames: Map<String, String> = emptyMap()

        fun submit(list: List<SessionSummary>, recordIds: Set<String>, names: Map<String, String>) {
            items = list
            records = recordIds
            trackNames = names
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
            val tags = listOfNotNull(
                s.vehicleLabel,
                if (s.remoteLapId != null) getString(R.string.session_in_leaderboard) else null,
            ).joinToString("  ·  ").ifBlank { null }
            val splits = r?.splits?.joinToString(" · ") { "${it.name}: ${Fmt.time(it.elapsed)}" }
            holder.info.text = listOfNotNull(tags, splits?.ifBlank { null }).joinToString("\n")
            holder.itemView.setOnClickListener { onClick(s) }
        }
    }
}
