package com.gpstelemetry.app

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gpstelemetry.core.Friend
import com.gpstelemetry.core.Friends
import com.gpstelemetry.core.FriendsBoard
import com.gpstelemetry.core.Nickname
import java.util.Locale
import kotlin.math.abs

/**
 * Interfaccia degli AMICI:
 *  - [showTimes]: tempi degli amici su un tracciato pubblicato;
 *  - [manage]: ricerca, aggiunta e rimozione degli amici ([FriendsActivity]);
 *  - [toggle]: aggiunta/rimozione tenendo premuto un pilota in classifica.
 * La lista è solo sul telefono ([FriendsStore]).
 */
object FriendsUi {

    private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun Activity.color(res: Int) = ContextCompat.getColor(this, res)

    private fun Activity.ui(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }

    /* --------------------------- Tempi degli amici ----------------------- */

    fun showTimes(a: Activity, publishedId: String, trackName: String) {
        if (FriendsStore.list(a).isEmpty()) {
            MaterialAlertDialogBuilder(a)
                .setTitle(R.string.friends_title)
                .setMessage(R.string.friends_empty)
                .setPositiveButton(R.string.friends_manage) { _, _ -> manage(a) }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }
        val box = column(a)
        box.addView(secondary(a, a.getString(R.string.friends_loading)))
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle(a.getString(R.string.friends_times_title, trackName))
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(R.string.friends_manage) { _, _ -> manage(a) { showTimes(a, publishedId, trackName) } }
            .setNegativeButton(R.string.close, null)
            .show()
        val me = Profile.account(a)
        Community.api.friendsTimes(publishedId, FriendsStore.list(a), me) { result ->
            a.ui {
                if (!dialog.isShowing) return@ui
                box.removeAllViews()
                result.onSuccess { board ->
                    // Nomi cambiati sul server: si aggiorna la lista locale.
                    FriendsStore.list(a).let { l -> Friends.refreshNames(l, board).takeIf { it != l }?.let { FriendsStore.save(a, it) } }
                    fillTimes(a, box, board)
                }.onFailure {
                    box.addView(secondary(a, a.getString(R.string.server_error, it.message ?: "")))
                }
            }
        }
    }

    /** Righe: la mia posizione, poi gli amici con distacco da me. */
    internal fun fillTimes(a: Activity, box: LinearLayout, board: FriendsBoard) {
        box.addView(secondary(a, board.me?.let {
            a.getString(R.string.friends_my_position, it.rank, board.drivers, Fmt.time(it.lapMs))
        } ?: a.getString(R.string.friends_no_my_time)).apply { setPadding(0, 0, 0, a.dp(6)) })
        board.friends.forEach { f ->
            val e = f.entry
            val (right, rightColor) = when {
                !f.found -> a.getString(R.string.friends_unavailable) to R.color.text_secondary
                e == null -> a.getString(R.string.friends_no_time) to R.color.text_secondary
                else -> Fmt.time(e.lapMs) to (if (e.rank == 1) R.color.sector_best else R.color.text_primary)
            }
            val gap = f.gapMs?.let { g ->
                // Più veloce di me in viola (come i record), più lento in grigio.
                signed(g) to (if (g < 0) R.color.sector_best else R.color.text_secondary)
            }
            box.addView(row(a, e?.rank?.toString() ?: "–", f.friend.nickname, gap, right, rightColor, dim = !f.found))
        }
    }

    private fun signed(ms: Long): String {
        val s = String.format(Locale.US, "%.2f", abs(ms) / 1000.0)
        return when {
            ms < 0 -> "−$s"
            ms > 0 -> "+$s"
            else -> "=$s"
        }
    }

    /* ------------------------------ Gestione ----------------------------- */

    /** Schermata degli amici: ricerca dei piloti e lista ([FriendsActivity]). */
    fun manage(a: Activity, onClose: (() -> Unit)? = null) = FriendsActivity.open(a, onClose)

    /** Tenendo premuto un pilota in classifica: lo aggiunge o lo toglie dagli amici. */
    fun toggle(a: Activity, nickname: String, onChanged: () -> Unit = {}) {
        val me = Profile.account(a)
        if (nickname == PRIVATE_NAME || (me != null && Nickname.key(me.nickname) == Nickname.key(nickname))) return
        val existing = Friends.byNickname(FriendsStore.list(a), nickname)
        if (existing != null) {
            MaterialAlertDialogBuilder(a)
                .setMessage(a.getString(R.string.friends_remove_confirm, existing.nickname))
                .setPositiveButton(R.string.friends_remove) { _, _ ->
                    FriendsStore.remove(a, existing.userId)
                    Toast.makeText(a, a.getString(R.string.friends_removed, existing.nickname), Toast.LENGTH_SHORT).show()
                    onChanged()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(a)
            .setMessage(a.getString(R.string.friends_add_confirm, nickname))
            .setPositiveButton(R.string.friends_add) { _, _ ->
                Community.api.findDriver(nickname, me) { r ->
                    a.ui {
                        val d = r.getOrNull()
                        val msg = when {
                            r.isFailure -> a.getString(R.string.server_error, r.exceptionOrNull()?.message ?: "")
                            d == null -> a.getString(R.string.friends_not_found)
                            else -> {
                                FriendsStore.add(a, Friend(d.userId, d.nickname))
                                onChanged()
                                a.getString(R.string.friends_added, d.nickname)
                            }
                        }
                        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Nome con cui compaiono i profili privati (non si possono aggiungere). */
    private const val PRIVATE_NAME = "Pilota privato"

    /* ------------------------------ Viste -------------------------------- */

    private fun column(a: Activity) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(20), a.dp(10), a.dp(20), 0)
    }

    private fun secondary(a: Activity, text: String) = TextView(a).apply {
        this.text = text
        textSize = 14f
        setTextColor(a.color(R.color.text_secondary))
    }

    private fun row(
        a: Activity,
        rank: String,
        name: String,
        gap: Pair<String, Int>?,
        right: String,
        rightColor: Int,
        dim: Boolean = false,
    ): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = a.dp(44)
        setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
        if (rank.isNotEmpty()) addView(TextView(a).apply {
            text = rank
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(a.color(R.color.text_secondary))
        }, LinearLayout.LayoutParams(a.dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(a).apply {
            text = name
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(a.color(if (dim) R.color.text_secondary else R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gap?.let { (t, c) ->
            addView(TextView(a).apply {
                text = t
                textSize = 13f
                fontFeatureSettings = "tnum"
                setTextColor(a.color(c))
                setPadding(a.dp(8), 0, a.dp(10), 0)
            })
        }
        addView(TextView(a).apply {
            text = right
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            fontFeatureSettings = "tnum"
            textSize = 16f
            setTextColor(a.color(rightColor))
        })
    }
}
