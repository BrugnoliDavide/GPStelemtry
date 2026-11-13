package com.gpstelemetry.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.gpstelemetry.core.Nickname
import com.gpstelemetry.core.NicknameCheck
import com.gpstelemetry.core.ProfileVisibility
import kotlin.concurrent.thread

/**
 * Profilo utente:
 *  - foto profilo (galleria → ritaglio quadrato → caricamento sul server);
 *  - nickname: verifica di disponibilità sul server mentre si scrive
 *    (con suggerimenti se è preso) e registrazione;
 *  - community: profilo pubblico/privato, invio automatico dei risultati;
 *  - aspetto: tema scuro / chiaro / di sistema;
 *  - spazio per le impostazioni future.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var avatar: AvatarView
    private lateinit var edNick: EditText
    private lateinit var nickStatus: TextView
    private lateinit var suggestions: ChipGroup
    private lateinit var btnSave: MaterialButton
    private lateinit var registeredAs: TextView
    private lateinit var swPublic: MaterialSwitch
    private lateinit var swAuto: MaterialSwitch

    private val api get() = Community.api
    private val handler = Handler(Looper.getMainLooper())
    private var checkSeq = 0
    private var lastCheck: NicknameCheck? = null
    private var busy = false

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onPhotoPicked(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        avatar = findViewById(R.id.avatar)
        edNick = findViewById(R.id.edNickname)
        nickStatus = findViewById(R.id.nickStatus)
        suggestions = findViewById(R.id.nickSuggestions)
        btnSave = findViewById(R.id.btnSaveNick)
        registeredAs = findViewById(R.id.registeredAs)
        swPublic = findViewById(R.id.swPublicProfile)
        swAuto = findViewById(R.id.swAutoPublish)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        if (api.isMock) {
            findViewById<View>(R.id.mockBadge).visibility = View.VISIBLE
            findViewById<View>(R.id.serverNote).visibility = View.VISIBLE
        }

        val choosePhoto = View.OnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        avatar.setOnClickListener(choosePhoto)
        findViewById<View>(R.id.btnPhoto).setOnClickListener(choosePhoto)

        val account = Profile.account(this)
        account?.let { edNick.setText(it.nickname) }
        edNick.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = scheduleCheck()
        })
        btnSave.setOnClickListener { saveNickname() }

        swPublic.isChecked = (account?.visibility ?: ProfileVisibility.PUBLIC) == ProfileVisibility.PUBLIC
        swPublic.setOnCheckedChangeListener { _, checked -> onVisibilityChanged(checked) }
        swAuto.isChecked = Profile.autoPublishResults(this)
        swAuto.setOnCheckedChangeListener { _, checked -> Profile.setAutoPublishResults(this, checked) }

        setupTheme()
        setupUnits()
        setupUpcomingSettings()
        refreshHeader()
    }

    /* ------------------------- Unità di misura --------------------------- */

    private fun setupUnits() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.unitsGroup)
        group.check(if (Settings.units(this) == UnitSystem.IMPERIAL) R.id.unitsImperial else R.id.unitsMetric)
        updateUnitsExample()
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            Settings.setUnits(this, if (id == R.id.unitsImperial) UnitSystem.IMPERIAL else UnitSystem.METRIC)
            updateUnitsExample()
        }
    }

    /** Anteprima del formato scelto (velocità, distanza, quota). */
    private fun updateUnitsExample() {
        findViewById<TextView>(R.id.unitsExample).text =
            getString(R.string.settings_units_example, Units.speed(150.0), Units.dist(2080.0), Units.length(212.0))
    }

    /* ------------------- Impostazioni predisposte ------------------------ */

    private fun setupUpcomingSettings() {
        findViewById<MaterialSwitch>(R.id.swNotifyRecord).apply {
            isChecked = Settings.notifyRecordBeaten(this@ProfileActivity)
            setOnCheckedChangeListener { _, c -> Settings.setNotifyRecordBeaten(this@ProfileActivity, c) }
        }
        findViewById<MaterialSwitch>(R.id.swNotifyFriends).apply {
            isChecked = Settings.notifyFriendsLaps(this@ProfileActivity)
            setOnCheckedChangeListener { _, c -> Settings.setNotifyFriendsLaps(this@ProfileActivity, c) }
        }
        val lb = findViewById<MaterialButtonToggleGroup>(R.id.leaderboardGroup)
        lb.check(if (Settings.leaderboardMode(this) == Settings.LeaderboardMode.ALL_LAPS) R.id.lbAll else R.id.lbBest)
        lb.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                Settings.setLeaderboardMode(
                    this,
                    if (id == R.id.lbAll) Settings.LeaderboardMode.ALL_LAPS else Settings.LeaderboardMode.BEST_ONLY,
                )
            }
        }
        findViewById<View>(R.id.btnReplayTutorial).setOnClickListener {
            startActivity(
                android.content.Intent(this, TutorialActivity::class.java)
                    .putExtra(TutorialActivity.EXTRA_REPLAY, true)
            )
        }
        val accepted = Settings.disclaimerAcceptedAt(this)
        findViewById<TextView>(R.id.disclaimerAccepted).apply {
            visibility = if (accepted > 0) View.VISIBLE else View.GONE
            text = getString(
                R.string.settings_disclaimer_accepted,
                java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", java.util.Locale.ITALY).format(java.util.Date(accepted)),
            )
        }
        findViewById<View>(R.id.btnDeleteTimes).setOnClickListener { confirmDeleteTimes() }
        findViewById<View>(R.id.btnDeleteProfile).setOnClickListener { confirmDeleteProfile() }
    }

    private fun accountOrWarn(): com.gpstelemetry.core.UserAccount? =
        Profile.account(this).also {
            if (it == null) Toast.makeText(this, R.string.publish_block_no_nickname, Toast.LENGTH_LONG).show()
        }

    private fun confirmDeleteTimes() {
        val acc = accountOrWarn() ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_delete_times)
            .setMessage(R.string.settings_delete_times_confirm)
            .setPositiveButton(R.string.settings_delete_confirm_button) { _, _ ->
                api.deleteAllLaps(acc) { r -> runOnUiThread { showResult(r.exceptionOrNull()) } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Conferma rafforzata: bisogna riscrivere il proprio nickname. */
    private fun confirmDeleteProfile() {
        val acc = accountOrWarn() ?: return
        val input = EditText(this).apply {
            hint = acc.nickname
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val box = android.widget.LinearLayout(this).apply {
            val p = (22 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 3, p, 0)
            addView(input)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_delete_profile)
            .setMessage(getString(R.string.settings_delete_profile_confirm, acc.nickname))
            .setView(box)
            .setPositiveButton(R.string.settings_delete_confirm_button) { _, _ ->
                api.deleteAccount(acc) { r -> runOnUiThread { showResult(r.exceptionOrNull()) } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        val ok = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        ok.isEnabled = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                ok.isEnabled = s.toString().trim() == acc.nickname
            }
        })
    }

    private fun showResult(error: Throwable?) {
        if (isDestroyed) return
        Toast.makeText(this, error?.message ?: getString(R.string.settings_done), Toast.LENGTH_LONG).show()
    }

    private fun refreshHeader() {
        val a = Profile.account(this)
        avatar.set(Profile.avatar(this), a?.nickname ?: edNick.text.toString())
        registeredAs.text = a?.let { getString(R.string.profile_registered_as, it.nickname) }
            ?: getString(R.string.profile_not_registered)
        updateSaveButton()
    }

    /* ------------------------------ Nickname ----------------------------- */

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun setStatus(text: String, colorRes: Int) {
        nickStatus.text = text
        nickStatus.setTextColor(color(colorRes))
    }

    /** Verifica dopo una breve pausa di scrittura (debounce). */
    private fun scheduleCheck() {
        handler.removeCallbacksAndMessages(null)
        lastCheck = null
        suggestions.removeAllViews()
        updateSaveButton()
        val nick = edNick.text.toString().trim()
        val mine = Profile.account(this)?.nickname
        when {
            nick.isEmpty() -> setStatus(getString(R.string.profile_nickname_rules), R.color.text_secondary)
            mine != null && Nickname.key(nick) == Nickname.key(mine) && nick == mine ->
                setStatus(getString(R.string.profile_nickname_current), R.color.text_secondary)
            Nickname.validate(nick) != null -> setStatus("✗ " + Nickname.validate(nick), R.color.sector_worse)
            else -> {
                setStatus(getString(R.string.profile_nickname_checking), R.color.text_secondary)
                handler.postDelayed({ check(nick) }, 600)
            }
        }
    }

    private fun check(nick: String) {
        val seq = ++checkSeq
        api.checkNickname(nick) { result ->
            runOnUiThread {
                if (seq != checkSeq || isDestroyed) return@runOnUiThread
                result.onSuccess { c ->
                    lastCheck = c
                    if (c.available) {
                        setStatus(getString(R.string.profile_nickname_available, c.nickname), R.color.sector_better)
                    } else {
                        setStatus(getString(R.string.profile_nickname_taken, c.reason ?: ""), R.color.sector_worse)
                        showSuggestions(c.suggestions)
                    }
                }.onFailure {
                    setStatus(getString(R.string.server_error, it.message ?: ""), R.color.warn)
                }
                updateSaveButton()
            }
        }
    }

    private fun showSuggestions(list: List<String>) {
        suggestions.removeAllViews()
        list.forEach { s ->
            suggestions.addView(Chip(this).glass().apply {
                text = s
                setOnClickListener {
                    edNick.setText(s)
                    edNick.setSelection(s.length)
                }
            })
        }
    }

    private fun updateSaveButton() {
        val c = lastCheck
        btnSave.isEnabled = !busy && c != null && c.available && c.nickname == edNick.text.toString().trim()
    }

    private fun saveNickname() {
        val nick = edNick.text.toString().trim()
        busy = true
        updateSaveButton()
        api.registerNickname(nick, Profile.account(this)) { result ->
            runOnUiThread {
                busy = false
                result.onSuccess { acc ->
                    Profile.saveAccount(this, acc)
                    Toast.makeText(this, getString(R.string.profile_nickname_saved, acc.nickname), Toast.LENGTH_SHORT).show()
                    lastCheck = null
                    setStatus(getString(R.string.profile_nickname_current), R.color.text_secondary)
                    // Foto già scelta prima della registrazione: la carica ora.
                    Profile.avatarFile(this).takeIf { it.exists() }?.readBytes()?.let { uploadAvatar(it) }
                    if (!swPublic.isChecked) onVisibilityChanged(false)
                }.onFailure {
                    setStatus("✗ " + (it.message ?: getString(R.string.server_error, "")), R.color.sector_worse)
                }
                refreshHeader()
            }
        }
    }

    /* ------------------------------ Community ---------------------------- */

    private fun onVisibilityChanged(public: Boolean) {
        val acc = Profile.account(this) ?: return // applicata alla registrazione
        val vis = if (public) ProfileVisibility.PUBLIC else ProfileVisibility.PRIVATE
        api.updateProfile(acc, vis) { result ->
            runOnUiThread {
                result.onSuccess { Profile.saveAccount(this, it) }
                    .onFailure {
                        Toast.makeText(this, getString(R.string.server_error, it.message ?: ""), Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    /* -------------------------------- Foto ------------------------------- */

    private fun onPhotoPicked(uri: Uri) {
        val appCtx = applicationContext
        thread(name = "avatar") {
            val bmp = decodeScaled(uri, 1024) ?: return@thread
            val bytes = Profile.saveAvatar(appCtx, bmp)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshHeader()
                uploadAvatar(bytes)
            }
        }
    }

    private fun uploadAvatar(bytes: ByteArray) {
        val acc = Profile.account(this) ?: return
        api.uploadAvatar(acc, bytes) { result ->
            result.onSuccess { url -> Profile.saveAccount(applicationContext, acc.copy(avatarUrl = url)) }
        }
    }

    /** Decodifica ridotta (evita immagini enormi in memoria). */
    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /* ------------------------------- Tema -------------------------------- */

    private fun setupTheme() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        group.check(
            when (ThemePrefs.get(this)) {
                ThemePrefs.Mode.DARK -> R.id.themeDark
                ThemePrefs.Mode.LIGHT -> R.id.themeLight
                ThemePrefs.Mode.SYSTEM -> R.id.themeSystem
            }
        )
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = when (id) {
                R.id.themeLight -> ThemePrefs.Mode.LIGHT
                R.id.themeSystem -> ThemePrefs.Mode.SYSTEM
                else -> ThemePrefs.Mode.DARK
            }
            if (mode != ThemePrefs.get(this)) ThemePrefs.set(this, mode)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
