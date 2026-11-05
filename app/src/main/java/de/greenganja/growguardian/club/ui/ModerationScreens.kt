package de.greenganja.growguardian.club.ui

import de.greenganja.growguardian.ads.inlineAd
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.greenganja.growguardian.club.Announcement
import de.greenganja.growguardian.club.ModLogEntry
import de.greenganja.growguardian.club.ModMember
import de.greenganja.growguardian.club.ModNotice
import de.greenganja.growguardian.club.ModerationRules
import de.greenganja.growguardian.club.ModerationText
import de.greenganja.growguardian.club.ReportedListing

/**
 * Moderations-Reiter für Moderatoren und Team: Meldungen, Mitglieder (Verwarnen, Sperren), Ankündigungen, Protokoll.
 * Moderatoren sehen nur Anzeigenamen; Freigaben, Personendaten und die Abgabe-Doku bleiben beim Team.
 */
@Composable
internal fun ModerationTab(viewModel: ModerationViewModel, onMessage: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now = remember(state.overview) { System.currentTimeMillis() }
    var warning by remember { mutableStateOf<ModMember?>(null) }
    var blocking by remember { mutableStateOf<ModMember?>(null) }
    var removing by remember { mutableStateOf<ReportedListing?>(null) }
    var writing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Announcement?>(null) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { onMessage(it) } }

    if (state.loading) {
        Box(modifier = Modifier.fillMaxSize()) { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModSection.entries.forEach { section ->
                    val count = when (section) {
                        ModSection.REPORTS -> state.reports.size
                        ModSection.NEWS -> state.announcements.size
                        else -> 0
                    }
                    Pill(if (count > 0) "${section.label} ($count)" else section.label, state.section == section) { viewModel.show(section) }
                }
            }
        }
        state.error?.let { error ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Hint(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::refresh) { Text("Neu laden") }
                }
            }
        }
        inlineAd("moderation-top")
        when (state.section) {
            ModSection.REPORTS -> reportItems(state, onRestore = viewModel::restore, onRemove = { removing = it })
            ModSection.MEMBERS -> memberItems(state, now, onSearch = viewModel::search, onWarn = { warning = it }, onBlock = { blocking = it }, onUnblock = viewModel::unblock)
            ModSection.NEWS -> newsItems(state, onWrite = { writing = true }, onDelete = { deleting = it })
            ModSection.LOG -> logItems(state.overview?.log.orEmpty())
        }
        item { TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("Aktualisieren") } }
        inlineAd("moderation-end")
    }

    warning?.let { member ->
        ReasonDialog(
            title = "${member.name} verwarnen",
            hint = "Die Person sieht die Verwarnung mit Grund unter „Hinweise vom Club“.",
            confirm = "Verwarnen",
            required = true,
            onConfirm = { reason ->
                warning = null
                viewModel.warn(member, reason.orEmpty())
            },
            onDismiss = { warning = null },
        )
    }
    blocking?.let { member ->
        BlockDialog(
            name = member.name,
            onConfirm = { days, reason ->
                blocking = null
                viewModel.block(member, days, reason)
            },
            onDismiss = { blocking = null },
        )
    }
    removing?.let { report ->
        ReasonDialog(
            title = "„${report.title}“ entfernen?",
            hint = "Freiwillig: Grund für ${report.sellerName}. Das Inserat ist danach für niemanden mehr sichtbar.",
            confirm = "Entfernen",
            required = false,
            onConfirm = { reason ->
                removing = null
                viewModel.remove(report, reason)
            },
            onDismiss = { removing = null },
        )
    }
    if (writing) {
        AnnouncementDialog(
            canPin = state.pinnedCount < ModerationRules.MAX_PINNED,
            onConfirm = { title, text, pinned ->
                writing = false
                viewModel.post(title, text, pinned)
            },
            onDismiss = { writing = false },
        )
    }
    deleting?.let { announcement ->
        ConfirmDialog(
            title = "Ankündigung löschen?",
            text = "„${announcement.title}“ verschwindet für alle Mitglieder.",
            confirmLabel = "Löschen",
            onConfirm = {
                deleting = null
                viewModel.delete(announcement)
            },
            onDismiss = { deleting = null },
        )
    }
}

