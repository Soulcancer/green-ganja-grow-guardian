package de.greenganja.growguardian.grow.light

import de.greenganja.growguardian.grow.GrowPhase
import kotlin.math.roundToInt

/** Eine Lampe in der Box: Art, Leistung je Stück (W), Anzahl. */
data class LampUse(val lampId: String, val watts: Double? = null, val count: Int = 1) {
    val lamp: LampType? get() = LampCatalog.get(lampId)
}

/**
 * Licht einer Box, gespeichert im Box-Dokument unter `light` (Function saveLightSetup).
 *
 * @param areaM2 beleuchtete Fläche, z. B. 1,44 für ein 120×120-Zelt
 * @param dimPercent Dimmerstellung 10–100; null = kein Dimmer
 * @param hoursPerDay Lichtstunden; null = Vorgabe der Phase
 * @param calibration Korrekturfaktor fürs eigene Handy, aus dem Vergleich mit einem PAR-Meter
 */
data class LightSetup(
    val lamps: List<LampUse> = emptyList(),
    val areaM2: Double? = null,
    val dimPercent: Double? = null,
    val hoursPerDay: Double? = null,
    val calibration: Double = 1.0,
) {
    val lampIds: List<String> get() = lamps.map { it.lampId }.distinct()

    fun usesOnlyFreeLamps(): Boolean = lamps.all { it.lampId in LampCatalog.FREE_IDS }

    companion object {
        const val MAX_LAMPS = 6
        const val MAX_WATTS = 3000.0
        const val MAX_COUNT = 50
        val AREA_RANGE = 0.05..100.0
        val DIM_RANGE = 10.0..100.0
        val CALIBRATION_RANGE = 0.2..5.0
    }
}

/** Zielbereich für PPFD (µmol/m²/s) und DLI (mol/m²/Tag) einer Phase. */
data class LightTarget(
    val ppfdMin: Double,
    val ppfdMax: Double,
    val dliMin: Double,
    val dliMax: Double,
    val defaultHours: Double,
    val withCo2: Boolean = false,
) {
    val ppfdMid: Double get() = (ppfdMin + ppfdMax) / 2
}

enum class LightLevel(val label: String) { TOO_LOW("Zu wenig"), OK("Passt"), TOO_HIGH("Zu viel") }

/** Gleichmäßigkeit mehrerer Messpunkte. */
data class Uniformity(val average: Double, val min: Double, val max: Double) {
    /** min / Mittel; 1 = völlig gleichmäßig. */
    val ratio: Double get() = if (average > 0) min / average else 0.0

    val label: String
        get() = when {
            ratio >= 0.7 -> "Gleichmäßig"
            ratio >= 0.5 -> "Etwas ungleichmäßig"
            else -> "Ungleichmäßig"
        }
}

/** Ergebnis einer Messung. [uncalibratedPpfd] braucht die Kalibrierung. */
data class LightMeasurement(
    val source: LightSource,
    val lux: Double?,
    val ppfd: Double?,
    val uncalibratedPpfd: Double?,
    val reliable: Boolean,
    val saturated: Boolean = false,
    val note: String? = null,
)

/** Photonenmenge eines Bands in µmol/m²/s. */
data class BandAmount(val band: SpectrumBand, val percent: Double, val ppfd: Double?)

/**
 * Lichtrechnung ohne Android: Lux → PPFD, DLI, Spektrum-Mengen, Zielwerte und Hinweise.
 *
 * Das Handy misst Beleuchtungsstärke (Lux), gewichtet nach dem menschlichen Auge. Pflanzen zählen Photonen.
 * Der Umrechnungsfaktor hängt deshalb vom Spektrum der Lampe ab; bei mehreren Lampen wird er nach ihrem
 * Photonenanteil gemischt.
 */
object LightMath {
    /** Anteil des Lichts, der in einer Box mit reflektierenden Wänden auf der Pflanzenfläche ankommt. */
    const val UTILIZATION = 0.8

    /** mol/m²/Tag aus µmol/m²/s und Stunden: × 3600 s / 1 000 000. */
    fun dli(ppfd: Double, hours: Double): Double = ppfd * hours * 0.0036

