package de.greenganja.growguardian.club.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.billing.SubscriptionState
import de.greenganja.growguardian.club.Club
import de.greenganja.growguardian.club.ClubBox
import de.greenganja.growguardian.club.ClubBoxPrefs
import de.greenganja.growguardian.club.ClubBoxRepository
import de.greenganja.growguardian.club.ClubBoxRules
import de.greenganja.growguardian.club.MemberSettings
import de.greenganja.growguardian.club.ClubMember
import de.greenganja.growguardian.club.ClubRepository
import de.greenganja.growguardian.club.ClubRole
import de.greenganja.growguardian.club.Dispensation
import de.greenganja.growguardian.club.Announcement
import de.greenganja.growguardian.club.MemberStatus
import de.greenganja.growguardian.club.Membership
import de.greenganja.growguardian.club.ModNotice
import de.greenganja.growguardian.club.ModerationRepository
import de.greenganja.growguardian.club.Reservation
import de.greenganja.growguardian.club.StaffInvite
import de.greenganja.growguardian.grow.GrowBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Alles, was die Club-Bildschirme anzeigen. */
data class ClubUiState(
    val loading: Boolean = true,
    val myUid: String? = null,
    val membership: Membership? = null,
    val subscription: SubscriptionState = SubscriptionState(),
    /** Nur für das Team gefüllt. */
    val club: Club? = null,
    val members: List<ClubMember> = emptyList(),
    val openReservations: List<Reservation> = emptyList(),
    val dispensationMonth: String = ClubFormat.currentMonth(),
    val dispensations: List<Dispensation> = emptyList(),
    /** Eigene Reservierungen, für Mitglieder und Team. */
    val myReservations: List<Reservation> = emptyList(),
    /** Gemeinsame Boxen des Clubs, für Mitglieder und Team. */
    val clubBoxes: List<ClubBox> = emptyList(),
    /** Eigene Boxen zum Teilen, gefüllt beim Öffnen des Teilen-Dialogs. */
    val ownBoxes: List<GrowBox> = emptyList(),
    /** Eigene Einstellungen: Anzeigename, Benachrichtigungen. */
    val settings: MemberSettings = MemberSettings(),
    /** Persönliche Ordnung der Club-Boxen (nur auf diesem Gerät). */
    val pinnedBoxes: Set<String> = emptySet(),
    val hiddenBoxes: Set<String> = emptySet(),
    val showHiddenBoxes: Boolean = false,
    /** Eine Aktion läuft gerade, Buttons sperren. */
    val busy: Boolean = false,
    /** Reservierungen und Abgabe-Doku in dieser App-Fassung (nicht in der Play-Store-Fassung). */
    val dispensing: Boolean = true,
    /** Ankündigungen von Team und Moderation, für alle aktiven Mitglieder. */
    val announcements: List<Announcement> = emptyList(),
    /** Einträge der Moderation über einen selbst (Verwarnung, Sperre, entferntes Inserat). */
    val notices: List<ModNotice> = emptyList(),
    /** Moderation ist eingebunden (Ernennen, Reiter Moderation). */
    val moderationAvailable: Boolean = false,
) {
    /** Eigenes Social-Club-Abo, aber noch kein Club. */
    val canCreateClub: Boolean
        get() = membership == null && subscription.tier == FarmerTier.SOCIAL_CLUB && !subscription.isClubSeat

    val pendingMembers: List<ClubMember> get() = members.filter { it.status == MemberStatus.PENDING }
    val activeMembers: List<ClubMember> get() = members.filter { it.status == MemberStatus.ACTIVE }
    val team: List<ClubMember> get() = members.filter { it.role == ClubRole.OWNER || it.role == ClubRole.STAFF }
    val moderators: List<ClubMember> get() = members.filter { it.role == ClubRole.MODERATOR }
    val dispensedGramsInMonth: Double get() = dispensations.sumOf { it.grams }

    fun member(uid: String): ClubMember? = members.firstOrNull { it.uid == uid }

    /** Club-Boxen in persönlicher Reihenfolge, ohne ausgeblendete (außer auf Wunsch). */
    val visibleClubBoxes: List<ClubBox>
        get() = ClubBoxRules.arrange(clubBoxes, pinnedBoxes, hiddenBoxes, showHiddenBoxes)

    val hiddenBoxCount: Int get() = clubBoxes.count { it.id in hiddenBoxes }

    /** Eigener Mitglieds-Eintrag, nur für das Team bekannt. */
    val me: ClubMember? get() = myUid?.let(::member)
}

