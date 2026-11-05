package de.greenganja.growguardian.grow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.ads.inlineAd
import de.greenganja.growguardian.grow.GrowPhase
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.light.LampCatalog
import de.greenganja.growguardian.grow.light.LampCategory
import de.greenganja.growguardian.grow.light.LampType
import de.greenganja.growguardian.grow.light.LampUse
import de.greenganja.growguardian.grow.light.LightLevel
import de.greenganja.growguardian.grow.light.LightMath
import de.greenganja.growguardian.grow.light.LightMeasurement
import de.greenganja.growguardian.grow.light.LightReading
import de.greenganja.growguardian.grow.light.LightRepository
import de.greenganja.growguardian.grow.light.LightRules
import de.greenganja.growguardian.grow.light.LightSetup
import de.greenganja.growguardian.grow.light.LightSource
import de.greenganja.growguardian.grow.light.PhoneLightSensor
import de.greenganja.growguardian.grow.light.PhoneLux
import de.greenganja.growguardian.grow.light.Spectrum
import de.greenganja.growguardian.grow.light.SpectrumBand
import de.greenganja.growguardian.ui.theme.GrowPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class LightViewModel(
    private val boxId: String,
    private val repository: LightRepository,
    private val phone: PhoneLightSensor?,
) : ViewModel() {
    val readings: StateFlow<List<LightReading>?> = repository.readings(boxId).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _source = MutableStateFlow(if (phone?.available == true) LightSource.PHONE else LightSource.MANUAL_LUX)
    val source: StateFlow<LightSource> = _source.asStateFlow()

    val phoneAvailable: Boolean get() = phone?.available == true

    /** Live-Wert des Handy-Sensors, nur solange „Handy-Sensor“ gewählt ist. */
    val phoneLux: StateFlow<PhoneLux?> = _source
        .flatMapLatest { if (it == LightSource.PHONE && phone != null) phone.readings() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), null)

    /** Übernommene Messpunkte (PPFD), z. B. Mitte und vier Ecken. */
    private val _points = MutableStateFlow<List<Double>>(emptyList())
    val points: StateFlow<List<Double>> = _points.asStateFlow()

    /** Zuletzt eingegebenes Setup, bis das gespeicherte aus Firestore nachkommt (oder ohne Netz). */
    private val _draft = MutableStateFlow<LightSetup?>(null)
    val draft: StateFlow<LightSetup?> = _draft.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    fun setSource(source: LightSource) {
        _source.value = source
    }

    fun addPoint(ppfd: Double) {
        if (_points.value.size >= LightRules.MAX_POINTS) return
        _points.value = _points.value + ppfd
    }

    fun removePoint(index: Int) {
        _points.value = _points.value.filterIndexed { i, _ -> i != index }
    }

    /** @return Fehlermeldung oder null */
    fun saveSetup(setup: LightSetup, allLampTypes: Boolean): String? {
        LightRules.validate(setup, allLampTypes)?.let { return it }
        _draft.value = setup
        viewModelScope.launch {
            _busy.value = true
            repository.saveSetup(boxId, setup)
                .onSuccess { events.send("Lampen gespeichert.") }
                .onFailure { events.send(it.message ?: "Speichern hat nicht geklappt.") }
            _busy.value = false
        }
        return null
    }

    /** Speichert das Mittel der Messpunkte, ohne Punkte den aktuellen Wert. */
    fun saveReading(measurement: LightMeasurement, setup: LightSetup, phase: GrowPhase): String? {
        val current = measurement.ppfd
        val pts = _points.value.ifEmpty { listOfNotNull(current) }
        if (pts.isEmpty()) return "Noch kein Messwert."
        val uniformity = LightMath.uniformity(pts)!!
        val hours = LightMath.hours(setup, phase)
        val reading = LightReading(
            source = measurement.source,
            lux = measurement.lux?.takeIf { _points.value.isEmpty() },
            ppfd = uniformity.average,
            dli = LightMath.dli(uniformity.average, hours),
            hours = hours,
            points = pts.size,
            minPpfd = uniformity.min.takeIf { pts.size > 1 },
            maxPpfd = uniformity.max.takeIf { pts.size > 1 },
            lampIds = setup.lampIds,
            phase = phase.id,
        )
        LightRules.validateReading(reading)?.let { return it }
        viewModelScope.launch {
            try {
                repository.saveReading(boxId, reading)
                _points.value = emptyList()
                events.send("Messung gespeichert.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.send("Speichern hat nicht geklappt.")
            }
        }
        return null
    }

    fun deleteReading(reading: LightReading) {
        viewModelScope.launch {
            runCatching { repository.deleteReading(boxId, reading.id) }
            events.send("Messung gelöscht.")
        }
    }
}

