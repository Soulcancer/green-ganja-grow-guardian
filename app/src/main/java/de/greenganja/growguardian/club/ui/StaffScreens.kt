package de.greenganja.growguardian.club.ui

import de.greenganja.growguardian.ads.inlineAd
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.club.Club
import de.greenganja.growguardian.club.ClubMember
import de.greenganja.growguardian.club.ClubRole
import de.greenganja.growguardian.club.Dispensation
import de.greenganja.growguardian.club.MemberStatus
import de.greenganja.growguardian.club.ModerationRules
import de.greenganja.growguardian.club.Reservation
import de.greenganja.growguardian.club.ReservationStatus

/** Wer etwas bekommt: mit Reservierung oder direkt. */
private data class HandoutTarget(val member: ClubMember?, val reservation: Reservation?) {
    val memberName: String get() = member?.fullName ?: reservation?.memberName.orEmpty()
}

/** Bereich für Inhaber und Team mit vier Reitern. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StaffContent(
    state: ClubUiState,
    viewModel: ClubViewModel,
    onUpgradeClick: () -> Unit,
    onOpenMarket: (() -> Unit)? = null,
    /** Moderations-Reiter; null = ausgeblendet. */
    moderation: (@Composable () -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val club = state.club
    // In der Play-Store-Fassung ohne Anfragen (Reservierungen) und Abgabe-Doku
    val tabs = listOfNotNull(
        StaffTab.REQUESTS.takeIf { state.dispensing },
        StaffTab.MEMBERS,
        StaffTab.DOCS.takeIf { state.dispensing },
        StaffTab.BOXES,
        StaffTab.MODERATION.takeIf { moderation != null },
        StaffTab.CLUB,
    )
    val current = tabs.getOrElse(tab) { tabs.first() }
    val titles = tabs.map { t ->
        when (t) {
            StaffTab.REQUESTS -> if (state.openReservations.isEmpty()) "Anfragen" else "Anfragen (${state.openReservations.size})"
            StaffTab.MEMBERS -> if (state.pendingMembers.isEmpty()) "Mitglieder" else "Mitglieder (${state.pendingMembers.size})"
            StaffTab.DOCS -> "Doku"
            StaffTab.BOXES -> if (state.clubBoxes.isEmpty()) "Boxen" else "Boxen (${state.clubBoxes.size})"
            StaffTab.MODERATION -> "Moderation"
            StaffTab.CLUB -> "Club"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = club?.name ?: state.membership?.clubName.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            Hint(state.membership?.role?.label.orEmpty())
            if (onOpenMarket != null) TextButton(onClick = onOpenMarket) { Text("Marktplatz öffnen") }
        }
        if (club != null && !club.active) InactiveBanner(isOwner = state.membership?.isOwner == true, onUpgradeClick = onUpgradeClick)
        // Scrollbar statt fester Breiten: deutsche Beschriftungen mit Zähler passen sonst nicht auf schmale Handys.
        PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(current), edgePadding = 0.dp) {
            titles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
            }
        }
        when (current) {
            StaffTab.REQUESTS -> RequestsTab(state, viewModel)
            StaffTab.MEMBERS -> MembersTab(state, viewModel)
            StaffTab.DOCS -> DocumentationTab(state, viewModel)
            StaffTab.BOXES -> ClubBoxesTab(state, viewModel)
            StaffTab.MODERATION -> moderation?.invoke()
            StaffTab.CLUB -> ClubTab(state, viewModel)
        }
    }
}

private enum class StaffTab { REQUESTS, MEMBERS, DOCS, BOXES, MODERATION, CLUB }

@Composable
private fun InactiveBanner(isOwner: Boolean, onUpgradeClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Das Social-Club-Abo ist pausiert. Neue Reservierungen und Ausgaben sind gesperrt, " +
                    "die Dokumentation bleibt abrufbar.",
                color = MaterialTheme.colorScheme.error,
            )
            if (isOwner) Button(onClick = onUpgradeClick) { Text("Abo verlängern") }
        }
    }
}

// ---------- Anfragen ----------

