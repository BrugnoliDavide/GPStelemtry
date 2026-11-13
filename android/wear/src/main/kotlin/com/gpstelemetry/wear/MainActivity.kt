package com.gpstelemetry.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import com.google.android.gms.wearable.Wearable
import kotlin.math.roundToInt

/**
 * App Wear OS: misura la frequenza cardiaca con Health Services (sensore
 * dell'orologio, inclusi Samsung Galaxy Watch 4 e successivi) e invia i
 * campioni all'app telefono tramite Data Layer (MessageClient), percorso
 * /gpstelemetry/hr con payload "timestampMillis;bpm".
 *
 * Uso: avviare la misurazione sull'orologio prima o durante la sessione sul
 * telefono; l'app telefono associa i campioni ricevuti alla sessione in corso.
 */
class MainActivity : Activity() {

    companion object {
        private const val HR_PATH = "/gpstelemetry/hr"
        private const val PERMISSION_REQUEST = 10
    }

    private lateinit var bpmText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnToggle: Button

    private var measureClient: MeasureClient? = null
    private var measuring = false
    private var nodeIds: List<String> = emptyList()
    private var lastSentT = 0L

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            if (availability is DataTypeAvailability && availability != DataTypeAvailability.AVAILABLE) {
                runOnUiThread { statusText.text = getString(R.string.measuring) }
            }
        }

        override fun onDataReceived(data: DataPointContainer) {
            val samples = data.getData(DataType.HEART_RATE_BPM)
            val last = samples.lastOrNull() ?: return
            val bpm = last.value.roundToInt()
            if (bpm <= 0) return
            runOnUiThread {
                bpmText.text = bpm.toString()
                statusText.text = if (nodeIds.isEmpty()) {
                    getString(R.string.measuring)
                } else {
                    getString(R.string.sending)
                }
            }
            sendToPhone(bpm)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bpmText = findViewById(R.id.bpmText)
        statusText = findViewById(R.id.statusText)
        btnToggle = findViewById(R.id.btnToggle)

        measureClient = HealthServices.getClient(this).measureClient

        btnToggle.setOnClickListener {
            if (measuring) stopMeasuring() else requestAndStart()
        }

        refreshNodes()
    }

    private fun refreshNodes() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes -> nodeIds = nodes.map { it.id } }
    }

    private fun requestAndStart() {
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), PERMISSION_REQUEST)
        } else {
            startMeasuring()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMeasuring()
            } else {
                statusText.text = getString(R.string.no_permission)
            }
        }
    }

    private fun startMeasuring() {
        val client = measureClient ?: return
        measuring = true
        btnToggle.text = getString(R.string.stop_hr)
        statusText.text = getString(R.string.measuring)
        refreshNodes()
        client.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
    }

    private fun stopMeasuring() {
        measuring = false
        btnToggle.text = getString(R.string.start_hr)
        statusText.text = getString(R.string.idle)
        measureClient?.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
    }

    private fun sendToPhone(bpm: Int) {
        val now = System.currentTimeMillis()
        // Limita l'invio a ~1 campione al secondo.
        if (now - lastSentT < 1000) return
        lastSentT = now
        if (nodeIds.isEmpty()) {
            refreshNodes()
            return
        }
        val payload = "$now;$bpm".toByteArray(Charsets.UTF_8)
        val mc = Wearable.getMessageClient(this)
        for (id in nodeIds) {
            mc.sendMessage(id, HR_PATH, payload)
        }
    }

    override fun onDestroy() {
        if (measuring) {
            measureClient?.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        }
        super.onDestroy()
    }
}
