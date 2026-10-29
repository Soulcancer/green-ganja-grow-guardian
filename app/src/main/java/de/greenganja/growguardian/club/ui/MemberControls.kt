package de.greenganja.growguardian.club.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.club.MemberSettings

/**
 * „Mein Bereich“: was jedes Mitglied selbst bestimmt. Anzeigename für andere Mitglieder,
 * welche Nachrichten es bekommt und die Auskunft über alle eigenen Daten.
 */
@Composable
internal fun MySettingsCard(
    settings: MemberSettings,
    busy: Boolean,
    onSave: (MemberSettings) -> Unit,
    onExport: () -> Unit,
) {
    var publicName by remember(settings.publicName) { mutableStateOf(settings.publicName.orEmpty()) }
    var notify by remember(settings.notify) { mutableStateOf(settings.notify) }
    val draft = MemberSettings(publicName.trim().ifBlank { null }, notify)
    val nameError = publicName.isNotBlank() && publicName.trim().length !in 2..30

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Mein Bereich", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = publicName,
                onValueChange = { publicName = it.take(30) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Anzeigename (optional)") },
                supportingText = {
                    Text(if (nameError) "2 bis 30 Zeichen" else "So sehen dich andere Mitglieder, z. B. im Marktplatz. Leer = Vorname und Initiale.")
                },
                isError = nameError,
                singleLine = true,
            )
            HorizontalDivider()
            Text(text = "Benachrichtigungen", style = MaterialTheme.typography.titleSmall)
            ToggleRow("Reservierungen", "Bestätigt, abgelehnt oder ausgegeben", notify.reservations) {
                notify = notify.copy(reservations = it)
            }
            ToggleRow("Club-Boxen", "Werte außerhalb des Ziels oder Box offline", notify.clubBoxes) {
                notify = notify.copy(clubBoxes = it)
            }
            ToggleRow("Marktplatz", "Neue Inserate und Antworten", notify.marketplace) {
                notify = notify.copy(marketplace = it)
            }
            Button(
                onClick = { onSave(draft) },
                enabled = !busy && !nameError && draft != settings,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }
            HorizontalDivider()
            Text(text = "Meine Daten", style = MaterialTheme.typography.titleSmall)
            Hint(
                "Lade alles herunter, was der Club und die App über dich speichern: Mitgliedschaft, Reservierungen, " +
                    "dokumentierte Abgaben an dich, Boxen, Automationen und Inserate.",
            )
            OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Meine Daten herunterladen") }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

