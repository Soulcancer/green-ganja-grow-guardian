package de.greenganja.growguardian.club.ui

import de.greenganja.growguardian.ads.inlineAd
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.greenganja.growguardian.club.Listing
import de.greenganja.growguardian.club.ListingCategory
import de.greenganja.growguardian.club.ListingCondition
import de.greenganja.growguardian.club.ListingStatus
import de.greenganja.growguardian.club.ListingType
import de.greenganja.growguardian.club.Material
import de.greenganja.growguardian.club.PropagationLimits
import de.greenganja.growguardian.club.PropagationRequest
import de.greenganja.growguardian.club.PropagationStatus
import java.util.Locale

private enum class MarketMode { LIST, NEW_GEAR, NEW_PROPAGATION }

/** "89 €", "12,50 €" oder ein Text je nach Art. */
internal fun priceLabel(listing: Listing.Gear): String = when (listing.type) {
    ListingType.FREE -> "Zu verschenken"
    ListingType.WANTED -> "Gesucht"
    ListingType.SWAP -> "Tausch"
    ListingType.OFFER -> listing.priceEur?.let {
        if (it % 1.0 == 0.0) "${it.toLong()} €" else String.format(Locale.GERMANY, "%.2f €", it)
    } ?: "Preis auf Anfrage"
}

/**
 * Club-Marktplatz: Zubehör der Mitglieder (Biete, Suche, Tausch, Verschenken) und Samen oder Stecklinge,
 * die nur der Club anbietet (§ 20 KCanG). Für das Team zusätzlich offene Anfragen und gemeldete Inserate.
 */
@Composable
internal fun MarketScreen(viewModel: MarketViewModel, onMessage: (String) -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(MarketMode.LIST) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is MarketEvent.Message -> onMessage(event.text)
                MarketEvent.Done -> mode = MarketMode.LIST
            }
        }
    }

    val selected = state.selected
    when {
        mode == MarketMode.NEW_GEAR -> {
            BackHandler { mode = MarketMode.LIST }
            GearForm(busy = state.busy, onSubmit = viewModel::createGear, onBack = { mode = MarketMode.LIST })
        }
        mode == MarketMode.NEW_PROPAGATION -> {
            BackHandler { mode = MarketMode.LIST }
            PropagationForm(busy = state.busy, onSubmit = viewModel::createPropagation, onBack = { mode = MarketMode.LIST })
        }
        selected != null -> {
            BackHandler { viewModel.open(null) }
            ListingDetail(selected, state, viewModel, onBack = { viewModel.open(null) })
        }
        else -> MarketList(
            state = state,
            viewModel = viewModel,
            onNewGear = { mode = MarketMode.NEW_GEAR },
            onNewPropagation = { mode = MarketMode.NEW_PROPAGATION },
            onBack = onBack,
        )
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
        Text(text = title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
    }
}

