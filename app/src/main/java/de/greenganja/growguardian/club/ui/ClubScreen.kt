package de.greenganja.growguardian.club.ui

import de.greenganja.growguardian.ads.inlineAd
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.club.ClubBoxPrefs
import de.greenganja.growguardian.club.ClubBoxRepository
import de.greenganja.growguardian.club.ClubRepository
import de.greenganja.growguardian.club.MarketRepository
import de.greenganja.growguardian.club.ModerationRepository
import de.greenganja.growguardian.club.Reservation
import de.greenganja.growguardian.club.ReservationStatus
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.club.Membership
import de.greenganja.growguardian.club.StaffInvite
import de.greenganja.growguardian.ui.theme.ClubAreaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * Einstieg in den Club-Bereich. Zeigt je nach Rolle:
 * - ohne Club: Code einlösen oder (mit Social-Club-Abo) Club gründen
 * - Antrag offen: Hinweis auf die Freigabe im Club
 * - Mitglied: Reservieren und eigene Reservierungen, Ankündigungen und Hinweise vom Club
 * - Moderator: wie Mitglied, dazu der Reiter Moderation (Meldungen, Verwarnen, Sperren, Ankündigungen)
 * - Inhaber und Team: Anfragen, Mitglieder, Abgabe-Doku, Moderation und Club-Verwaltung
 *
 * @param onUpgradeClick öffnet euren Kaufbildschirm für Social Club Farmer
 */
@Composable
fun ClubScreen(
    clubs: ClubRepository,
    subscriptions: SubscriptionManager,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier,
    clubBoxes: ClubBoxRepository? = null,
    ownBoxes: (suspend () -> List<GrowBox>)? = null,
    boxPrefs: ClubBoxPrefs? = null,
    market: MarketRepository? = null,
    dispensing: Boolean = true,
    /** Gesehene Club-Einführungen je Rolle; null = keine automatische Einführung. */
    onboarding: de.greenganja.growguardian.ui.wizard.OnboardingPrefs? = null,
    /** Moderations-Werkzeuge, Ankündigungen und Hinweise; null = ausgeblendet. */
    moderation: ModerationRepository? = null,
    viewModel: ClubViewModel = viewModel { ClubViewModel(clubs, subscriptions, clubBoxes, ownBoxes, boxPrefs, dispensing, moderation) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<StaffInvite?>(null) }
    var showMarket by rememberSaveable { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ClubEvent.ExportReady?>(null) }
    var pendingDataExport by remember { mutableStateOf<String?>(null) }

    val dataExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingDataExport
        pendingDataExport = null
        if (uri != null && json != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
                    }.getOrDefault(false)
                }
                snackbar.showSnackbar(if (saved) "Deine Daten wurden gespeichert." else "Die Datei konnte nicht gespeichert werden.")
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val export = pendingExport
        pendingExport = null
        if (uri != null && export != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(UTF8_BOM) // damit Excel Umlaute richtig liest
                            stream.write(export.csv.toByteArray(Charsets.UTF_8))
                        } != null
                    }.getOrDefault(false)
                }
                snackbar.showSnackbar(if (saved) "Export gespeichert." else "Der Export konnte nicht gespeichert werden.")
            }
        }
    }

    val onMessage: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ClubEvent.Message -> snackbar.showSnackbar(event.text)
                is ClubEvent.InviteCreated -> invite = event.invite
                is ClubEvent.ExportReady -> {
                    pendingExport = event
                    exportLauncher.launch("abgaben-${event.year}.csv")
                }
                is ClubEvent.DataExportReady -> {
                    pendingDataExport = event.json
                    dataExportLauncher.launch("meine-daten.json")
                }
            }
        }
    }

    // Eigene Einführung je Rolle, einmal beim ersten Öffnen (erneut über Konto → Club-Einführung)
    val wizardRole = ClubWizardRole.of(state.membership)
    val wizardKey = de.greenganja.growguardian.ui.wizard.OnboardingPrefs.club(wizardRole.key)
    var wizardOpen by remember(wizardRole) { mutableStateOf(onboarding?.seen(wizardKey, ClubWizardContent.VERSION) == false) }

    // Mitglieder, Antragsteller und Menschen ohne Club sehen den Bereich in Rot-Lila, das Team im App-Grün
    ClubAreaTheme(member = state.membership?.isStaff != true) {
        if (wizardOpen && !state.loading) {
            androidx.activity.compose.BackHandler { onboarding?.markSeen(wizardKey, ClubWizardContent.VERSION); wizardOpen = false }
            Box(modifier = modifier.fillMaxSize()) {
                ClubWizard(wizardRole, dispensing, onDone = {
                    onboarding?.markSeen(wizardKey, ClubWizardContent.VERSION)
                    wizardOpen = false
                })
            }
        } else Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                val membership = state.membership
                when {
                    state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    showMarket && market != null && membership != null && !membership.isPending -> {
                        val marketViewModel: MarketViewModel = viewModel(key = "market-${membership.clubId}-${membership.role.id}") {
                            MarketViewModel(membership.clubId, membership.isStaff, market, propagation = dispensing, canModerate = membership.canModerate)
                        }
                        MarketScreen(
                            viewModel = marketViewModel,
                            onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } },
                            onBack = { showMarket = false },
                        )
                    }
                    membership == null -> NoClubContent(state, viewModel, onUpgradeClick)
                    membership.isPending -> PendingContent(membership, viewModel)
                    membership.isStaff -> StaffContent(
                        state, viewModel, onUpgradeClick,
                        onOpenMarket = market?.let { { showMarket = true } },
                        moderation = moderation?.let { repository -> @Composable { ModerationArea(membership.clubId, repository, onMessage) } },
                    )
                    membership.isModerator && moderation != null -> ModeratorContent(
                        moderation = { ModerationArea(membership.clubId, moderation, onMessage) },
                        member = { MemberContent(state, membership, viewModel, onOpenMarket = market?.let { { showMarket = true } }) },
                    )
                    else -> MemberContent(state, membership, viewModel, onOpenMarket = market?.let { { showMarket = true } })
                }
            }
        }
        invite?.let { InviteDialog(invite = it, onDismiss = { invite = null }) }
    }
}

