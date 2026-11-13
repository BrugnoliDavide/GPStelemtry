package com.gpstelemetry.app

import android.content.Intent
import android.os.Bundle

import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.Gpx
import com.gpstelemetry.core.Sectors
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.SessionStatus
import com.gpstelemetry.core.VehicleType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Dettaglio di una sessione: tempo e settori colorati rispetto allo storico,
 * VEICOLO usato (testo libero con suggerimenti), pubblicazione del giro
 * nella classifica della community, immagine da condividere, esportazioni
 * ed eliminazione.
 */
class SessionDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
    }

    private var session: Session? = null
    private var exportContent: String? = null
    private lateinit var vehicle: MaterialAutoCompleteTextView
    private lateinit var vehicleType: MaterialAutoCompleteTextView
    private var selectedType: VehicleType? = null
    private lateinit var publish: MaterialSwitch
    private lateinit var publishHint: TextView
    private var updatingSwitch = false

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
        setContentView(R.layout.activity_session)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        vehicle = findViewById(R.id.sdVehicle)
        vehicleType = findViewById(R.id.sdVehicleType)
        publish = findViewById(R.id.sdPublish)
        publishHint = findViewById(R.id.sdPublishHint)

        val id = intent.getStringExtra(EXTRA_SESSION_ID) ?: return finish()
        val appCtx = applicationContext
        thread(name = "session-detail") {
            val s = TrackStore.getSession(appCtx, id)
            val bench = s?.let { TrackStore.benchmark(appCtx, it.trackId, it.trackSplits.size, excludeId = it.id) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (s == null || bench == null) {
                    finish()
                } else {
                    session = s
                    bind(s, bench)
                }
            }
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun bind(s: Session, bench: Sectors.Benchmark) {
        val track = TrackStore.getTrack(this, s.trackId)
        findViewById<TextView>(R.id.sdTrack).text = track?.name ?: s.name.substringBefore(" — ")
        findViewById<TextView>(R.id.sdDate).text =
            SimpleDateFormat("EEEE d MMMM yyyy · HH:mm", Locale.ITALY).format(Date(s.startedAt))

        // Tempo e confronto.
        val r = s.result
        val total = r?.elapsedMs
        val finished = s.status == SessionStatus.FINISHED && total != null
        val time = findViewById<TextView>(R.id.sdTime)
        val delta = findViewById<TextView>(R.id.sdDelta)
        if (finished) {
            val rating = Sectors.rate(total!!, bench.lap)
            time.text = Fmt.time(total)
            time.setTextColor(TrackShape.ratingColor(this, rating))
            val ref = bench.lap
            delta.text = when {
                ref == null -> getString(R.string.first_record)
                total < ref.best -> getString(R.string.record) + "  −" + secs(ref.best - total)
                else -> getString(R.string.delta_best_fmt, "+" + secs(total - ref.best))
            }
            delta.setTextColor(TrackShape.ratingColor(this, rating))
        } else {
            time.text = r?.elapsedMs?.let { Fmt.time(it) } ?: "—"
            time.setTextColor(color(R.color.text_secondary))
            delta.text = getString(R.string.session_aborted)
            delta.setTextColor(color(R.color.text_secondary))
        }

        // Settori.
        val n = Sectors.count(s.trackSplits.size)
        val durations = Sectors.durations(r?.splits ?: emptyList(), if (finished) total else null)
        findViewById<SectorBarView>(R.id.sdSectors).setSectors(List(n) { i ->
            val d = durations.getOrNull(i)
            SectorBarView.Sector(
                label = if (n == 1) getString(R.string.sector_lap) else "S${i + 1}",
                timeMs = d,
                rating = d?.let { Sectors.rate(it, bench.sectors.getOrNull(i)) },
            )
        })

        // Resoconto: velocità, accelerazione, inclinazione, dislivello, uscite.
        val stats = com.gpstelemetry.core.LapStats.compute(s)
        Summary.bindAlert(findViewById(R.id.sdOffTrack), stats)
        Summary.fill(findViewById(R.id.sdSummary), Summary.tiles(this, stats), columns = 3, valueSp = 19f)

        bindVehicle(s)
        bindPublish(s)

        findViewById<MaterialButton>(R.id.sdShare).setOnClickListener {
            startActivity(Intent(this, ShareResultActivity::class.java).putExtra(ShareResultActivity.EXTRA_SESSION_ID, s.id))
        }
        findViewById<MaterialButton>(R.id.sdExport).setOnClickListener { showExport(s) }
        findViewById<MaterialButton>(R.id.sdDelete).setOnClickListener { confirmDelete(s) }
    }

    private fun secs(ms: Long) = String.format(Locale.US, "%.2f", abs(ms) / 1000.0)

    /* ------------------------------ Veicolo ------------------------------ */

    /**
     * Veicolo: categoria dal menu a tendina + modello libero, con i modelli
     * già usati per quella categoria come suggerimenti.
     */
    private fun bindVehicle(s: Session) {
        val types = VehicleType.entries
        vehicleType.setSimpleItems(types.map { it.label }.toTypedArray())
        // setText(…, false): mostra la scelta senza aprire/filtrare il menu.
        vehicleType.setText(s.vehicleType?.label ?: "", false)
        selectedType = s.vehicleType
        vehicleType.setOnItemClickListener { _, _, position, _ ->
            selectedType = types[position]
            refreshModelSuggestions()
            saveVehicle()
        }

        vehicle.setText(s.vehicle ?: "")
        refreshModelSuggestions()
        vehicle.setOnItemClickListener { _, _, _, _ -> saveVehicle() }
        vehicle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveVehicle()
                vehicle.clearFocus()
            }
            false
        }
        vehicle.setOnFocusChangeListener { _, has -> if (!has) saveVehicle() }
    }

    private fun refreshModelSuggestions() {
        val models = Profile.models(this, selectedType)
        vehicle.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, models))
    }

    private fun saveVehicle() {
        val s = session ?: return
        val model = vehicle.text.toString().trim().ifBlank { null }
        if (model == s.vehicle && selectedType == s.vehicleType) return
        s.vehicle = model
        s.vehicleType = selectedType
        Profile.rememberVehicle(this, selectedType, model)
        val appCtx = applicationContext
        thread(name = "save-vehicle") { TrackStore.saveSession(appCtx, s) }
        Toast.makeText(this, R.string.vehicle_saved, Toast.LENGTH_SHORT).show()
    }

    /* ---------------------------- Pubblicazione -------------------------- */

    private fun bindPublish(s: Session) {
        val blocker = ResultPublisher.blocker(this, s)
        updatingSwitch = true
        publish.isChecked = s.remoteLapId != null
        publish.isEnabled = blocker == null || s.remoteLapId != null
        updatingSwitch = false
        publishHint.text = when {
            s.remoteLapId != null -> getString(R.string.session_published_hint)
            blocker != null -> getString(blocker)
            else -> getString(R.string.session_publish_hint)
        }
        publish.setOnCheckedChangeListener { _, checked ->
            if (!updatingSwitch) onPublishToggled(s, checked)
        }
    }

    private fun onPublishToggled(s: Session, checked: Boolean) {
        saveVehicle() // il veicolo viaggia con il giro
        publish.isEnabled = false
        publishHint.text = getString(R.string.publishing)
        val done: (Boolean, String?) -> Unit = { ok, message ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!ok) {
                    Toast.makeText(this, getString(R.string.server_error, message ?: ""), Toast.LENGTH_LONG).show()
                } else if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
                bindPublish(s)
            }
        }
        if (checked) {
            ResultPublisher.submit(this, s) { r ->
                r.onSuccess { e -> done(true, getString(R.string.session_published_rank, e.rank)) }
                    .onFailure { done(false, it.message) }
            }
        } else {
            ResultPublisher.withdraw(this, s) { r ->
                r.onSuccess { done(true, getString(R.string.session_withdrawn)) }
                    .onFailure { done(false, it.message) }
            }
        }
    }

    /* ------------------------ Esportazione / elimina --------------------- */

    private fun showExport(s: Session) {
        val base = MainActivity.safeName(s.name)
        val items = arrayOf(
            getString(R.string.export_gpx),
            getString(R.string.export_csv_gps),
            getString(R.string.export_csv_motion),
            getString(R.string.export_csv_hr),
            getString(R.string.export_json),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_title)
            .setItems(items) { _, which ->
                val (name, content) = when (which) {
                    0 -> "$base.gpx" to Gpx.session(s)
                    1 -> "${base}_gps.csv" to Gpx.gpsCsv(s)
                    2 -> "${base}_motion.csv" to Gpx.motionCsv(s)
                    3 -> "${base}_hr.csv" to Gpx.hrCsv(s)
                    else -> "$base.json" to TrackStore.sessionJson(s)
                }
                exportContent = content
                exportLauncher.launch(name)
            }
            .show()
    }

    private fun confirmDelete(s: Session) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_delete_session)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                TrackStore.deleteSession(this, s.id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onPause() {
        saveVehicle()
        super.onPause()
    }
}