@Composable
internal fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun MarketList(
    state: MarketUiState,
    viewModel: MarketViewModel,
    onNewGear: () -> Unit,
    onNewPropagation: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header("Marktplatz", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarketFilter.entries.filter { state.propagation || it != MarketFilter.CLUB }.forEach { f ->
                        Pill(f.label, state.filter == f, onClick = { viewModel.setFilter(f) })
                    }
                }
            }
            inlineAd("market-top")
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onNewGear, enabled = !state.busy) { Text("+ Inserat") }
                    if (state.isStaff && state.propagation) OutlinedButton(onClick = onNewPropagation, enabled = !state.busy) { Text("+ Club-Angebot") }
                }
            }
            item {
                Hint(
                    if (state.propagation) {
                        "Hier nur Zubehör. Samen und Stecklinge gibt es ausschließlich als Club-Angebot, " +
                            "Abholung persönlich im Club (§ 20 KCanG). Cannabis selbst wird nicht gehandelt."
                    } else {
                        "Nur Zubehör: Licht, Zelte, Lüftung, Töpfe, Technik. Cannabis, Samen und Stecklinge sind hier nicht erlaubt."
                    },
                )
            }

            if (state.isStaff && state.openRequests.isNotEmpty()) {
                item { SectionTitle("Anfragen Samen & Stecklinge (${state.openRequests.size})") }
                items(state.openRequests, key = { "req-${it.id}" }) { request ->
                    RequestCard(request) {
                        Row {
                            TextButton(onClick = { viewModel.decidePropagation(request.id, false) }, enabled = !state.busy) { Text("Ablehnen") }
                            TextButton(onClick = { viewModel.decidePropagation(request.id, true) }, enabled = !state.busy) { Text("Ausgegeben") }
                        }
                    }
                }
                item { Hint("„Ausgegeben“ erst nach Ausweiskontrolle vor Ort. Die Ausgabe wird 5 Jahre dokumentiert.") }
            }
            if (state.canModerate && state.reported.isNotEmpty()) {
                item { SectionTitle("Gemeldet (${state.reported.size})") }
                items(state.reported, key = { "rep-${it.id}" }) { listing ->
                    ListingCard(listing, onClick = { viewModel.open(listing.id) })
                }
            }
            val mine = state.myRequests.filter { it.status == PropagationStatus.REQUESTED || it.month == state.month }
            if (mine.isNotEmpty()) {
                item { SectionTitle("Meine Anfragen") }
                items(mine, key = { "mine-${it.id}" }) { request ->
                    RequestCard(request, showMember = false) {
                        if (request.status == PropagationStatus.REQUESTED) {
                            TextButton(onClick = { viewModel.cancelPropagation(request.id) }, enabled = !state.busy) { Text("Zurückziehen") }
                        }
                    }
                }
            }

            item { SectionTitle("Inserate") }
            if (state.visible.isEmpty()) item { Hint(if (state.loading) "Wird geladen …" else "Hier ist noch nichts.") }
            items(state.visible, key = { it.id }) { listing -> ListingCard(listing, onClick = { viewModel.open(listing.id) }) }
            inlineAd("market-end")
        }
    }
}

@Composable
private fun ListingCard(listing: Listing, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (listing) {
                is Listing.Gear -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = listing.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                        Text(text = priceLabel(listing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Hint(listOfNotNull(listing.type.label, listing.category.label, listing.condition?.label).joinToString(" · "))
                }
                is Listing.Propagation -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "${listing.material.plural}: ${listing.strain}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(text = "Club-Angebot", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    }
                    Hint("Noch ${listing.stock} verfügbar · kostenlos · Abholung im Club")
                }
            }
            Hint(
                listOfNotNull(
                    "Hervorgehoben".takeIf { listing is Listing.Gear && listing.boostedUntil > System.currentTimeMillis() },
                    listing.sellerName,
                    ClubBoxAge.of(listing.createdAtMillis),
                    listing.status.takeIf { it != ListingStatus.OPEN }?.label,
                ).joinToString(" · "),
            )
        }
    }
}

/** Alter eines Inserats in Worten. */
private object ClubBoxAge {
    fun of(createdAt: Long): String? =
        if (createdAt <= 0) null else de.greenganja.growguardian.club.ClubBoxRules.ageLabel(createdAt, System.currentTimeMillis())
}

@Composable
private fun RequestCard(request: PropagationRequest, showMember: Boolean = true, actions: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "${request.count} ${if (request.count == 1) request.material.label else request.material.plural} · ${request.strain}",
                style = MaterialTheme.typography.titleMedium,
            )
            Hint(listOfNotNull(request.memberName.takeIf { showMember }, request.status.label).joinToString(" · "))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