@Composable
private fun RequestsTab(state: ClubUiState, viewModel: ClubViewModel) {
    var declining by remember { mutableStateOf<Reservation?>(null) }
    var handout by remember { mutableStateOf<HandoutTarget?>(null) }
    var pickingMember by remember { mutableStateOf(false) }
    val active = state.club?.active == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(onClick = { pickingMember = true }, modifier = Modifier.fillMaxWidth(), enabled = active) {
                Text("Ausgabe ohne Reservierung")
            }
        }
        inlineAd("staff-requests-top")
        if (state.openReservations.isEmpty()) {
            item { Hint("Keine offenen Reservierungen.") }
        }
        items(state.openReservations, key = { it.id }) { reservation ->
            ReservationCard(reservation, showMember = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { declining = reservation }, enabled = !state.busy) { Text("Ablehnen") }
                    if (reservation.status == ReservationStatus.REQUESTED) {
                        OutlinedButton(onClick = { viewModel.confirmReservation(reservation.id) }, enabled = !state.busy && active) {
                            Text("Bestätigen")
                        }
                    }
                }
                Button(
                    onClick = { handout = HandoutTarget(state.member(reservation.memberUid), reservation) },
                    enabled = !state.busy && active,
                ) { Text("Ausgeben") }
            }
        }
        inlineAd("staff-requests-end")
    }

    declining?.let { reservation ->
        DeclineDialog(
            busy = state.busy,
            onConfirm = { reason ->
                declining = null
                viewModel.declineReservation(reservation.id, reason)
            },
            onDismiss = { declining = null },
        )
    }
    if (pickingMember) {
        MemberPickerDialog(
            members = state.activeMembers.filter { it.hasProfile },
            onPick = { member ->
                pickingMember = false
                handout = HandoutTarget(member, null)
            },
            onDismiss = { pickingMember = false },
        )
    }
    handout?.let { target ->
        HandoutDialog(
            memberName = target.memberName,
            birthDate = target.member?.birthDate,
            initialGrams = target.reservation?.grams,
            initialStrain = target.reservation?.strain,
            busy = state.busy,
            onConfirm = { grams, thc, strain ->
                handout = null
                viewModel.recordHandout(
                    grams = grams,
                    thcPercent = thc,
                    strain = strain,
                    reservationId = target.reservation?.id,
                    memberUid = if (target.reservation == null) target.member?.uid else null,
                )
            },
            onDismiss = { handout = null },
        )
    }
}

// ---------- Mitglieder ----------