private fun num(value: Double, decimals: Int = 0): String = String.format(Locale.GERMANY, "%.${decimals}f", value)
private fun plainNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else num(value, 2).trimEnd('0').trimEnd(',')
private val READING_DATE = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

private fun bandColor(band: SpectrumBand): Color = when (band) {
    SpectrumBand.UV -> Color(0xFF8B5CF6)
    SpectrumBand.BLUE -> Color(0xFF3B82F6)
    SpectrumBand.GREEN -> Color(0xFF22C55E)
    SpectrumBand.RED -> Color(0xFFEF4444)
    SpectrumBand.FAR_RED -> Color(0xFF991B1B)
}

private fun levelColor(level: LightLevel): Color = when (level) {
    LightLevel.OK -> GrowPalette.Green
    LightLevel.TOO_LOW, LightLevel.TOO_HIGH -> GrowPalette.Warning
}

private fun lampLine(use: LampUse): String {
    val name = use.lamp?.name ?: use.lampId
    val watts = use.watts?.let { " × ${plainNumber(it)} W" } ?: ""
    return if (use.count > 1 || use.watts != null) "${use.count}$watts $name" else name
}

/**
 * Lichtmodul einer Box: Lampen eintragen, mit Handy, Tuya-Sensor oder Messgerät messen, PPFD/DLI gegen die Phase,
 * Spektrum-Mengen und Lampenrechner. Freemium wählt nur Tageslicht und Glühbirne.
 */
