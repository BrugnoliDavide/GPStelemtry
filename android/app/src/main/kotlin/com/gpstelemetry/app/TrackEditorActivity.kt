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
import android.widget.LinearLayout
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
import com.gpstelemetry.core.Gate
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.LatLon
import com.gpstelemetry.core.PathPoint
import com.gpstelemetry.core.SplitGate
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackType
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/**
 * Editor dei tracciati: creazione e modifica.
 *
 * Partenza, traguardo e parziali sono LINEE di cronometraggio:
 *  - si posizionano toccando la mappa (orientate automaticamente perpendicolari
 *    al percorso, quando disponibile);
 *  - si SPOSTANO trascinando il segnaposto al centro della linea;
 *  - si INCLINANO (ruotano) e si allargano trascinando il cerchietto
 *    all'estremità della linea, oppure inserendo angolo e larghezza numerici
 *    toccando il segnaposto;
 *  - i parziali si possono rinominare o eliminare dallo stesso dialogo.
 *
 * Il percorso si registra percorrendolo con il GPS, oppure in presa diretta
 * (partenza = primo fix, traguardo = ultimo fix, parziali al volo).
 */
class TrackEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRACK_ID = "trackId"
        private const val MIN_GATE_WIDTH = 4.0
        private const val MAX_GATE_WIDTH = 500.0
    }

    private enum class Pick { NONE, START, FINISH, SPLIT }

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
    private var start: Gate? = null
    private var finish: Gate? = null
    private val splits = mutableListOf<SplitGate>()
    private var path = mutableListOf<PathPoint>()

    private var picking = Pick.NONE
    private var recordingPath = false
    private var liveCapture = false
    private val liveSplits = mutableListOf<SplitGate>()
    private var lastFix: LatLon? = null
    private var prevFix: LatLon? = null
    private var centeredOnce = false

    private val gateOverlays = mutableListOf<Overlay>()
    private var pathLine: Polyline? = null

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

    private fun tolerance(): Double =
        (edTolerance.text.toString().toDoubleOrNull() ?: 15.0).coerceIn(5.0, 100.0)

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

    /**
     * Cancello predefinito nel punto toccato: perpendicolare al percorso se
     * registrato, larghezza doppia della tolleranza.
     */
    private fun defaultGate(p: LatLon): Gate {
        val pathHeading = Geo.pathHeadingAt(path, p)
        val heading = if (pathHeading != null) (pathHeading + 90.0) % 360.0 else 0.0
        return Gate(p.lat, p.lon, heading, (2 * tolerance()).coerceAtLeast(20.0))
    }

    private fun onMapTap(p: LatLon) {
        when (picking) {
            Pick.START -> {
                start = defaultGate(p)
                picking = Pick.NONE
            }
            Pick.FINISH -> {
                finish = defaultGate(p)
                picking = Pick.NONE
            }
            Pick.SPLIT -> splits.add(SplitGate("Parziale ${splits.size + 1}", defaultGate(p)))
            Pick.NONE -> return
        }
        updateHint()
        redraw()
    }

    /* --------------------------- Disegno cancelli ------------------------ */

    private fun redraw() {
        gateOverlays.forEach { map.overlays.remove(it) }
        gateOverlays.clear()
        pathLine?.let { map.overlays.remove(it) }
        pathLine = null

        if (path.size > 1) {
            pathLine = Polyline().apply {
                setPoints(path.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = ContextCompat.getColor(this@TrackEditorActivity, R.color.path_blue)
                outlinePaint.strokeWidth = 8f
            }
            map.overlays.add(pathLine)
        }

        start?.let { g ->
            drawGate(
                g,
                ContextCompat.getColor(this, R.color.ok),
                "Partenza",
                onChange = { start = it },
                onCenterClick = { showGateDialog("Partenza", g, splitIndex = null) { start = it } },
            )
        }
        if (trackType() == TrackType.OPEN) {
            finish?.let { g ->
                drawGate(
                    g,
                    ContextCompat.getColor(this, R.color.danger),
                    "Traguardo",
                    onChange = { finish = it },
                    onCenterClick = { showGateDialog("Traguardo", g, splitIndex = null) { finish = it } },
                )
            }
        }
        splits.forEachIndexed { i, sp ->
            drawGate(
                sp.gate,
                ContextCompat.getColor(this, R.color.warn),
                sp.name,
                onChange = { splits[i] = SplitGate(sp.name, it) },
                onCenterClick = {
                    showGateDialog(sp.name, sp.gate, splitIndex = i) { splits[i] = SplitGate(splits[i].name, it) }
                },
            )
        }

        map.invalidate()
        updateStatus()
    }

    /**
     * Disegna un cancello: linea colorata, segnaposto centrale trascinabile
     * (spostamento) e cerchietto all'estremità trascinabile (rotazione e
     * larghezza). onChange riceve il cancello aggiornato.
     */
    private fun drawGate(
        g: Gate,
        color: Int,
        title: String,
        onChange: (Gate) -> Unit,
        onCenterClick: () -> Unit,
    ) {
        val (e1, e2) = Geo.gateEndpoints(g)

        val line = Polyline().apply {
            setPoints(listOf(GeoPoint(e1.lat, e1.lon), GeoPoint(e2.lat, e2.lon)))
            outlinePaint.color = color
            outlinePaint.strokeWidth = 12f
        }
        map.overlays.add(line)
        gateOverlays.add(line)

        val center = Marker(map).apply {
            position = GeoPoint(g.lat, g.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.title = title
            isDraggable = true
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) = Unit
                override fun onMarkerDragStart(marker: Marker) = Unit
                override fun onMarkerDragEnd(marker: Marker) {
                    onChange(g.copy(lat = marker.position.latitude, lon = marker.position.longitude))
                    redraw()
                }
            })
            setOnMarkerClickListener { _, _ ->
                onCenterClick()
                true
            }
        }
        map.overlays.add(center)
        gateOverlays.add(center)

        val handle = Marker(map).apply {
            position = GeoPoint(e1.lat, e1.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(this@TrackEditorActivity, R.drawable.ic_rotate_handle)
            isDraggable = true
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) = Unit
                override fun onMarkerDragStart(marker: Marker) = Unit
                override fun onMarkerDragEnd(marker: Marker) {
                    val pos = LatLon(marker.position.latitude, marker.position.longitude)
                    val newHeading = Geo.bearingDeg(g.center, pos)
                    val newWidth = (2 * Geo.haversine(g.center, pos))
                        .coerceIn(MIN_GATE_WIDTH, MAX_GATE_WIDTH)
                    onChange(g.copy(headingDeg = newHeading, widthM = newWidth))
                    redraw()
                }
            })
        }
        map.overlays.add(handle)
        gateOverlays.add(handle)
    }

    /**
     * Dialogo numerico del cancello: angolo e larghezza; per i parziali anche
     * nome ed eliminazione.
     */
    private fun showGateDialog(
        title: String,
        g: Gate,
        splitIndex: Int?,
        onChange: (Gate) -> Unit,
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val nameInput: EditText? = if (splitIndex != null) {
            EditText(this).apply {
                setText(splits[splitIndex].name)
                hint = getString(R.string.track_name_hint)
                container.addView(this)
            }
        } else null
        val angleInput = EditText(this).apply {
            setText(g.headingDeg.toInt().toString())
            hint = getString(R.string.gate_angle)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val angleLabel = TextView(this).apply { text = getString(R.string.gate_angle) }
        val widthInput = EditText(this).apply {
            setText(g.widthM.toInt().toString())
            hint = getString(R.string.gate_width)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val widthLabel = TextView(this).apply { text = getString(R.string.gate_width) }
        container.addView(angleLabel)
        container.addView(angleInput)
        container.addView(widthLabel)
        container.addView(widthInput)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val angle = ((angleInput.text.toString().toDoubleOrNull() ?: g.headingDeg) % 360.0 + 360.0) % 360.0
                val width = (widthInput.text.toString().toDoubleOrNull() ?: g.widthM)
                    .coerceIn(MIN_GATE_WIDTH, MAX_GATE_WIDTH)
                if (splitIndex != null && nameInput != null) {
                    val newName = nameInput.text.toString().ifBlank { splits[splitIndex].name }
                    splits[splitIndex] = SplitGate(newName, splits[splitIndex].gate)
                }
                onChange(g.copy(headingDeg = angle, widthM = width))
                redraw()
            }
            .setNegativeButton(R.string.cancel, null)
        if (splitIndex != null) {
            builder.setNeutralButton(R.string.action_delete) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.delete_split)
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        splits.removeAt(splitIndex)
                        redraw()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        builder.show()
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
                prevFix = lastFix
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
        pathLine?.let { map.overlays.remove(it) }
        pathLine = Polyline().apply {
            setPoints(path.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = ContextCompat.getColor(this@TrackEditorActivity, R.color.path_blue)
            outlinePaint.strokeWidth = 8f
        }
        map.overlays.add(pathLine)
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

    /** Cancello perpendicolare al percorso in corrispondenza di un suo punto. */
    private fun perpendicularGateAt(p: LatLon): Gate {
        val h = Geo.pathHeadingAt(path, p)
        val heading = if (h != null) (h + 90.0) % 360.0 else 0.0
        return Gate(p.lat, p.lon, heading, (2 * tolerance()).coerceAtLeast(20.0))
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
                path = Geo.simplify(path, 2.0).toMutableList()
                // Linee perpendicolari alla direzione del percorso nei due estremi.
                start = perpendicularGateAt(LatLon(path.first().lat, path.first().lon))
                finish = if (trackType() == TrackType.OPEN) {
                    perpendicularGateAt(LatLon(path.last().lat, path.last().lon))
                } else null
                splits.clear()
                splits.addAll(liveSplits)
                Toast.makeText(this, R.string.capture_done, Toast.LENGTH_LONG).show()
            }
            redraw()
        }
    }

    private fun markLiveSplit() {
        val p = lastFix ?: return
        // Perpendicolare alla direzione di moto corrente, se disponibile.
        val heading = prevFix?.let { (Geo.bearingDeg(it, p) + 90.0) % 360.0 } ?: 0.0
        liveSplits.add(
            SplitGate(
                "Parziale ${liveSplits.size + 1}",
                Gate(p.lat, p.lon, heading, (2 * tolerance()).coerceAtLeast(20.0)),
            )
        )
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
        val track = Track(
            id = editingId ?: TrackStore.newId(),
            name = name,
            type = type,
            tolerance = tolerance(),
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
