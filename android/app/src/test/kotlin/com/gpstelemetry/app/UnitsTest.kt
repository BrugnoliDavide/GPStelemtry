package com.gpstelemetry.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @After
    fun reset() {
        Units.system = UnitSystem.METRIC
    }

    @Test
    fun metric() {
        Units.system = UnitSystem.METRIC
        assertEquals("173 km/h", Units.speed(173.4))
        assertEquals("36 km/h", Units.speedMs(10.0))
        assertEquals("850 m", Units.dist(850.0))
        assertEquals("2.08 km", Units.dist(2080.0))
        assertEquals("212 m", Units.length(212.3))
        assertEquals("+32 / −12 m", Units.elevation(32.0, 12.0))
    }

    @Test
    fun imperial() {
        Units.system = UnitSystem.IMPERIAL
        assertEquals("100 mph", Units.speed(160.9344))
        assertEquals("1.29 mi", Units.dist(2080.0))
        assertEquals("492 ft", Units.dist(150.0)) // sotto 0,1 mi in piedi
        assertEquals("696 ft", Units.length(212.2))
        assertEquals("+105 / −39 ft", Units.elevation(32.0, 12.0))
    }
}
