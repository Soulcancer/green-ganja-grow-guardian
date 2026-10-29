package de.greenganja.growguardian.grow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.TargetRange
import de.greenganja.growguardian.grow.history.ChartPoint
import de.greenganja.growguardian.grow.history.HistoryMath
import de.greenganja.growguardian.ui.theme.GrowPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Liniendiagramm wie im Entwurf: grüne Linie mit Verlauf darunter, Zielbereich als helles Band,
 * bei Tageswerten zusätzlich die Spanne von Minimum bis Maximum. Lücken in den Daten bleiben sichtbar.
 */
@Composable
internal fun HistoryChart(
    points: List<ChartPoint>,
    metric: Metric,
    target: TargetRange?,
    fromMillis: Long,
    toMillis: Long,
    dailyValues: Boolean,
    modifier: Modifier = Modifier,
) {
    val shown = HistoryMath.downsample(points, 240)
    val values = shown.flatMap { listOf(it.low, it.high) } + listOfNotNull(target?.min, target?.max)
    if (values.isEmpty()) return
    val rawMin = values.min()
    val rawMax = values.max()
    val pad = ((rawMax - rawMin) * 0.12).coerceAtLeast(if (metric.decimals >= 2) 0.05 else 0.5)
    val yMin = rawMin - pad
    val yMax = rawMax + pad
    val span = (toMillis - fromMillis).coerceAtLeast(1)
    val lineColor = GrowPalette.Green
    val gridColor = MaterialTheme.colorScheme.outline

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            // Y-Achse: oben, Mitte, unten
            Column(modifier = Modifier.width(40.dp).fillMaxHeight().padding(end = 6.dp)) {
                listOf(yMax, (yMax + yMin) / 2, yMin).forEachIndexed { index, value ->
                    if (index > 0) Box(modifier = Modifier.weight(1f))
                    Text(
                        text = metric.formatNumber(value),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height
                fun x(time: Long) = ((time - fromMillis).toFloat() / span) * w
                fun y(value: Double) = (h - ((value - yMin) / (yMax - yMin)) * h).toFloat()

                for (i in 0..2) {
                    val gy = h * i / 2f
                    drawLine(color = gridColor, start = Offset(0f, gy), end = Offset(w, gy), strokeWidth = 1f)
                }
                if (target != null) {
                    val top = y(target.max)
                    drawRect(
                        color = lineColor.copy(alpha = 0.09f),
                        topLeft = Offset(0f, top),
                        size = Size(w, (y(target.min) - top).coerceAtLeast(1f)),
                    )
                }
                for (segment in HistoryMath.segments(shown)) {
                    if (dailyValues && segment.size > 1) {
                        val band = Path()
                        segment.forEachIndexed { i, p -> if (i == 0) band.moveTo(x(p.timeMillis), y(p.high)) else band.lineTo(x(p.timeMillis), y(p.high)) }
                        segment.asReversed().forEach { p -> band.lineTo(x(p.timeMillis), y(p.low)) }
                        band.close()
                        drawPath(path = band, color = lineColor.copy(alpha = 0.18f))
                    }
                    if (segment.size == 1) {
                        val p = segment.first()
                        drawCircle(color = lineColor, radius = 4f, center = Offset(x(p.timeMillis), y(p.value)))
                        continue
                    }
                    val line = Path()
                    segment.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.timeMillis), y(p.value)) else line.lineTo(x(p.timeMillis), y(p.value)) }
                    if (!dailyValues) {
                        val fill = Path()
                        segment.forEachIndexed { i, p -> if (i == 0) fill.moveTo(x(p.timeMillis), y(p.value)) else fill.lineTo(x(p.timeMillis), y(p.value)) }
                        fill.lineTo(x(segment.last().timeMillis), h)
                        fill.lineTo(x(segment.first().timeMillis), h)
                        fill.close()
                        drawPath(path = fill, brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.28f), Color.Transparent)))
                    }
                    drawPath(path = line, color = lineColor, style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
        // X-Achse: Anfang, Mitte, Ende in Ortszeit
        val pattern = if (toMillis - fromMillis <= 26 * 60 * 60 * 1000L) "HH:mm" else "dd.MM."
        val format = SimpleDateFormat(pattern, Locale.GERMANY)
        Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(fromMillis, (fromMillis + toMillis) / 2, toMillis).forEachIndexed { index, time ->
                Text(
                    text = format.format(Date(time)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        1 -> TextAlign.Center
                        else -> TextAlign.End
                    },
                )
            }
        }
    }
}