@Composable
internal fun LightScreen(
    item: BoxItem,
    allLampTypes: Boolean,
    viewModel: LightViewModel,
    onUpgradeClick: () -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit,
) {
    val box = item.box
    val readings by viewModel.readings.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val phoneLux by viewModel.phoneLux.collectAsStateWithLifecycle()
    val points by viewModel.points.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) { viewModel.messages.collect(onMessage) }

    val setup: LightSetup? = draft ?: box.light
    if (editing) {
        BackHandler { editing = false }
        LampEditor(
            initial = setup ?: LightSetup(),
            phase = box.phase,
            allLampTypes = allLampTypes,
            onUpgradeClick = onUpgradeClick,
            onSave = { s -> viewModel.saveSetup(s, allLampTypes).also { if (it == null) editing = false } },
            onBack = { editing = false },
        )
        return
    }

    val co2 = item.state.reading(Metric.CO2)?.value
    val target = LightMath.target(box.phase, co2)
    val tuyaLux = item.state.reading(Metric.LIGHT)?.value
    val effective = setup ?: LightSetup(listOf(LampUse(LampCatalog.DAYLIGHT)))
    val lux = when (source) {
        LightSource.PHONE -> phoneLux?.lux
        LightSource.TUYA -> tuyaLux
        LightSource.MANUAL_LUX -> parseNumber(manual)
        LightSource.MANUAL_PPFD -> null
    }
    val measurement = LightMath.measure(
        source, lux, if (source == LightSource.MANUAL_PPFD) parseNumber(manual) else null, effective,
        saturated = source == LightSource.PHONE && phoneLux?.saturated == true,
    )
    val hours = LightMath.hours(setup, box.phase)
    val spectrum = LightMath.spectrum(effective)
    val estimate = setup?.let { LightMath.estimatedPpfd(it) }

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = "Licht · ${box.name}", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SetupCard(setup, box.phase, hours, estimate, target, busy, onEdit = { editing = true }) }
            if (!allLampTypes) {
                item {
                    UpgradeRow(
                        title = "Alle Lampenarten ab Premium",
                        subtitle = "Ohne Abo: Tageslicht und Glühbirne. Mit Premium: LED, NDL, CMH, MH, T5 und mehr",
                        onClick = onUpgradeClick,
                    )
                }
            }
            inlineAd("light-top")

            item {
                MeasureCard(
                    source = source,
                    phoneAvailable = viewModel.phoneAvailable,
                    tuyaLux = tuyaLux,
                    phoneLux = phoneLux,
                    manual = manual,
                    onManual = { manual = it },
                    onSource = { viewModel.setSource(it); manual = "" },
                    measurement = measurement,
                    target = target,
                    hours = hours,
                    advice = measurement.ppfd?.let { LightMath.advice(it, target, effective, box.phase) },
                    setupMissing = setup == null,
                )
            }
            item {
                PointsCard(
                    points = points,
                    canAdd = measurement.ppfd != null,
                    onAdd = { measurement.ppfd?.let(viewModel::addPoint) },
                    onRemove = viewModel::removePoint,
                    onSave = { error = viewModel.saveReading(measurement, effective, box.phase) },
                    error = error,
                )
            }

            item { SpectrumCard(spectrum, measurement.ppfd ?: estimate, box.phase, effective) }
            if (setup != null) {
                item { CalculatorCard(setup, target) }
                item {
                    CalibrationCard(
                        setup = setup,
                        measurement = measurement,
                        busy = busy,
                        onCalibrate = { factor -> viewModel.saveSetup(setup.copy(calibration = factor), allLampTypes)?.let { error = it } },
                    )
                }
            }

            item { SectionHeader("Gespeicherte Messungen") }
            val list = readings.orEmpty()
            if (readings != null && list.isEmpty()) item { Muted("Noch keine Messungen. Miss am besten an mehreren Punkten: Mitte und vier Ecken.") }
            items(list, key = { it.id }) { reading -> ReadingCard(reading, target, onDelete = { viewModel.deleteReading(reading) }) }
            inlineAd("light-end")
        }
    }
}

@Composable
private fun SetupCard(
    setup: LightSetup?,
    phase: GrowPhase,
    hours: Double,
    estimate: Double?,
    target: de.greenganja.growguardian.grow.light.LightTarget,
    busy: Boolean,
    onEdit: () -> Unit,
) {
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lampen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                PillAction(if (setup == null) "Eintragen" else "Bearbeiten", onClick = onEdit, enabled = !busy)
            }
            if (setup == null) {
                Muted("Trag ein, welche Lampen über der Box hängen. Davon hängen die Umrechnung von Lux in PPFD und das Spektrum ab.")
            } else {
                setup.lamps.forEach { Text(lampLine(it), style = MaterialTheme.typography.bodyMedium) }
                Muted(
                    listOfNotNull(
                        setup.areaM2?.let { "Fläche ${plainNumber(it)} m²" },
                        setup.dimPercent?.let { "Dimmer ${num(it)} %" },
                        "${plainNumber(hours)} h Licht/Tag",
                        "Kalibrierung ×${num(setup.calibration, 2)}".takeIf { setup.calibration != 1.0 },
                    ).joinToString(" · "),
                )
                if (estimate != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Geschätzt Ø ${num(estimate)} µmol/m²/s · DLI ${num(LightMath.dli(estimate, hours), 1)}", style = MaterialTheme.typography.bodyMedium)
                        val level = LightMath.level(estimate, target.ppfdMin, target.ppfdMax)
                        StatusChip(level.label, levelColor(level))
                    }
                    Muted("Schätzung aus Leistung, typischer Effizienz und Fläche; die Messung ist genauer.")
                }
            }
            Muted("Ziel „${phase.label}“: ${num(target.ppfdMin)}–${num(target.ppfdMax)} µmol/m²/s, DLI ${num(target.dliMin)}–${num(target.dliMax)} mol/m²/Tag" + if (target.withCo2) " (mit CO₂)" else "")
        }
    }
}

