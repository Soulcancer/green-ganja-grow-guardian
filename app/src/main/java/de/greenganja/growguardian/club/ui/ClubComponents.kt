package de.greenganja.growguardian.club.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.club.ClubMember
import de.greenganja.growguardian.club.ClubRole
import de.greenganja.growguardian.club.KcanLimits
import de.greenganja.growguardian.club.MemberStatus
import de.greenganja.growguardian.club.Reservation
import de.greenganja.growguardian.club.ReservationStatus

internal val ReservationStatus.label: String
    get() = when (this) {
        ReservationStatus.REQUESTED -> "Angefragt"
        ReservationStatus.CONFIRMED -> "Bestätigt"
        ReservationStatus.DECLINED -> "Abgelehnt"
        ReservationStatus.HANDED_OUT -> "Ausgegeben"
        ReservationStatus.CANCELLED -> "Storniert"
    }

internal val ClubRole.label: String
    get() = when (this) {
        ClubRole.OWNER -> "Inhaber"
        ClubRole.STAFF -> "Team"
        ClubRole.MODERATOR -> "Moderator"
        ClubRole.MEMBER -> "Mitglied"
    }

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Eine Reservierung als Karte; [actions] erscheinen unten rechts. */
@Composable
internal fun ReservationCard(
    reservation: Reservation,
    showMember: Boolean,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val statusColor = when (reservation.status) {
        ReservationStatus.CONFIRMED, ReservationStatus.HANDED_OUT -> MaterialTheme.colorScheme.primary
        ReservationStatus.DECLINED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showMember) reservation.memberName else ClubFormat.grams(reservation.grams),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = reservation.status.label, style = MaterialTheme.typography.labelLarge, color = statusColor)
            }
            val details = listOfNotNull(
                ClubFormat.grams(reservation.grams).takeIf { showMember },
                "Abholung ${ClubFormat.date(reservation.pickupDate)}",
                reservation.strain,
            )
            Text(text = details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            reservation.note?.let { Hint("Notiz: $it") }
            reservation.declineReason?.let { Hint("Grund: $it", color = MaterialTheme.colorScheme.error) }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = actions,
            )
        }
    }
}

/** Mitglied mit Rolle und Status; [actions] darunter. */
@Composable
internal fun MemberCard(member: ClubMember, actions: @Composable ColumnScope.() -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = member.fullName, style = MaterialTheme.typography.titleMedium)
            val status = when {
                member.status == MemberStatus.PENDING -> "Antrag von ${member.displayName}"
                !member.hasProfile -> "${member.role.label} · Daten fehlen"
                else -> "${member.role.label} · geboren ${ClubFormat.date(member.birthDate.orEmpty())}"
            }
            Hint(status)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = actions,
            )
        }
    }
}

/** Formular für eine neue Reservierung oder, mit [initial], zum Ändern einer bestehenden. */
@Composable
internal fun ReservationForm(
    busy: Boolean,
    initial: Reservation? = null,
    onCancel: (() -> Unit)? = null,
    onSubmit: (grams: Double, pickupDate: String, strain: String?, note: String?) -> Unit,
) {
    val key = initial?.id ?: "new"
    var gramsText by rememberSaveable(key) { mutableStateOf(initial?.grams?.let { ClubFormat.decimal(it) }.orEmpty()) }
    var pickupDate by rememberSaveable(key) { mutableStateOf(initial?.pickupDate ?: ClubFormat.todayIso()) }
    var strain by rememberSaveable(key) { mutableStateOf(initial?.strain.orEmpty()) }
    var note by rememberSaveable(key) { mutableStateOf(initial?.note.orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }

    val grams = ClubFormat.parseDecimal(gramsText)
    val gramsError = gramsText.isNotBlank() && (grams == null || grams <= 0.0 || grams > KcanLimits.DAILY_G)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = if (initial == null) "Menge reservieren" else "Reservierung ändern", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = gramsText,
                onValueChange = { gramsText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Menge in Gramm") },
                supportingText = { Text(if (gramsError) "Bitte 0,1 bis 25 g angeben" else "Höchstens 25 g pro Tag") },
                isError = gramsError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
            OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Abholung am ${ClubFormat.date(pickupDate)}")
            }
            OutlinedTextField(
                value = strain,
                onValueChange = { strain = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Sorte (optional)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notiz ans Team (optional)") },
                maxLines = 3,
            )
            Hint(
                "Abholung nur persönlich im Club mit Mitglieds- und Lichtbildausweis. " +
                    "Pro Monat höchstens 50 g, unter 21 Jahren 30 g.",
            )
            Button(
                onClick = { grams?.let { onSubmit(it, pickupDate, strain.trim().ifBlank { null }, note.trim().ifBlank { null }) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && grams != null && !gramsError,
            ) {
                Text(if (initial == null) "Anfragen" else "Änderung speichern")
            }
            if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
        }
    }

    if (pickingDate) {
        PickupDateDialog(
            initial = pickupDate,
            onPick = {
                pickupDate = it
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}

/** Text über das Android-Teilen-Menü weitergeben. */
internal fun Context.shareText(text: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, chooserTitle))
}
