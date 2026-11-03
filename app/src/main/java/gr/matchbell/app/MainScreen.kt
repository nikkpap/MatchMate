@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package gr.matchbell.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

typealias UpdatePrefs = ((Preferences) -> Preferences) -> Unit

/** A broadcast that started less than this long ago is shown as "on air". */
private const val LIVE_WINDOW_MIN = 150L

@Composable fun MatchMateApp(
    state: AppSnapshot, openedEvent: String?, onOpenedConsumed: () -> Unit,
    openUpdate: Boolean, onUpdateConsumed: () -> Unit,
    onUpdate: UpdatePrefs, onRefresh: () -> Unit, onDismissError: () -> Unit,
    onNotificationPermission: () -> Unit, onExactPermission: () -> Unit,
    onSystemSettings: () -> Unit, onTest: () -> Boolean
) {
    val t = LocalTxt.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Broadcast?>(null) }
    var about by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
    LaunchedEffect(openUpdate) { if (openUpdate) { about = true; onUpdateConsumed() } }
    LaunchedEffect(openedEvent, state.events, state.loading) {
        if (openedEvent != null) {
            val found = state.events.firstOrNull { it.id == openedEvent }
            if (found != null) { detail = found; tab = 0; onOpenedConsumed() }
            else if (!state.loading && state.lastSync != null) {
                toast(t("Η μετάδοση δεν υπάρχει πλέον στο τρέχον πρόγραμμα.", "This broadcast is no longer in the current schedule."))
                onOpenedConsumed()
            }
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                listOf(Triple(t("Πρόγραμμα", "Schedule"), Icons.Rounded.Tv, 0),
                    Triple(t("Επιλογές", "Following"), Icons.Rounded.FavoriteBorder, 1),
                    Triple(t("Ρυθμίσεις", "Settings"), Icons.Rounded.Tune, 2)).forEach { (label, icon, index) ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(icon, label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Logo()
                Spacer(Modifier.weight(1f))
                if (tab == 0) {
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        if (state.loading) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, t("Ανανέωση προγράμματος", "Refresh schedule"))
                    }
                }
                IconButton(onClick = { about = true }) { Icon(Icons.Rounded.Info, t("Σχετικά", "About")) }
            }
            when (tab) {
                0 -> ProgrammeScreen(state, onUpdate, onRefresh, onDismissError, { tab = it }, { detail = it }, { about = true })
                1 -> FollowingScreen(state, onUpdate)
                2 -> SettingsScreen(state, onUpdate, onNotificationPermission, onExactPermission, onSystemSettings,
                    { toast(if (onTest()) t("Στάλθηκε δοκιμαστική ειδοποίηση.", "Test notification sent.")
                        else t("Ενεργοποίησε τις ειδοποιήσεις και το κανάλι δοκιμής στις ρυθμίσεις Android.",
                            "Enable notifications and the test channel in Android settings.")) },
                    onRefresh, toast, { about = true })
            }
        }
    }
    detail?.let { initial ->
        val event = state.events.firstOrNull { it.id == initial.id } ?: initial
        EventDetails(event, state.prefs, onUpdate, { detail = null }, toast)
    }
    if (about) AboutSheet(state.prefs, onUpdate) { about = false }
}