@Composable
private fun SourceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MeasureCard(
    source: LightSource,
    phoneAvailable: Boolean,
    tuyaLux: Double?,
    phoneLux: PhoneLux?,
    manual: String,
    onManual: (String) -> Unit,
    onSource: (LightSource) -> Unit,
    measurement: LightMeasurement,
    target: de.greenganja.growguardian.grow.light.LightTarget,
    hours: Double,
    advice: String?,
    setupMissing: Boolean,
) {
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Messen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phoneAvailable) SourceChip("Handy", source == LightSource.PHONE) { onSource(LightSource.PHONE) }
                if (tuyaLux != null) SourceChip("Tuya-Sensor", source == LightSource.TUYA) { onSource(LightSource.TUYA) }
                SourceChip("Lux eingeben", source == LightSource.MANUAL_LUX) { onSource(LightSource.MANUAL_LUX) }
                SourceChip("PPFD eingeben", source == LightSource.MANUAL_PPFD) { onSource(LightSource.MANUAL_PPFD) }
            }
            when (source) {
                LightSource.PHONE -> {
                    Muted("Handy flach auf Höhe der Blattspitzen legen, Display nach oben. Der Sensor sitzt oben neben der Frontkamera: nicht verdecken, keinen Schatten werfen.")
                    Text(
                        phoneLux?.let { "${num(it.lux)} lx" } ?: "Warte auf den Sensor …",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                LightSource.TUYA -> Text(
                    tuyaLux?.let { "${num(it)} lx vom Tuya-Lichtsensor" } ?: "Gerade kein Wert vom Tuya-Lichtsensor",
                    style = MaterialTheme.typography.bodyLarge,
                )
                LightSource.MANUAL_LUX, LightSource.MANUAL_PPFD -> OutlinedTextField(
                    value = manual,
                    onValueChange = { onManual(it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(8)) },
                    label = { Text(if (source == LightSource.MANUAL_LUX) "Lux vom Luxmeter" else "PPFD vom PAR-Meter (µmol/m²/s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (setupMissing && source != LightSource.MANUAL_PPFD) Muted("Ohne eingetragene Lampen rechnet die App mit Tageslicht.")
            val ppfd = measurement.ppfd
            if (ppfd != null) {
                val level = LightMath.level(ppfd, target.ppfdMin, target.ppfdMax)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${num(ppfd)} µmol/m²/s", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    StatusChip(level.label, levelColor(level))
                }
                val dli = LightMath.dli(ppfd, hours)
                val dliLevel = LightMath.level(dli, target.dliMin, target.dliMax)
                Muted("DLI ${num(dli, 1)} mol/m²/Tag bei ${plainNumber(hours)} h Licht (Ziel ${num(target.dliMin)}–${num(target.dliMax)}): ${dliLevel.label.lowercase()}")
                TargetBar(ppfd, target)
                advice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            measurement.note?.let { Muted(it, color = GrowPalette.Warning) }
        }
    }
}

/** Balken 0 … 1,3 × Zielobergrenze mit markiertem Zielbereich. */
@Composable
private fun TargetBar(ppfd: Double, target: de.greenganja.growguardian.grow.light.LightTarget) {
    val scale = (target.ppfdMax * 1.3).coerceAtLeast(1.0)
    val fill = (ppfd / scale).toFloat().coerceIn(0f, 1f)
    val level = LightMath.level(ppfd, target.ppfdMin, target.ppfdMax)
    Box(modifier = Modifier.fillMaxWidth().height(10.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(50))) {
        Box(modifier = Modifier.fillMaxWidth(fill).height(10.dp).background(levelColor(level), RoundedCornerShape(50)))
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Muted("0", modifier = Modifier.weight(1f))
        Muted("Ziel ${num(target.ppfdMin)}–${num(target.ppfdMax)}", modifier = Modifier.weight(2f))
        Muted(num(scale))
    }
}

@Composable
private fun PointsCard(
    points: List<Double>,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onSave: () -> Unit,
    error: String?,
) {
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Messpunkte", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Muted("Miss an mehreren Stellen (Mitte, vier Ecken), dann siehst du, wie gleichmäßig das Licht verteilt ist.")
            if (points.isNotEmpty()) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    points.forEachIndexed { index, value -> SourceChip("${index + 1}: ${num(value)} ✕", selected = false) { onRemove(index) } }
                }
                LightMath.uniformity(points)?.takeIf { points.size > 1 }?.let { u ->
                    Muted("Ø ${num(u.average)} · min ${num(u.min)} · max ${num(u.max)} · Gleichmäßigkeit ${num(u.ratio * 100)} % (${u.label})")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAdd, enabled = canAdd && points.size < LightRules.MAX_POINTS) { Text("Punkt übernehmen") }
                Button(onClick = onSave, enabled = canAdd || points.isNotEmpty()) { Text(if (points.size > 1) "Mittel speichern" else "Messung speichern") }
            }
            error?.let { Muted(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SpectrumCard(spectrum: Spectrum, ppfd: Double?, phase: GrowPhase, setup: LightSetup) {
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Spektrum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Muted(
                if (ppfd != null) "Photonen je Band bei ${num(ppfd)} µmol/m²/s PAR (typisches Spektrum der Lampen, Richtwerte)"
                else "Anteile je Band (typisches Spektrum der Lampen, Richtwerte). Miss oder trag Leistung und Fläche ein für die Mengen.",
            )
            val bands = LightMath.bands(spectrum, ppfd)
            val maxPercent = bands.maxOf { it.percent }.coerceAtLeast(1.0)
            bands.forEach { amount ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.width(96.dp)) {
                        Text(amount.band.label, style = MaterialTheme.typography.bodyMedium)
                        Muted(amount.band.range)
                    }
                    Box(modifier = Modifier.weight(1f).height(12.dp)) {
                        Box(
                            modifier = Modifier.fillMaxWidth((amount.percent / maxPercent).toFloat().coerceIn(0.02f, 1f)).height(12.dp)
                                .background(bandColor(amount.band), RoundedCornerShape(4.dp)),
                        )
                    }
                    Text(
                        num(amount.percent) + " %" + (amount.ppfd?.let { " · ${num(it)}" } ?: ""),
                        modifier = Modifier.padding(start = 8.dp).width(96.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val rfr = spectrum.redToFarRed
            Muted(
                listOfNotNull(
                    "PAR ${num(spectrum.par)} % der Photonen",
                    "Blau ${num(spectrum.blueOfPar)} % vom PAR",
                    "Rot : Dunkelrot " + (rfr?.let { num(it, 1) } ?: "ohne Dunkelrot"),
                    ppfd?.let { p -> LightMath.extendedPpfd(spectrum, p)?.let { "380–780 nm: ${num(it)} µmol/m²/s" } },
                ).joinToString(" · "),
            )
            LightMath.spectrumHints(spectrum, phase).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            setup.lamps.mapNotNull { it.lamp }.distinct().forEach { lamp -> Muted("${lamp.name}: ${lamp.hint}") }
        }
    }
}

@Composable
private fun CalculatorCard(setup: LightSetup, target: de.greenganja.growguardian.grow.light.LightTarget) {
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Lampenrechner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val area = setup.areaM2
            if (area == null) {
                Muted("Trag die Fläche ein, dann rechnet die App aus, wie viel Leistung du für das Ziel brauchst.")
            } else {
                Muted("Für ${num(target.ppfdMid)} µmol/m²/s auf ${plainNumber(area)} m² (ungedimmt, ${num(LightMath.UTILIZATION * 100)} % kommen an):")
                setup.lamps.mapNotNull { it.lamp }.distinct().forEach { lamp ->
                    val watts = LightMath.wattsFor(lamp, target.ppfdMid, area)
                    Text(
                        if (watts != null) "≈ ${num(watts)} W ${lamp.name} (${num(lamp.parEfficacy ?: 0.0, 2)} µmol/J)"
                        else "${lamp.name}: ohne Leistungsangabe nicht berechenbar",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationCard(setup: LightSetup, measurement: LightMeasurement, busy: Boolean, onCalibrate: (Double) -> Unit) {
    var reference by remember { mutableStateOf("") }
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Handy kalibrieren", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Muted("Handy-Sensoren weichen oft 20–30 % ab. Leg Handy und PAR-Meter an dieselbe Stelle und trag den Wert des PAR-Meters ein. Aktuell: ×${num(setup.calibration, 2)}")
            OutlinedTextField(
                value = reference,
                onValueChange = { reference = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(6) },
                label = { Text("PAR-Meter zeigt (µmol/m²/s)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            val raw = measurement.uncalibratedPpfd.takeIf { measurement.source == LightSource.PHONE }
            val factor = parseNumber(reference)?.let { ref -> raw?.let { LightMath.calibrationFor(ref, it) } }
            if (measurement.source != LightSource.PHONE) Muted("Zum Kalibrieren oben „Handy“ als Quelle wählen.")
            else if (parseNumber(reference) != null && factor == null) Muted("Der Unterschied ist zu groß. Liegen Handy und PAR-Meter an derselben Stelle?", color = GrowPalette.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { factor?.let(onCalibrate) }, enabled = factor != null && !busy) {
                    Text(factor?.let { "Faktor ${num(it, 2)} übernehmen" } ?: "Übernehmen")
                }
                if (setup.calibration != 1.0) TextButton(onClick = { onCalibrate(1.0) }, enabled = !busy) { Text("Zurücksetzen") }
            }
        }
    }
}

@Composable
private fun ReadingCard(reading: LightReading, target: de.greenganja.growguardian.grow.light.LightTarget, onDelete: () -> Unit) {
    GrowCard {
        Row(modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${num(reading.ppfd)} µmol/m²/s", style = MaterialTheme.typography.titleSmall)
                    val level = LightMath.level(reading.ppfd, target.ppfdMin, target.ppfdMax)
                    StatusChip(level.label, levelColor(level))
                }
                Muted(
                    listOfNotNull(
                        reading.createdAtMillis?.let { READING_DATE.format(Date(it)) },
                        reading.dli?.let { "DLI ${num(it, 1)}" },
                        "${reading.points} Punkte".takeIf { reading.points > 1 },
                        reading.minPpfd?.let { min -> reading.maxPpfd?.let { max -> "${num(min)}–${num(max)}" } },
                        reading.source.label,
                    ).joinToString(" · "),
                )
                Muted(reading.lampIds.mapNotNull { LampCatalog.get(it)?.name }.joinToString(", "))
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Löschen") }
        }
    }
}

/** Lampen bearbeiten: Art, Leistung, Anzahl; Fläche, Dimmer, Lichtstunden. */
@Composable
internal fun LampEditor(
    initial: LightSetup,
    phase: GrowPhase,
    allLampTypes: Boolean,
    onUpgradeClick: () -> Unit,
    onSave: (LightSetup) -> String?,
    onBack: () -> Unit,
) {
    val lamps = remember { mutableStateListOf<LampRow>().apply { addAll(initial.lamps.map { LampRow(it.lampId, it.watts?.let(::plainNumber).orEmpty(), it.count.toString()) }) } }
    var area by remember { mutableStateOf(initial.areaM2?.let(::plainNumber).orEmpty()) }
    var dim by remember { mutableStateOf(initial.dimPercent?.let(::plainNumber).orEmpty()) }
    var hours by remember { mutableStateOf(initial.hoursPerDay?.let(::plainNumber).orEmpty()) }
    var picking by remember { mutableStateOf(initial.lamps.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    if (picking) {
        BackHandler { picking = false }
        LampPicker(
            allLampTypes = allLampTypes,
            onUpgradeClick = onUpgradeClick,
            onPick = { lamp ->
                lamps.add(LampRow(lamp.id, "", "1"))
                picking = false
            },
            onBack = { if (lamps.isEmpty()) onBack() else picking = false },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = "Lampen", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(lamps.size) { index ->
                val row = lamps[index]
                val lamp = LampCatalog.get(row.lampId)
                GrowCard {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lamp?.name ?: row.lampId, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { lamps.removeAt(index) }) { Icon(Icons.Filled.Delete, contentDescription = "Entfernen") }
                        }
                        if (lamp?.photonEfficacy != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallNumber("Watt je Lampe", row.watts, { lamps[index] = row.copy(watts = it) }, Modifier.weight(1f))
                                SmallNumber("Anzahl", row.count, { lamps[index] = row.copy(count = it.filter(Char::isDigit)) }, Modifier.weight(1f))
                            }
                            Muted("Echte Leistung aus der Steckdose, nicht die „Ersatz“-Watt der Verpackung.")
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { picking = true }, enabled = lamps.size < LightSetup.MAX_LAMPS, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Lampe hinzufügen")
                }
            }
            item { SectionHeader("Box") }
            item { SmallNumber("Fläche in m² (z. B. 1,44 für 120 × 120 cm)", area, { area = it }, Modifier.fillMaxWidth()) }
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("60×60" to "0,36", "80×80" to "0,64", "100×100" to "1", "120×120" to "1,44", "150×150" to "2,25", "240×120" to "2,88")
                        .forEach { (label, value) -> SourceChip(label, area == value) { area = value } }
                }
            }
            item { SmallNumber("Dimmer in % (leer = kein Dimmer)", dim, { dim = it }, Modifier.fillMaxWidth()) }
            item {
                SmallNumber(
                    "Lichtstunden pro Tag (leer = ${plainNumber(LightMath.target(phase).defaultHours)} h für „${phase.label}“)",
                    hours, { hours = it }, Modifier.fillMaxWidth(),
                )
            }
            error?.let { item { Muted(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(onClick = {
                    val setup = initial.copy(
                        lamps = lamps.map { LampUse(it.lampId, parseNumber(it.watts), it.count.toIntOrNull() ?: 1) },
                        areaM2 = parseNumber(area),
                        dimPercent = parseNumber(dim),
                        hoursPerDay = parseNumber(hours),
                    )
                    error = onSave(setup)
                }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
            }
        }
    }
}

private data class LampRow(val lampId: String, val watts: String, val count: String)

@Composable
private fun SmallNumber(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(7)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** Alle Lampenarten nach Gruppen; gesperrte führen zum Kaufbildschirm. */
@Composable
private fun LampPicker(allLampTypes: Boolean, onUpgradeClick: () -> Unit, onPick: (LampType) -> Unit, onBack: () -> Unit) {
    val groups = LampCatalog.grouped()
    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = "Lampe wählen", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!allLampTypes) item { Muted("Ohne Abo gehen Tageslicht und Glühbirne. Alle anderen Lampen gibt es ab Premium Farmer.") }
            LampCategory.entries.forEach { category ->
                val lamps = groups[category].orEmpty()
                if (lamps.isEmpty()) return@forEach
                item(key = "h-${category.name}") { SectionHeader(category.label) }
                items(lamps, key = { it.id }) { lamp ->
                    val allowed = LampCatalog.allowed(lamp, allLampTypes)
                    GrowCard(onClick = { if (allowed) onPick(lamp) else onUpgradeClick() }) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(lamp.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Muted(lamp.hint)
                                Muted(
                                    listOfNotNull(
                                        "Blau ${num(lamp.spectrum.blueOfPar)} % vom PAR",
                                        lamp.spectrum.redToFarRed?.let { "R:FR ${num(it, 1)}" },
                                        lamp.parEfficacy?.let { "${num(it, 1)} µmol/J" },
                                        lamp.luxPerPpfd?.let { "${num(it)} lx je µmol" } ?: "nicht per Lux messbar",
                                    ).joinToString(" · "),
                                )
                            }
                            if (!allowed) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = GrowPalette.Gold, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