    /** Ziele je Phase; mit CO₂ ab 800 ppm vertragen Pflanzen in der Blüte mehr Licht. */
    fun target(phase: GrowPhase, co2Ppm: Double? = null): LightTarget {
        val co2 = (co2Ppm ?: 0.0) >= 800
        return when (phase) {
            GrowPhase.SEEDLING -> LightTarget(100.0, 300.0, 6.0, 19.0, 18.0)
            GrowPhase.VEGETATIVE ->
                if (co2) LightTarget(300.0, 750.0, 19.0, 48.0, 18.0, withCo2 = true) else LightTarget(300.0, 600.0, 19.0, 39.0, 18.0)
            GrowPhase.FLOWERING ->
                if (co2) LightTarget(600.0, 1200.0, 26.0, 52.0, 12.0, withCo2 = true) else LightTarget(600.0, 900.0, 26.0, 39.0, 12.0)
            GrowPhase.LATE_FLOWERING ->
                if (co2) LightTarget(500.0, 1100.0, 22.0, 48.0, 12.0, withCo2 = true) else LightTarget(500.0, 900.0, 22.0, 39.0, 12.0)
            GrowPhase.DRYING -> LightTarget(0.0, 5.0, 0.0, 0.2, 0.0)
        }
    }

    fun hours(setup: LightSetup?, phase: GrowPhase): Double = setup?.hoursPerDay ?: target(phase).defaultHours

    fun level(value: Double, min: Double, max: Double): LightLevel = when {
        value < min -> LightLevel.TOO_LOW
        value > max -> LightLevel.TOO_HIGH
        else -> LightLevel.OK
    }

    /** Gewicht jeder Lampe: Photonen 380–780 nm je Sekunde; ohne Leistung oder bei Tageslicht gleich gewichtet. */
    private fun weights(setup: LightSetup): List<Pair<LampType, Double>> {
        val lamps = setup.lamps.mapNotNull { use -> use.lamp?.let { it to use } }
        val dim = (setup.dimPercent ?: 100.0) / 100.0
        val photons = lamps.map { (lamp, use) ->
            val watts = use.watts
            val eff = lamp.photonEfficacy
            lamp to if (watts != null && eff != null) watts * use.count.coerceAtLeast(1) * eff * dim else null
        }
        // Fehlt irgendwo die Leistung, lassen sich die Lampen nicht vergleichen: gleich gewichten
        return if (photons.any { it.second == null }) photons.map { it.first to 1.0 } else photons.map { it.first to it.second!! }
    }

    /** Spektrum aller Lampen zusammen, nach Photonen gewichtet. */
    fun spectrum(setup: LightSetup): Spectrum {
        val w = weights(setup)
        val sum = w.sumOf { it.second }
        if (w.isEmpty() || sum <= 0) return Spectrum.ZERO
        fun mix(pick: (Spectrum) -> Double) = w.sumOf { (lamp, weight) -> pick(lamp.spectrum) * weight } / sum
        return Spectrum(mix { it.uv }, mix { it.blue }, mix { it.green }, mix { it.red }, mix { it.farRed }).normalized()
    }

    /**
     * PPFD je 1000 Lux für die Mischung.
     *
     * Jede Lampe liefert PAR-Photonen P_i und dafür Lux P_i / f_i. Gemessene Lux → PAR aller Lampen, auch der,
     * die ein Luxmeter nicht sieht (UV, Far-Red). null, wenn keine Lampe mit Lux messbar ist.
     */
    fun ppfdPerKlx(setup: LightSetup): Double? {
        val w = weights(setup)
        val parAll = w.sumOf { (lamp, weight) -> weight * lamp.spectrum.par }
        val visible = w.filter { it.first.ppfdPerKlx != null }
        val parVisible = visible.sumOf { (lamp, weight) -> weight * lamp.spectrum.par }
        val luxVisible = visible.sumOf { (lamp, weight) -> weight * lamp.spectrum.par / lamp.ppfdPerKlx!! }
        if (parVisible <= 0 || luxVisible <= 0) return null
        return parAll / luxVisible
    }

    /** Taugt Lux für diese Mischung? Nein, wenn schmalbandige Lampen mehr als 20 % des PAR liefern. */
    fun luxReliable(setup: LightSetup): Boolean {
        val w = weights(setup)
        val parAll = w.sumOf { (lamp, weight) -> weight * lamp.spectrum.par }
        if (parAll <= 0) return false
        val unreliable = w.filter { !it.first.luxReliable }.sumOf { (lamp, weight) -> weight * lamp.spectrum.par }
        return unreliable / parAll <= 0.2 && ppfdPerKlx(setup) != null
    }

