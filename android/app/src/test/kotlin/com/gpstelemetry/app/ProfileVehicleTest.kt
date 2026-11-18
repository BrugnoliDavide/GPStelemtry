package com.gpstelemetry.app

import androidx.test.core.app.ApplicationProvider
import com.gpstelemetry.core.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Veicolo predefinito nelle impostazioni e scelta per le nuove sessioni. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileVehicleTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun defaultVehicleIsUsedForNewSessions() {
        // Senza predefinito: si usa l'ultimo veicolo.
        Profile.rememberVehicle(ctx, VehicleType.KART, "Rotax Max")
        assertNull(Profile.defaultVehicle(ctx))
        assertEquals(VehicleType.KART to "Rotax Max", Profile.vehicleForNewSession(ctx))

        // Con predefinito: prevale sul "ultimo usato".
        Profile.setDefaultVehicle(ctx, VehicleType.MOTORCYCLE, "Honda CBR1000RR")
        assertEquals(VehicleType.MOTORCYCLE to "Honda CBR1000RR", Profile.defaultVehicle(ctx))
        assertEquals(VehicleType.MOTORCYCLE to "Honda CBR1000RR", Profile.vehicleForNewSession(ctx))

        // Predefinito con sola categoria (modello libero).
        Profile.setDefaultVehicle(ctx, VehicleType.CAR, null)
        assertEquals(VehicleType.CAR to null, Profile.vehicleForNewSession(ctx))

        // Rimozione del predefinito: si torna all'ultimo usato.
        Profile.setDefaultVehicle(ctx, null, "ignorato")
        assertNull(Profile.defaultVehicle(ctx))
        assertEquals(VehicleType.KART to "Rotax Max", Profile.vehicleForNewSession(ctx))
    }
}
