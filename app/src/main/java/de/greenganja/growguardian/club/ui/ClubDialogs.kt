package de.greenganja.growguardian.club.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.club.ClubMember
import de.greenganja.growguardian.club.KcanLimits
import de.greenganja.growguardian.club.MemberStatus
import de.greenganja.growguardian.club.StaffInvite

@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        title = { Text(title) },
        text = { Text(text) },
    )
}

/** Zeigt den Code für einen Team-Platz und bietet Teilen an. */
@Composable
internal fun InviteDialog(invite: StaffInvite, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val code = ClubFormat.code(invite.code)
    val until = ClubFormat.dateOf(invite.expiresAt.time)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    context.shareText(
                        "Einladung in unser Club-Team bei Green Ganja Grow Guardian: Code $code, gültig bis $until. " +
                            "In der App unter Club → Code einlösen.",
                        "Einladung teilen",
                    )
                },
            ) { Text("Teilen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        title = { Text("Team-Einladung") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = code, style = MaterialTheme.typography.headlineMedium)
                Text("Gültig bis $until und nur einmal einlösbar. Wer ihn einlöst, belegt einen der Team-Plätze.")
            }
        },
    )
}

/** Mitglied nach Ausweiskontrolle freigeben oder fehlende Daten eintragen. */
@Composable
internal fun ApproveMemberDialog(
    member: ClubMember,
    busy: Boolean,
    onConfirm: (firstName: String, lastName: String, birthDate: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var firstName by rememberSaveable { mutableStateOf(member.firstName.orEmpty()) }
    var lastName by rememberSaveable { mutableStateOf(member.lastName.orEmpty()) }
    var birthText by rememberSaveable { mutableStateOf(member.birthDate?.let(ClubFormat::date).orEmpty()) }
    var idChecked by rememberSaveable { mutableStateOf(false) }

    val birthDate = ClubFormat.parseGermanDate(birthText)
    val age = birthDate?.let { ClubFormat.ageOn(it, ClubFormat.todayIso()) }
    val birthError = when {
        birthText.isBlank() -> null
        birthDate == null -> "Bitte als TT.MM.JJJJ eingeben"
        age != null && age < 18 -> "Mitglieder müssen mindestens 18 Jahre alt sein"
        else -> null
    }
    val valid = firstName.isNotBlank() && lastName.isNotBlank() && birthDate != null && birthError == null && idChecked

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { birthDate?.let { onConfirm(firstName.trim(), lastName.trim(), it) } },
                enabled = valid && !busy,
            ) { Text(if (member.status == MemberStatus.PENDING) "Freigeben" else "Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        title = { Text(if (member.status == MemberStatus.PENDING) "Mitglied freigeben" else "Daten eintragen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Angemeldet als ${member.displayName}. Bitte die Angaben aus dem Ausweis übernehmen.")
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("Vorname") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Nachname") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = birthText,
                    onValueChange = { birthText = it },
                    label = { Text("Geburtsdatum") },
                    placeholder = { Text("TT.MM.JJJJ") },
                    supportingText = birthError?.let { { Text(it) } },
                    isError = birthError != null,
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = idChecked, onCheckedChange = { idChecked = it })
                    Text("Amtlichen Lichtbildausweis geprüft")
                }
            }
        },
    )
}

@Composable
internal fun DeclineDialog(busy: Boolean, onConfirm: (reason: String?) -> Unit, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim().ifBlank { null }) }, enabled = !busy) { Text("Ablehnen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        title = { Text("Reservierung ablehnen") },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Grund (optional, sieht das Mitglied)") },
                maxLines = 3,
            )
        },
    )
}

/** Ausgabe dokumentieren: Menge, THC-Gehalt und Sorte. */
@Composable
internal fun HandoutDialog(
    memberName: String,
    birthDate: String?,
    initialGrams: Double?,
    initialStrain: String?,
    busy: Boolean,
    onConfirm: (grams: Double, thcPercent: Double, strain: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var gramsText by rememberSaveable { mutableStateOf(initialGrams?.let { ClubFormat.grams(it).removeSuffix(" g") }.orEmpty()) }
    var thcText by rememberSaveable { mutableStateOf("") }
    var strain by rememberSaveable { mutableStateOf(initialStrain.orEmpty()) }

    val grams = ClubFormat.parseDecimal(gramsText)
    val thc = ClubFormat.parseDecimal(thcText)
    val age = birthDate?.let { ClubFormat.ageOn(it, ClubFormat.todayIso()) }
    val under21 = age != null && age < 21
    val gramsError = grams != null && (grams <= 0.0 || grams > KcanLimits.DAILY_G)
    val thcError = thc != null && (thc > 100.0 || (under21 && thc > KcanLimits.MAX_THC_UNDER_21_PERCENT))

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (grams != null && thc != null) onConfirm(grams, thc, strain.trim().ifBlank { null })
                },
                enabled = !busy && grams != null && thc != null && !gramsError && !thcError,
            ) { Text("Dokumentieren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        title = { Text("Ausgabe an $memberName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Vorher Mitgliedsausweis und amtlichen Lichtbildausweis prüfen.")
                if (under21) {
                    Hint(
                        "Unter 21 Jahren: höchstens 30 g im Monat und höchstens 10 % THC.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = gramsText,
                    onValueChange = { gramsText = it },
                    label = { Text("Menge in Gramm") },
                    isError = gramsError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = thcText,
                    onValueChange = { thcText = it },
                    label = { Text("THC-Gehalt in %") },
                    supportingText = if (thcError && under21) {
                        { Text("Höchstens 10 % unter 21 Jahren") }
                    } else {
                        null
                    },
                    isError = thcError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = strain,
                    onValueChange = { strain = it },
                    label = { Text("Sorte") },
                    singleLine = true,
                )
            }
        },
    )
}

/** Mitglied für eine Ausgabe ohne Reservierung auswählen. */
@Composable
internal fun MemberPickerDialog(members: List<ClubMember>, onPick: (ClubMember) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        title = { Text("Mitglied wählen") },
        text = {
            if (members.isEmpty()) {
                Text("Es gibt noch keine freigegebenen Mitglieder mit vollständigen Daten.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(members, key = { it.uid }) { member ->
                        TextButton(onClick = { onPick(member) }, modifier = Modifier.fillMaxWidth()) {
                            Text(member.fullName, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
    )
}

/** Abholtag wählen: heute bis in 30 Tagen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickupDateDialog(initial: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val today = remember { ClubFormat.todayIso() }
    val first = remember(today) { ClubFormat.utcMillisFromIso(today) ?: 0L }
    val last = remember(today) { ClubFormat.utcMillisFromIso(ClubFormat.addDays(today, 30)) ?: Long.MAX_VALUE }
    val selectable = remember(first, last) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in first..last
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = ClubFormat.utcMillisFromIso(initial),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(ClubFormat.isoFromUtcMillis(it)) } ?: onDismiss() },
            ) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    ) {
        DatePicker(state = state)
    }
}
