package com.gpstelemetry.app

import android.content.Context
import com.gpstelemetry.core.Friend
import com.gpstelemetry.core.Friends

/**
 * Lista degli amici, salvata SOLO sul telefono (SharedPreferences "friends").
 * Il server non la conosce: l'app la invia quando chiede i tempi degli amici.
 */
object FriendsStore {
    private const val PREFS = "friends"
    private const val KEY = "list"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Friend> = Friends.parse(prefs(ctx).getString(KEY, null))

    fun save(ctx: Context, list: List<Friend>) {
        prefs(ctx).edit().putString(KEY, Friends.toJson(list)).apply()
    }

    fun add(ctx: Context, f: Friend) = save(ctx, Friends.add(list(ctx), f))

    fun remove(ctx: Context, userId: String) = save(ctx, Friends.remove(list(ctx), userId))

    fun isFriend(ctx: Context, nickname: String) = Friends.byNickname(list(ctx), nickname) != null
}
