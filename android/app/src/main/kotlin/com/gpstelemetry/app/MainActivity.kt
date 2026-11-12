package com.gpstelemetry.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.Gpx
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackType

/** Elenco dei tracciati: creazione, modifica, cronometraggio, esportazione. */
class MainActivity : AppCompatActivity() {

    private lateinit var adapter: TrackAdapter
    private var exportContent: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
                Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            val content = exportContent ?: return@registerForActivityResult
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
                Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show()
            }
            exportContent = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = getString(R.string.tracks_title)

        adapter = TrackAdapter { track -> showTrackActions(track) }
        findViewById<RecyclerView>(R.id.trackList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<FloatingActionButton>(R.id.fabNew).setOnClickListener {
            startActivity(Intent(this, TrackEditorActivity::class.java))
        }

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val tracks = TrackStore.listTracks(this).sortedByDescending { it.createdAt }
        adapter.submit(tracks)
        findViewById<TextView>(R.id.emptyText).visibility =
            if (tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_sessions) {
            startActivity(Intent(this, SessionsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showTrackActions(track: Track) {
        val actions = arrayOf(
            getString(R.string.action_timer),
            getString(R.string.action_edit),
            getString(R.string.action_export_gpx),
            getString(R.string.action_delete),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(track.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, TimerActivity::class.java)
                            .putExtra(TimerActivity.EXTRA_TRACK_ID, track.id)
                    )
                    1 -> startActivity(
                        Intent(this, TrackEditorActivity::class.java)
                            .putExtra(TrackEditorActivity.EXTRA_TRACK_ID, track.id)
                    )
                    2 -> {
                        exportContent = Gpx.track(track)
                        exportLauncher.launch(safeName(track.name) + ".gpx")
                    }
                    3 -> MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.confirm_delete_track, track.name))
                        .setPositiveButton(R.string.action_delete) { _, _ ->
                            TrackStore.deleteTrack(this, track.id)
                            refresh()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    companion object {
        fun safeName(s: String): String =
            s.replace(Regex("[^\\w \\-\\u00C0-\\u017F]+"), "").trim()
                .replace(Regex("\\s+"), "_")
                .ifBlank { "export" }
    }

    /* ------------------------------ Adapter ----------------------------- */

    private inner class TrackAdapter(
        private val onClick: (Track) -> Unit,
    ) : RecyclerView.Adapter<TrackAdapter.VH>() {

        private var items: List<Track> = emptyList()

        fun submit(list: List<Track>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.trackName)
            val info: TextView = view.findViewById(R.id.trackInfo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            holder.name.text = t.name
            val typeStr = getString(
                if (t.type == TrackType.CIRCULAR) R.string.track_type_circular else R.string.track_type_open
            )
            val len = if (t.path.size > 1) Fmt.dist(Geo.pathLength(t.path)) else "—"
            holder.info.text =
                "$typeStr · $len · ±${t.tolerance.toInt()} m · parziali: ${t.splits.size}"
            holder.itemView.setOnClickListener { onClick(t) }
        }
    }
}
