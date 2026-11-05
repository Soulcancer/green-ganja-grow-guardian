package de.greenganja.growguardian.grow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.TargetRange
import de.greenganja.growguardian.grow.history.ChartPoint
import de.greenganja.growguardian.grow.history.HistoryMath
import de.greenganja.growguardian.grow.light.LampCatalog
import de.greenganja.growguardian.grow.light.LampUse
import de.greenganja.growguardian.grow.light.LightHistory
import de.greenganja.growguardian.grow.light.LightLevel
import de.greenganja.growguardian.grow.light.LightMath
import de.greenganja.growguardian.grow.light.LightReading
import de.greenganja.growguardian.grow.light.LightSetup
import de.greenganja.growguardian.grow.light.LightTarget
import de.greenganja.growguardian.ui.theme.GrowPalette
import java.util.Locale

private fun n(value: Double, decimals: Int = 0) = String.format(Locale.GERMANY, "%.${decimals}f", value)

/** Was der Licht-Reiter zeigt, ohne Compose berechnet. */
internal data class LightHistoryView(
    /** Kurve: PPFD (Einzelwerte) oder DLI je Tag (Tageswerte). */
    val points: List<ChartPoint>,
    val chartTitle: String,
    val chartTarget: TargetRange?,
    val dailyValues: Boolean,
    val chartDecimals: Int,
    /** Wert im Bogen (DLI) und woher er stammt. */
    val dli: Double?,
    val dliCaption: String,
    val ppfdNow: Double?,
    val note: String?,
    val fromSensor: Boolean,
    /** Ø nur über Zeiten mit Licht (Nacht = 0 verfälscht sonst den Mittelwert). */
    val averageWhenOn: Boolean = false,
) {
    companion object {
        fun of(
            box: GrowBox,
            luxPoints: List<ChartPoint>,
            usesDailyStats: Boolean,
            readings: List<LightReading>,
            fromMillis: Long,
            toMillis: Long,
            target: LightTarget,
        ): LightHistoryView {
            val setup = box.light ?: LightSetup(listOf(LampUse(LampCatalog.DAYLIGHT)))
            val lampNote = if (box.light == null) "Keine Lampen eingetragen: gerechnet wird mit Tageslicht." else null
            val ppfd = if (luxPoints.isEmpty()) null else LightHistory.ppfdPoints(luxPoints, setup)

            if (ppfd != null && ppfd.isNotEmpty()) {
                if (usesDailyStats) {
                    val perDay = LightHistory.dliFromDailyAverages(ppfd)
                    return LightHistoryView(
                        points = perDay,
                        chartTitle = "DLI je Tag (mol/m²/Tag)",
                        chartTarget = TargetRange(target.dliMin, target.dliMax),
                        dailyValues = false,
                        chartDecimals = 1,
                        dli = perDay.lastOrNull()?.value,
                        dliCaption = "Letzter Tag, Lichtsensor",
                        ppfdNow = null,
                        note = lampNote,
                        fromSensor = true,
                    )
                }
                val last = LightHistory.lastDay(ppfd, toMillis)
                return LightHistoryView(
                    points = ppfd,
                    chartTitle = "PPFD (µmol/m²/s)",
                    chartTarget = TargetRange(target.ppfdMin, target.ppfdMax),
                    dailyValues = false,
                    chartDecimals = 0,
                    dli = last?.dli,
                    dliCaption = "Letzte 24 Stunden, Lichtsensor",
                    ppfdNow = ppfd.lastOrNull()?.value,
                    averageWhenOn = true,
                    note = listOfNotNull(
                        lampNote,
                        last?.takeIf { !it.complete }?.let { "Lücken im Verlauf (${n(it.coverage * 100)} % der Zeit gemessen): die DLI ist eher zu niedrig." },
                    ).joinToString(" ").ifBlank { null },
                    fromSensor = true,
                )
            }

            // Ohne Lichtsensor: gespeicherte Einzelmessungen aus dem Lichtmodul
            val spot = LightHistory.readingPoints(readings, fromMillis, toMillis)
            val latest = readings.maxByOrNull { it.createdAtMillis ?: 0L }
            val dli = latest?.let { it.dli ?: LightMath.dli(it.ppfd, it.hours ?: LightMath.hours(box.light, box.phase)) }
            val unmeasurable = luxPoints.isNotEmpty() && ppfd == null
            return LightHistoryView(
                points = spot,
                chartTitle = "Gemessener PPFD (µmol/m²/s)",
                chartTarget = TargetRange(target.ppfdMin, target.ppfdMax),
                dailyValues = false,
                chartDecimals = 0,
                dli = dli,
                dliCaption = if (latest != null) "Letzte Messung × ${n(latest.hours ?: LightMath.hours(box.light, box.phase))} h Licht" else "Noch keine Messung",
                ppfdNow = latest?.ppfd,
                note = when {
                    unmeasurable -> "Diese Lampen sieht ein Luxsensor kaum (UV, Far-Red); der Verlauf zeigt deshalb die Einzelmessungen."
                    spot.isEmpty() && latest == null ->
                        "Kein Lichtsensor in dieser Box. Miss im Lichtmodul oder ordne einen Tuya-Helligkeitssensor zu, dann erscheint hier die Lichtkurve."
                    else -> "Kein Lichtsensor in dieser Box: Punkte sind deine Einzelmessungen aus dem Lichtmodul."
                },
                fromSensor = false,
            )
        }
    }
}