    /** PPFD (400–700 nm) aus Lux, mit Kalibrierfaktor des Handys. */
    fun ppfdFromLux(lux: Double, setup: LightSetup): Double? =
        ppfdPerKlx(setup)?.let { lux / 1000.0 * it * setup.calibration }

    /** Lux, die bei dieser Mischung einem PPFD entsprechen (für Hinweise). */
    fun luxForPpfd(ppfd: Double, setup: LightSetup): Double? =
        ppfdPerKlx(setup)?.let { ppfd * 1000.0 / it / setup.calibration }

    /** Photonenfluss der Lampen im PAR-Bereich, µmol/s; null ohne Leistungsangaben. */
    fun parPhotonFlux(setup: LightSetup): Double? {
        if (setup.lamps.isEmpty()) return null
        val dim = (setup.dimPercent ?: 100.0) / 100.0
        var sum = 0.0
        for (use in setup.lamps) {
            val lamp = use.lamp ?: return null
            val eff = lamp.parEfficacy ?: return null
            val watts = use.watts ?: return null
            sum += watts * use.count.coerceAtLeast(1) * eff * dim
        }
        return sum
    }

    /** Geschätzter mittlerer PPFD auf der Fläche, aus Leistung, Effizienz und Fläche. */
    fun estimatedPpfd(setup: LightSetup): Double? {
        val area = setup.areaM2 ?: return null
        if (area <= 0) return null
        return parPhotonFlux(setup)?.let { it * UTILIZATION / area }
    }

    /** Leistung (W) einer Lampenart für einen Ziel-PPFD auf der Fläche, ungedimmt. */
    fun wattsFor(lamp: LampType, targetPpfd: Double, areaM2: Double): Double? {
        val eff = lamp.parEfficacy ?: return null
        if (eff <= 0 || areaM2 <= 0) return null
        return targetPpfd * areaM2 / (UTILIZATION * eff)
    }

    /** Photonen je Band in µmol/m²/s, ausgehend vom PPFD (400–700 nm). */
    fun bands(spectrum: Spectrum, ppfd: Double?): List<BandAmount> {
        val total = if (ppfd != null && spectrum.par > 0) ppfd / (spectrum.par / 100.0) else null
        return SpectrumBand.entries.map { band ->
            val percent = spectrum.band(band)
            BandAmount(band, percent, total?.let { it * percent / 100.0 })
        }
    }

    /** Photonen 380–780 nm (ePAR) zu einem PPFD. */
    fun extendedPpfd(spectrum: Spectrum, ppfd: Double): Double? =
        if (spectrum.par > 0) ppfd / (spectrum.par / 100.0) else null

    /**
     * Messwert in PPFD umrechnen. Der Kalibrierfaktor gilt nur für den Handy-Sensor;
     * Luxmeter und Tuya-Sensor gelten als richtig geeicht.
     */
    fun measure(source: LightSource, lux: Double?, manualPpfd: Double?, setup: LightSetup, saturated: Boolean = false): LightMeasurement {
        if (source == LightSource.MANUAL_PPFD) {
            return LightMeasurement(source, null, manualPpfd, manualPpfd, reliable = true)
        }
        if (lux == null) return LightMeasurement(source, null, null, null, reliable = true)
        val raw = ppfdFromLux(lux, setup.copy(calibration = 1.0))
            ?: return LightMeasurement(
                source, lux, null, null, reliable = false,
                note = "Diese Lampen sieht ein Luxmeter kaum (UV, Far-Red). Miss mit einem PAR-Meter und trag den PPFD ein.",
            )
        val factor = if (source == LightSource.PHONE) setup.calibration else 1.0
        val reliable = luxReliable(setup)
        val note = when {
            saturated -> "Der Handy-Sensor ist am Anschlag, der echte Wert liegt höher. Handy etwas tiefer halten oder ein Luxmeter nutzen."
            !reliable -> "Bei diesen Lampen passt Lux nur grob (±30 % und mehr). Genauer misst ein PAR-Meter."
            else -> null
        }
        return LightMeasurement(source, lux, raw * factor, raw, reliable, saturated, note)
    }

