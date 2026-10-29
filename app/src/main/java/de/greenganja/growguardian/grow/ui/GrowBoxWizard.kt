package de.greenganja.growguardian.grow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.grow.DefaultTargets
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.GrowMedium
import de.greenganja.growguardian.grow.GrowPhase
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.Vpd
import java.util.Locale

internal const val MAX_BOX_NAME = 40

internal val GrowMedium.icon: ImageVector
    get() = when (this) {
        GrowMedium.SOIL -> Icons.Filled.Terrain
        GrowMedium.COCO -> Icons.Filled.Eco
        GrowMedium.HYDRO -> Icons.Filled.WaterDrop
    }

internal val GrowPhase.icon: ImageVector
    get() = when (this) {
        GrowPhase.SEEDLING -> Icons.Filled.Grass
        GrowPhase.VEGETATIVE -> Icons.Filled.Eco
        GrowPhase.FLOWERING -> Icons.Filled.LocalFlorist
        GrowPhase.LATE_FLOWERING -> Icons.Filled.Spa
        GrowPhase.DRYING -> Icons.Filled.Air
    }

/**
 * Assistent in drei Schritten wie „Neue Pflanze“ im Entwurf: Name und Medium, Phase, Blatttemperatur.
 * Mit [initial] bearbeitet er eine bestehende Box; Sensoren und eigene Zielwerte bleiben erhalten.
 */
@Composable
internal fun BoxWizard(initial: GrowBox?, busy: Boolean, onSave: (GrowBox) -> Unit, onBack: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(1) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var medium by rememberSaveable { mutableStateOf(initial?.medium ?: GrowMedium.SOIL) }
    var phase by rememberSaveable { mutableStateOf(initial?.phase ?: GrowPhase.VEGETATIVE) }
    var leafOffset by rememberSaveable { mutableStateOf(initial?.leafOffsetC ?: Vpd.DEFAULT_LEAF_OFFSET) }
    val back: () -> Unit = {
        if (step > 1) step -= 1 else onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = if (initial == null) "Neue Grow-Box" else "Box bearbeiten", onBack = back)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepIndicator(step = step, total = 3)
            when (step) {
                1 -> {
                    Text(text = "Name und Medium", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_BOX_NAME) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        placeholder = { Text("z. B. Zelt 80×80") },
                        singleLine = true,
                    )
                    Muted("Worin wachsen die Pflanzen? Davon hängen pH- und Feuchte-Ziele ab.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GrowMedium.entries.forEach { option ->
                            ChoiceTile(option.label, option.icon, selected = option == medium, onClick = { medium = option }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                2 -> {
                    Text(text = "Wachstumsphase", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Muted("Die Zielwerte für Klima, VPD, pH und EC passen sich der Phase an.")
                    GrowPhase.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { option ->
                                ChoiceTile(option.label, option.icon, selected = option == phase, onClick = { phase = option }, modifier = Modifier.weight(1f))
                            }
                            if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                        }
                    }
                    PhasePreview(phase, medium)
                }
                else -> {
                    Text(text = "Blatttemperatur für den VPD", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Muted("Unter LED sind Blätter meist 1 bis 3 °C kühler als die Luft. Mit Infrarot-Thermometer messen oder −2 °C lassen.")
                    GrowCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OutlinedButton(onClick = { leafOffset = (leafOffset - 0.5).coerceAtLeast(-5.0) }) { Text("−") }
                            Text(
                                text = String.format(Locale.GERMANY, "%+.1f °C", leafOffset),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            OutlinedButton(onClick = { leafOffset = (leafOffset + 0.5).coerceAtMost(5.0) }) { Text("+") }
                        }
                    }
                    val example = Vpd.leaf(25.0, 60.0, leafOffset)
                    Muted("Beispiel: 25 °C und 60 % ergeben ${Metric.VPD.format(example)}.")
                }
            }
        }
        Button(
            onClick = {
                if (step < 3) {
                    step++
                } else {
                    val base = initial ?: GrowBox(id = "", name = "")
                    onSave(base.copy(name = name.trim(), medium = medium, phase = phase, leafOffsetC = leafOffset))
                }
            },
            enabled = !busy && (step > 1 || name.isNotBlank()),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            Text(
                when {
                    step < 3 -> "Weiter"
                    initial == null -> "Box anlegen"
                    else -> "Speichern"
                },
            )
        }
    }
}

@Composable
private fun PhasePreview(phase: GrowPhase, medium: GrowMedium) {
    val targets = DefaultTargets.forPhase(phase, medium)
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "Startwerte", style = MaterialTheme.typography.titleSmall)
            targets.forEach { (metric, range) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Muted(metric.label, modifier = Modifier.weight(1f))
                    Text(text = range.format(metric), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