/** Einmalige Ereignisse für die Oberfläche. */
sealed interface ClubEvent {
    data class Message(val text: String) : ClubEvent
    data class InviteCreated(val invite: StaffInvite) : ClubEvent
    data class ExportReady(val year: Int, val csv: String) : ClubEvent

    /** Auskunft über die eigenen Daten als JSON, zum Speichern. */
    data class DataExportReady(val json: String) : ClubEvent
}

private data class ClubData(
    val membership: Membership? = null,
    val club: Club? = null,
    val members: List<ClubMember> = emptyList(),
    val openReservations: List<Reservation> = emptyList(),
    val dispensations: List<Dispensation> = emptyList(),
    val myReservations: List<Reservation> = emptyList(),
    val clubBoxes: List<ClubBox> = emptyList(),
    val settings: MemberSettings = MemberSettings(),
    val announcements: List<Announcement> = emptyList(),
    val notices: List<ModNotice> = emptyList(),
)

private data class BoxOrder(val pinned: Set<String>, val hidden: Set<String>, val showHidden: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class ClubViewModel(
    private val clubs: ClubRepository,
    subscriptions: SubscriptionManager,
    private val clubBoxes: ClubBoxRepository? = null,
    private val ownBoxSource: (suspend () -> List<GrowBox>)? = null,
    private val boxPrefs: ClubBoxPrefs? = null,
    private val dispensing: Boolean = true,
    private val moderation: ModerationRepository? = null,
) : ViewModel() {

    private val month = MutableStateFlow(ClubFormat.currentMonth())
    private val busy = MutableStateFlow(false)
    private val ownBoxes = MutableStateFlow<List<GrowBox>>(emptyList())
    private val boxOrder = MutableStateFlow(BoxOrder(boxPrefs?.pinned().orEmpty(), boxPrefs?.hidden().orEmpty(), false))
    private val events = Channel<ClubEvent>(Channel.BUFFERED)

    /** Meldungen, Einladungscodes und fertige Exporte. */
    val eventFlow: Flow<ClubEvent> = events.receiveAsFlow()

    private val data: Flow<ClubData> = clubs.membership().flatMapLatest { membership ->
        val boxes = if (membership != null && !membership.isPending && clubBoxes != null) {
            clubBoxes.boxes(membership.clubId).catch { emit(emptyList()) }
        } else {
            flowOf(emptyList())
        }
        val settings = if (membership != null && !membership.isPending) {
            clubs.mySettings(membership.clubId).catch { emit(MemberSettings()) }
        } else {
            flowOf(MemberSettings())
        }
        val activeClub = membership?.takeIf { !it.isPending }?.clubId
        val mod = moderation
        val announcements = if (activeClub != null && mod != null) {
            mod.announcements(activeClub).catch { emit(emptyList()) }
        } else {
            flowOf(emptyList())
        }
        val notices = if (activeClub != null && mod != null) {
            mod.myNotices(activeClub, clubs.currentUid).catch { emit(emptyList()) }
        } else {
            flowOf(emptyList())
        }
        combine(dataFor(membership), boxes, settings, announcements, notices) { data, shared, mine, news, notes ->
            data.copy(clubBoxes = shared, settings = mine, announcements = news, notices = notes)
        }
    }

    private fun dataFor(membership: Membership?): Flow<ClubData> {
        return when {
            membership == null -> flowOf(ClubData())
            membership.isStaff -> combine(
                clubs.club(membership.clubId),
                clubs.members(membership.clubId),
                clubs.openReservations(membership.clubId),
                month.flatMapLatest { clubs.dispensations(membership.clubId, it) },
                clubs.myReservations(membership.clubId),
            ) { club, members, open, dispensations, mine ->
                ClubData(membership, club, members, open, dispensations, mine)
            }
            membership.isPending -> flowOf(ClubData(membership))
            else -> clubs.myReservations(membership.clubId).map { ClubData(membership, myReservations = it) }
        }
    }

    val state: StateFlow<ClubUiState> =
        combine(data, subscriptions.state, month, busy, combine(ownBoxes, boxOrder, ::Pair)) { data, subscription, month, busy, (own, order) ->
            ClubUiState(
                loading = false,
                myUid = clubs.currentUid,
                membership = data.membership,
                subscription = subscription,
                club = data.club,
                members = data.members,
                openReservations = data.openReservations,
                dispensationMonth = month,
                dispensations = data.dispensations,
                myReservations = data.myReservations,
                clubBoxes = data.clubBoxes,
                ownBoxes = own,
                settings = data.settings,
                pinnedBoxes = order.pinned,
                hiddenBoxes = order.hidden,
                showHiddenBoxes = order.showHidden,
                dispensing = dispensing,
                busy = busy,
                announcements = data.announcements,
                notices = data.notices,
                moderationAvailable = moderation != null,
            )
        }
            .catch {
                events.trySend(ClubEvent.Message("Die Club-Daten konnten nicht geladen werden."))
                emit(ClubUiState(loading = false))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClubUiState())

    // ---------- Beitreten und Gründen ----------

    fun joinClub(code: String) = act({ clubs.joinClub(code) }) { role ->
        if (role == ClubRole.MEMBER) "Antrag gestellt. Das Team gibt dich nach der Ausweiskontrolle frei." else "Willkommen im Club-Team."
    }

    fun createClub(name: String) = act({ clubs.createClub(name) }) { "Club gegründet." }

    fun leaveClub() = act({ clubs.leaveClub() }) { "Du hast den Club verlassen." }

    // ---------- Team ----------

    fun createStaffInvite() = act({ clubs.createStaffInvite() }) { invite ->
        events.send(ClubEvent.InviteCreated(invite))
        null
    }

    fun removeStaff(uid: String) = act({ clubs.removeStaff(uid) }) { "Team-Platz ist wieder frei." }

    fun approveMember(uid: String, firstName: String, lastName: String, birthDate: String) {
        val wasPending = state.value.member(uid)?.status == MemberStatus.PENDING
        act({ clubs.approveMember(uid, firstName, lastName, birthDate) }) {
            if (wasPending) "Mitglied freigegeben." else "Daten gespeichert."
        }
    }

    fun removeMember(uid: String) {
        val wasPending = state.value.member(uid)?.status == MemberStatus.PENDING
        act({ clubs.removeMember(uid) }) { if (wasPending) "Antrag abgelehnt." else "Mitglied entfernt." }
    }

    /** Moderator ernennen oder abberufen (Team und Inhaber). */
    fun setModerator(uid: String, moderator: Boolean) {
        val repository = moderation ?: return
        act({ repository.setModerator(uid, moderator) }) {
            if (moderator) "Zum Moderator ernannt. Die Person sieht jetzt den Reiter „Moderation“." else "Als Moderator abberufen."
        }
    }

    fun rotateMemberCode() = act({ clubs.rotateMemberCode() }) { "Neuer Mitglieder-Code erstellt, der alte gilt nicht mehr." }

    // ---------- Gemeinsame Boxen ----------

    /** Lädt die eigenen Boxen für den Teilen-Dialog. */
    fun loadOwnBoxes() {
        val source = ownBoxSource ?: return
        viewModelScope.launch { runCatching { source() }.onSuccess { ownBoxes.value = it } }
    }

    fun shareBox(boxId: String) {
        val repository = clubBoxes ?: return
        act({ repository.share(boxId) }) { "Box mit dem Club geteilt. Die Werte erscheinen nach dem nächsten Messlauf deines Handys." }
    }

    fun unshareBox(clubBoxId: String) {
        val repository = clubBoxes ?: return
        act({ repository.unshare(clubBoxId) }) { "Teilen beendet." }
    }

    fun togglePinned(boxId: String) {
        val order = boxOrder.value
        val pinned = if (boxId in order.pinned) order.pinned - boxId else order.pinned + boxId
        boxPrefs?.setPinned(pinned)
        boxOrder.value = order.copy(pinned = pinned)
    }

    fun toggleHidden(boxId: String) {
        val order = boxOrder.value
        val hidden = if (boxId in order.hidden) order.hidden - boxId else order.hidden + boxId
        boxPrefs?.setHidden(hidden)
        boxOrder.value = order.copy(hidden = hidden)
    }

    fun showHiddenBoxes(show: Boolean) {
        boxOrder.value = boxOrder.value.copy(showHidden = show)
    }

    // ---------- Eigene Einstellungen und Daten ----------

    fun saveSettings(settings: MemberSettings) = act({ clubs.updateSettings(settings) }) { "Einstellungen gespeichert." }

    fun exportMyData() = act({ clubs.exportMyData() }) { json ->
        events.send(ClubEvent.DataExportReady(json))
        null
    }

    // ---------- Reservierungen ----------

    fun requestReservation(grams: Double, pickupDate: String, strain: String?, note: String?) =
        act({ clubs.requestReservation(grams, pickupDate, strain, note) }) { remaining ->
            "Reserviert. In diesem Monat sind noch ${ClubFormat.grams(remaining)} möglich."
        }

    fun updateReservation(id: String, grams: Double, pickupDate: String, strain: String?, note: String?) =
        act({ clubs.updateReservation(id, grams, pickupDate, strain, note) }) { remaining ->
            "Reservierung geändert. In diesem Monat sind noch ${ClubFormat.grams(remaining)} möglich."
        }

    fun cancelReservation(id: String) = act({ clubs.cancelReservation(id) }) { "Reservierung storniert." }

    fun confirmReservation(id: String) = act({ clubs.confirmReservation(id) }) { "Reservierung bestätigt." }

    fun declineReservation(id: String, reason: String?) =
        act({ clubs.declineReservation(id, reason) }) { "Reservierung abgelehnt." }

    // ---------- Abgabe-Dokumentation ----------

    fun recordHandout(grams: Double, thcPercent: Double, strain: String?, reservationId: String?, memberUid: String?) =
        act({ clubs.recordHandout(grams, thcPercent, reservationId, memberUid, strain) }) { remaining ->
            "Ausgabe dokumentiert. Noch ${ClubFormat.grams(remaining)} in diesem Monat."
        }

    fun showMonth(delta: Int) {
        month.value = ClubFormat.shiftMonth(month.value, delta)
    }

    fun exportYear(year: Int) = act({ clubs.exportDispensations(year) }) { csv ->
        events.send(ClubEvent.ExportReady(year, csv))
        null
    }

    /** Führt eine Aktion aus, sperrt solange die Buttons und meldet Erfolg oder Fehler. */
    private fun <T> act(action: suspend () -> Result<T>, onSuccess: suspend (T) -> String?) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                action()
                    .onSuccess { value -> onSuccess(value)?.let { events.send(ClubEvent.Message(it)) } }
                    .onFailure { error ->
                        events.send(ClubEvent.Message(error.message ?: "Etwas ist schiefgelaufen. Bitte versuche es noch einmal."))
                    }
            } finally {
                busy.value = false
            }
        }
    }
}