private fun LazyListScope.reportItems(state: ModerationUiState, onRestore: (ReportedListing) -> Unit, onRemove: (ReportedListing) -> Unit) {
    item { Hint("Ab 3 Meldungen ist ein Inserat ausgeblendet, bis ihr entscheidet.") }
    if (state.reports.isEmpty()) item { Hint("Keine offenen Meldungen.") }
    items(state.reports, key = { "report-${it.listingId}" }) { report ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = report.title, style = MaterialTheme.typography.titleMedium)
                Hint("${if (report.isGear) "Zubehör von ${report.sellerName}" else "Club-Angebot"} · ${report.reports} Meldungen")
                report.reasons.take(3).forEach { Text(text = "„$it“", style = MaterialTheme.typography.bodyMedium) }
                if (report.reasons.size > 3) Hint("und ${report.reasons.size - 3} weitere")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onRestore(report) }, enabled = !state.busy) { Text("Wieder freigeben") }
                    if (report.mayRemove) {
                        TextButton(onClick = { onRemove(report) }, enabled = !state.busy) {
                            Text("Entfernen", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (!report.mayRemove) Hint("Club-Angebote entfernt nur das Team.")
            }
        }
    }
}

private fun LazyListScope.memberItems(
    state: ModerationUiState,
    now: Long,
    onSearch: (String) -> Unit,
    onWarn: (ModMember) -> Unit,
    onBlock: (ModMember) -> Unit,
    onUnblock: (ModMember) -> Unit,
) {
    item {
        OutlinedTextField(
            value = state.query,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Mitglied suchen") },
            singleLine = true,
        )
    }
    item { Hint("Nur Anzeigenamen. Aufnahme, Ausweisdaten und Abgaben bleiben beim Team.") }
    val members = state.members(now)
    if (members.isEmpty()) item { Hint("Niemand gefunden.") }
    items(members, key = { "mod-member-${it.uid}" }) { member ->
        val blocked = ModerationRules.isBlocked(member.blockedUntil, now)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = member.name, style = MaterialTheme.typography.titleMedium)
                Hint(
                    listOfNotNull(
                        member.role.label,
                        member.warnings.takeIf { it > 0 }?.let { if (it == 1) "1 Verwarnung" else "$it Verwarnungen" },
                        if (blocked) "gesperrt bis ${ClubFormat.dateOf(member.blockedUntil ?: now)}" else null,
                    ).joinToString(" · "),
                    color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (blocked) member.blockReason?.let { Hint("Grund: $it") }
                if (member.mayAct) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onWarn(member) }, enabled = !state.busy) { Text("Verwarnen") }
                        if (blocked) {
                            OutlinedButton(onClick = { onUnblock(member) }, enabled = !state.busy) { Text("Sperre aufheben") }
                        } else {
                            OutlinedButton(onClick = { onBlock(member) }, enabled = !state.busy) { Text("Im Marktplatz sperren") }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.newsItems(state: ModerationUiState, onWrite: () -> Unit, onDelete: (Announcement) -> Unit) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onWrite, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Neue Ankündigung") }
            Hint("Alle aktiven Mitglieder sehen sie 30 Tage lang im Club. Nur intern, keine Werbung nach außen (§ 6 KCanG).")
        }
    }
    if (state.announcements.isEmpty()) item { Hint("Noch keine Ankündigungen.") }
    items(state.announcements, key = { "mod-news-${it.id}" }) { announcement ->
        AnnouncementCard(announcement) {
            TextButton(onClick = { onDelete(announcement) }, enabled = !state.busy) { Text("Löschen") }
        }
    }
}