@Composable
private fun MembersTab(state: ClubUiState, viewModel: ClubViewModel) {
    val context = LocalContext.current
    var approving by remember { mutableStateOf<ClubMember?>(null) }
    var removing by remember { mutableStateOf<ClubMember?>(null) }
    var confirmRotate by remember { mutableStateOf(false) }
    var appointing by remember { mutableStateOf<ClubMember?>(null) }
    val club = state.club

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (club != null) {
            item {
                MemberCodeCard(
                    club = club,
                    busy = state.busy,
                    onShare = {
                        context.shareText(
                            "Aufnahme in ${club.name}: In der App Green Ganja Grow Guardian unter Club den Code " +
                                "${ClubFormat.code(club.memberCode)} einlösen. Die Freigabe erfolgt nach Ausweiskontrolle im Club.",
                            "Mitglieder-Code teilen",
                        )
                    },
                    onRotate = { confirmRotate = true },
                )
            }
        }
        inlineAd("staff-members-top")
        if (state.pendingMembers.isNotEmpty()) {
            item { SectionTitle("Offene Anträge") }
            items(state.pendingMembers, key = { "pending-${it.uid}" }) { member ->
                MemberCard(member) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { removing = member }, enabled = !state.busy) { Text("Ablehnen") }
                        Button(onClick = { approving = member }, enabled = !state.busy && club?.active == true) { Text("Freigeben") }
                    }
                }
            }
        }
        item { SectionTitle("Mitglieder (${state.activeMembers.size})") }
        if (state.moderationAvailable) {
            item { Hint("Moderatoren (${state.moderators.size} von ${ModerationRules.MAX_MODERATORS}) entscheiden Meldungen, verwarnen, sperren im Marktplatz und schreiben Ankündigungen. Sie belegen keinen Team-Platz und sehen keine Personendaten.") }
        }
        items(state.activeMembers, key = { "active-${it.uid}" }) { member ->
            MemberCard(member) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (member.role == ClubRole.MEMBER || member.role == ClubRole.MODERATOR) {
                        TextButton(onClick = { removing = member }, enabled = !state.busy) { Text("Entfernen") }
                    }
                    if (state.moderationAvailable && (member.role == ClubRole.MEMBER || member.role == ClubRole.MODERATOR)) {
                        val full = member.role == ClubRole.MEMBER && state.moderators.size >= ModerationRules.MAX_MODERATORS
                        TextButton(onClick = { appointing = member }, enabled = !state.busy && !full) {
                            Text(if (member.role == ClubRole.MODERATOR) "Abberufen" else "Moderator")
                        }
                    }
                    if (!member.hasProfile) {
                        OutlinedButton(onClick = { approving = member }, enabled = !state.busy) { Text("Daten eintragen") }
                    }
                }
            }
        }
        inlineAd("staff-members-end")
    }

    approving?.let { member ->
        ApproveMemberDialog(
            member = member,
            busy = state.busy,
            onConfirm = { firstName, lastName, birthDate ->
                approving = null
                viewModel.approveMember(member.uid, firstName, lastName, birthDate)
            },
            onDismiss = { approving = null },
        )
    }
    removing?.let { member ->
        val pending = member.status == MemberStatus.PENDING
        ConfirmDialog(
            title = if (pending) "Antrag ablehnen?" else "${member.fullName} entfernen?",
            text = if (pending) {
                "Die Person kann später erneut einen Antrag stellen."
            } else {
                "Offene Reservierungen werden storniert. Die Abgabe-Dokumentation bleibt erhalten."
            },
            confirmLabel = if (pending) "Ablehnen" else "Entfernen",
            onConfirm = {
                removing = null
                viewModel.removeMember(member.uid)
            },
            onDismiss = { removing = null },
        )
    }
    appointing?.let { member ->
        val dismiss = member.role == ClubRole.MODERATOR
        ConfirmDialog(
            title = if (dismiss) "${member.fullName} abberufen?" else "${member.fullName} zum Moderator ernennen?",
            text = if (dismiss) {
                "Die Person bleibt Mitglied, verliert aber den Reiter Moderation."
            } else {
                "Moderatoren entscheiden Meldungen, verwarnen, sperren im Marktplatz und schreiben Ankündigungen. " +
                    "Sie sehen nur Anzeigenamen und belegen keinen Team-Platz."
            },
            confirmLabel = if (dismiss) "Abberufen" else "Ernennen",
            onConfirm = {
                appointing = null
                viewModel.setModerator(member.uid, !dismiss)
            },
            onDismiss = { appointing = null },
        )
    }
    if (confirmRotate) {
        ConfirmDialog(
            title = "Neuen Mitglieder-Code erstellen?",
            text = "Der bisherige Code funktioniert danach nicht mehr. Bereits gestellte Anträge bleiben erhalten.",
            confirmLabel = "Neuer Code",
            onConfirm = {
                confirmRotate = false
                viewModel.rotateMemberCode()
            },
            onDismiss = { confirmRotate = false },
        )
    }
}

@Composable
private fun MemberCodeCard(club: Club, busy: Boolean, onShare: () -> Unit, onRotate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Mitglieder-Code", style = MaterialTheme.typography.titleMedium)
            Text(text = ClubFormat.code(club.memberCode), style = MaterialTheme.typography.headlineMedium)
            Hint("Damit beantragen neue Mitglieder die Aufnahme. Freigeben könnt ihr erst nach der Ausweiskontrolle.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare) { Text("Teilen") }
                TextButton(onClick = onRotate, enabled = !busy && club.active) { Text("Neuer Code") }
            }
        }
    }
}

// ---------- Abgabe-Dokumentation ----------

@Composable
private fun DocumentationTab(state: ClubUiState, viewModel: ClubViewModel) {
    val currentMonth = remember { ClubFormat.currentMonth() }
    val year = state.dispensationMonth.take(4).toIntOrNull() ?: 0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { viewModel.showMonth(-1) }) { Text("‹ Zurück") }
                Text(text = ClubFormat.month(state.dispensationMonth), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { viewModel.showMonth(1) }, enabled = state.dispensationMonth < currentMonth) {
                    Text("Weiter ›")
                }
            }
        }
        inlineAd("staff-doku-top")
        item {
            Hint("${state.dispensations.size} Ausgaben, zusammen ${ClubFormat.grams(state.dispensedGramsInMonth)}")
        }
        if (state.dispensations.isEmpty()) {
            item { Hint("In diesem Monat wurde nichts ausgegeben.") }
        }
        items(state.dispensations, key = { it.id }) { dispensation ->
            DispensationRow(dispensation)
            HorizontalDivider()
        }
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.exportYear(year) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Jahr $year als CSV exportieren")
                }
                Hint("Dokumentation nach § 26 KCanG. Einträge werden fünf Jahre aufbewahrt und danach automatisch gelöscht.")
            }
        }
        inlineAd("staff-doku-end")
    }
}

