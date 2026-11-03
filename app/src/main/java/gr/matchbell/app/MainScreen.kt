@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package gr.matchbell.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

typealias UpdatePrefs = ((Preferences) -> Preferences) -> Unit

@Composable fun MatchMateApp(
    state: AppSnapshot, openedEvent: String?, onOpenedConsumed: () -> Unit,
    onUpdate: UpdatePrefs, onRefresh: () -> Unit,
    onNotificationPermission: () -> Unit, onExactPermission: () -> Unit,
    onSystemSettings: () -> Unit, onTest: () -> Boolean
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Broadcast?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
    LaunchedEffect(openedEvent, state.events) {
        if (openedEvent != null) {
            state.events.firstOrNull { it.id == openedEvent }?.let { detail = it; tab = 0; onOpenedConsumed() }
            if (!state.loading && state.lastSync != null && state.events.none { it.id == openedEvent }) {
                toast("Η μετάδοση δεν υπάρχει πλέον στο τρέχον πρόγραμμα.")
                onOpenedConsumed()
            }
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                listOf(Triple("Πρόγραμμα", Icons.Rounded.Tv, 0), Triple("Επιλογές", Icons.Rounded.FavoriteBorder, 1),
                    Triple("Ρυθμίσεις", Icons.Rounded.Tune, 2)).forEach { (label, icon, index) ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(icon, label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Rounded.NotificationsActive, null, Modifier.padding(9.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(10.dp))
                Text("match", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("mate", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (tab == 0) {
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        if (state.loading) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, "Ανανέωση προγράμματος")
                    }
                }
            }
            when (tab) {
                0 -> ProgrammeScreen(state, onUpdate, onRefresh, { tab = it }, { detail = it })
                1 -> FollowingScreen(state, onUpdate)
                2 -> SettingsScreen(state, onUpdate, onNotificationPermission, onExactPermission, onSystemSettings,
                    { toast(if (onTest()) "Στάλθηκε δοκιμαστική ειδοποίηση." else "Ενεργοποίησε τις ειδοποιήσεις και το κανάλι δοκιμής στις ρυθμίσεις Android.") }, onRefresh)
            }
        }
    }
    detail?.let { initial ->
        val event = state.events.firstOrNull { it.id == initial.id } ?: initial
        EventDetails(event, state.prefs, onUpdate, { detail = null })
    }
}

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}
@Composable fun SectionTitle(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (subtitle != null) {
        Spacer(Modifier.height(5.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun EmptyState(icon: ImageVector, title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (action != null) { Spacer(Modifier.height(10.dp)); TextButton(onClick = onAction) { Text(action) } }
    }
}

@Composable private fun ProgrammeScreen(
    state: AppSnapshot, update: UpdatePrefs, refresh: () -> Unit, goTab: (Int) -> Unit, open: (Broadcast) -> Unit
) {
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val current = Instant.now()
                now = current
                delay(60_000L - current.toEpochMilli() % 60_000L)
            }
        }
    }
    val today = now.atZone(Athens).toLocalDate()
    var dateKey by rememberSaveable { mutableStateOf(today.toString()) }
    var view by rememberSaveable { mutableStateOf("all") }
    var sport by rememberSaveable { mutableStateOf("all") }
    var search by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val prefs = state.prefs
    val dates = remember(today) { (0L..6L).map { today.plusDays(it) } }
    LaunchedEffect(today) {
        if (dateKey != "week" && dateKey !in dates.map { it.toString() }) dateKey = today.toString()
    }
    val interestedIds = remember(state.events, prefs.reminderSettings()) {
        state.events.filter { prefs.interested(it) }.map { it.id }.toSet()
    }
    val searchKey = remember(search) { normalized(search) }
    val filtered = remember(state.events, dateKey, view, sport, searchKey, interestedIds, prefs.favorites) { state.events.filter { e ->
        (dateKey == "week" || e.localDay == dateKey) &&
            (view == "all" || (view == "mine" && e.id in interestedIds) || (view == "stars" && e.id in prefs.favorites)) &&
            (sport == "all" || e.sport == sport || e.series == sport) &&
            (searchKey.isBlank() || e.searchText.contains(searchKey))
    }.sortedBy { it.startsAt } }
    val next = remember(state.events, now, view, interestedIds) {
        val upcoming = state.events.asSequence().filter { it.startsAt > now && (view != "mine" || it.id in interestedIds) }
        upcoming.firstOrNull { it.id in interestedIds } ?: upcoming.firstOrNull()
    }
    val grouped = remember(filtered) { filtered.groupBy { it.local.toLocalDate() } }
    val available = remember(state.events) {
        (state.events.map { it.sport } + state.events.mapNotNull { it.series }).distinct()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Το παιχνίδι ξεκινά εδώ.", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text("Αγώνες, κανάλια και οι δικές σου υπενθυμίσεις.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { goTab(2) },
                color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.notificationsAllowed && prefs.enabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                        null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text(when {
                        !state.notificationsAllowed -> "Ενεργοποίησε τις ειδοποιήσεις"
                        !prefs.enabled -> "Οι υπενθυμίσεις είναι σε παύση"
                        else -> "${state.scheduled} προγραμματισμένες υπενθυμίσεις"
                    }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(17.dp))
                }
            }
        }
        if (prefs.teams.isEmpty() && prefs.sports.isEmpty() && prefs.competitions.isEmpty() && prefs.manual.isEmpty()) {
            item {
                Panel(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { goTab(1) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Κάν’ το δικό σου", style = MaterialTheme.typography.titleMedium)
                            Text("Διάλεξε ομάδες, διοργανώσεις και σπορ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Διάλεξε αγαπημένα", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else if (next != null) {
            item { NextBroadcast(next, now, Modifier.padding(horizontal = 20.dp), { open(next) }) }
        }
        if (state.error != null) {
            item {
                Surface(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(state.error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = refresh, enabled = !state.loading) { Text("Δοκίμασε ξανά") }
                    }
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(dates, key = { it.toString() }) { date ->
                    val selected = dateKey == date.toString()
                    Surface(Modifier.width(70.dp).clickable { dateKey = date.toString() },
                        shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            Text(when (date) { today -> "Σήμερα"; today.plusDays(1) -> "Αύριο"; else -> date.format(DateTimeFormatter.ofPattern("EEE", Greek)) }, color = color, style = MaterialTheme.typography.labelSmall)
                            Text(date.dayOfMonth.toString(), color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item {
                    Surface(Modifier.width(85.dp).clickable { dateKey = "week" }, shape = RoundedCornerShape(18.dp),
                        color = if (dateKey == "week") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val color = if (dateKey == "week") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            Text("Εβδομάδα", color = color, style = MaterialTheme.typography.labelSmall)
                            Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(27.dp), tint = color)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    listOf("all" to "Όλα", "mine" to "Για μένα", "stars" to "★").forEachIndexed { i, (key, label) ->
                        SegmentedButton(selected = view == key, onClick = { view = key }, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
                            Text(label)
                        }
                    }
                }
                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) search = "" }) {
                    Icon(if (showSearch) Icons.Rounded.Close else Icons.Rounded.Search, "Αναζήτηση")
                }
            }
        }
        if (showSearch) item {
            OutlinedTextField(search, { search = it }, Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                placeholder = { Text("Ομάδα, διοργάνωση ή κανάλι") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(16.dp))
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(sport == "all", { sport = "all" }, label = { Text("Όλα τα σπορ") }) }
                items(available.distinct()) { key ->
                    FilterChip(sport == key, { sport = key }, label = { Text(SelectableSports[key] ?: SportLabels[key] ?: state.events.firstOrNull { it.sport == key }?.sportLabel ?: key) })
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Μεταδόσεις", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text("${filtered.size}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        if (filtered.isEmpty()) {
            item {
                if (state.loading && state.events.isEmpty()) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else EmptyState(Icons.Rounded.Tv, "Δεν βρέθηκαν μεταδόσεις", when {
                    view == "mine" -> "Πρόσθεσε επιλογές ή άλλαξε ημέρα."
                    view == "stars" -> "Πάτησε το αστέρι σε μια μετάδοση για να τη βρίσκεις εδώ."
                    search.isNotBlank() -> "Δοκίμασε άλλη αναζήτηση."
                    else -> "Ανανέωσε το πρόγραμμα ή διάλεξε άλλη ημέρα."
                }, if (view == "mine") "Οι επιλογές μου" else "Ανανέωση", { if (view == "mine") goTab(1) else refresh() })
            }
        }
        grouped.forEach { (date, events) ->
            if (dateKey == "week") item(key = "day-$date") {
                Text(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Greek)), Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            items(events, key = { it.id }) { event ->
                BroadcastCard(event, prefs, now, Modifier.padding(horizontal = 20.dp), { open(event) }, update)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Πηγή: SPORT24 / 24MEDIA · Ώρες Ελλάδας", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.lastSync?.let {
                    Text("Ενημέρωση: ${it.atZone(Athens).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun NextBroadcast(event: Broadcast, now: Instant, modifier: Modifier, onClick: () -> Unit) {
    val minutes = Duration.between(now, event.startsAt).toMinutes()
    Surface(modifier.fillMaxWidth().clickable(onClick = onClick), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface))).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text("ΕΠΟΜΕΝΗ ΜΕΤΑΔΟΣΗ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(if (minutes < 60) "Σε $minutes′" else event.local.format(DateTimeFormatter.ofPattern("dd/MM HH:mm")), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(13.dp))
            Text(event.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Text("${event.local.format(TimeFormat)}  ·  ${event.channel}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
fun toggleReminder(event: Broadcast, prefs: Preferences): Preferences =
    if (prefs.interested(event) && event.id !in prefs.muted) prefs.copy(muted = prefs.muted + event.id, manual = prefs.manual - event.id)
    else prefs.copy(manual = prefs.manual + event.id, muted = prefs.muted - event.id)
fun toggleFavorite(event: Broadcast, prefs: Preferences): Preferences = prefs.copy(
    favorites = if (event.id in prefs.favorites) prefs.favorites - event.id else prefs.favorites + event.id)

@Composable private fun BroadcastCard(event: Broadcast, prefs: Preferences, now: Instant, modifier: Modifier, open: () -> Unit, update: UpdatePrefs) {
    val reminder = prefs.interested(event) && event.id !in prefs.muted && (!event.isShow || prefs.includeShows || event.id in prefs.manual)
    val imminent = event.startsAt > now && Duration.between(now, event.startsAt).toMinutes() <= 60
    Surface(modifier.fillMaxWidth().clickable(onClick = open), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (imminent && reminder) MaterialTheme.colorScheme.primary.copy(alpha = .5f) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(start = 15.dp, top = 15.dp, bottom = 15.dp, end = 5.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(58.dp)) {
                Text(event.local.format(TimeFormat), fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    color = if (imminent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Icon(sportIcon(event.series ?: event.sport), null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (event.startsAt <= now) {
                    Spacer(Modifier.height(7.dp))
                    Text("Ξεκίνησε", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(5.dp))
                Text(event.competitions.firstOrNull()?.label ?: SelectableSports[event.series ?: event.sport] ?: event.sportLabel,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tv, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(5.dp)); Text(event.channel, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column {
                IconButton(onClick = { update { toggleFavorite(event, it) } }, modifier = Modifier.size(44.dp)) {
                    Icon(if (event.id in prefs.favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        if (event.id in prefs.favorites) "Αφαίρεση από αγαπημένα" else "Προσθήκη στα αγαπημένα",
                        Modifier.size(21.dp), tint = if (event.id in prefs.favorites) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { update { toggleReminder(event, it) } }, enabled = event.startsAt > now, modifier = Modifier.size(44.dp)) {
                    Icon(if (reminder) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                        if (reminder) "Απενεργοποίηση υπενθύμισης" else "Προσθήκη υπενθύμισης", Modifier.size(21.dp),
                        tint = if (reminder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
fun sportIcon(key: String): ImageVector = when (key) {
    "podosfairo" -> Icons.Rounded.SportsSoccer
    "basket" -> Icons.Rounded.SportsBasketball
    "tennis" -> Icons.Rounded.SportsTennis
    "f1", "motogp", "mixanokinitos-athlitismos" -> Icons.Rounded.SportsMotorsports
    "volley" -> Icons.Rounded.SportsVolleyball
    "handball" -> Icons.Rounded.SportsHandball
    else -> Icons.Rounded.EmojiEvents
}

@Composable private fun EventDetails(event: Broadcast, prefs: Preferences, update: UpdatePrefs, close: () -> Unit) {
    val context = LocalContext.current
    val enabled = prefs.interested(event) && event.id !in prefs.muted && (!event.isShow || prefs.includeShows || event.id in prefs.manual)
    ModalBottomSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Icon(sportIcon(event.series ?: event.sport), null, Modifier.size(35.dp), tint = MaterialTheme.colorScheme.primary)
            Text(event.title, style = MaterialTheme.typography.headlineSmall)
            Text(event.local.format(DateTimeFormatter.ofPattern("EEEE d MMMM · HH:mm", Greek)), style = MaterialTheme.typography.titleMedium)
            Text("📺 ${event.channel}", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleMedium)
            if (event.description.isNotBlank()) Text(event.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Ώρα μετάδοσης, σύμφωνα με την πηγή. Μπορεί να διαφέρει από την έναρξη του αγώνα.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { update { toggleReminder(event, it) } }, enabled = event.startsAt > Instant.now(), modifier = Modifier.fillMaxWidth()) {
                Icon(if (enabled) Icons.Rounded.NotificationsOff else Icons.Rounded.NotificationsActive, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp)); Text(if (enabled) "Αφαίρεση υπενθύμισης" else "Υπενθύμισέ μου")
            }
            if (enabled) Text("Χρόνοι: ${prefs.offsets.sortedDescending().joinToString { if (it == 0) "στην έναρξη" else "$it′ πριν" }} · Ρυθμίζονται στις Ρυθμίσεις.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (enabled && !prefs.enabled) Text("Οι ειδοποιήσεις είναι σε παύση. Ενεργοποίησέ τες στις Ρυθμίσεις.", color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { update { toggleFavorite(event, it) } }, Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.StarBorder, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp))
                Text(if (event.id in prefs.favorites) "Αφαίρεση από αγαπημένα" else "Αποθήκευση στα αγαπημένα")
            }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.sport24.gr/tvprogram/"))) }) { Text("Πρόγραμμα στο SPORT24 ↗") }
        }
    }
}
