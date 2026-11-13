package com.gpstelemetry.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Schermata principale con barra di navigazione in basso:
 *  1. Cronometra — tracciati personali, editor e cronometro;
 *  2. Esplora   — tracciati pubblicati e classifiche (predisposizione);
 *  3. Sessioni  — storico dei giri, esportazioni, immagini.
 *
 * I fragment restano in memoria (mostra/nascondi): cambiando scheda non si
 * perde la posizione di scorrimento.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
                Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val tabs = linkedMapOf(
        R.id.nav_timer to "tab_timer",
        R.id.nav_explore to "tab_explore",
        R.id.nav_sessions to "tab_sessions",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (savedInstanceState == null) {
            val tx = supportFragmentManager.beginTransaction()
            tabs.forEach { (id, tag) ->
                val f = newTab(id)
                tx.add(R.id.tabContainer, f, tag)
                if (id != R.id.nav_timer) tx.hide(f)
            }
            tx.commitNow()
        }
        askedPermissions = savedInstanceState?.getBoolean("askedPermissions") ?: false
        nav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        nav.setOnItemReselectedListener { item ->
            (fragment(item.itemId) as? TabFragment)?.scrollToTop()
        }
        showTab(nav.selectedItemId)
    }

    private var askedPermissions = false

    override fun onResume() {
        super.onResume()
        if (!Settings.tutorialDone(this)) {
            // Primo avvio: tutorial e avvertenze prima di tutto il resto.
            startActivity(Intent(this, TutorialActivity::class.java))
        } else if (!askedPermissions) {
            // I permessi si chiedono dopo il tutorial, non sopra.
            askedPermissions = true
            requestNeededPermissions()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("askedPermissions", askedPermissions)
    }

    private fun newTab(id: Int): Fragment = when (id) {
        R.id.nav_explore -> ExploreFragment()
        R.id.nav_sessions -> SessionsFragment()
        else -> TracksFragment()
    }

    private fun fragment(id: Int): Fragment? = supportFragmentManager.findFragmentByTag(tabs[id])

    private fun showTab(id: Int) {
        val tx = supportFragmentManager.beginTransaction()
        tabs.keys.forEach { key ->
            fragment(key)?.let { if (key == id) tx.show(it) else tx.hide(it) }
        }
        tx.commitNow()
    }

    /** Seleziona una scheda dal codice (es. test o collegamenti futuri). */
    fun selectTab(id: Int) {
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = id
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    companion object {
        fun safeName(s: String): String =
            s.replace(Regex("[^\\w \\-\\u00C0-\\u017F]+"), "").trim()
                .replace(Regex("\\s+"), "_")
                .ifBlank { "export" }
    }
}

/** Scheda della barra di navigazione. */
interface TabFragment {
    /** Nuovo tocco sulla scheda già attiva: torna in cima. */
    fun scrollToTop()
}