@Composable
private fun ListingDetail(listing: Listing, state: MarketUiState, viewModel: MarketViewModel, onBack: () -> Unit) {
    val mine = listing.sellerUid == state.myUid
    var interestOpen by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var count by remember(listing.id) { mutableStateOf(1) }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(if (listing is Listing.Gear) listing.title else "Club-Angebot", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (listing) {
                            is Listing.Gear -> {
                                Text(text = priceLabel(listing), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                Hint(listOfNotNull(listing.type.label, listing.category.label, listing.condition?.label).joinToString(" · "))
                            }
                            is Listing.Propagation -> {
                                Text(text = "${listing.material.plural}: ${listing.strain}", style = MaterialTheme.typography.headlineSmall)
                                Hint("Noch ${listing.stock} verfügbar · kostenlos")
                            }
                        }
                        if (listing.description.isNotBlank()) Text(text = listing.description, style = MaterialTheme.typography.bodyLarge)
                        Hint("Von ${listing.sellerName} · ${listing.status.label}")
                    }
                }
            }
            inlineAd("market-detail-top")

            when {
                listing is Listing.Propagation && !state.isStaff -> {
                    val (seeds, cuttings) = PropagationLimits.counted(state.myRequests, state.month)
                    val left = minOf(PropagationLimits.left(listing.material, seeds, cuttings), listing.stock)
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(text = "Anfragen", style = MaterialTheme.typography.titleMedium)
                                if (left == 0) {
                                    Hint("In diesem Monat ist keine weitere Anfrage möglich (höchstens 7 Samen, 5 Stecklinge oder zusammen 5).")
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { if (count > 1) count-- }) { Text("−") }
                                        Text(text = count.coerceAtMost(left).toString(), style = MaterialTheme.typography.titleLarge)
                                        OutlinedButton(onClick = { if (count < left) count++ }) { Text("+") }
                                    }
                                    Hint("Noch $left in diesem Monat möglich. Abholung nur persönlich im Club mit Lichtbildausweis, kein Versand.")
                                    Button(
                                        onClick = { viewModel.requestPropagation(listing, count.coerceAtMost(left)) },
                                        enabled = !state.busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Anfragen") }
                                }
                            }
                        }
                    }
                }
                listing is Listing.Gear && !mine && listing.status == ListingStatus.OPEN -> item {
                    Button(onClick = { interestOpen = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Interesse melden") }
                }
            }

            if (mine) {
                if (listing is Listing.Gear && listing.status == ListingStatus.OPEN) {
                    item {
                        val boosted = listing.boostedUntil > System.currentTimeMillis()
                        OutlinedButton(onClick = { viewModel.boost(listing) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (boosted) "Hervorhebung um 3 Tage verlängern (20 Coins)" else "3 Tage hervorheben (20 Coins)")
                        }
                    }
                }
                item { SectionTitle("Status") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ListingStatus.OPEN, ListingStatus.RESERVED, ListingStatus.CLOSED).forEach { status ->
                            Pill(status.label, listing.status == status, onClick = { if (listing.status != status) viewModel.setStatus(listing, status) })
                        }
                    }
                }
                if (listing is Listing.Gear) {
                    item { SectionTitle("Interessenten (${state.interests.size})") }
                    if (state.interests.isEmpty()) item { Hint("Noch niemand. Interessenten erscheinen hier mit Name und Nachricht.") }
                    items(state.interests, key = { it.uid }) { interest ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = interest.name, style = MaterialTheme.typography.titleSmall)
                                interest.message?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                    item { Hint("Verabredet euch im Club. Bitte teilt keine Adressen im Marktplatz.") }
                }
            }
            if (!mine) {
                item { TextButton(onClick = { reportOpen = true }, enabled = !state.busy) { Text("Inserat melden") } }
            }
            // Team: alles; Moderatoren: Zubehör (Club-Angebote entscheidet das Team)
            if ((state.isStaff || (state.canModerate && listing is Listing.Gear)) && listing.status != ListingStatus.REMOVED) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (listing.status == ListingStatus.REPORTED) {
                            OutlinedButton(onClick = { viewModel.restore(listing) }, enabled = !state.busy) { Text("Wieder freigeben") }
                        }
                        TextButton(onClick = { viewModel.setStatus(listing, ListingStatus.REMOVED) }, enabled = !state.busy) {
                            Text("Entfernen", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            inlineAd("market-detail-end")
        }
    }

    if (interestOpen) {
        TextDialog(
            title = "Interesse melden",
            label = "Nachricht (optional)",
            hint = "${listing.sellerName} sieht deinen Anzeigenamen und diese Nachricht.",
            confirm = "Senden",
            onConfirm = {
                interestOpen = false
                viewModel.expressInterest(listing, it)
            },
            onDismiss = { interestOpen = false },
        )
    }
    if (reportOpen) {
        TextDialog(
            title = "Inserat melden",
            label = "Grund (optional)",
            hint = "Zum Beispiel: Cannabis angeboten, unpassend, Betrugsverdacht. Das Team sieht deine Meldung.",
            confirm = "Melden",
            onConfirm = {
                reportOpen = false
                viewModel.report(listing, it)
            },
            onDismiss = { reportOpen = false },
        )
    }
}