@Composable fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // App logo (same artwork as the launcher icon).
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher), null,
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(10.dp))
        Text("match", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("mate", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
    state: AppSnapshot, update: UpdatePrefs, refresh: () -> Unit, dismissError: () -> Unit,
    goTab: (Int) -> Unit, open: (Broadcast) -> Unit, openAbout: () -> Unit
) {
    val t = LocalTxt.current
    val updateContext = LocalContext.current
    val updateState by remember { UpdateManager.get(updateContext) }.state.collectAsStateWithLifecycle()
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
    // View, men/women and sports are saved in preferences: they survive restarts and
    // the same profile drives automatic reminders (see Preferences.inScope).
    val view = state.prefs.programmeView
    var search by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showPast by rememberSaveable { mutableStateOf(false) }
    val prefs = state.prefs
    val dates = remember(today) { (0L..6L).map { today.plusDays(it) } }
    LaunchedEffect(today) {
        if (dateKey != "week" && dateKey !in dates.map { it.toString() }) dateKey = today.toString()
    }
    val interestedIds = remember(state.events, prefs.reminderSettings()) {
        state.events.filter { prefs.interested(it) }.map { it.id }.toSet()
    }
    val searchKey = remember(search) { normalized(search) }
    val matching = remember(state.events, dateKey, view, prefs.scopeGender, prefs.scopeSports, searchKey, interestedIds, prefs.favorites, prefs.hiddenChannels, prefs.manual) {
        state.events.filter { e ->
            (dateKey == "week" || e.localDay == dateKey) &&
                (prefs.channelVisible(e) || e.id in prefs.manual || e.id in prefs.favorites) &&
                (view == "all" || (view == "mine" && e.id in interestedIds) || (view == "stars" && e.id in prefs.favorites)) &&
                (view == "stars" || e.id in prefs.manual || prefs.inScope(e)) &&
                (searchKey.isBlank() || e.searchText.contains(searchKey))
        }.sortedBy { it.startsAt }
    }
    // Broadcasts that ended long ago are folded away so today opens on what is next.
    val pastCutoff = now.minusSeconds(LIVE_WINDOW_MIN * 60)
    val pastCount = remember(matching, pastCutoff.epochSecond / 60) { matching.count { it.startsAt < pastCutoff } }
    val filtered = if (showPast) matching else matching.filter { it.startsAt >= pastCutoff }
    val next = remember(state.events, now, interestedIds) {
        state.events.firstOrNull { it.startsAt > now && it.id in interestedIds }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.local.toLocalDate() } }
    val available = remember(state.events) {
        (state.events.map { it.sport } + state.events.mapNotNull { it.series }).distinct()
    }
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(t("Το παιχνίδι ξεκινά εδώ.", "Game on."), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(t("Αγώνες, κανάλια και οι δικές σου υπενθυμίσεις.", "Matches, channels and your own reminders."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { goTab(2) },
                color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.notificationsAllowed && prefs.enabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                        null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text(when {
                        !state.notificationsAllowed -> t("Ενεργοποίησε τις ειδοποιήσεις", "Turn on notifications")
                        !prefs.enabled -> t("Οι υπενθυμίσεις είναι σε παύση", "Reminders are paused")
                        else -> t("${state.scheduled} προγραμματισμένες υπενθυμίσεις", "${state.scheduled} reminders scheduled")
                    }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(17.dp))
                }
            }
        }
        if (updateState is UpdateState.Available || updateState is UpdateState.Downloading || updateState is UpdateState.Ready)
            item(key = "update-banner") { UpdateBanner(Modifier.padding(horizontal = 20.dp), openAbout) }
        if (!prefs.hasSelections) {
            item {
                Panel(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { goTab(1) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("Κάν’ το δικό σου", "Make it yours"), style = MaterialTheme.typography.titleMedium)
                            Text(t("Διάλεξε ομάδες, διοργανώσεις και σπορ.", "Pick teams, competitions and sports."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else if (next != null) {
            item { NextBroadcast(next, now, Modifier.padding(horizontal = 20.dp)) { open(next) } }
        }
        if (state.error != null) {
            item {
                Surface(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.CloudOff, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(10.dp))
                            Text(localError(state.error, t), Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row {
                            TextButton(onClick = refresh, enabled = !state.loading) { Text(t("Δοκίμασε ξανά", "Try again")) }
                            TextButton(onClick = dismissError) { Text(t("Κλείσιμο", "Dismiss")) }
                        }
                    }
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(dates, key = { it.toString() }) { date ->
                    val selected = dateKey == date.toString()
                    val count = remember(state.events, date, interestedIds) { state.events.count { it.localDay == date.toString() && it.id in interestedIds } }
                    DayChip(selected, when (date) {
                        today -> t("Σήμερα", "Today"); today.plusDays(1) -> t("Αύριο", "Tmrw")
                        else -> date.format(t.pattern("EEE"))
                    }, { Text(date.dayOfMonth.toString(), color = it, fontSize = 22.sp, fontWeight = FontWeight.Bold) }, count) { dateKey = date.toString() }
                }
                item {
                    DayChip(dateKey == "week", t("Εβδομάδα", "Week"), { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(27.dp), tint = it) }, 0, 85) { dateKey = "week" }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    listOf("all" to t("Όλα", "All"), "mine" to t("Για μένα", "For me"), "stars" to "★").forEachIndexed { i, (key, label) ->
                        SegmentedButton(selected = view == key, onClick = { update { p -> p.copy(programmeView = key) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) search = "" }) {
                    Icon(if (showSearch) Icons.Rounded.Close else Icons.Rounded.Search, t("Αναζήτηση", "Search"))
                }
            }
        }
        if (showSearch) item {
            OutlinedTextField(search, { search = it }, Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                placeholder = { Text(t("Ομάδα, διοργάνωση ή κανάλι", "Team, competition or channel")) }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = if (search.isNotEmpty()) ({ IconButton(onClick = { search = "" }) { Icon(Icons.Rounded.Clear, t("Καθαρισμός", "Clear")) } }) else null,
                shape = RoundedCornerShape(16.dp))
        }
        item {
            ProfileFilters(prefs, update, available.ifEmpty { listOf("podosfairo", "basket", "tennis", "volley") },
                Modifier.padding(horizontal = 20.dp), state.events)
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Μεταδόσεις", "Broadcasts"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text("${filtered.size}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        if (pastCount > 0) item(key = "past-toggle") {
            TextButton(onClick = { showPast = !showPast }, Modifier.padding(horizontal = 12.dp)) {
                Icon(if (showPast) Icons.Rounded.ExpandLess else Icons.Rounded.History, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (showPast) t("Απόκρυψη προηγούμενων", "Hide earlier")
                    else t("Εμφάνιση προηγούμενων ($pastCount)", "Show earlier ($pastCount)"))
            }
        }
        if (filtered.isEmpty()) {
            item {
                if (state.loading && state.events.isEmpty()) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else EmptyState(Icons.Rounded.Tv, t("Δεν βρέθηκαν μεταδόσεις", "No broadcasts found"), when {
                    view == "mine" -> t("Πρόσθεσε επιλογές ή άλλαξε ημέρα.", "Add selections or pick another day.")
                    view == "stars" -> t("Πάτησε το αστέρι σε μια μετάδοση για να τη βρίσκεις εδώ.", "Tap the star on a broadcast to keep it here.")
                    search.isNotBlank() -> t("Δοκίμασε άλλη αναζήτηση.", "Try another search.")
                    else -> t("Ανανέωσε το πρόγραμμα ή διάλεξε άλλη ημέρα.", "Refresh the schedule or pick another day.")
                }, if (view == "mine") t("Οι επιλογές μου", "My selections") else t("Ανανέωση", "Refresh"),
                    { if (view == "mine") goTab(1) else refresh() })
            }
        }
        grouped.forEach { (date, events) ->
            if (dateKey == "week") stickyHeader(key = "day-$date") {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                    Text(date.format(t.pattern("EEEE d MMMM")).replaceFirstChar { it.titlecase(t.locale) },
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            items(events, key = { it.id }) { event ->
                BroadcastCard(event, prefs, now, Modifier.padding(horizontal = 20.dp).animateItem(), { open(event) }, update)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(t("Πηγή: SPORT24 / 24MEDIA · Ώρες Ελλάδας", "Source: SPORT24 / 24MEDIA · Greek time"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.lastSync?.let {
                    Text(t("Ενημέρωση: ", "Updated: ") + it.atZone(Athens).format(t.pattern("dd/MM HH:mm")),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (prefs.hiddenChannels.isNotEmpty()) Text(t("Κρυφά κανάλια: ${prefs.hiddenChannels.size}", "Hidden channels: ${prefs.hiddenChannels.size}"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}

/** The store keeps Greek error texts; show an English equivalent when needed. */
fun localError(error: String, t: Txt): String = if (!t.en) error else when {
    error.contains("αποθηκευμένο") -> "Couldn't refresh. Showing the saved schedule."
    error.startsWith("Αδυναμία") -> "Couldn't refresh. Check your connection and try again."
    error.contains("αποθηκεύτηκαν") -> "Changes weren't saved. Please try again."
    error.contains("ρυθμίσεων") -> "Saved settings couldn't be read."
    else -> error
}

@Composable private fun DayChip(selected: Boolean, label: String, big: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
                                badge: Int, width: Int = 70, onClick: () -> Unit) {
    Surface(Modifier.width(width.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Text(label, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            big(color)
            // A dot per day that has broadcasts matching the user's selections.
            Box(Modifier.size(5.dp).clip(CircleShape).background(
                if (badge > 0) (if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                else androidx.compose.ui.graphics.Color.Transparent))
        }
    }
}

@Composable private fun NextBroadcast(event: Broadcast, now: Instant, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTxt.current
    val minutes = Duration.between(now, event.startsAt).toMinutes()
    Surface(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface))).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text(t("Η ΕΠΟΜΕΝΗ ΣΟΥ ΜΕΤΑΔΟΣΗ", "YOUR NEXT BROADCAST"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                    Text(if (minutes < 24 * 60) t.inMinutes(minutes) else event.local.format(t.pattern("EEE dd/MM")),
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(13.dp))
            Text(event.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Text("${event.local.format(TimeFormat)}  ·  ${event.channel}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun LiveDot() {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(0.35f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(7.dp).alpha(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.error))
}

@Composable private fun BroadcastCard(event: Broadcast, prefs: Preferences, now: Instant, modifier: Modifier, open: () -> Unit, update: UpdatePrefs) {
    val t = LocalTxt.current
    val haptic = LocalHapticFeedback.current
    val reminder = prefs.bellOn(event)
    val untilStart = Duration.between(now, event.startsAt).toMinutes()
    val started = event.startsAt <= now
    val imminent = !started && untilStart <= 60
    val live = started && Duration.between(event.startsAt, now).toMinutes() < LIVE_WINDOW_MIN
    val key = event.series ?: event.sport
    Surface(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = open), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (imminent && reminder) MaterialTheme.colorScheme.primary.copy(alpha = .6f) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(sportAccent(key).copy(alpha = if (started && !live) .35f else .9f)))
            Row(Modifier.weight(1f).padding(start = 12.dp, top = 14.dp, bottom = 14.dp, end = 4.dp).alpha(if (started && !live) .6f else 1f),
                verticalAlignment = Alignment.Top) {
                Column(Modifier.width(58.dp)) {
                    Text(event.local.format(TimeFormat), fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = if (imminent || live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Icon(sportIcon(key), null, Modifier.size(21.dp), tint = sportAccent(key))
                }
                Column(Modifier.weight(1f)) {
                    Text(event.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(5.dp))
                    Text(event.competitions.firstOrNull()?.label ?: t.sport(key, event.sportLabel),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(9.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Tv, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(5.dp)); Text(event.channel, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (event.isWomen) WomenBadge()
                        when {
                            live -> Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LiveDot(); Spacer(Modifier.width(5.dp))
                                    Text(t("Σε εξέλιξη", "On air"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                            imminent -> Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                                Text(t.inMinutes(untilStart), Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                            }
                            started -> Text(t("Ξεκίνησε", "Started"), Modifier.padding(vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Column {
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); update { toggleFavorite(event, it) } }, modifier = Modifier.size(44.dp)) {
                        Icon(if (event.id in prefs.favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            if (event.id in prefs.favorites) t("Αφαίρεση από αγαπημένα", "Remove from favourites") else t("Προσθήκη στα αγαπημένα", "Add to favourites"),
                            Modifier.size(21.dp), tint = if (event.id in prefs.favorites) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); update { toggleReminder(event, it) } },
                        enabled = !started, modifier = Modifier.size(44.dp)) {
                        Icon(if (reminder) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                            if (reminder) t("Απενεργοποίηση υπενθύμισης", "Turn reminder off") else t("Προσθήκη υπενθύμισης", "Add reminder"),
                            Modifier.size(21.dp).alpha(if (reminder && !prefs.enabled) .45f else 1f),
                            tint = if (reminder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
fun sportIcon(key: String): ImageVector = when (key) {
    "podosfairo", "podosfairo_salas" -> Icons.Rounded.SportsSoccer
    "basket" -> Icons.Rounded.SportsBasketball
    "tennis" -> Icons.Rounded.SportsTennis
    "f1", "motogp", "mixanokinitos-athlitismos" -> Icons.Rounded.SportsMotorsports
    "volley" -> Icons.Rounded.SportsVolleyball
    "handball" -> Icons.Rounded.SportsHandball
    "polo" -> Icons.Rounded.Pool
    "stivos" -> Icons.AutoMirrored.Rounded.DirectionsRun
    "polemikes-texnes" -> Icons.Rounded.SportsMma
    "show" -> Icons.Rounded.Mic
    else -> Icons.Rounded.EmojiEvents
}

private fun Context.tryStart(intent: Intent): Boolean = try { startActivity(intent); true } catch (_: ActivityNotFoundException) { false }

fun calendarIntent(event: Broadcast, t: Txt): Intent = Intent(Intent.ACTION_INSERT)
    .setData(CalendarContract.Events.CONTENT_URI)
    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startsAt.toEpochMilli())
    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.startsAt.plusSeconds(2 * 3600).toEpochMilli())
    .putExtra(CalendarContract.Events.TITLE, event.title)
    .putExtra(CalendarContract.Events.EVENT_LOCATION, event.channel)
    .putExtra(CalendarContract.Events.DESCRIPTION, listOf(t("Κανάλι: ", "Channel: ") + event.channel,
        event.competitions.firstOrNull()?.label.orEmpty(), event.description).filter { it.isNotBlank() }.joinToString("\n"))

fun shareText(event: Broadcast, t: Txt): String =
    "📺 ${event.title}\n🕒 ${event.local.format(t.pattern("EEE d MMM · HH:mm"))} · ${event.channel}" +
        (event.competitions.firstOrNull()?.let { "\n🏆 ${it.label}" } ?: "") + "\n— MatchMate"

@Composable private fun EventDetails(event: Broadcast, prefs: Preferences, update: UpdatePrefs, close: () -> Unit, toast: (String) -> Unit) {
    val t = LocalTxt.current
    val context = LocalContext.current
    val enabled = prefs.bellOn(event)
    val key = event.series ?: event.sport
    val future = event.startsAt > Instant.now()
    ModalBottomSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 34.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = sportAccent(key).copy(alpha = .18f), shape = RoundedCornerShape(14.dp)) {
                    Icon(sportIcon(key), null, Modifier.padding(10.dp).size(28.dp), tint = sportAccent(key))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(t.sport(key, event.sportLabel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    event.competitions.firstOrNull()?.let { Text(it.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text(event.title, style = MaterialTheme.typography.headlineSmall)
            Text(event.local.format(t.pattern("EEEE d MMMM · HH:mm")).replaceFirstChar { it.titlecase(t.locale) }, style = MaterialTheme.typography.titleMedium)
            if (event.isWomen) WomenBadge()
            Text("📺 ${event.channel}", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleMedium)
            if (event.description.isNotBlank()) Text(event.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t("Ώρα μετάδοσης, σύμφωνα με την πηγή. Μπορεί να διαφέρει από την έναρξη του αγώνα.",
                "Broadcast time as given by the source. It may differ from kick-off."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { update { toggleReminder(event, it) } }, enabled = future, modifier = Modifier.fillMaxWidth()) {
                Icon(if (enabled) Icons.Rounded.NotificationsOff else Icons.Rounded.NotificationsActive, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp)); Text(if (enabled) t("Αφαίρεση υπενθύμισης", "Remove reminder") else t("Υπενθύμισέ μου", "Remind me"))
            }
            if (enabled) Text(t("Χρόνοι: ", "Times: ") + prefs.offsets.sortedDescending().joinToString { if (it == 0) t("στην έναρξη", "at start") else t("$it′ πριν", "$it′ before") } +
                t(" · Ρυθμίζονται στις Ρυθμίσεις.", " · Change them in Settings."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (enabled && !prefs.enabled) Text(t("Οι ειδοποιήσεις είναι σε παύση. Ενεργοποίησέ τες στις Ρυθμίσεις.", "Notifications are paused. Turn them on in Settings."),
                color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { update { toggleFavorite(event, it) } }, Modifier.weight(1f)) {
                    Icon(if (event.id in prefs.favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (event.id in prefs.favorites) t("Αγαπημένο", "Favourite") else t("Αγαπημένα", "Favourite"), maxLines = 1)
                }
                OutlinedButton(onClick = {
                    if (!context.tryStart(calendarIntent(event, t))) toast(t("Δεν βρέθηκε εφαρμογή ημερολογίου.", "No calendar app found."))
                }, Modifier.weight(1f), enabled = future) {
                    Icon(Icons.Rounded.EventAvailable, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(t("Ημερολόγιο", "Calendar"), maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.tryStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, shareText(event, t)), t("Κοινοποίηση", "Share")))
                }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Κοινοποίηση", "Share"), maxLines = 1)
                }
                OutlinedButton(onClick = {
                    context.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.sport24.gr/tvprogram/")))
                }, Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("SPORT24", maxLines = 1)
                }
            }
            if (event.channel !in prefs.hiddenChannels) TextButton(onClick = {
                update { it.copy(hiddenChannels = it.hiddenChannels + event.channel) }
                toast(t("Το «${event.channel}» κρύφτηκε. Επαναφορά: Επιλογές → Κανάλια.", "“${event.channel}” hidden. Undo: Following → Channels."))
                close()
            }) {
                Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(t("Δεν έχω το ${event.channel}", "I don't have ${event.channel}"))
            }
        }
    }
}

/** About: version, data source, privacy, and the GR / EN language switch. */
@Composable fun AboutSheet(prefs: Preferences, update: UpdatePrefs, close: () -> Unit) {
    val t = LocalTxt.current
    val context = LocalContext.current
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }
    ModalBottomSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Logo()
            Text(t("Έκδοση $version · Ανεξάρτητη εφαρμογή", "Version $version · Independent app"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LanguageSwitch(prefs, update)
            HorizontalDivider()
            UpdatePanel()
            HorizontalDivider()
            Text(t("Τι κάνει", "What it does"), style = MaterialTheme.typography.titleMedium)
            Text(t("Το εβδομαδιαίο πρόγραμμα αθλητικών μεταδόσεων στην ελληνική TV, με υπενθυμίσεις για τις ομάδες, τις διοργανώσεις και τα αθλήματα που ακολουθείς — ακόμη και με κλειστή εφαρμογή.",
                "The weekly schedule of sports broadcasts on Greek TV, with reminders for the teams, competitions and sports you follow — even when the app is closed."),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t("Πηγή & ιδιωτικότητα", "Source & privacy"), style = MaterialTheme.typography.titleMedium)
            Text(t("Πρόγραμμα: SPORT24 / 24MEDIA. Οι επιλογές αποθηκεύονται μόνο στη συσκευή. Χωρίς λογαριασμό, διαφημίσεις ή analytics.",
                "Schedule: SPORT24 / 24MEDIA. Your choices stay on this device. No account, ads or analytics."),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t("Δεν είναι επίσημη εφαρμογή του SPORT24. Οι ώρες αφορούν την τηλεοπτική μετάδοση και μπορεί να διαφέρουν από την έναρξη του αγώνα.",
                "Not an official SPORT24 app. Times refer to the TV broadcast and may differ from the actual start of play."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t("Ανάπτυξη: ALU DEV TEAM", "Developed by ALU DEV TEAM"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { context.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.sport24.gr/tvprogram/"))) }) {
                Text(t("Πρόγραμμα στο SPORT24 ↗", "Schedule on SPORT24 ↗"))
            }
        }
    }
}

@Composable fun LanguageSwitch(prefs: Preferences, update: UpdatePrefs) {
    val t = LocalTxt.current
    Column {
        Text(t("Γλώσσα / Language", "Language / Γλώσσα"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("el" to "🇬🇷  Ελληνικά", "en" to "🇬🇧  English").forEachIndexed { i, (code, label) ->
                SegmentedButton(selected = prefs.language == code, onClick = { update { it.copy(language = code) } },
                    shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
    }
}

/** Small pink "♀ Women" chip on cards and in the details sheet. */
@Composable fun WomenBadge() {
    val t = LocalTxt.current
    Surface(color = WomenPink.copy(alpha = .18f), shape = RoundedCornerShape(8.dp)) {
        Text("♀ " + t("Γυναικών", "Women"), Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall, color = WomenPink, fontWeight = FontWeight.Bold)
    }
}
val WomenPink = androidx.compose.ui.graphics.Color(0xFFF06292)

/**
 * The saved profile: men / women + sports. Shared by the schedule and the Following screen,
 * and applied to automatic reminders, so "Ανδρικά + Ποδόσφαιρο" is remembered everywhere.
 */
@Composable fun ProfileFilters(prefs: Preferences, update: UpdatePrefs, sports: List<String>, modifier: Modifier = Modifier,
                               events: List<Broadcast> = emptyList()) {
    val t = LocalTxt.current
    Column(modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Genders.forEachIndexed { i, g ->
                SegmentedButton(selected = prefs.scopeGender == g, onClick = { update { it.copy(scopeGender = g) } },
                    shape = SegmentedButtonDefaults.itemShape(i, Genders.size), icon = {}) {
                    Text(when (g) { "men" -> "♂ " + t.gender(g); "women" -> "♀ " + t.gender(g); else -> "♂♀ " + t("Όλα", "All") }, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(prefs.scopeSports.isEmpty(), { update { it.copy(scopeSports = emptySet()) } }, label = { Text(t("Όλα τα σπορ", "All sports")) }) }
            // Picked sports stay visible even if they have no broadcast this week.
            items((prefs.scopeSports.toList() + sports).distinct(), key = { it }) { key ->
                val on = key in prefs.scopeSports
                FilterChip(on, { update { it.copy(scopeSports = if (key in it.scopeSports) it.scopeSports - key else it.scopeSports + key) } },
                    leadingIcon = { Icon(if (on) Icons.Rounded.Check else sportIcon(key), null, Modifier.size(16.dp)) },
                    label = { Text(t.sport(key, events.firstOrNull { it.sport == key }?.sportLabel)) })
            }
        }
        if (prefs.hasScope) {
            Spacer(Modifier.height(4.dp))
            Text("🔔 " + t("Αποθηκεύτηκε · ισχύει και για τις αυτόματες ειδοποιήσεις. Το κουδουνάκι σε μια μετάδοση ισχύει πάντα.",
                "Saved · also applies to automatic reminders. A bell on a single broadcast always works."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
