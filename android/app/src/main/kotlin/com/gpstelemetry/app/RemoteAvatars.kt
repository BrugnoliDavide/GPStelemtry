package com.gpstelemetry.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foto profilo degli altri piloti (es. risultati della ricerca amici):
 * scaricate una volta, ridotte e tenute in memoria (poche decine).
 * Errori ignorati: resta l'iniziale del nickname.
 */
object RemoteAvatars {
    private const val SIZE_PX = 160

    private val cache = LruCache<String, Bitmap>(40)
    private val missing = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    /** Mostra subito l'iniziale, poi la foto quando arriva (se la vista mostra ancora lo stesso pilota). */
    fun load(view: AvatarView, url: String?, nickname: String) {
        view.tag = url
        val cached = url?.let { cache.get(it) }
        view.set(cached, nickname)
        if (url == null || cached != null || !url.startsWith("http") || synchronized(missing) { url in missing }) return
        executor.execute {
            val bmp = runCatching { fetch(url) }.getOrNull()
            if (bmp == null) synchronized(missing) { missing += url } else cache.put(url, bmp)
            main.post { if (view.tag == url && bmp != null) view.set(bmp, nickname) }
        }
    }

    private fun fetch(url: String): Bitmap? =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val bytes = r.body?.bytes() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE_PX) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
}
