package com.gpstelemetry.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.MapTileIndex
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLayersTest {

    @Test
    fun esriUrlUsesZoomRowColumnOrder() {
        val url = MapLayers.SATELLITE.getTileURLString(MapTileIndex.getTileIndex(17, 68_960, 47_120))
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/17/47120/68960",
            url,
        )
    }

    @Test
    fun satelliteIsDefaultAndRemembered() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(MapLayers.Layer.SATELLITE, MapLayers.get(ctx))
        MapLayers.set(ctx, MapLayers.Layer.MAP)
        assertEquals(MapLayers.Layer.MAP, MapLayers.get(ctx))
    }
}