    fun uniformity(points: List<Double>): Uniformity? {
        if (points.isEmpty()) return null
        return Uniformity(points.average(), points.min(), points.max())
    }

    /** Neuer Kalibrierfaktor: Referenz (PAR-Meter) / berechneter Wert ohne Kalibrierung. */
    fun calibrationFor(referencePpfd: Double, uncalibratedPpfd: Double): Double? {
        if (referencePpfd <= 0 || uncalibratedPpfd <= 0) return null
        val factor = referencePpfd / uncalibratedPpfd
        return if (factor in LightSetup.CALIBRATION_RANGE) factor else null
    }

    /** Was tun? Ein Satz für die Oberfläche. */
    fun advice(ppfd: Double, target: LightTarget, setup: LightSetup, phase: GrowPhase): String {
        if (phase == GrowPhase.DRYING) {
            return if (ppfd > target.ppfdMax) "Zum Trocknen im Dunkeln: Licht aus, auch kein Streulicht." else "Dunkel genug zum Trocknen."
        }
        val onlyDaylight = setup.lamps.isNotEmpty() && setup.lamps.all { it.lampId == LampCatalog.DAYLIGHT }
        val dim = setup.dimPercent
        return when (level(ppfd, target.ppfdMin, target.ppfdMax)) {
            LightLevel.OK -> "Passt für die Phase „${phase.label}“."
            LightLevel.TOO_LOW -> when {
                onlyDaylight -> "Zu wenig Licht: helleren Platz suchen oder mit einer Lampe ergänzen."
                dim != null && dim < 100 -> {
                    val needed = (dim * target.ppfdMid / ppfd.coerceAtLeast(1.0)).coerceAtMost(100.0)
                    if (needed >= 99.5) "Dimmer auf 100 % und die Lampe näher an die Pflanzen."
                    else "Dimmer auf etwa ${needed.roundToInt()} % stellen."
                }
                else -> "Lampe näher an die Pflanzen hängen oder mehr Leistung einsetzen."
            }
            LightLevel.TOO_HIGH -> when {
                onlyDaylight -> "Sehr viel Licht: schattieren oder die Pflanzen langsam daran gewöhnen."
                dim != null -> {
                    val needed = (dim * target.ppfdMid / ppfd).coerceAtLeast(10.0)
                    "Auf etwa ${needed.roundToInt()} % dimmen oder die Lampe höher hängen."
                }
                else -> "Lampe höher hängen, sonst drohen Lichtschäden (gelbe, ausgeblichene Spitzen)."
            }
        }
    }

    /** Hinweise zum Spektrum, höchstens drei. */
    fun spectrumHints(spectrum: Spectrum, phase: GrowPhase): List<String> {
        if (spectrum.total <= 0) return emptyList()
        val hints = mutableListOf<String>()
        val blue = spectrum.blueOfPar
        val growing = phase == GrowPhase.SEEDLING || phase == GrowPhase.VEGETATIVE
        when {
            blue < 10 -> hints += "Wenig Blau (${blue.roundToInt()} % vom PAR): Pflanzen strecken sich eher. Üblich sind 15–30 %."
            growing && blue >= 15 -> hints += "Guter Blauanteil fürs Wachstum (${blue.roundToInt()} % vom PAR): kompakter Wuchs."
            !growing && blue > 30 -> hints += "Viel Blau für die Blüte (${blue.roundToInt()} % vom PAR). Rotlastigeres Licht ist hier üblich."
        }
        val rfr = spectrum.redToFarRed
        if (rfr != null && rfr < 3) {
            hints += if (growing) "Viel Dunkelrot (Rot : Dunkelrot ${fmt(rfr)}): fördert Streckung."
            else "Viel Dunkelrot (Rot : Dunkelrot ${fmt(rfr)}): kann die Blüte beschleunigen, aber auch strecken."
        }
        if (growing && spectrum.redOfPar > 55) hints += "Sehr rotlastig für die Phase „${phase.label}“: mehr Blau hält die Pflanzen kompakt."
        if (spectrum.uv >= 2) hints += "Mit UV-A-Anteil: Schutzbrille tragen und nicht in die Lampe sehen."
        return hints.take(3)
    }

    private fun fmt(value: Double) = String.format(java.util.Locale.GERMANY, "%.1f", value)
}