/** Moderations-Reiter mit eigenem ViewModel je Club. */
@Composable
private fun ModerationArea(clubId: String, repository: ModerationRepository, onMessage: (String) -> Unit) {
    val moderationViewModel: ModerationViewModel = viewModel(key = "moderation-$clubId") { ModerationViewModel(repository) }
    ModerationTab(moderationViewModel, onMessage)
}

/** Moderatoren: Mitglieder-Ansicht und Moderation in zwei Reitern. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeratorContent(moderation: @Composable () -> Unit, member: @Composable () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Mitglied") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Moderation") })
        }
        if (tab == 1) moderation() else member()
    }
}

@Composable
private fun NoClubContent(state: ClubUiState, viewModel: ClubViewModel, onUpgradeClick: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var clubName by rememberSaveable { mutableStateOf("") }
    val normalizedCode = code.uppercase().filter { it.isLetterOrDigit() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = "Social Club", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Code einlösen", style = MaterialTheme.typography.titleMedium)
                    Hint(
                        "Mit dem Mitglieder-Code deines Clubs beantragst du die Aufnahme. " +
                            "Mit einer Team-Einladung bekommst du einen der Team-Plätze.",
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Code") },
                        placeholder = { Text("ABCD-EFGH") },
                        singleLine = true,
                    )
                    Button(
                        onClick = { viewModel.joinClub(normalizedCode) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy && normalizedCode.length == 8,
                    ) { Text("Einlösen") }
                }
            }
        }
        inlineAd("club-start-top")
        if (state.canCreateClub) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Eigenen Club gründen", style = MaterialTheme.typography.titleMedium)
                        Hint("Dein Social-Club-Abo umfasst 5 Personen: dich und 4 Team-Plätze. Mitglieder kommen kostenlos dazu.")
                        OutlinedTextField(
                            value = clubName,
                            onValueChange = { clubName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Name des Clubs") },
                            singleLine = true,
                        )
                        Button(
                            onClick = { viewModel.createClub(clubName.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy && clubName.trim().length >= 2,
                        ) { Text("Club gründen") }
                    }
                }
            }
        } else if (state.subscription.tier != FarmerTier.SOCIAL_CLUB) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Einen Club führen", style = MaterialTheme.typography.titleMedium)
                        Hint("Mit Social Club Farmer verwaltest du deinen Club mit bis zu 5 Personen im Team, Reservierungen und der Abgabe-Dokumentation.")
                        OutlinedButton(onClick = onUpgradeClick, modifier = Modifier.fillMaxWidth()) {
                            Text("Social Club Farmer ansehen")
                        }
                    }
                }
            }
        }
        inlineAd("club-start-end")
    }
}

@Composable
private fun PendingContent(membership: Membership, viewModel: ClubViewModel) {
    var confirmLeave by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Antrag bei ${membership.clubName}", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Das Club-Team gibt dich frei, sobald es deinen Ausweis im Club geprüft hat. " +
                "Danach kannst du hier Mengen reservieren.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { confirmLeave = true }) { Text("Antrag zurückziehen") }
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Antrag zurückziehen?",
            text = "Du kannst später mit dem Code erneut einen Antrag stellen.",
            confirmLabel = "Zurückziehen",
            onConfirm = {
                confirmLeave = false
                viewModel.leaveClub()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun MemberContent(state: ClubUiState, membership: Membership, viewModel: ClubViewModel, onOpenMarket: (() -> Unit)?) {
    var confirmLeave by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Reservation?>(null) }
    val now = remember(state.clubBoxes) { System.currentTimeMillis() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(text = membership.clubName, style = MaterialTheme.typography.headlineSmall)
                Hint(membership.role.label)
            }
        }
        inlineAd("club-member-top")
        clubNewsItems(state.announcements, state.notices, now)
        if (onOpenMarket != null) {
            item {
                OutlinedButton(onClick = onOpenMarket, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.dispensing) "Marktplatz: Zubehör, Samen & Stecklinge" else "Marktplatz für Zubehör")
                }
            }
        }
        if (!state.dispensing) {
            item { Hint("Reservierungen laufen in dieser App-Version nicht über die App. Sprich dazu das Team im Club an.") }
        }
        if (state.dispensing) item {
            val current = editing
            if (current == null) {
                ReservationForm(busy = state.busy) { grams, date, strain, note ->
                    viewModel.requestReservation(grams, date, strain, note)
                }
            } else {
                ReservationForm(busy = state.busy, initial = current, onCancel = { editing = null }) { grams, date, strain, note ->
                    viewModel.updateReservation(current.id, grams, date, strain, note)
                    editing = null
                }
            }
        }
        clubBoxItems(state, now, viewModel)
        if (state.dispensing) item { SectionTitle("Meine Reservierungen") }
        if (state.dispensing && state.myReservations.isEmpty()) {
            item { Hint("Noch keine Reservierungen.") }
        }
        items(if (state.dispensing) state.myReservations else emptyList(), key = { it.id }) { reservation ->
            ReservationCard(reservation, showMember = false) {
                if (reservation.status.isOpen) {
                    Row {
                        // Ändern nur, solange das Team noch nicht bestätigt hat
                        if (reservation.status == ReservationStatus.REQUESTED) {
                            TextButton(onClick = { editing = reservation }, enabled = !state.busy) { Text("Ändern") }
                        }
                        TextButton(onClick = { viewModel.cancelReservation(reservation.id) }, enabled = !state.busy) {
                            Text("Stornieren")
                        }
                    }
                }
            }
        }
        item {
            MySettingsCard(
                settings = state.settings,
                busy = state.busy,
                onSave = viewModel::saveSettings,
                onExport = viewModel::exportMyData,
            )
        }
        item {
            TextButton(onClick = { confirmLeave = true }) { Text("Club verlassen") }
        }
        inlineAd("club-member-end")
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Club verlassen?",
            text = "Deine offenen Reservierungen werden storniert.",
            confirmLabel = "Verlassen",
            onConfirm = {
                confirmLeave = false
                viewModel.leaveClub()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}
