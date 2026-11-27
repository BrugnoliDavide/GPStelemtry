package com.gpstelemetry.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.gpstelemetry.core.DriverProfile
import com.gpstelemetry.core.Friend
import com.gpstelemetry.core.Friends

/**
 * AMICI: si cerca un pilota scrivendo le prime lettere del nickname (i
 * risultati arrivano mentre si scrive) e lo si aggiunge con un tocco.
 * Sotto, la lista degli amici, salvata solo sul telefono ([FriendsStore]).
 */
class FriendsActivity : AppCompatActivity() {

    companion object {
        /** Attesa dopo l'ultimo tasto prima di interrogare il server. */
        private const val DEBOUNCE_MS = 250L
        private const val LIMIT = 8

        /** Da richiamare alla chiusura (chi ha aperto la schermata aggiorna i dati). */
        private var onClose: (() -> Unit)? = null

        fun open(a: Activity, onClose: (() -> Unit)? = null) {
            this.onClose = onClose
            a.startActivity(Intent(a, FriendsActivity::class.java))
        }
    }

    private lateinit var query: EditText
    private lateinit var progress: ProgressBar
    private lateinit var clear: ImageButton
    private lateinit var resultsCard: View
    private lateinit var resultsLabel: TextView
    private lateinit var results: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var listLabel: TextView
    private lateinit var count: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    /** Numero dell'ultima ricerca: le risposte arrivate tardi vengono scartate. */
    private var seq = 0
    /** Risultati già ottenuti in questa schermata (cancellando lettere non si richiede di nuovo). */
    private val cache = HashMap<String, List<DriverProfile>>()
    private var lastHits: List<DriverProfile> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int) = ContextCompat.getColor(this, res)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friends)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        query = findViewById(R.id.frQuery)
        progress = findViewById(R.id.frProgress)
        clear = findViewById(R.id.frClear)
        resultsCard = findViewById(R.id.frResultsCard)
        resultsLabel = findViewById(R.id.frResultsLabel)
        results = findViewById(R.id.frResults)
        list = findViewById(R.id.frList)
        listLabel = findViewById(R.id.frListLabel)
        count = findViewById(R.id.frCount)

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onQueryChanged()
        })
        query.setOnEditorActionListener { _, id, _ ->
            (id == EditorInfo.IME_ACTION_SEARCH).also { if (it) { hideKeyboard(); search(now = true) } }
        }
        clear.setOnClickListener { query.setText("") }

        refreshList()
        onQueryChanged()
        query.requestFocus()
    }

    override fun onDestroy() {
        pending?.let { handler.removeCallbacks(it) }
        if (isFinishing) onClose?.invoke().also { onClose = null }
        super.onDestroy()
    }

    /* ------------------------------ Ricerca ------------------------------ */

    private fun onQueryChanged() {
        val text = query.text.toString()
        clear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        search(now = false)
    }

    private fun search(now: Boolean) {
        pending?.let { handler.removeCallbacks(it) }
        val text = query.text.toString().trim()
        val my = ++seq
        if (text.isEmpty()) {
            progress.visibility = View.GONE
            resultsCard.visibility = View.GONE
            return
        }
        resultsCard.visibility = View.VISIBLE
        val q = Friends.searchable(text)
        if (q == null) {
            showHits(text, emptyList())
            return
        }
        cache[q.lowercase()]?.let { showHits(q, it); return }
        val run = Runnable {
            progress.visibility = View.VISIBLE
            Community.api.searchDrivers(q, Profile.account(this), LIMIT) { r ->
                runOnUiThread {
                    if (isDestroyed || my != seq) return@runOnUiThread
                    progress.visibility = View.GONE
                    r.onSuccess {
                        cache[q.lowercase()] = it
                        showHits(q, it)
                    }.onFailure { showMessage(getString(R.string.server_error, it.message ?: ""), null) }
                }
            }
        }
        pending = run
        handler.postDelayed(run, if (now) 0 else DEBOUNCE_MS)
    }

    private fun showHits(q: String, hits: List<DriverProfile>) {
        lastHits = hits
        if (hits.isEmpty()) {
            showMessage(getString(R.string.friends_results_none, q), getString(R.string.friends_results_none_hint))
            return
        }
        resultsLabel.text = getString(R.string.friends_results)
        results.removeAllViews()
        hits.forEachIndexed { i, d ->
            if (i > 0) results.addView(divider())
            results.addView(hitRow(d, q))
        }
    }

    private fun showMessage(title: String, hint: String?) {
        lastHits = emptyList()
        resultsLabel.text = getString(R.string.friends_results)
        results.removeAllViews()
        results.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(color(R.color.text_primary))
            setPadding(0, dp(10), 0, dp(2))
        })
        hint?.let { results.addView(hintText(it).apply { setPadding(0, 0, 0, dp(8)) }) }
    }

    /** Riga di un pilota trovato: foto, nickname con le lettere cercate evidenziate, pulsante. */
    private fun hitRow(d: DriverProfile, q: String): View {
        val friend = FriendsStore.list(this).any { it.userId == d.userId }
        val sub = listOfNotNull(
            d.displayName?.takeIf { it.isNotBlank() },
            d.tracks?.let { if (it == 0) getString(R.string.friends_tracks_none) else resources.getQuantityString(R.plurals.friends_tracks, it, it) },
        ).joinToString(" · ")
        val action = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            minHeight = dp(40)
            minimumHeight = dp(40)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(20)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            iconPadding = dp(4)
            iconSize = dp(18)
            setPadding(dp(14), 0, dp(16), 0)
            if (friend) {
                setText(R.string.friends_is_friend)
                setIconResource(R.drawable.ic_check)
                iconTint = ContextCompat.getColorStateList(this@FriendsActivity, R.color.ok)
                setTextColor(color(R.color.ok))
                backgroundTintList = ContextCompat.getColorStateList(this@FriendsActivity, R.color.glass_inner)
                contentDescription = getString(R.string.friends_remove_confirm, d.nickname)
                setOnClickListener { removeFriend(Friend(d.userId, d.nickname)) }
            } else {
                setText(R.string.friends_add)
                setIconResource(R.drawable.ic_add)
                iconTint = ContextCompat.getColorStateList(this@FriendsActivity, R.color.on_accent)
                setTextColor(color(R.color.on_accent))
                backgroundTintList = ContextCompat.getColorStateList(this@FriendsActivity, R.color.accent)
                setOnClickListener { addFriend(d) }
            }
        }
        return personRow(d.nickname, d.avatarUrl, highlight(d.nickname, q), sub.ifEmpty { null }, action).apply {
            setOnClickListener { action.performClick() }
        }
    }

    private fun highlight(nickname: String, q: String): CharSequence {
        val m = Friends.match(nickname, q) ?: return nickname
        return SpannableString(nickname).apply {
            setSpan(ForegroundColorSpan(color(R.color.accent)), m.first, m.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), m.first, m.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun addFriend(d: DriverProfile) {
        if (FriendsStore.list(this).size >= Friends.MAX) {
            snack(getString(R.string.friends_limit, Friends.MAX))
            return
        }
        FriendsStore.add(this, Friend(d.userId, d.nickname))
        snack(getString(R.string.friends_added, d.nickname))
        refreshAll()
    }

    private fun removeFriend(f: Friend) {
        FriendsStore.remove(this, f.userId)
        refreshAll()
        Snackbar.make(findViewById(R.id.frRoot), getString(R.string.friends_removed, f.nickname), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { FriendsStore.add(this, f); refreshAll() }
            .setActionTextColor(color(R.color.accent))
            .show()
    }

    private fun refreshAll() {
        refreshList()
        if (lastHits.isNotEmpty()) showHits(query.text.toString().trim(), lastHits)
    }

    /* ------------------------------- Amici ------------------------------- */

    private fun refreshList() {
        val friends = FriendsStore.list(this).sortedBy { it.nickname.lowercase() }
        count.text = if (friends.isEmpty()) getString(R.string.friends_count_none)
        else resources.getQuantityString(R.plurals.friends_count, friends.size, friends.size)
        listLabel.text = getString(R.string.friends_my_list, friends.size)
        list.removeAllViews()
        if (friends.isEmpty()) {
            list.addView(emptyState())
            return
        }
        friends.forEachIndexed { i, f ->
            if (i > 0) list.addView(divider())
            val remove = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(color(R.color.text_secondary))
                background = ContextCompat.getDrawable(this@FriendsActivity, R.drawable.bg_glass_circle)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.friends_remove_confirm, f.nickname)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                setOnClickListener { removeFriend(f) }
            }
            list.addView(personRow(f.nickname, null, f.nickname, null, remove))
        }
    }

    private fun emptyState() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(8), dp(16), dp(8), dp(12))
        addView(android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(color(R.color.accent))
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_circle)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        })
        addView(TextView(context).apply {
            setText(R.string.friends_empty_title)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_primary))
            setPadding(0, dp(12), 0, dp(4))
        })
        addView(hintText(getString(R.string.friends_empty_body)).apply { gravity = Gravity.CENTER })
        setOnClickListener { query.requestFocus(); showKeyboard() }
    }

    /* ------------------------------- Viste ------------------------------- */

    private fun personRow(nickname: String, avatarUrl: String?, title: CharSequence, subtitle: String?, action: View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(0, dp(8), 0, dp(8))
            background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            addView(AvatarView(context).apply {
                ringDp = 1.5f
                RemoteAvatars.load(this, avatarUrl, nickname)
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(color(R.color.text_primary))
                })
                subtitle?.let {
                    addView(TextView(context).apply {
                        text = it
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(color(R.color.text_secondary))
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(action)
        }

    private fun divider() = View(this).apply {
        setBackgroundColor(color(R.color.glass_stroke))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(54) }
    }

    private fun hintText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setLineSpacing(dp(2).toFloat(), 1f)
        setTextColor(color(R.color.text_secondary))
    }

    private fun snack(text: String) = Snackbar.make(findViewById(R.id.frRoot), text, Snackbar.LENGTH_SHORT).show()

    private fun hideKeyboard() =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)

    private fun showKeyboard() =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
}
