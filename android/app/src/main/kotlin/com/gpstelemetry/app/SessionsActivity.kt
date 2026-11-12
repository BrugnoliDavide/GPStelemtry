package com.gpstelemetry.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gpstelemetry.core.Gpx
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.SessionStatus

/** Elenco delle sessioni registrate: dettagli, esportazione, eliminazione. */
class SessionsActivity : AppCompatActivity() {

    private lateinit var adapter: SessionAdapter
    private var exportContent: String? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val content = exportContent ?: return@registerForActivityResult
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
                Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show()
            }
            exportContent = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)
        title = getString(R.string.sessions_title)

        adapter = SessionAdapter { showSessionActions(it) }
        findViewById<RecyclerView>(R.id.sessionList).apply {
            layoutManager = LinearLayoutManager(this@SessionsActivity)
            adapter = this@SessionsActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val sessions = TrackStore.listSessions(this)
        adapter.submit(sessions)
        findViewById<TextView>(R.id.emptyText).visibility =
            if (sessions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showSessionActions(session: Session) {
        val actions = arrayOf(
            getString(R.string.export_gpx),
            getString(R.string.export_csv_gps),
            getString(R.string.export_csv_motion),
            getString(R.string.export_csv_hr),
            getString(R.string.export_json),
            getString(R.string.action_delete),
        )
        val base = MainActivity.safeName(session.name)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.export_title))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> export("$base.gpx", Gpx.session(session))
                    1 -> export("${base}_gps.csv", Gpx.gpsCsv(session))
                    2 -> export("${base}_motion.csv", Gpx.motionCsv(session))
                    3 -> export("${base}_hr.csv", Gpx.hrCsv(session))
                    4 -> export("$base.json", TrackStore.sessionJson(session))
                    5 -> MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.confirm_delete_session)
                        .setPositiveButton(R.string.action_delete) { _, _ ->
                            TrackStore.deleteSession(this, session.id)
                            refresh()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun export(filename: String, content: String) {
        exportContent = content
        exportLauncher.launch(filename)
    }

    /* ------------------------------ Adapter ------------------------------ */

    private inner class SessionAdapter(
        private val onClick: (Session) -> Unit,
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        private var items: List<Session> = emptyList()

        fun submit(list: List<Session>) {
            items = list
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
            holder.name.text = s.name
            holder.time.text = if (s.status == SessionStatus.FINISHED) {
                "⏱ ${Fmt.time(r?.elapsedMs)}"
            } else {
                "⚠ ${getString(R.string.session_aborted)}"
            }
            val off = getString(
                R.string.session_offtrack_fmt,
                (r?.offTrackMs ?: 0) / 1000.0,
                r?.offTrackEpisodes ?: 0,
            )
            val pts = getString(R.string.session_points_fmt, s.samples.size, s.hr.size)
            val splits = r?.splits?.joinToString(" · ") { "${it.name}: ${Fmt.time(it.elapsed)}" }
            holder.info.text = listOfNotNull(off, pts, splits?.ifBlank { null }).joinToString("\n")
            holder.itemView.setOnClickListener { onClick(s) }
        }
    }
}