private fun LazyListScope.logItems(log: List<ModLogEntry>) {
    item { Hint("Die letzten 50 Aktionen. Einträge werden nach einem Jahr gelöscht; betroffene Mitglieder sehen ihre eigenen.") }
    if (log.isEmpty()) item { Hint("Noch keine Einträge.") }
    items(log, key = { "log-${it.id}" }) { entry ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = ModerationText.logLine(entry, ClubFormat::dateOf), style = MaterialTheme.typography.bodyMedium)
            Hint(listOfNotNull(entry.at?.let(ClubFormat::dateOf), entry.reason).joinToString(" · "))
        }
    }
}

@Composable
private fun AnnouncementCard(announcement: Announcement, actions: @Composable () -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (announcement.pinned) Hint("Angeheftet", color = MaterialTheme.colorScheme.primary)
            Text(text = announcement.title, style = MaterialTheme.typography.titleMedium)
            Text(text = announcement.text, style = MaterialTheme.typography.bodyMedium)
            Hint(listOfNotNull(announcement.byName.ifBlank { null }, announcement.at?.let(ClubFormat::dateOf)).joinToString(" · "))
            actions()
        }
    }
}

/** Ankündigungen und eigene Hinweise der Moderation im Club-Bereich von Mitgliedern, Moderatoren und Team. */
internal fun LazyListScope.clubNewsItems(announcements: List<Announcement>, notices: List<ModNotice>, now: Long) {
    val news = ModerationRules.arrange(announcements, now).take(5)
    val mine = ModerationRules.recentNotices(notices, now).take(5)
    if (mine.isNotEmpty()) {
        item { SectionTitle("Hinweise vom Club") }
        items(mine, key = { "notice-${it.id}" }) { notice ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = ModerationText.noticeTitle(notice, now, ClubFormat::dateOf), style = MaterialTheme.typography.titleSmall)
                    notice.reason?.let { Text(text = "Grund: $it", style = MaterialTheme.typography.bodyMedium) }
                    Hint(listOfNotNull(notice.at?.let(ClubFormat::dateOf), notice.byName.ifBlank { null }).joinToString(" · "))
                }
            }
        }
        item { Hint("Fragen dazu? Sprich das Club-Team an.") }
    }
    if (news.isNotEmpty()) {
        item { SectionTitle("Aktuelles vom Club") }
        items(news, key = { "news-${it.id}" }) { AnnouncementCard(it) }
    }
}

@Composable
private fun ReasonDialog(
    title: String,
    hint: String,
    confirm: String,
    required: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val valid = if (required) ModerationRules.reasonValid(text) else text.isBlank() || ModerationRules.reasonValid(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ModerationRules.REASON_MAX) },
                    label = { Text(if (required) "Grund" else "Grund (freiwillig)") },
                    maxLines = 4,
                )
                Hint(hint)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim().ifBlank { null }) }, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun BlockDialog(name: String, onConfirm: (Int, String) -> Unit, onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(7) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name im Marktplatz sperren") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModerationRules.BLOCK_DAYS.forEach { option ->
                        Pill(ModerationRules.blockLabel(option), days == option) { days = option }
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(ModerationRules.REASON_MAX) },
                    label = { Text("Grund") },
                    maxLines = 4,
                )
                Hint("Keine neuen Inserate und keine Anfragen, bestehende Inserate bleiben. Die Person sieht Dauer und Grund.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(days, reason.trim()) }, enabled = ModerationRules.reasonValid(reason)) { Text("Sperren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun AnnouncementDialog(canPin: Boolean, onConfirm: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }
    val error = ModerationRules.announcementError(title, text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Ankündigung") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(ModerationRules.TITLE_MAX) },
                    label = { Text("Titel") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ModerationRules.TEXT_MAX) },
                    label = { Text("Text") },
                    maxLines = 8,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = pinned && canPin, onCheckedChange = { pinned = it }, enabled = canPin)
                    Text(if (canPin) "Oben anheften" else "Schon ${ModerationRules.MAX_PINNED} angeheftet")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, text, pinned && canPin) }, enabled = error == null) { Text("Veröffentlichen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
