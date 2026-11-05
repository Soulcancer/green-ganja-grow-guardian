package de.greenganja.growguardian.club.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.club.Announcement
import de.greenganja.growguardian.club.ModMember
import de.greenganja.growguardian.club.ModerationOverview
import de.greenganja.growguardian.club.ModerationRepository
import de.greenganja.growguardian.club.ModerationRules
import de.greenganja.growguardian.club.ReportedListing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Bereiche des Moderations-Reiters. */
enum class ModSection(val label: String) {
    REPORTS("Meldungen"),
    MEMBERS("Mitglieder"),
    NEWS("Ankündigungen"),
    LOG("Protokoll"),
}

data class ModerationUiState(
    val loading: Boolean = true,
    val overview: ModerationOverview? = null,
    /** Laden fehlgeschlagen; mit „Neu laden“ erneut versuchen. */
    val error: String? = null,
    val busy: Boolean = false,
    val section: ModSection = ModSection.REPORTS,
    val query: String = "",
) {
    val reports: List<ReportedListing> get() = overview?.reports.orEmpty()
    val announcements: List<Announcement> get() = overview?.announcements.orEmpty()

    /** Mitglieder nach Suche; Gesperrte und Verwarnte zuerst, damit offene Fälle oben stehen. */
    fun members(now: Long): List<ModMember> {
        val q = query.trim().lowercase()
        return overview?.members.orEmpty()
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .sortedWith(compareByDescending<ModMember> { ModerationRules.isBlocked(it.blockedUntil, now) }.thenByDescending { it.warnings }.thenBy { it.name.lowercase() })
    }

    val pinnedCount: Int get() = announcements.count { it.pinned }
}

/** Moderations-Werkzeuge für Moderatoren und Team. Lädt alles über moderationOverview und lädt nach jeder Aktion neu. */
class ModerationViewModel(private val repository: ModerationRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ModerationUiState())
    val state: StateFlow<ModerationUiState> = mutableState
    private val events = Channel<String>(Channel.BUFFERED)

    /** Meldungen für die Snackbar. */
    val messages: Flow<String> = events.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = mutableState.value.overview == null, error = null)
            repository.overview()
                .onSuccess { mutableState.value = mutableState.value.copy(loading = false, overview = it, error = null) }
                .onFailure { mutableState.value = mutableState.value.copy(loading = false, error = it.message ?: "Die Moderation konnte nicht geladen werden.") }
        }
    }

    fun show(section: ModSection) {
        mutableState.value = mutableState.value.copy(section = section)
    }

    fun search(query: String) {
        mutableState.value = mutableState.value.copy(query = query.take(40))
    }

    fun warn(member: ModMember, reason: String) =
        act({ repository.warn(member.uid, reason.trim()) }, "${member.name} wurde verwarnt.")

    fun block(member: ModMember, days: Int, reason: String) =
        act({ repository.block(member.uid, days, reason.trim()) }, "${member.name} ist ${ModerationRules.blockLabel(days)} im Marktplatz gesperrt.")

    fun unblock(member: ModMember) = act({ repository.unblock(member.uid) }, "Sperre von ${member.name} aufgehoben.")

    fun restore(report: ReportedListing) =
        act({ repository.decideReport(report.listingId, restore = true) }, "„${report.title}“ ist wieder sichtbar.")

    fun remove(report: ReportedListing, reason: String?) =
        act({ repository.decideReport(report.listingId, restore = false, reason = reason?.trim()?.ifBlank { null }) }, "„${report.title}“ wurde entfernt.")

    fun post(title: String, text: String, pinned: Boolean) =
        act({ repository.postAnnouncement(title.trim(), text.trim(), pinned) }, "Ankündigung veröffentlicht.")

    fun delete(announcement: Announcement) =
        act({ repository.deleteAnnouncement(announcement.id) }, "Ankündigung gelöscht.")

    private fun act(action: suspend () -> Result<*>, success: String) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            try {
                action()
                    .onSuccess { events.send(success) }
                    .onFailure { events.send(it.message ?: "Etwas ist schiefgelaufen. Bitte versuche es noch einmal.") }
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
                refresh()
            }
        }
    }
}
