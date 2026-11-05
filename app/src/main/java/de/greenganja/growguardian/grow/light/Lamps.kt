package de.greenganja.growguardian.grow.light

/**
 * Photonenanteile einer Lampe in Prozent zwischen 380 und 780 nm, Summe 100.
 *
 * Bänder: UV-A 380–400, Blau 400–500, Grün 500–600, Rot 600–700, Dunkelrot (Far-Red) 700–780 nm.
 * PAR (photosynthetisch aktiv) ist 400–700 nm, also Blau + Grün + Rot.
 */
data class Spectrum(
    val uv: Double,
    val blue: Double,
    val green: Double,
    val red: Double,
    val farRed: Double,
) {
    val total: Double get() = uv + blue + green + red + farRed

    /** PAR-Anteil an allen Photonen 380–780 nm, in Prozent. */
    val par: Double get() = blue + green + red

    /** Blauanteil am PAR in Prozent. */
    val blueOfPar: Double get() = if (par > 0) blue / par * 100.0 else 0.0

    /** Rotanteil am PAR in Prozent. */
    val redOfPar: Double get() = if (par > 0) red / par * 100.0 else 0.0

    /** Rot zu Dunkelrot; null, wenn die Lampe kein Dunkelrot hat (Verhältnis unendlich). */
    val redToFarRed: Double? get() = if (farRed > 0) red / farRed else null

    fun band(band: SpectrumBand): Double = when (band) {
        SpectrumBand.UV -> uv
        SpectrumBand.BLUE -> blue
        SpectrumBand.GREEN -> green
        SpectrumBand.RED -> red
        SpectrumBand.FAR_RED -> farRed
    }

    /** Auf Summe 100 bringen. */
    fun normalized(): Spectrum {
        val t = total
        if (t <= 0) return this
        val k = 100.0 / t
        return Spectrum(uv * k, blue * k, green * k, red * k, farRed * k)
    }

    companion object {
        val ZERO = Spectrum(0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

enum class SpectrumBand(val label: String, val range: String, val inPar: Boolean) {
    UV("UV-A", "380–400 nm", false),
    BLUE("Blau", "400–500 nm", true),
    GREEN("Grün", "500–600 nm", true),
    RED("Rot", "600–700 nm", true),
    FAR_RED("Dunkelrot", "700–780 nm", false),
}

enum class LampCategory(val label: String) {
    DAYLIGHT("Tageslicht"),
    INCANDESCENT("Glühlampen"),
    LED("LED"),
    HID("Hochdrucklampen (HID)"),
    FLUORESCENT("Leuchtstofflampen"),
    OTHER("Sonstige"),
}

/**
 * Eine Lampenart.
 *
 * @param ppfdPerKlx PPFD in µmol/m²/s je 1000 Lux; null, wenn ein Luxmeter diese Lampe nicht sinnvoll misst
 *   (UV, Far-Red). Quellen: Thimijan & Heins 1983, Apogee Instruments; LED-Werte nach CRI. Richtwerte, ±10–15 %.
 * @param luxReliable false, wenn Lux bei dieser Lampe nur grob passt (schmalbandige LEDs)
 * @param photonEfficacy Photonen 380–780 nm je Joule (µmol/J) einer typischen Leuchte; null für Tageslicht
 * @param spectrum typische Photonenverteilung (Richtwerte)
 * @param freemium auch ohne Abo wählbar
 */
data class LampType(
    val id: String,
    val name: String,
    val category: LampCategory,
    val ppfdPerKlx: Double?,
    val luxReliable: Boolean,
    val photonEfficacy: Double?,
    val spectrum: Spectrum,
    val hint: String,
    val freemium: Boolean = false,
) {
    /** PAR-Photonen je Joule (µmol/J), die übliche Angabe auf Datenblättern. */
    val parEfficacy: Double? get() = photonEfficacy?.let { it * spectrum.par / 100.0 }

    /** Lux je µmol/m²/s, wie es Herstellertabellen oft angeben. */
    val luxPerPpfd: Double? get() = ppfdPerKlx?.let { 1000.0 / it }
}

/** Alle Lampenarten. Freemium: nur Tageslicht und Glühbirne. Liste muss zu LAMP_IDS im Backend passen. */
object LampCatalog {
    const val DAYLIGHT = "daylight"
    const val INCANDESCENT = "incandescent"

    private fun s(uv: Number, blue: Number, green: Number, red: Number, farRed: Number) =
        Spectrum(uv.toDouble(), blue.toDouble(), green.toDouble(), red.toDouble(), farRed.toDouble())

    val all: List<LampType> = listOf(
        // ---------- frei ----------
        LampType(
            DAYLIGHT, "Tageslicht (Sonne)", LampCategory.DAYLIGHT, 18.5, true, null, s(3, 21, 26, 28, 22),
            "Draußen, am Fenster oder im Gewächshaus. Glas schluckt einen Teil des UV.", freemium = true,
        ),
        LampType(
            INCANDESCENT, "Glühbirne / Halogen", LampCategory.INCANDESCENT, 20.0, true, 0.35, s(0, 4, 14, 30, 52),
            "Viel Wärme und Dunkelrot, wenig Blau: für Pflanzen kaum geeignet, Pflanzen strecken sich.", freemium = true,
        ),

        // ---------- LED ----------
        LampType("led_white_2700", "LED weiß 2700 K", LampCategory.LED, 14.5, true, 2.75, s(0, 11, 40, 45, 4), "Warmweiß, rotlastig: gut für die Blüte."),
        LampType("led_white_3000", "LED weiß 3000 K (CRI 80)", LampCategory.LED, 14.3, true, 2.85, s(0, 13, 42, 41, 4), "Der übliche Allrounder für Wachstum und Blüte (z. B. Quantum Boards)."),
        LampType("led_white_3000_90", "LED weiß 3000 K (CRI 90)", LampCategory.LED, 15.9, true, 2.55, s(0, 12, 37, 45, 6), "Mehr Rot und Dunkelrot als CRI 80, etwas weniger effizient."),
        LampType("led_white_3500", "LED weiß 3500 K", LampCategory.LED, 14.2, true, 2.85, s(0, 16, 43, 37, 4), "Ausgewogen, etwas mehr Blau als 3000 K."),
        LampType("led_white_4000", "LED weiß 4000 K", LampCategory.LED, 14.0, true, 2.85, s(0, 19, 44, 34, 3), "Neutralweiß, kompakter Wuchs."),
        LampType("led_white_5000", "LED weiß 5000–6500 K", LampCategory.LED, 13.9, true, 2.8, s(0, 25, 45, 27, 3), "Kaltweiß, viel Blau: Stecklinge und Wachstum."),
        LampType("led_white_red", "LED weiß + Rot 660 nm", LampCategory.LED, 17.0, true, 2.95, s(0, 11, 32, 53, 4), "Weiße Dioden mit zusätzlichem Tiefrot: sehr effizient in der Blüte."),
        LampType("led_full_uv_ir", "LED Vollspektrum mit UV und Far-Red", LampCategory.LED, 17.5, true, 2.75, s(2, 11, 29, 47, 11), "Weiß + 660 nm + UV-A + 730 nm. UV und Far-Red misst ein Luxmeter nicht mit."),
        LampType("led_cob", "LED COB 3000–3500 K", LampCategory.LED, 15.0, true, 2.4, s(0, 14, 41, 41, 4), "Punktförmige Hochleistungsdioden, heller Hotspot unter der Lampe."),
        LampType("led_blurple", "LED Rot/Blau („Blurple“)", LampCategory.LED, 30.0, false, 2.0, s(1, 30, 4, 63, 2), "Ältere Rot/Blau-Lampen. Lux taugt hier nur grob, besser ein PAR-Meter."),
        LampType("led_red_660", "LED Rot 660 nm (Zusatz)", LampCategory.LED, 133.0, false, 3.6, s(0, 0, 0, 96, 4), "Zusatzlicht für die Blüte. Lux passt hier nicht, PPFD lieber berechnen oder mit PAR-Meter messen."),
        LampType("led_blue_450", "LED Blau 450 nm (Zusatz)", LampCategory.LED, 145.0, false, 3.0, s(1, 97, 2, 0, 0), "Zusatzlicht für kompakten Wuchs. Lux passt hier nicht."),
        LampType("led_uv", "LED UV-A 365–395 nm (Zusatz)", LampCategory.LED, null, false, 1.2, s(92, 8, 0, 0, 0), "Nur als Zusatz, kurz am Tag. Schutzbrille, nicht hineinsehen. Mit Lux nicht messbar."),
        LampType("led_far_red", "LED Far-Red 730 nm (Zusatz)", LampCategory.LED, null, false, 2.8, s(0, 0, 0, 6, 94), "Zum Ende der Lichtphase („Emerson-Effekt“). Mit Lux nicht messbar."),

        // ---------- HID ----------
        LampType("hps_se", "NDL / HPS (Natriumdampf)", LampCategory.HID, 12.2, true, 1.6, s(0, 5, 47, 41, 7), "Klassische Blütelampe, viel Wärme. Abstand je nach Leistung 30–60 cm."),
        LampType("hps_de", "NDL / HPS Double Ended", LampCategory.HID, 13.0, true, 2.05, s(0, 5, 45, 43, 7), "Effizienter als einseitig gesockelt, gleichmäßiger, sehr heiß."),
        LampType("hps_dual", "NDL Dual-Spektrum / Agro", LampCategory.HID, 12.5, true, 1.6, s(1, 8, 45, 39, 7), "Natriumdampf mit mehr Blau, für Wachstum und Blüte."),
        LampType("mh", "MH / Metallhalogenid", LampCategory.HID, 14.1, true, 1.2, s(3, 24, 43, 23, 7), "Bläulich, für die Wachstumsphase."),
        LampType("cmh_3100", "CMH / LEC 3100 K", LampCategory.HID, 17.0, true, 1.95, s(2, 14, 35, 38, 11), "Keramik-Metallhalogenid, sonnenähnlich, rotverschoben: Blüte."),
        LampType("cmh_4200", "CMH / LEC 4200 K", LampCategory.HID, 15.4, true, 1.85, s(3, 21, 39, 28, 9), "Keramik-Metallhalogenid, mehr Blau: Wachstum."),

        // ---------- Leuchtstoff ----------
        LampType("t5_6500", "T5 Leuchtstoff 6500 K", LampCategory.FLUORESCENT, 13.5, true, 1.05, s(2, 31, 44, 22, 1), "Für Stecklinge und Mütter, wenig Wärme, nah an die Pflanzen."),
        LampType("t5_3000", "T5 Leuchtstoff 2700–3000 K", LampCategory.FLUORESCENT, 13.6, true, 1.05, s(1, 14, 45, 38, 2), "Warmweiße Röhre, für kleine Blüte-Setups."),
        LampType("cfl_6500", "ESL / CFL 6500 K", LampCategory.FLUORESCENT, 13.5, true, 0.8, s(2, 30, 45, 22, 1), "Energiesparlampe, kaltweiß: Keimlinge und Stecklinge."),
        LampType("cfl_2700", "ESL / CFL 2700 K", LampCategory.FLUORESCENT, 13.7, true, 0.8, s(1, 12, 45, 40, 2), "Energiesparlampe, warmweiß."),
        LampType("induction", "Induktionslampe", LampCategory.FLUORESCENT, 13.5, true, 1.1, s(2, 27, 45, 24, 2), "Langlebig, wenig Wärme, mäßige Effizienz."),

        // ---------- Sonstige ----------
        LampType("plasma", "Plasma (LEP)", LampCategory.OTHER, 17.0, true, 1.3, s(3, 20, 37, 30, 10), "Sonnenähnliches Spektrum, selten und teuer."),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: String?): LampType? = id?.let { byId[it] }

    val FREE_IDS: Set<String> = all.filter { it.freemium }.map { it.id }.toSet()

    fun grouped(): Map<LampCategory, List<LampType>> = all.groupBy { it.category }

    /** Darf diese Stufe die Lampe wählen? */
    fun allowed(lamp: LampType, allLampTypes: Boolean): Boolean = allLampTypes || lamp.freemium
}
