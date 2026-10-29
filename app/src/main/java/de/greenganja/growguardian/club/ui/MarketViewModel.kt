package de.greenganja.growguardian.club.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.club.Interest
import de.greenganja.growguardian.club.Listing
import de.greenganja.growguardian.club.ListingCategory
import de.greenganja.growguardian.club.ListingCondition
import de.greenganja.growguardian.club.ListingStatus
import de.greenganja.growguardian.club.ListingType
import de.greenganja.growguardian.club.MarketRepository
import de.greenganja.growguardian.club.Material
import de.greenganja.growguardian.club.PropagationRequest
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Filter oben im Marktplatz. */
enum class MarketFilter(val label: String) {
    ALL("Alle"),
    OFFER("Biete"),
    WANTED("Suche"),
    SWAP("Tausch"),
    FREE("Verschenken"),
    CLUB("Samen & Stecklinge"),
    ;

    fun matches(listing: Listing): Boolean = when (this) {
        ALL -> true
        CLUB -> listing is Listing.Propagation
        OFFER -> listing is Listing.Gear && listing.type == ListingType.OFFER
        WANTED -> listing is Listing.Gear && listing.type == ListingType.WANTED
        SWAP -> listing is Listing.Gear && listing.type == ListingType.SWAP
        FREE -> listing is Listing.Gear && listing.type == ListingType.FREE
    }
}

data class MarketUiState(
    val loading: Boolean = true,
    val myUid: String? = null,
    val isStaff: Boolean = false,
    val propagation: Boolean = true,
    val listings: List<Listing> = emptyList(),
    val filter: MarketFilter = MarketFilter.ALL,
    val myRequests: List<PropagationRequest> = emptyList(),
    /** Nur für das Team. */
    val openRequests: List<PropagationRequest> = emptyList(),
    val reported: List<Listing> = emptyList(),
    /** Interessenten am gerade geöffneten eigenen Inserat. */
    val interests: List<Interest> = emptyList(),
    val selectedId: String? = null,
    val busy: Boolean = false,
) {
    /** Hervorgehobene Inserate zuerst, sonst neueste zuerst (Reihenfolge aus Firestore). */
    val visible: List<Listing>
        get() {
            val now = System.currentTimeMillis()
            return listings.filter(filter::matches).sortedByDescending { (it as? Listing.Gear)?.boostedUntil?.let { until -> until > now } ?: false }
        }
    val selected: Listing? get() = selectedId?.let { id -> (listings + reported).firstOrNull { it.id == id } }
    val month: String get() = ClubFormat.todayIso().take(7)
}

sealed interface MarketEvent {
    data class Message(val text: String) : MarketEvent

    /** Formular oder Detail schließen. */
    data object Done : MarketEvent
}

private data class MarketData(
    val listings: List<Listing>,
    val myRequests: List<PropagationRequest>,
    val openRequests: List<PropagationRequest>,
    val reported: List<Listing>,
)

private data class Ui(val filter: MarketFilter, val selectedId: String?, val busy: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class MarketViewModel(
    private val clubId: String,
    private val isStaff: Boolean,
    private val market: MarketRepository,
    /** Samen und Stecklinge als Club-Angebot; nicht in der Play-Store-Fassung. */
    private val propagation: Boolean = true,
) : ViewModel() {

    private val ui = MutableStateFlow(Ui(MarketFilter.ALL, null, false))
    private val events = Channel<MarketEvent>(Channel.BUFFERED)
    val eventFlow: Flow<MarketEvent> = events.receiveAsFlow()

    private fun <T> Flow<List<T>>.safe() = catch { emit(emptyList()) }

    private val data: Flow<MarketData> = combine(
        market.listings(clubId).safe(),
        if (propagation) market.myPropagationRequests(clubId).safe() else flowOf(emptyList()),
        if (isStaff && propagation) market.openPropagationRequests(clubId).safe() else flowOf(emptyList()),
        if (isStaff) market.reported(clubId).safe() else flowOf(emptyList()),
        ::MarketData,
    )

    /** Interessenten nur für eigene Inserate laden. */
    private val interests: Flow<List<Interest>> = combine(ui, data) { u, d -> u.selectedId?.takeIf { id -> d.listings.any { it.id == id && it.sellerUid == market.currentUid } } }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else market.interests(clubId, id).safe() }

    val state: StateFlow<MarketUiState> = combine(data, ui, interests) { d, u, i ->
        MarketUiState(
            loading = false,
            myUid = market.currentUid,
            isStaff = isStaff,
            propagation = propagation,
            listings = if (propagation) d.listings else d.listings.filter { it !is Listing.Propagation },
            filter = u.filter,
            myRequests = d.myRequests,
            openRequests = d.openRequests,
            reported = d.reported,
            interests = i,
            selectedId = u.selectedId,
            busy = u.busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketUiState(isStaff = isStaff))

    fun setFilter(filter: MarketFilter) {
        ui.value = ui.value.copy(filter = filter)
    }

    fun open(listingId: String?) {
        ui.value = ui.value.copy(selectedId = listingId)
    }

    fun createGear(type: ListingType, category: ListingCategory, condition: ListingCondition?, title: String, description: String, priceEur: Double?) =
        act({ market.createListing(type, category, condition, title, description, priceEur) }, "Inserat veröffentlicht.", close = true)

    fun createPropagation(material: Material, strain: String, description: String, stock: Int) =
        act({ market.createPropagationOffer(material, strain, description, stock) }, "Club-Angebot veröffentlicht.", close = true)

    fun setStatus(listing: Listing, status: ListingStatus) =
        act({ market.setStatus(listing.id, status) }, if (status == ListingStatus.REMOVED) "Inserat entfernt." else "Status: ${status.label}.")

    fun expressInterest(listing: Listing, message: String?) =
        act({ market.expressInterest(listing.id, message) }, "Interesse gemeldet. ${listing.sellerName} sieht deinen Namen und deine Nachricht.")

    fun boost(listing: Listing) = act({ market.boost(listing.id) }, "Inserat ist 3 Tage ganz oben.")

    fun report(listing: Listing, reason: String?) =
        act({ market.report(listing.id, reason) }, "Danke, das Team schaut sich das an.", close = true)

    fun requestPropagation(listing: Listing.Propagation, count: Int) =
        act(
            { market.requestPropagation(listing.id, count) },
            "Angefragt. Abholung nur persönlich im Club mit Lichtbildausweis.",
            close = true,
        )

    fun cancelPropagation(requestId: String) = act({ market.cancelPropagation(requestId) }, "Anfrage zurückgezogen.")

    fun decidePropagation(requestId: String, handOut: Boolean) =
        act({ market.decidePropagation(requestId, handOut) }, if (handOut) "Ausgabe dokumentiert." else "Anfrage abgelehnt.")

    private fun act(action: suspend () -> Result<*>, success: String, close: Boolean = false) {
        if (ui.value.busy) return
        ui.value = ui.value.copy(busy = true)
        viewModelScope.launch {
            try {
                action()
                    .onSuccess {
                        events.send(MarketEvent.Message(success))
                        if (close) {
                            ui.value = ui.value.copy(selectedId = null)
                            events.send(MarketEvent.Done)
                        }
                    }
                    .onFailure { events.send(MarketEvent.Message(it.message ?: "Das hat nicht geklappt.")) }
            } finally {
                ui.value = ui.value.copy(busy = false)
            }
        }
    }
}
