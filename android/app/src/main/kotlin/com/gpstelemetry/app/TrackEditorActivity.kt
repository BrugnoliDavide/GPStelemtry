package com.gpstelemetry.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration as UiConfiguration
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
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
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

/**
 * Editor dei tracciati (mappa a tutto schermo).
 *
 * Partenza, traguardo e parziali sono LINEE di cronometraggio, disegnate
 * sulla mappa con la loro vera lunghezza e orientamento ([GateOverlay]):
 *  - si aggiungono con gli strumenti in basso e un tocco sulla mappa
 *    (orientate perpendicolari al percorso, se registrato);
 *  - si SPOSTANO trascinando il pallino centrale;
 *  - si RUOTANO trascinando la maniglia sull'asse della linea selezionata,
 *    oppure con i cursori di angolo e larghezza (e "Perpendicolare");
 *  - i parziali si rinominano o eliminano dal pannello della linea.
 *
 * Il percorso si registra percorrendolo con il GPS, oppure in presa diretta
 * (partenza = primo fix, traguardo = ultimo fix, parziali al volo).
 * Se il tracciato è pubblicato, il salvataggio aggiorna anche la versione
 * sulla community.
 */
class TrackEditorActivity : AppCompatActivity(), GateOverlay.Listener {

    companion object {
        const val EXTRA_TRACK_ID = "trackId"
        private const val MIN_GATE_WIDTH = 4.0
        private const val MAX_GATE_WIDTH = 500.0
        private const val KEY_START = "start"
        private const val KEY_FINISH = "finish"
        private const val SPLIT_PREFIX = "split:"
    }

    private enum class Pick { NONE, START, FINISH, SPLIT }

    private lateinit var map: MapView
    private var copyright: org.osmdroid.views.overlay.CopyrightOverlay? = null
    private var layer = MapLayers.Layer.SATELLITE
    private lateinit var gates: GateOverlay
    private lateinit var edName: EditText
    private lateinit var edHint: TextView
    private lateinit var edStatus: TextView
    private lateinit var btnRecordPath: MaterialButton
    private lateinit var btnLiveCapture: MaterialButton
    private lateinit var btnLiveSplit: MaterialButton
    private lateinit var chipTolerance: Chip
    private lateinit var toolsPanel: LinearLayout
    private lateinit var gatePanel: LinearLayout
    private lateinit var angleSlider: Slider
    private lateinit var widthSlider: Slider

    private lateinit var fused: FusedLocationProviderClient

    private var editingId: String? = null
    private var original: Track? = null
    private var createdAt: Long = 0
    private var type = TrackType.CIRCULAR
    private var tolerance = 15.0
    private var start: Gate? = null
    private var finish: Gate? = null
    private val splits = mutableListOf<SplitGate>()
    private var path = mutableListOf<PathPoint>()

