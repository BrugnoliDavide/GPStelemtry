package com.gpstelemetry.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gpstelemetry.core.ProfileVisibility
import com.gpstelemetry.core.UserAccount
import java.io.File

/**
 * Profilo dell'utente sul telefono: account della community (nickname),
 * foto, preferenze di pubblicazione e ultimo veicolo usato.
 */
object Profile {
    private const val PREFS = "profile"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* ------------------------------ Account ------------------------------ */

    fun account(ctx: Context): UserAccount? {
        val p = prefs(ctx)
        val id = p.getString("userId", null) ?: return null
        return UserAccount(
            userId = id,
            nickname = p.getString("nickname", null) ?: return null,
            token = p.getString("token", null) ?: return null,
            visibility = runCatching {
                ProfileVisibility.valueOf(p.getString("visibility", null) ?: "PUBLIC")
            }.getOrDefault(ProfileVisibility.PUBLIC),
            avatarUrl = p.getString("avatarUrl", null),
        )
    }

    fun saveAccount(ctx: Context, a: UserAccount) {
        prefs(ctx).edit()
            .putString("userId", a.userId)
            .putString("nickname", a.nickname)
            .putString("token", a.token)
            .putString("visibility", a.visibility.name)
            .putString("avatarUrl", a.avatarUrl)
            .apply()
    }

    /* ---------------------------- Preferenze ----------------------------- */

    /** Invio automatico dei giri conclusi sui tracciati collegati alla community. */
    fun autoPublishResults(ctx: Context) = prefs(ctx).getBoolean("autoPublishResults", true)
    fun setAutoPublishResults(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("autoPublishResults", v).apply()

    /** Ultimo veicolo usato: proposto per le nuove sessioni. */
    fun lastVehicle(ctx: Context): String? = prefs(ctx).getString("lastVehicle", null)
    fun setLastVehicle(ctx: Context, v: String?) = prefs(ctx).edit().putString("lastVehicle", v).apply()

    /** Veicoli usati in passato (per i suggerimenti), più recenti per primi. */
    fun vehicles(ctx: Context): List<String> =
        prefs(ctx).getString("vehicles", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun rememberVehicle(ctx: Context, v: String) {
        val list = (listOf(v) + vehicles(ctx).filter { !it.equals(v, ignoreCase = true) }).take(12)
        prefs(ctx).edit().putString("vehicles", list.joinToString("\n")).putString("lastVehicle", v).apply()
    }

    /** Suggerimenti di base per il tipo di veicolo. */
    val DEFAULT_VEHICLES = listOf("Auto", "Moto", "Kart", "Bicicletta", "MTB", "Corsa a piedi", "Monopattino")

    /* ------------------------------- Foto -------------------------------- */

    fun avatarFile(ctx: Context) = File(ctx.filesDir, "avatar.jpg")

    fun avatar(ctx: Context): Bitmap? =
        avatarFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /**
     * Salva la foto profilo: ritaglio quadrato centrale, 512 px, JPEG.
     * Restituisce i byte salvati (da caricare sul server).
     */
    fun saveAvatar(ctx: Context, src: Bitmap): ByteArray {
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 512, 512, true)
        val bytes = java.io.ByteArrayOutputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)
            it.toByteArray()
        }
        avatarFile(ctx).writeBytes(bytes)
        return bytes
    }
}