@Composable
private fun DispensationRow(dispensation: Dispensation) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "${dispensation.lastName}, ${dispensation.firstName}", style = MaterialTheme.typography.bodyLarge)
            Text(text = ClubFormat.grams(dispensation.grams), style = MaterialTheme.typography.bodyLarge)
        }
        Hint(
            listOfNotNull(
                ClubFormat.date(dispensation.date),
                "THC ${ClubFormat.percent(dispensation.thcPercent)}",
                dispensation.strain,
                "Jg. ${dispensation.birthYear}",
            ).joinToString(" · "),
        )
    }
}

// ---------- Club ----------

@Composable
private fun ClubTab(state: ClubUiState, viewModel: ClubViewModel) {
    val club = state.club
    val isOwner = state.membership?.isOwner == true
    var removingStaff by remember { mutableStateOf<ClubMember?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val me = state.me
    val now = remember(state.notices, state.announcements) { System.currentTimeMillis() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (club != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "Social Club Farmer", style = MaterialTheme.typography.titleMedium)
                        val until = club.expiresAt?.let { ClubFormat.dateOf(it.time) }
                        Hint(
                            when {
                                !club.active -> "Abo pausiert"
                                until != null -> "Aktiv, läuft bis $until"
                                else -> "Aktiv"
                            },
                        )
                    }
                }
            }
            item { SectionTitle("Team (${club.staffUids.size} von ${club.seats} Plätzen)") }
            items(state.team, key = { "team-${it.uid}" }) { member ->
                MemberCard(member) {
                    if (isOwner && member.role == ClubRole.STAFF) {
                        TextButton(onClick = { removingStaff = member }, enabled = !state.busy) { Text("Platz entziehen") }
                    }
                }
            }
            if (isOwner) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { viewModel.createStaffInvite() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy && club.active && club.freeSeats > 0,
                        ) { Text(if (club.freeSeats > 0) "Team-Platz vergeben" else "Alle Plätze belegt") }
                        Hint("Die Einladung gilt 7 Tage. Wer sie einlöst, bekommt Social Club Farmer, solange dein Abo läuft.")
                    }
                }
            }
        }
        inlineAd("staff-club-top")
        clubNewsItems(state.announcements, state.notices, now)

        if (state.dispensing) item { SectionTitle("Eigene Reservierung") }
        if (!state.dispensing) {
            // Play-Store-Fassung: keine Reservierungen
        } else if (me != null && !me.hasProfile) {
            item { Hint("Trage zuerst deine Daten im Reiter Mitglieder ein, dann kannst du reservieren.") }
        } else {
            item {
                ReservationForm(busy = state.busy) { grams, date, strain, note ->
                    viewModel.requestReservation(grams, date, strain, note)
                }
            }
        }
        items(if (state.dispensing) state.myReservations else emptyList(), key = { "mine-${it.id}" }) { reservation ->
            ReservationCard(reservation, showMember = false) {
                if (reservation.status.isOpen) {
                    TextButton(onClick = { viewModel.cancelReservation(reservation.id) }, enabled = !state.busy) {
                        Text("Stornieren")
                    }
                }
            }
        }
        if (!isOwner) {
            item { TextButton(onClick = { confirmLeave = true }) { Text("Club verlassen") } }
        }
        inlineAd("staff-club-end")
    }

    removingStaff?.let { member ->
        ConfirmDialog(
            title = "Team-Platz entziehen?",
            text = "${member.fullName} bleibt Mitglied, verliert aber Social Club Farmer und den Zugriff auf die Club-Verwaltung.",
            confirmLabel = "Entziehen",
            onConfirm = {
                removingStaff = null
                viewModel.removeStaff(member.uid)
            },
            onDismiss = { removingStaff = null },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Club verlassen?",
            text = "Dein Team-Platz wird frei und offene Reservierungen werden storniert.",
            confirmLabel = "Verlassen",
            onConfirm = {
                confirmLeave = false
                viewModel.leaveClub()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}
