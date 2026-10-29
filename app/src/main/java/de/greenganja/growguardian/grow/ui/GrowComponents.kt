package de.greenganja.growguardian.grow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Co2
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.grow.BoxStatus
import de.greenganja.growguardian.grow.DeviceKind
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.MetricReading
import de.greenganja.growguardian.grow.MetricStatus
import de.greenganja.growguardian.ui.theme.GrowPalette

internal val Metric.icon: ImageVector
    get() = when (this) {
        Metric.AIR_TEMPERATURE, Metric.SOIL_TEMPERATURE, Metric.WATER_TEMPERATURE -> Icons.Filled.Thermostat
        Metric.AIR_HUMIDITY -> Icons.Filled.WaterDrop
        Metric.VPD -> Icons.Filled.Air
        Metric.CO2 -> Icons.Filled.Co2
        Metric.SOIL_MOISTURE -> Icons.Filled.Grass
        Metric.PH -> Icons.Filled.Science
        Metric.EC, Metric.TDS -> Icons.Filled.Opacity
        Metric.POWER -> Icons.Filled.Bolt
    }

internal val DeviceKind.icon: ImageVector
    get() = when (this) {
        DeviceKind.CLIMATE -> Icons.Filled.Thermostat
        DeviceKind.CO2 -> Icons.Filled.Co2
        DeviceKind.SOIL -> Icons.Filled.Grass
        DeviceKind.WATER -> Icons.Filled.Science
        DeviceKind.PLUG -> Icons.Filled.Bolt
        DeviceKind.OTHER -> Icons.Filled.Air
    }

internal val MetricStatus.color: Color
    get() = when (this) {
        MetricStatus.OK -> GrowPalette.Green
        MetricStatus.LOW, MetricStatus.HIGH -> GrowPalette.Warning
        MetricStatus.UNRATED -> GrowPalette.Text
    }

internal val BoxStatus.color: Color
    get() = when (this) {
        BoxStatus.OK -> GrowPalette.Green
        BoxStatus.WARNING -> GrowPalette.Warning
        BoxStatus.OFFLINE -> GrowPalette.Error
        BoxStatus.NO_DATA, BoxStatus.NO_DEVICES -> GrowPalette.TextMuted
    }

/** Kurzform für Chips, wie "Healthy" im Entwurf. */
internal val BoxStatus.chipLabel: String
    get() = when (this) {
        BoxStatus.OK -> "Im Ziel"
        BoxStatus.WARNING -> "Prüfen"
        BoxStatus.OFFLINE -> "Offline"
        BoxStatus.NO_DATA -> "Wartet"
        BoxStatus.NO_DEVICES -> "Ohne Sensor"
    }

/** Karte im Stil des Entwurfs: dunkles Grün mit feinem Rand. */
@Composable
internal fun GrowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = content)
    }
}

/** Kleiner Status-Chip, z. B. "Im Ziel" oder "Premium". */
@Composable
internal fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
    )
}

/** Rundes Icon auf getöntem Grund, wie die Messwert-Icons im Entwurf. */
@Composable
internal fun IconBadge(icon: ImageVector, tint: Color = GrowPalette.Green, size: Int = 36) {
    Box(
        modifier = Modifier.size(size.dp).background(tint.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size((size * 0.55f).dp), tint = tint)
    }
}

@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, action: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, content = action)
    }
}

/** Kleiner grüner Aktions-Button rechts in Überschriften ("+ Neue Box"). */
@Composable
internal fun PillAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Große Kachel für Temperatur, Luftfeuchte und VPD. */
@Composable
internal fun ValueTile(metric: Metric, reading: MetricReading?, modifier: Modifier = Modifier) {
    GrowCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = reading?.let { metric.formatNumber(it.value) + metric.unit.let { u -> if (u == "%" || u == "°C") u else " $u" } } ?: "–",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = reading?.status?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** Zeile einer Messwertliste: Icon, Name mit Zielbereich, Wert rechts. */
@Composable
internal fun ReadingRow(reading: MetricReading) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(reading.metric.icon, tint = reading.status.color, size = 32)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = reading.metric.label, style = MaterialTheme.typography.bodyLarge)
            Muted(reading.target?.let { "Ziel ${it.format(reading.metric)}" } ?: "Kein Zielwert")
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = reading.formatted,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = reading.status.color,
            )
            if (reading.status == MetricStatus.LOW || reading.status == MetricStatus.HIGH) {
                Muted(reading.status.label, color = reading.status.color)
            }
        }
    }
}

/** Upgrade-Zeile mit goldener Krone, wie "Mehr Pflanzen & Funktionen" im Entwurf. */
@Composable
internal fun UpgradeRow(title: String, subtitle: String, onClick: () -> Unit) {
    GrowCard(onClick = onClick) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.WorkspacePremium, tint = GrowPalette.Gold, size = 38)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Muted(subtitle)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Kopfzeile mit Zurück-Pfeil. */
@Composable
internal fun BackBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** Auswahlkachel für Medium und Phase, wie die Pflanzenart-Kacheln im Entwurf. */
@Composable
internal fun ChoiceTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier.height(88.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = label,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** Schrittanzeige 1 – 2 – 3 für den Assistenten. */
@Composable
internal fun StepIndicator(step: Int, total: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (index in 1..total) {
            val reached = index <= step
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(if (reached) GrowPalette.Green else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (reached) GrowPalette.OnGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index < total) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .height(2.dp)
                        .background(if (index < step) GrowPalette.Green else MaterialTheme.colorScheme.outline),
                )
            }
        }
    }
}

/** Abstand ohne Spacer-Import an jeder Stelle. */
@Composable
internal fun Gap(width: Int = 0, height: Int = 0) {
    Box(modifier = Modifier.width(width.dp).height(height.dp))
}
