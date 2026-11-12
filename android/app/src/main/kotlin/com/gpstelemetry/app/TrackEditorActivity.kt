package com.gpstelemetry.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.LatLon
import com.gpstelemetry.core.PathPoint
import com.gpstelemetry.core.SplitPoint
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackType
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Editor dei tracciati: creazione e modifica.
 *  - Posizionamento di partenza, traguardo e parziali toccando la mappa.
 *  - MODIFICA: i punti esistenti si spostano trascinandoli sulla mappa;
 *    toccando un parziale lo si può rinominare o eliminare.
 *  - Registrazione del percorso percorrendolo con il GPS.
 *  - Cattura in presa diretta (partenza = primo fix, traguardo = ultimo fix,
 *    parziali contrassegnabili al volo).
 */
class TrackEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRACK_ID = "trackId"
        private enum class Pick { NONE, START, FINISH, SPLIT }
    }

    private lateinit var map: MapView
    private lateinit var edName: EditText
    private lateinit var edType: Spinner
    private lateinit var edTolerance: EditText
    private lateinit var edHint: TextView
    private lateinit var edStatus: TextView
    private lateinit var btnRecordPath: Button
    private lateinit var btnLiveCapture: Button
    private lateinit var btnLiveSplit: Button

    private lateinit var fused: FusedLocationProviderClient

    private var editingId: String? = null
    private var createdAt: Long = 0
    private var start: LatLon? = null
    private var finish: LatLon? = null
    private val splits = mutableListOf<SplitPoint>()
    private var path = mutableListOf<PathPoint>()

    private var picking = Pick.NONE
    private var recordingPath = false
    private var liveCapture = false
    private val liveSplits = mutableListOf<SplitPoint>()
    private var lastFix: LatLon? = null
    private var centeredOnce = false

    private val markers = mutableListOf<Marker>()
    private var polyline: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Richiesto dalla policy dei tile server OSM.
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_editor)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fused = LocationServices.getFusedLocationProviderClient(this)

        edName = findViewById(R.id.edName)
        edType = findViewById(R.id.edType)
        edTolerance = findViewById(R.id.edTolerance)
        edHint = findViewById(R.id.edHint)
        edStatus = findViewById(R.id.edStatus)
        btnRecordPath = findViewById(R.id.btnRecordPath)
        btnLiveCapture = findViewById(R.id.btnLiveCapture)
        btnLiveSplit = findViewById(R.id.btnLiveSplit)

        edType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.track_type_circular), getString(R.string.track_type_open)),
        )

        setupMap()
        loadExisting()
        setupButtons()
        redraw()
    }

    private fun trackType(): TrackType =
        if (edType.selectedItemPosition == 0) TrackType.CIRCULAR else TrackType.OPEN

    private fun loadExisting() {
        val id = intent.getStringExtra(EXTRA_TRACK_ID) ?: return
        val t = TrackStore.getTrack(this, id) ?: return
        editingId = t.id
        createdAt = t.createdAt
        edName.setText(t.name)
        edType.setSelection(if (t.type == TrackType.CIRCULAR) 0 else 1)
        edTolerance.setText(t.tolerance.toInt().toString())
        start = t.start
        finish = t.finish
        splits.addAll(t.splits)
        path = t.path.toMutableList()
        // centra la mappa sul tracciato esistente
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(t.start.lat, t.start.lon))
        centeredOnce = true
    }

    /* ------------------------------- Mappa ------------------------------- */

    private fun setupMap() {
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTap(LatLon(p.latitude, p.longitude))
                return picking != Pick.NONE
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

        if (!centeredOnce && hasLocationPermission()) {
            centerOnCurrentLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun centerOnCurrentLocation() {
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && !centeredOnce) {
                centeredOnce = true
                map.controller.setZoom(16.0)
                map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
            }
        }
    }

    private fun onMapTap(p: LatLon) {
        when (picking) {
            Pick.START -> {
                start = p
                picking = Pick.NONE
            }
            Pick.FINISH -> {
                finish = p
                picking = Pick.NONE
            }
            Pick.SPLIT -> splits.add(SplitPoint("Parziale ${splits.size + 1}", p.lat, p.lon))
            Pick.NONE -> return
        }
        updateHint()
        redraw()
    }

    private fun redraw() {
        markers.forEach { map.overlays.remove(it) }
        markers.clear()
        polyline?.let { map.overlays.remove(it) }
        polyline = null

        start?.let { addMarker(it, "Partenza", draggable = true) { pos -> start = pos } }
        if (trackType() == TrackType.OPEN) {
            finish?.let { addMarker(it, "Traguardo", draggable = true) { pos -> finish = pos } }
        }
        splits.forEachIndexed { i, sp ->
            val m = addMarker(LatLon(sp.lat, sp.lon), sp.name, draggable = true) { pos ->
                splits[i] = SplitPoint(sp.name, pos.lat, pos.lon)
            }
            m.setOnMarkerClickListener { _, _ ->
                showSplitDialog(i)
                true
            }
        }

        if (path.size > 1) {
            polyline = Polyline().apply {
                setPoints(path.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = ContextCompat.getColor(this@TrackEditorActivity, R.color.path_blue)
                outlinePaint.strokeWidth = 8f
            }
            map.overlays.add(polyline)
        }
        map.invalidate()
        updateStatus()
    }

    private fun addMarker(
        p: LatLon,
        title: String,
        draggable: Boolean,
        onMoved: (LatLon) -> Unit,
    ): Marker {
        val m = Marker(map)
        m.position = GeoPoint(p.lat, p.lon)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.title = title
        m.isDraggable = draggable
        m.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDrag(marker: Marker) = Unit
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDragEnd(marker: Marker) {
                onMoved(LatLon(marker.position.latitude, marker.position.longitude))
                updateStatus()
            }
        })
        map.overlays.add(m)
        markers.add(m)
        return m
    }

    private fun showSplitDialog(index: Int) {
        val sp = splits[index]
        val input = EditText(this).apply { setText(sp.name) }
        MaterialAlertDialogBuilder(this)
            .setTitle(sp.name)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                splits[index] = SplitPoint(input.text.toString().ifBlank { sp.name }, sp.lat, sp.lon)
                redraw()
            }
            .setNeutralButton(R.string.action_delete) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.delete_split)
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        splits.removeAt(index)
                        redraw()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* ----------------------------- Pulsanti ------------------------------ */

    private fun setupButtons() {
        findViewById<Button>(R.id.btnPickStart).setOnClickListener {
            picking = if (picking == Pick.START) Pick.NONE else Pick.START
            updateHint()
        }
        findViewById<Button>(R.id.btnPickFinish).setOnClickListener {
            if (trackType() == TrackType.CIRCULAR) {
                Toast.makeText(this, R.string.finish_equals_start, Toast.LENGTH_SHORT).show()
            } else {
                picking = if (picking == Pick.FINISH) Pick.NONE else Pick.FINISH
                updateHint()
            }
        }
        findViewById<Button>(R.id.btnPickSplit).setOnClickListener {
            picking = if (picking == Pick.SPLIT) Pick.NONE else Pick.SPLIT
            updateHint()
        }
        btnRecordPath.setOnClickListener { toggleRecordPath() }
        btnLiveCapture.setOnClickListener { toggleLiveCapture() }
        btnLiveSplit.setOnClickListener { markLiveSplit() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun updateHint() {
        edHint.text = when (picking) {
            Pick.NONE -> getString(R.string.editor_hint_idle)
            Pick.START -> getString(R.string.editor_hint_pick, "partenza")
            Pick.FINISH -> getString(R.string.editor_hint_pick, "traguardo")
            Pick.SPLIT -> getString(R.string.editor_hint_split)
        }
    }

    private fun updateStatus() {
        val pathStr = if (path.size > 1) {
            "percorso: ${path.size} punti, ${Fmt.dist(Geo.pathLength(path))}"
        } else {
            "percorso non registrato"
        }
        edStatus.text = listOfNotNull(
            if (start != null) "✓ partenza" else "✗ partenza",
            if (trackType() == TrackType.OPEN) (if (finish != null) "✓ traguardo" else "✗ traguardo") else null,
            "parziali: ${splits.size}",
            pathStr,
        ).joinToString(" · ")
    }

    /* ------------------- Registrazione GPS del percorso ------------------ */

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                val p = LatLon(loc.latitude, loc.longitude)
                lastFix = p
                if (recordingPath || liveCapture) {
                    path.add(PathPoint(p.lat, p.lon, if (loc.hasAltitude()) loc.altitude else null))
                    edStatus.text = getString(R.string.recording_path, path.size, Fmt.dist(Geo.pathLength(path)))
                    if (path.size % 5 == 0) redrawPathOnly()
                }
                if (!centeredOnce) {
                    centeredOnce = true
                    map.controller.setZoom(17.0)
                }
                map.controller.setCenter(GeoPoint(p.lat, p.lon))
            }
        }
    }

    private fun redrawPathOnly() {
        polyline?.let { map.overlays.remove(it) }
        polyline = Polyline().apply {
            setPoints(path.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = ContextCompat.getColor(this@TrackEditorActivity, R.color.path_blue)
            outlinePaint.strokeWidth = 8f
        }
        map.overlays.add(polyline)
        map.invalidate()
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateIntervalMillis(250L)
            .build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun toggleRecordPath() {
        if (liveCapture) {
            Toast.makeText(this, R.string.stop_recording_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!recordingPath) {
            if (!hasLocationPermission()) {
                Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
                return
            }
            recordingPath = true
            path = mutableListOf()
            startLocationUpdates()
            btnRecordPath.setText(R.string.record_path_stop)
        } else {
            recordingPath = false
            fused.removeLocationUpdates(locationCallback)
            path = Geo.simplify(path, 2.0).toMutableList()
            btnRecordPath.setText(R.string.record_path)
            redraw()
        }
    }

    private fun toggleLiveCapture() {
        if (recordingPath) {
            Toast.makeText(this, R.string.stop_recording_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!liveCapture) {
            if (!hasLocationPermission()) {
                Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
                return
            }
            liveCapture = true
            path = mutableListOf()
            liveSplits.clear()
            startLocationUpdates()
            btnLiveCapture.setText(R.string.live_stop)
            btnLiveSplit.visibility = android.view.View.VISIBLE
            Toast.makeText(this, R.string.capture_started, Toast.LENGTH_LONG).show()
        } else {
            liveCapture = false
            fused.removeLocationUpdates(locationCallback)
            btnLiveCapture.setText(R.string.live_capture)
            btnLiveSplit.visibility = android.view.View.GONE
            if (path.size < 2) {
                Toast.makeText(this, R.string.not_enough_points, Toast.LENGTH_LONG).show()
            } else {
                start = LatLon(path.first().lat, path.first().lon)
                finish = if (trackType() == TrackType.OPEN) {
                    LatLon(path.last().lat, path.last().lon)
                } else null
                splits.clear()
                splits.addAll(liveSplits)
                path = Geo.simplify(path, 2.0).toMutableList()
                Toast.makeText(this, R.string.capture_done, Toast.LENGTH_LONG).show()
            }
            redraw()
        }
    }

    private fun markLiveSplit() {
        val p = lastFix ?: return
        liveSplits.add(SplitPoint("Parziale ${liveSplits.size + 1}", p.lat, p.lon))
        Toast.makeText(this, getString(R.string.split_added, liveSplits.size), Toast.LENGTH_SHORT).show()
    }

    /* ----------------------------- Salvataggio --------------------------- */

    private fun save() {
        if (recordingPath || liveCapture) {
            Toast.makeText(this, R.string.stop_recording_first, Toast.LENGTH_SHORT).show()
            return
        }
        val name = edName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.name_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val s = start ?: run {
            Toast.makeText(this, R.string.start_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val type = trackType()
        if (type == TrackType.OPEN && finish == null) {
            Toast.makeText(this, R.string.finish_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val tolerance = (edTolerance.text.toString().toDoubleOrNull() ?: 15.0).coerceIn(5.0, 100.0)
        val track = Track(
            id = editingId ?: TrackStore.newId(),
            name = name,
            type = type,
            tolerance = tolerance,
            createdAt = if (createdAt > 0) createdAt else System.currentTimeMillis(),
            start = s,
            finish = if (type == TrackType.OPEN) finish else null,
            splits = splits.toMutableList(),
            path = path.toMutableList(),
        )
        TrackStore.saveTrack(this, track)
        Toast.makeText(this, R.string.track_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /* ------------------------------ Ciclo vita --------------------------- */

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }
}
