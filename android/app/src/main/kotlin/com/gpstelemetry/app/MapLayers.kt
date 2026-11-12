package com.gpstelemetry.app

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.content.res.Configuration as UiConfiguration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay

/**
 * Sfondi della mappa dell'editor:
 *  - SATELLITE (predefinito): immagini aeree Esri World Imagery, per vedere
 *    i bordi reali della pista, i cordoli e le vie di fuga;
 *  - MAPPA: OpenStreetMap (scurita nel tema scuro).
 * La scelta è ricordata tra una sessione e l'altra.
 *
 * Nota licenza: le immagini Esri richiedono l'attribuzione (mostrata sulla
 * mappa). Per una distribuzione commerciale vanno verificati i termini
 * d'uso Esri / ArcGIS, oppure si sostituisce la sorgente (es. Mapbox o
 * MapTiler Satellite con chiave API) cambiando solo [SATELLITE].
 */
object MapLayers {

    enum class Layer { SATELLITE, MAP }

    private const val PREFS = "ui"
    private const val KEY = "editorLayer"

    /** Esri World Imagery: URL nell'ordine z/y/x (diverso dallo standard z/x/y). */
    val SATELLITE = object : OnlineTileSourceBase(
        "EsriWorldImagery", 1, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Immagini © Esri, Maxar, Earthstar Geographics e la comunità GIS",
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
    }

    fun get(ctx: Context): Layer = runCatching {
        Layer.valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "SATELLITE")
    }.getOrDefault(Layer.SATELLITE)

    fun set(ctx: Context, layer: Layer) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, layer.name).apply()

    /** Applica lo sfondo alla mappa (e il filtro scuro per la mappa stradale nel tema scuro). */
    fun apply(map: MapView, layer: Layer, copyright: CopyrightOverlay?) {
        val ctx = map.context
        when (layer) {
            Layer.SATELLITE -> {
                map.setTileSource(SATELLITE)
                map.overlayManager.tilesOverlay.setColorFilter(null)
            }
            Layer.MAP -> {
                map.setTileSource(TileSourceFactory.MAPNIK)
                map.overlayManager.tilesOverlay.setColorFilter(if (isNight(ctx)) darkTiles() else null)
            }
        }
        // Oltre lo zoom massimo delle immagini si ingrandiscono le ultime disponibili.
        map.maxZoomLevel = 21.0
        copyright?.setCopyrightNotice(map.tileProvider.tileSource.copyrightNotice)
        map.invalidate()
    }

    fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and UiConfiguration.UI_MODE_NIGHT_MASK) == UiConfiguration.UI_MODE_NIGHT_YES

    /** Mappa stradale scura: colori invertiti e desaturati. */
    private fun darkTiles(): ColorMatrixColorFilter {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val dim = ColorMatrix().apply { setSaturation(0.25f) }
        dim.postConcat(ColorMatrix().apply { setScale(0.85f, 0.85f, 0.88f, 1f) })
        invert.postConcat(dim)
        return ColorMatrixColorFilter(invert)
    }

    /** Attribuzione leggibile sia sulle foto aeree sia sulla mappa. */
    fun copyrightOverlay(ctx: Context): CopyrightOverlay = CopyrightOverlay(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(10)
        setAlignBottom(false)
        setAlignRight(false)
    }
}