    private var picking = Pick.NONE
    private var selectedKey: String? = null
    private var dirty = false
    private var recordingPath = false
    private var liveCapture = false
    private val liveSplits = mutableListOf<SplitGate>()
    private var lastFix: LatLon? = null
    private var prevFix: LatLon? = null
    private var centeredOnce = false
    private var pathLine: Polyline? = null
    private var bindingPanel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Richiesto dalla policy dei tile server OSM.
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_editor)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fused = LocationServices.getFusedLocationProviderClient(this)

        edName = findViewById(R.id.edName)
        edHint = findViewById(R.id.edHint)
        edStatus = findViewById(R.id.edStatus)
        btnRecordPath = findViewById(R.id.btnRecordPath)
        btnLiveCapture = findViewById(R.id.btnLiveCapture)
        btnLiveSplit = findViewById(R.id.btnLiveSplit)
        chipTolerance = findViewById(R.id.chipTolerance)
        toolsPanel = findViewById(R.id.toolsPanel)
        gatePanel = findViewById(R.id.gatePanel)
        angleSlider = findViewById(R.id.gateAngle)
        widthSlider = findViewById(R.id.gateWidth)

        setupMap()
        loadExisting()
        setupTopBar()
        setupTools()
        setupGatePanel()
        setupBack()
        redraw()
        if (path.size > 1 || start != null) map.post { fitToTrack() }
    }

    /* ------------------------------ Caricamento -------------------------- */

    private fun loadExisting() {
        val id = intent.getStringExtra(EXTRA_TRACK_ID) ?: return
        val t = TrackStore.getTrack(this, id) ?: return
        original = t
        editingId = t.id
        createdAt = t.createdAt
        edName.setText(t.name)
        type = t.type
        tolerance = t.tolerance
        start = t.start
        finish = t.finish
        splits.addAll(t.splits)
        path = t.path.toMutableList()
        centeredOnce = true
    }

    /* ------------------------------- Mappa ------------------------------- */

    private fun setupMap() {
        map = findViewById(R.id.map)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(16.0)
        map.minZoomLevel = 3.0
        // Sfondo: satellite (predefinito) o mappa stradale, con attribuzione.
        val density = resources.displayMetrics.density
        copyright = MapLayers.copyrightOverlay(this).apply {
            setOffset((16 * density).toInt(), (136 * density).toInt())
        }
        layer = MapLayers.get(this)
        MapLayers.apply(map, layer, copyright)

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (picking != Pick.NONE) {
                    onMapTap(LatLon(p.latitude, p.longitude))
                    return true
                }
                if (selectedKey != null) {
                    select(null)
                    return true
                }
                return false
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        gates = GateOverlay(this, this)
        map.overlays.add(gates)
        copyright?.let { map.overlays.add(it) }

        if (!centeredOnce && hasLocationPermission()) centerOnCurrentLocation(16.0)
    }

    /** Alterna satellite / mappa stradale e ricorda la scelta. */
    private fun toggleLayer() {
        layer = if (layer == MapLayers.Layer.SATELLITE) MapLayers.Layer.MAP else MapLayers.Layer.SATELLITE
        MapLayers.set(this, layer)
        MapLayers.apply(map, layer, copyright)
        updateLayerButton()
        Toast.makeText(
            this,
            if (layer == MapLayers.Layer.SATELLITE) R.string.editor_layer_satellite else R.string.editor_layer_map,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Il pulsante mostra il livello a cui si passa. */
    private fun updateLayerButton() {
        findViewById<ImageButton>(R.id.btnLayer).setImageResource(
            if (layer == MapLayers.Layer.SATELLITE) R.drawable.ic_map else R.drawable.ic_satellite
        )
    }

    @SuppressLint("MissingPermission")
    private fun centerOnCurrentLocation(zoom: Double) {
        if (!hasLocationPermission()) {
            Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
            return
        }
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                centeredOnce = true
                map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude), zoom, 500L)
            }
        }
    }

    /** Inquadra percorso e linee. */
    private fun fitToTrack() {
        val pts = path.map { GeoPoint(it.lat, it.lon) } +
            listOfNotNull(start, finish).map { GeoPoint(it.lat, it.lon) } +
            splits.map { GeoPoint(it.gate.lat, it.gate.lon) }
        if (pts.isEmpty()) {
            centerOnCurrentLocation(17.0)
            return
        }
        if (pts.size == 1) {
            map.controller.animateTo(pts[0], 18.0, 500L)
            return
        }
        val box = BoundingBox.fromGeoPointsSafe(pts)
        val pad = (resources.displayMetrics.density * 96).toInt()
        map.zoomToBoundingBox(box.increaseByScale(1.15f), true, pad)
    }

    /** Avvicina la mappa perché la linea sia ben visibile e modificabile. */
    private fun focusOn(g: Gate) {
        val zoom = maxOf(map.zoomLevelDouble, 18.0)
        map.controller.animateTo(GeoPoint(g.lat, g.lon), zoom, 400L)
    }

    /**
     * Cancello predefinito nel punto toccato: perpendicolare al percorso se
     * registrato, larghezza doppia della tolleranza.
     */
    private fun defaultGate(p: LatLon): Gate {
        val pathHeading = Geo.pathHeadingAt(path, p)
        val heading = if (pathHeading != null) GateOverlay.normalize(pathHeading + 90.0) else 0.0
        return Gate(p.lat, p.lon, heading, (2 * tolerance).coerceAtLeast(20.0))
    }

    private fun onMapTap(p: LatLon) {
        val g = defaultGate(p)
        when (picking) {
            Pick.START -> {
                start = g
                setPicking(Pick.NONE)
                select(KEY_START)
            }
            Pick.FINISH -> {
                finish = g
                setPicking(Pick.NONE)
                select(KEY_FINISH)
            }
            Pick.SPLIT -> {
                splits.add(SplitGate(getString(R.string.split_default_name, splits.size + 1), g))
                Toast.makeText(this, getString(R.string.split_added, splits.size), Toast.LENGTH_SHORT).show()
            }
            Pick.NONE -> return
        }
        dirty = true
        redraw()
        if (map.zoomLevelDouble < 17.5) focusOn(g)
    }

    /* ------------------------------ Linee -------------------------------- */

    private fun gateFor(key: String): Gate? = when {
        key == KEY_START -> start
        key == KEY_FINISH -> finish
        key.startsWith(SPLIT_PREFIX) -> splits.getOrNull(key.removePrefix(SPLIT_PREFIX).toInt())?.gate
        else -> null
    }

    private fun setGate(key: String, g: Gate) {
        when {
            key == KEY_START -> start = g
            key == KEY_FINISH -> finish = g
            key.startsWith(SPLIT_PREFIX) -> {
                val i = key.removePrefix(SPLIT_PREFIX).toInt()
                if (i in splits.indices) splits[i] = splits[i].copy(gate = g)
            }
        }
        dirty = true
    }

    private fun titleFor(key: String): String = when {
        key == KEY_START -> getString(if (type == TrackType.CIRCULAR) R.string.gate_start_finish else R.string.gate_start)
        key == KEY_FINISH -> getString(R.string.gate_finish)
        else -> {
            val i = key.removePrefix(SPLIT_PREFIX).toInt()
            "P${i + 1} · ${splits.getOrNull(i)?.name ?: ""}"
        }
    }

    private fun redraw() {
        pathLine?.let { map.overlays.remove(it) }
        pathLine = null
        if (path.size > 1) {
            pathLine = Polyline().apply {
                setPoints(path.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = ContextCompat.getColor(this@TrackEditorActivity, R.color.path_blue)
                outlinePaint.strokeWidth = 7 * resources.displayMetrics.density
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            }
            map.overlays.add(1, pathLine) // sotto le linee di cronometraggio
        }

        val items = mutableListOf<GateOverlay.Item>()
        start?.let {
            items += GateOverlay.Item(
                KEY_START,
                getString(if (type == TrackType.CIRCULAR) R.string.gate_label_start_finish else R.string.gate_label_start),
                ContextCompat.getColor(this, R.color.gate_start), it, checkered = true,
            )
        }
        if (type == TrackType.OPEN) finish?.let {
            items += GateOverlay.Item(
                KEY_FINISH, getString(R.string.gate_label_finish),
                ContextCompat.getColor(this, R.color.gate_finish), it, checkered = true,
            )
        }
        splits.forEachIndexed { i, sp ->
            items += GateOverlay.Item(
                "$SPLIT_PREFIX$i", "P${i + 1}",
                ContextCompat.getColor(this, R.color.accent), sp.gate, checkered = false,
            )
        }
        gates.items = items
        if (selectedKey != null && items.none { it.key == selectedKey }) select(null)
        gates.selectedKey = selectedKey
        map.invalidate()
        updateStatus()
    }

    /* ---------------------- Eventi del livello linee --------------------- */

    override fun onGateChanging(key: String, gate: Gate) {
        if (key == selectedKey) bindGatePanel(key, gate)
    }

    override fun onGateChanged(key: String, gate: Gate) {
        setGate(key, gate)
        redraw()
        if (key == selectedKey) bindGatePanel(key, gate)
    }

    override fun onGateTapped(key: String) {
        setPicking(Pick.NONE)
        select(if (selectedKey == key) null else key)
    }

    /* --------------------------- Pannello linea -------------------------- */

    private fun select(key: String?) {
        selectedKey = key
        gates.selectedKey = key
        val g = key?.let { gateFor(it) }
        if (key != null && g != null) {
            toolsPanel.visibility = View.GONE
            gatePanel.visibility = View.VISIBLE
            bindGatePanel(key, g)
            if (map.zoomLevelDouble < 17.5) focusOn(g)
        } else {
            selectedKey = null
            gates.selectedKey = null
            toolsPanel.visibility = View.VISIBLE
            gatePanel.visibility = View.GONE
        }
        map.invalidate()
    }

    private fun bindGatePanel(key: String, g: Gate) {
        bindingPanel = true
        findViewById<TextView>(R.id.gateTitle).text = titleFor(key)
        val angle = GateOverlay.normalize(g.headingDeg).roundToInt().coerceIn(0, 179)
        angleSlider.value = angle.toFloat()
        val w = g.widthM.roundToInt().coerceIn(MIN_GATE_WIDTH.toInt(), MAX_GATE_WIDTH.toInt())
        widthSlider.valueTo = maxOf(200f, w.toFloat())
        widthSlider.value = w.toFloat()
        findViewById<TextView>(R.id.gateAngleLabel).text = getString(R.string.gate_angle_fmt, angle)
        findViewById<TextView>(R.id.gateWidthLabel).text = getString(R.string.gate_width_fmt, w)
        val isSplit = key.startsWith(SPLIT_PREFIX)
        findViewById<View>(R.id.gateRename).visibility = if (isSplit) View.VISIBLE else View.GONE
        findViewById<View>(R.id.gateDelete).visibility = if (key == KEY_START) View.GONE else View.VISIBLE
        bindingPanel = false
    }

    private fun setupGatePanel() {
        angleSlider.addOnChangeListener { _, value, fromUser ->
            if (bindingPanel || !fromUser) return@addOnChangeListener
            val key = selectedKey ?: return@addOnChangeListener
            val g = gateFor(key) ?: return@addOnChangeListener
            setGate(key, g.copy(headingDeg = value.toDouble()))
            findViewById<TextView>(R.id.gateAngleLabel).text = getString(R.string.gate_angle_fmt, value.toInt())
            redraw()
        }
        widthSlider.addOnChangeListener { _, value, fromUser ->
            if (bindingPanel || !fromUser) return@addOnChangeListener
            val key = selectedKey ?: return@addOnChangeListener
            val g = gateFor(key) ?: return@addOnChangeListener
            setGate(key, g.copy(widthM = value.toDouble()))
            findViewById<TextView>(R.id.gateWidthLabel).text = getString(R.string.gate_width_fmt, value.toInt())
            redraw()
        }
        findViewById<View>(R.id.gatePerpendicular).setOnClickListener {
            val key = selectedKey ?: return@setOnClickListener
            val g = gateFor(key) ?: return@setOnClickListener
            val h = Geo.pathHeadingAt(path, g.center)
            if (h == null) {
                Toast.makeText(this, R.string.editor_need_path, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ng = g.copy(headingDeg = GateOverlay.normalize(h + 90.0))
            setGate(key, ng)
            redraw()
            bindGatePanel(key, ng)
        }
        findViewById<View>(R.id.gateDone).setOnClickListener { select(null) }
        findViewById<View>(R.id.gateRename).setOnClickListener { renameSelectedSplit() }
        findViewById<View>(R.id.gateDelete).setOnClickListener { deleteSelected() }
    }

    private fun renameSelectedSplit() {
        val key = selectedKey ?: return
        val i = key.removePrefix(SPLIT_PREFIX).toIntOrNull() ?: return
        val input = EditText(this).apply {
            setText(splits[i].name)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    splits[i] = splits[i].copy(name = name)
                    dirty = true
                    redraw()
                    gateFor(key)?.let { bindGatePanel(key, it) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteSelected() {
        val key = selectedKey ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(if (key == KEY_FINISH) R.string.delete_finish else R.string.delete_split)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                when {
                    key == KEY_FINISH -> finish = null
                    key.startsWith(SPLIT_PREFIX) -> splits.removeAt(key.removePrefix(SPLIT_PREFIX).toInt())
                }
                dirty = true
                select(null)
                redraw()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* ----------------------------- Barra alta ---------------------------- */

    private fun setupTopBar() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        val chipCircular = findViewById<Chip>(R.id.chipCircular)
        val chipOpen = findViewById<Chip>(R.id.chipOpen)
        chipCircular.isChecked = type == TrackType.CIRCULAR
        chipOpen.isChecked = type == TrackType.OPEN
        chipCircular.setOnCheckedChangeListener { _, c -> if (c) setType(TrackType.CIRCULAR) }
        chipOpen.setOnCheckedChangeListener { _, c -> if (c) setType(TrackType.OPEN) }
        updateToleranceChip()
        chipTolerance.setOnClickListener { editTolerance() }
        findViewById<View>(R.id.btnFit).setOnClickListener { fitToTrack() }
        findViewById<View>(R.id.btnLayer).setOnClickListener { toggleLayer() }
        updateLayerButton()
        findViewById<View>(R.id.btnMyLocation).setOnClickListener { centerOnCurrentLocation(18.0) }
        edName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { dirty = true }
        })
        dirty = false
    }

    private fun setType(t: TrackType) {
        if (type == t) return
        type = t
        dirty = true
        if (selectedKey == KEY_FINISH && t == TrackType.CIRCULAR) select(null)
        updateHint()
        redraw()
    }

    private fun updateToleranceChip() {
        chipTolerance.text = "± " + Units.length(tolerance)
    }

    private fun editTolerance() {
        val label = TextView(this).apply {
            text = getString(R.string.tolerance_hint)
            setTextColor(ContextCompat.getColor(this@TrackEditorActivity, R.color.text_secondary))
        }
        val value = TextView(this).apply {
            textSize = 28f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@TrackEditorActivity, R.color.text_primary))
            text = "± " + Units.length(tolerance)
        }
        val slider = Slider(this).apply {
            valueFrom = 5f
            valueTo = 100f
            stepSize = 1f
            this.value = tolerance.toFloat().coerceIn(5f, 100f)
            addOnChangeListener { _, v, _ -> value.text = "± " + Units.length(v.toDouble()) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (22 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(label)
            addView(value)
            addView(slider)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_tolerance_title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                tolerance = slider.value.toDouble()
                dirty = true
                updateToleranceChip()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* ----------------------------- Strumenti ----------------------------- */

    private fun setPicking(p: Pick) {
        picking = p
        findViewById<Chip>(R.id.btnPickStart).isChecked = p == Pick.START
        findViewById<Chip>(R.id.btnPickFinish).isChecked = p == Pick.FINISH
        findViewById<Chip>(R.id.btnPickSplit).isChecked = p == Pick.SPLIT
        updateHint()
    }

    private fun setupTools() {
        findViewById<Chip>(R.id.btnPickStart).setOnClickListener {
            select(null)
            setPicking(if (picking == Pick.START) Pick.NONE else Pick.START)
        }
        findViewById<Chip>(R.id.btnPickFinish).setOnClickListener {
            select(null)
            if (type == TrackType.CIRCULAR) {
                Toast.makeText(this, R.string.finish_equals_start, Toast.LENGTH_SHORT).show()
                setPicking(Pick.NONE)
            } else {
                setPicking(if (picking == Pick.FINISH) Pick.NONE else Pick.FINISH)
            }
        }
        findViewById<Chip>(R.id.btnPickSplit).setOnClickListener {
            select(null)
            setPicking(if (picking == Pick.SPLIT) Pick.NONE else Pick.SPLIT)
        }
        btnRecordPath.setOnClickListener { toggleRecordPath() }
        btnLiveCapture.setOnClickListener { toggleLiveCapture() }
        btnLiveSplit.setOnClickListener { markLiveSplit() }
        updateHint()
    }

    private fun updateHint() {
        edHint.text = when (picking) {
            Pick.NONE -> getString(R.string.editor_hint_idle)
            Pick.START -> getString(R.string.editor_hint_pick, getString(R.string.gate_start).lowercase())
            Pick.FINISH -> getString(R.string.editor_hint_pick, getString(R.string.gate_finish).lowercase())
            Pick.SPLIT -> getString(R.string.editor_hint_split)
        }
    }

    private fun updateStatus() {
        val pathStr = if (path.size > 1) {
            getString(R.string.editor_status_path, Fmt.dist(Geo.pathLength(path)))
        } else {
            getString(R.string.editor_status_no_path)
        }
        edStatus.text = listOfNotNull(
            if (start != null) "✓ " + getString(R.string.gate_start).lowercase() else "✗ " + getString(R.string.gate_start).lowercase(),
            if (type == TrackType.OPEN) {
                (if (finish != null) "✓ " else "✗ ") + getString(R.string.gate_finish).lowercase()
            } else null,
            resources.getQuantityString(R.plurals.editor_status_splits, splits.size, splits.size),
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
            outlinePaint.strokeWidth = 7 * resources.displayMetrics.density
        }
        map.overlays.add(1, pathLine)
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
            dirty = true
            redraw()
        }
    }

    /** Cancello perpendicolare al percorso in corrispondenza di un suo punto. */
    private fun perpendicularGateAt(p: LatLon): Gate {
        val h = Geo.pathHeadingAt(path, p)
        val heading = if (h != null) GateOverlay.normalize(h + 90.0) else 0.0
        return Gate(p.lat, p.lon, heading, (2 * tolerance).coerceAtLeast(20.0))
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
            btnLiveSplit.visibility = View.VISIBLE
            Toast.makeText(this, R.string.capture_started, Toast.LENGTH_LONG).show()
        } else {
            liveCapture = false
            fused.removeLocationUpdates(locationCallback)
            btnLiveCapture.setText(R.string.live_capture)
            btnLiveSplit.visibility = View.GONE
            if (path.size < 2) {
                Toast.makeText(this, R.string.not_enough_points, Toast.LENGTH_LONG).show()
            } else {
                path = Geo.simplify(path, 2.0).toMutableList()
                // Linee perpendicolari alla direzione del percorso nei due estremi.
                start = perpendicularGateAt(LatLon(path.first().lat, path.first().lon))
                finish = if (type == TrackType.OPEN) {
                    perpendicularGateAt(LatLon(path.last().lat, path.last().lon))
                } else null
                splits.clear()
                splits.addAll(liveSplits)
                dirty = true
                Toast.makeText(this, R.string.capture_done, Toast.LENGTH_LONG).show()
            }
            redraw()
            fitToTrack()
        }
    }

    private fun markLiveSplit() {
        val p = lastFix ?: return
        // Perpendicolare alla direzione di moto corrente, se disponibile.
        val heading = prevFix?.let { GateOverlay.normalize(Geo.bearingDeg(it, p) + 90.0) } ?: 0.0
        liveSplits.add(
            SplitGate(
                getString(R.string.split_default_name, liveSplits.size + 1),
                Gate(p.lat, p.lon, heading, (2 * tolerance).coerceAtLeast(20.0)),
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
            edName.requestFocus()
            return
        }
        val s = start ?: run {
            Toast.makeText(this, R.string.start_missing, Toast.LENGTH_SHORT).show()
            return
        }
        if (type == TrackType.OPEN && finish == null) {
            Toast.makeText(this, R.string.finish_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val old = original
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
            published = old?.published ?: false,
            remoteId = old?.remoteId,
            remoteAuthor = old?.remoteAuthor,
        )
        TrackStore.saveTrack(this, track)
        // Tracciato pubblicato: aggiorna anche la versione sulla community.
        val acc = Profile.account(this)
        if (track.published && acc != null) {
            val app = applicationContext
            Community.api.publishTrack(acc, track) { r ->
                r.onFailure {
                    runOnUiThread {
                        Toast.makeText(app, app.getString(R.string.server_error, it.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        Toast.makeText(this, R.string.track_saved, Toast.LENGTH_SHORT).show()
        dirty = false
        finish()
    }

    /* ------------------------------ Indietro ----------------------------- */

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selectedKey != null -> select(null)
                    picking != Pick.NONE -> setPicking(Pick.NONE)
                    dirty -> MaterialAlertDialogBuilder(this@TrackEditorActivity)
                        .setTitle(R.string.editor_discard_title)
                        .setMessage(R.string.editor_discard_text)
                        .setPositiveButton(R.string.editor_discard) { _, _ -> finish() }
                        .setNeutralButton(R.string.save) { _, _ -> save() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    else -> finish()
                }
            }
        })
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
