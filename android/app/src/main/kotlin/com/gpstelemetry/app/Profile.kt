package com.gpstelemetry.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gpstelemetry.core.ProfileVisibility
import com.gpstelemetry.core.UserAccount
import com.gpstelemetry.core.Vehicle
import com.gpstelemetry.core.VehicleType
import java.io.File

/**
 * Profilo dell'utente sul telefono: account della community (nickname),
 * foto, preferenze di pubblicazione e ultimo veicolo usato.
 */
object Profile {
    private const val PREFS = "profile"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* ------------------------------ Account ------------------------------ */

    /** Account sul server collegato ([Community.api]); null su un altro server. */
    fun account(ctx: Context): UserAccount? {
        val p = prefs(ctx)
        val id = p.getString("userId", null) ?: return null
        if (Community.isReady && p.getString("server", "mock") != Community.api.serverId) return null
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
            .putString("server", if (Community.isReady) Community.api.serverId else "mock")
            .apply()
    }

    /* ---------------------------- Preferenze ----------------------------- */

    /** Invio automatico dei giri conclusi sui tracciati collegati alla community. */
    fun autoPublishResults(ctx: Context) = prefs(ctx).getBoolean("autoPublishResults", true)
    fun setAutoPublishResults(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("autoPublishResults", v).apply()

    /**
     * Ultimo veicolo usato (categoria + modello): proposto per le nuove
     * sessioni. Compatibile con il vecchio valore a testo unico.
     */
    fun lastVehicle(ctx: Context): Pair<VehicleType?, String?> {
        val p = prefs(ctx)
        return Vehicle.migrate(VehicleType.parse(p.getString("lastVehicleType", null)), p.getString("lastVehicle", null))
    }

    /** Modelli già usati per una categoria (suggerimenti), più recenti per primi. */
    fun models(ctx: Context, type: VehicleType?): List<String> =
        prefs(ctx).getString(modelsKey(type), null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    private fun modelsKey(type: VehicleType?) = "models_${type?.name ?: "ANY"}"

    /** Memorizza la scelta: ultima categoria/modello e storico dei modelli. */
    fun rememberVehicle(ctx: Context, type: VehicleType?, model: String?) {
        val e = prefs(ctx).edit()
            .putString("lastVehicleType", type?.name)
            .putString("lastVehicle", model)
        if (!model.isNullOrBlank()) {
            val list = (listOf(model) + models(ctx, type).filter { !it.equals(model, ignoreCase = true) }).take(12)
            e.putString(modelsKey(type), list.joinToString("\n"))
        }
        e.apply()
    }

    /* -------------------------- Veicolo predefinito ---------------------- */

    /**
     * Veicolo predefinito scelto nelle impostazioni (categoria + modello).
     * Null se non impostato. Quando c'è, ogni nuova sessione viene salvata
     * automaticamente con questo veicolo.
     */
    fun defaultVehicle(ctx: Context): Pair<VehicleType, String?>? {
        val type = VehicleType.parse(prefs(ctx).getString("defaultVehicleType", null)) ?: return null
        return type to prefs(ctx).getString("defaultVehicle", null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setDefaultVehicle(ctx: Context, type: VehicleType?, model: String?) {
        val e = prefs(ctx).edit()
        if (type == null) {
            e.remove("defaultVehicleType").remove("defaultVehicle")
        } else {
            e.putString("defaultVehicleType", type.name).putString("defaultVehicle", model?.trim())
        }
        e.apply()
    }

    /**
     * Veicolo da assegnare a una nuova sessione: il predefinito se impostato,
     * altrimenti l'ultimo usato.
     */
    fun vehicleForNewSession(ctx: Context): Pair<VehicleType?, String?> =
        defaultVehicle(ctx) ?: lastVehicle(ctx)

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
