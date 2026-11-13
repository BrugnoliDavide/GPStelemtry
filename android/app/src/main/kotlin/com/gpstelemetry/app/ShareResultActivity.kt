package com.gpstelemetry.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.concurrent.thread

/**
 * Anteprima dell'immagine riepilogativa di una sessione con i comandi
 * Condividi (foglio di condivisione di Android) e Salva (file PNG).
 */
class ShareResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
    }

    private var bitmap: Bitmap? = null
    private var fileBase = "risultato"

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            val bmp = bitmap ?: return@registerForActivityResult
            uri ?: return@registerForActivityResult
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        val image = findViewById<ImageView>(R.id.shareImage)
        val progress = findViewById<ProgressBar>(R.id.shareProgress)
        val btnShare = findViewById<MaterialButton>(R.id.btnShareNow)
        val btnSave = findViewById<MaterialButton>(R.id.btnSaveImage)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        btnShare.isEnabled = false
        btnSave.isEnabled = false

        val id = intent.getStringExtra(EXTRA_SESSION_ID) ?: return finish()
        val appCtx = applicationContext
        thread(name = "share-card") {
            val session = TrackStore.getSession(appCtx, id)
            if (session == null) {
                runOnUiThread { finish() }
                return@thread
            }
            val track = TrackStore.getTrack(appCtx, session.trackId)
            val bench = TrackStore.benchmark(appCtx, session.trackId, session.trackSplits.size, excludeId = id)
            val bmp = ShareCard.render(session, track, bench)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                bitmap = bmp
                fileBase = MainActivity.safeName(session.name)
                image.setImageBitmap(bmp)
                progress.visibility = View.GONE
                btnShare.isEnabled = true
                btnSave.isEnabled = true
            }
        }

        btnShare.setOnClickListener { share() }
        btnSave.setOnClickListener { saveLauncher.launch("$fileBase.png") }
    }

    private fun share() {
        val bmp = bitmap ?: return
        val dir = File(cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // tiene solo l'ultima immagine
        val file = File(dir, "$fileBase.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }
}