@Composable
private fun TextDialog(title: String, label: String, hint: String, confirm: String, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(300) }, label = { Text(label) }, maxLines = 4)
                Hint(hint)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim().ifBlank { null }) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun GearForm(
    busy: Boolean,
    onSubmit: (ListingType, ListingCategory, ListingCondition?, String, String, Double?) -> Unit,
    onBack: () -> Unit,
) {
    var type by remember { mutableStateOf(ListingType.OFFER) }
    var category by remember { mutableStateOf(ListingCategory.LIGHT) }
    var condition by remember { mutableStateOf<ListingCondition?>(ListingCondition.GOOD) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val priceValue = ClubFormat.parseDecimal(price)
    val priceError = type == ListingType.OFFER && price.isNotBlank() && (priceValue == null || priceValue < 0)

    Column(modifier = Modifier.fillMaxSize()) {
        Header("Neues Inserat", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListingType.entries.forEach { t -> Pill(t.label, type == t, onClick = { type = t }) }
                }
            }
            item {
                OutlinedTextField(value = title, onValueChange = { title = it.take(60) }, modifier = Modifier.fillMaxWidth(), label = { Text("Titel") }, singleLine = true)
            }
            item { SectionTitle("Kategorie") }
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListingCategory.entries.forEach { c -> Pill(c.label, category == c, onClick = { category = c }) }
                }
            }
            if (type != ListingType.WANTED) {
                item { SectionTitle("Zustand") }
                item {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListingCondition.entries.forEach { c -> Pill(c.label, condition == c, onClick = { condition = c }) }
                    }
                }
            }
            if (type == ListingType.OFFER) {
                item {
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(9) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Preis in € (leer = auf Anfrage)") },
                        isError = priceError,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(1000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Beschreibung") },
                    minLines = 3,
                    maxLines = 8,
                )
            }
            item { Hint("Nur Zubehör. Inserate mit Cannabis, Samen oder Stecklingen lehnt die App ab.") }
            item {
                Button(
                    onClick = {
                        onSubmit(
                            type, category, condition.takeIf { type != ListingType.WANTED }, title.trim(), description.trim(),
                            priceValue.takeIf { type == ListingType.OFFER && price.isNotBlank() },
                        )
                    },
                    enabled = !busy && title.trim().length >= 3 && !priceError,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Veröffentlichen") }
            }
        }
    }
}

@Composable
private fun PropagationForm(busy: Boolean, onSubmit: (Material, String, String, Int) -> Unit, onBack: () -> Unit) {
    var material by remember { mutableStateOf(Material.SEED) }
    var strain by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    val stockValue = stock.toIntOrNull()

    Column(modifier = Modifier.fillMaxSize()) {
        Header("Club-Angebot", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Material.entries.forEach { m -> Pill(m.plural, material == m, onClick = { material = m }) }
                }
            }
            item {
                OutlinedTextField(value = strain, onValueChange = { strain = it.take(60) }, modifier = Modifier.fillMaxWidth(), label = { Text("Sorte") }, singleLine = true)
            }
            item {
                OutlinedTextField(
                    value = stock,
                    onValueChange = { stock = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Verfügbare Stückzahl") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hinweise (optional)") },
                    minLines = 2,
                )
            }
            item {
                Hint(
                    "Nach § 20 KCanG: nur aus dem eigenen Anbau des Clubs, persönlich im Club nach Ausweiskontrolle, " +
                        "höchstens 7 Samen, 5 Stecklinge oder zusammen 5 je Person und Monat. Stecklinge ohne Blüten und Wurzeln, kein Versand.",
                )
            }
            item {
                Button(
                    onClick = { stockValue?.let { onSubmit(material, strain.trim(), description.trim(), it) } },
                    enabled = !busy && strain.trim().length >= 2 && (stockValue ?: 0) >= 1,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Veröffentlichen") }
            }
        }
    }
}
