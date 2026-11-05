package de.greenganja.growguardian.grow.light

import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.grow.GrowPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LightMathTest {
    private fun setup(vararg lamps: LampUse, area: Double? = null, dim: Double? = null, calibration: Double = 1.0) =
        LightSetup(lamps.toList(), areaM2 = area, dimPercent = dim, calibration = calibration)

    @Test
    fun `Katalog vollstaendig und stimmig`() {
        val ids = LampCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(LampCatalog.all.size >= 25)
        for (lamp in LampCatalog.all) {
            assertEquals(lamp.id, 100.0, lamp.spectrum.total, 0.001)
            lamp.ppfdPerKlx?.let { assertTrue(lamp.id, it in 10.0..200.0) }
            lamp.photonEfficacy?.let { assertTrue(lamp.id, it in 0.1..4.0) }
        }
        // Jede Kategorie hat mindestens eine Lampe
        assertEquals(LampCategory.entries.toSet(), LampCatalog.grouped().keys)
    }

    @Test
    fun `Freemium nur Tageslicht und Gluehbirne`() {
        assertEquals(setOf(LampCatalog.DAYLIGHT, LampCatalog.INCANDESCENT), LampCatalog.FREE_IDS)
        val led = LampCatalog.get("led_white_3000")!!
        assertFalse(LampCatalog.allowed(led, FarmerTier.FREEMIUM.features.allLampTypes))
        assertTrue(LampCatalog.allowed(led, FarmerTier.PREMIUM.features.allLampTypes))
        assertTrue(LampCatalog.allowed(led, FarmerTier.SOCIAL_CLUB.features.allLampTypes))
        assertTrue(LampCatalog.allowed(LampCatalog.get(LampCatalog.DAYLIGHT)!!, false))
        assertNotNull(LightRules.validate(setup(LampUse("hps_se", 600.0)), allLampTypes = false))
        assertNull(LightRules.validate(setup(LampUse(LampCatalog.INCANDESCENT, 100.0)), allLampTypes = false))
    }

    @Test
    fun `Lux zu PPFD je Lampe`() {
        // Sonne: 100 klx ≈ 1850 µmol/m²/s
        assertEquals(1850.0, LightMath.ppfdFromLux(100_000.0, setup(LampUse(LampCatalog.DAYLIGHT)))!!, 0.01)
        // HPS: 50 klx ≈ 610
        assertEquals(610.0, LightMath.ppfdFromLux(50_000.0, setup(LampUse("hps_se", 600.0)))!!, 0.01)
        // Kalibrierung wirkt linear
        assertEquals(671.0, LightMath.ppfdFromLux(50_000.0, setup(LampUse("hps_se", 600.0), calibration = 1.1))!!, 0.01)
        // UV allein: mit Lux nicht messbar
        assertNull(LightMath.ppfdFromLux(1000.0, setup(LampUse("led_uv", 50.0))))
        assertFalse(LightMath.luxReliable(setup(LampUse("led_blurple", 300.0))))
        assertTrue(LightMath.luxReliable(setup(LampUse("led_white_3000", 300.0))))
    }

    @Test
    fun `Mischung zweier Lampen gewichtet nach Photonen`() {
        val led = LampUse("led_white_3000", 300.0)
        val hps = LampUse("hps_se", 600.0)
        val mix = LightMath.ppfdPerKlx(setup(led, hps))!!
        assertTrue(mix in 12.2..14.3)
        // Far-Red-Zusatz erhöht den PAR je Lux leicht (er liefert etwas Rot, aber kaum Lux)
        val withFr = LightMath.ppfdPerKlx(setup(led, LampUse("led_far_red", 100.0)))!!
        assertTrue(withFr > 14.3)
        // Spektrum der Mischung liegt zwischen beiden
        val s = LightMath.spectrum(setup(led, hps))
        assertEquals(100.0, s.total, 0.001)
        assertTrue(s.blue in 5.0..13.0)
    }

    @Test
    fun `PPFD-Schaetzung, DLI und Leistung`() {
        // 300 W LED 3000 K auf 1 m²: 300 × 2,85 × 0,96 × 0,8 ≈ 657
        val est = LightMath.estimatedPpfd(setup(LampUse("led_white_3000", 300.0), area = 1.0))!!
        assertEquals(656.6, est, 0.5)
        // gedimmt auf 50 %
        assertEquals(est / 2, LightMath.estimatedPpfd(setup(LampUse("led_white_3000", 300.0), area = 1.0, dim = 50.0))!!, 0.01)
        assertNull(LightMath.estimatedPpfd(setup(LampUse(LampCatalog.DAYLIGHT), area = 1.0)))
        assertEquals(38.88, LightMath.dli(900.0, 12.0), 0.001)
        val watts = LightMath.wattsFor(LampCatalog.get("led_white_3000")!!, 750.0, 1.44)!!
        assertEquals(493.4, watts, 0.5)
    }

    @Test
    fun `Spektrum-Mengen und Verhaeltnisse`() {
        val spectrum = LampCatalog.get("led_full_uv_ir")!!.spectrum
        val bands = LightMath.bands(spectrum, 870.0)
        // PAR-Bänder ergeben zusammen den PPFD
        val par = bands.filter { it.band.inPar }.sumOf { it.ppfd!! }
        assertEquals(870.0, par, 0.001)
        assertEquals(1000.0, LightMath.extendedPpfd(spectrum, 870.0)!!, 0.001)
        assertEquals(47.0 / 11.0, spectrum.redToFarRed!!, 0.0001)
        assertNull(LampCatalog.get("t5_6500")!!.spectrum.copy(farRed = 0.0).redToFarRed)
        assertTrue(LightMath.bands(spectrum, null).all { it.ppfd == null })
    }

    @Test
    fun `Ziele, Bewertung und Hinweise`() {
        val bloom = LightMath.target(GrowPhase.FLOWERING)
        assertEquals(900.0, bloom.ppfdMax, 0.0)
        assertEquals(1200.0, LightMath.target(GrowPhase.FLOWERING, co2Ppm = 1000.0).ppfdMax, 0.0)
        assertEquals(LightLevel.TOO_LOW, LightMath.level(400.0, bloom.ppfdMin, bloom.ppfdMax))
        assertEquals(LightLevel.OK, LightMath.level(700.0, bloom.ppfdMin, bloom.ppfdMax))

        val dimmed = setup(LampUse("led_white_3000", 480.0), dim = 50.0)
        assertEquals("Dimmer auf etwa 94 % stellen.", LightMath.advice(400.0, bloom, dimmed, GrowPhase.FLOWERING))
        assertEquals("Auf etwa 38 % dimmen oder die Lampe höher hängen.", LightMath.advice(1000.0, bloom, dimmed, GrowPhase.FLOWERING))
        assertTrue(LightMath.advice(100.0, bloom, setup(LampUse(LampCatalog.DAYLIGHT)), GrowPhase.FLOWERING).contains("helleren Platz"))

        val glow = LampCatalog.get(LampCatalog.INCANDESCENT)!!.spectrum
        val hints = LightMath.spectrumHints(glow, GrowPhase.VEGETATIVE)
        assertTrue(hints.any { it.startsWith("Wenig Blau") })
        assertTrue(hints.any { it.startsWith("Viel Dunkelrot") })
    }

    @Test
    fun `Gleichmaessigkeit und Kalibrierung`() {
        val u = LightMath.uniformity(listOf(800.0, 600.0, 700.0, 500.0))!!
        assertEquals(650.0, u.average, 0.0)
        assertEquals(500.0 / 650.0, u.ratio, 0.0001)
        assertEquals("Gleichmäßig", u.label)
        assertNull(LightMath.uniformity(emptyList()))
        assertEquals(1.2, LightMath.calibrationFor(600.0, 500.0)!!, 0.0001)
        assertNull(LightMath.calibrationFor(600.0, 10.0)) // Faktor 60: unplausibel
    }

    @Test
    fun `Setup aus Firestore lesen`() {
        val raw = mapOf(
            "lamps" to listOf(mapOf("lampId" to "cmh_3100", "watts" to 315L, "count" to 2L), mapOf("lampId" to "gibtsnicht")),
            "areaM2" to 1.44, "dimPercent" to 80L, "calibration" to 9.0,
        )
        val s = LightRules.parseSetup(raw)!!
        assertEquals(listOf(LampUse("cmh_3100", 315.0, 2)), s.lamps)
        assertEquals(80.0, s.dimPercent!!, 0.0)
        assertEquals(1.0, s.calibration, 0.0) // außerhalb des Bereichs → 1
        assertNull(LightRules.parseSetup(mapOf("lamps" to emptyList<Any>())))
    }

    @Test
    fun `Messung aus Handy, Luxmeter und PAR-Meter`() {
        val hps = LightSetup(listOf(LampUse("hps_se", 600.0)), calibration = 1.2)
        val phone = LightMath.measure(LightSource.PHONE, 50_000.0, null, hps)
        assertEquals(732.0, phone.ppfd!!, 0.01) // 610 × 1,2
        assertEquals(610.0, phone.uncalibratedPpfd!!, 0.01)
        // Luxmeter und Tuya-Sensor ohne Handy-Kalibrierung
        assertEquals(610.0, LightMath.measure(LightSource.MANUAL_LUX, 50_000.0, null, hps).ppfd!!, 0.01)
        assertEquals(610.0, LightMath.measure(LightSource.TUYA, 50_000.0, null, hps).ppfd!!, 0.01)
        assertEquals(800.0, LightMath.measure(LightSource.MANUAL_PPFD, null, 800.0, hps).ppfd!!, 0.0)
        assertNotNull(LightMath.measure(LightSource.PHONE, 50_000.0, null, hps, saturated = true).note)
        val uv = LightMath.measure(LightSource.PHONE, 500.0, null, LightSetup(listOf(LampUse("led_uv", 30.0))))
        assertNull(uv.ppfd)
        assertNotNull(uv.note)
        assertNull(LightMath.measure(LightSource.PHONE, null, null, hps).ppfd)
    }

    @Test
    fun `gleiche Lampen wie im Backend`() {
        // Läuft in Gradle mit dem Modulordner app/ als Arbeitsverzeichnis
        val file = listOf("../backend/functions/src/rules/light.ts", "backend/functions/src/rules/light.ts").map(::File).firstOrNull { it.exists() } ?: return
        val text = file.readText()
        val block = text.substringAfter("export const LAMP_IDS = [").substringBefore("] as const")
        val ids = Regex("\"([a-z0-9_]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(LampCatalog.all.map { it.id }.sorted(), ids.sorted())
        val free = Regex("\"([a-z0-9_]+)\"").findAll(text.substringAfter("FREE_LAMP_IDS").substringBefore(";")).map { it.groupValues[1] }.toSet()
        assertEquals(LampCatalog.FREE_IDS, free)
    }
}