/**
 * Bogen für die Tageslichtmenge: grauer Bogen über 240°, Zielbereich hell hinterlegt, Wert farbig.
 * Skala bis 1,4 × Zielobergrenze.
 */
@Composable
internal fun DliArc(dli: Double?, target: LightTarget, caption: String, ppfdNow: Double?, modifier: Modifier = Modifier) {
    val scale = (target.dliMax * 1.4).coerceAtLeast(1.0)
    val level = dli?.let { LightMath.level(it, target.dliMin, target.dliMax) }
    val valueColor = when (level) {
        LightLevel.OK -> GrowPalette.Green
        LightLevel.TOO_LOW, LightLevel.TOO_HIGH -> GrowPalette.Warning
        null -> GrowPalette.TextMuted
    }
    val track = MaterialTheme.colorScheme.outline
    val start = 150f
    val sweep = 240f
    fun angle(value: Double) = (value / scale).coerceIn(0.0, 1.0).toFloat() * sweep

    GrowCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tageslichtmenge (DLI)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
            Box(modifier = Modifier.size(width = 220.dp, height = 170.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(200.dp)) {
                    val stroke = 16.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(inset, inset)
                    drawArc(color = track, startAngle = start, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
                    // Zielbereich
                    drawArc(
                        color = GrowPalette.Green.copy(alpha = 0.28f),
                        startAngle = start + angle(target.dliMin),
                        sweepAngle = (angle(target.dliMax) - angle(target.dliMin)).coerceAtLeast(1f),
                        useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                    if (dli != null && dli > 0) {
                        drawArc(
                            color = valueColor, startAngle = start, sweepAngle = angle(dli).coerceAtLeast(2f),
                            useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke * 0.55f, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dli?.let { n(it, 1) } ?: "–", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = valueColor)
                    Muted("mol/m²/Tag")
                    level?.let { StatusChip(it.label, valueColor, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Muted("0")
                Muted("Ziel ${n(target.dliMin)}–${n(target.dliMax)}")
                Muted(n(scale))
            }
            Muted(caption + (ppfdNow?.let { " · zuletzt ${n(it)} µmol/m²/s" } ?: ""), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Licht-Reiter in „Analyse und Verlauf“: Bogen mit DLI, Kurve, Kennzahlen. */
@Composable
internal fun LightHistorySection(view: LightHistoryView, target: LightTarget, fromMillis: Long, toMillis: Long, loading: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DliArc(view.dli, target, view.dliCaption, view.ppfdNow)
        GrowCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val summary = HistoryMath.summary(if (view.averageWhenOn) view.points.filter { it.value > 5.0 } else view.points)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(view.chartTitle, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    summary?.let { Text((if (view.averageWhenOn) "Ø bei Licht " else "Ø ") + n(it.avg, view.chartDecimals), style = MaterialTheme.typography.titleSmall) }
                }
                when {
                    loading -> Box(modifier = Modifier.fillMaxWidth().height(120.dp))
                    view.points.isEmpty() -> Muted("Noch keine Lichtwerte in diesem Zeitraum.", modifier = Modifier.padding(vertical = 16.dp))
                    else -> HistoryChart(
                        points = view.points,
                        metric = Metric.LIGHT,
                        target = view.chartTarget,
                        fromMillis = fromMillis,
                        toMillis = toMillis,
                        dailyValues = view.dailyValues,
                        formatValue = { n(it, view.chartDecimals) },
                        yFloor = 0.0,
                    )
                }
                view.chartTarget?.let { Muted("Helles Band: Ziel ${n(it.min, view.chartDecimals)}–${n(it.max, view.chartDecimals)}") }
                view.note?.let { Muted(it) }
                if (summary != null && view.points.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Muted("Min. ${n(summary.min, view.chartDecimals)}", modifier = Modifier.weight(1f))
                        Muted("Ø ${n(summary.avg, view.chartDecimals)}", modifier = Modifier.weight(1f))
                        Muted("Max. ${n(summary.max, view.chartDecimals)}", modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
