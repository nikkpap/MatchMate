@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package gr.matchbell.app

import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.Duration
import java.time.LocalDate

@Composable fun SettingsScreen(
    state: AppSnapshot, update: UpdatePrefs, notificationPermission: () -> Unit,
    exactPermission: () -> Unit, systemSettings: () -> Unit, test: () -> Unit, refresh: () -> Unit,
    toast: (String) -> Unit, openAbout: () -> Unit
) {
    val t = LocalTxt.current
    val p = state.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var custom by rememberSaveable { mutableStateOf("") }
    var reset by rememberSaveable { mutableStateOf(false) }
    val validCustom = custom.toIntOrNull()?.takeIf { it in 0..1440 }

    // Backup: a small JSON file through the system file picker (no storage permission needed).
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = PrefsCodec.exportBackup(p, "0.3.0")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null } catch (_: Exception) { false }
            }
            toast(if (ok) t("Οι ρυθμίσεις αποθηκεύτηκαν σε αρχείο.", "Settings exported.") else t("Η εξαγωγή απέτυχε.", "Export failed."))
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > 1_000_000) null else PrefsCodec.importBackup(String(bytes))
                    }
                } catch (_: Exception) { null }
            }
            if (restored != null) { update { restored }; toast(t("Οι ρυθμίσεις επαναφέρθηκαν.", "Settings restored.")) }
            else toast(t("Το αρχείο δεν είναι αντίγραφο MatchMate.", "That file is not a MatchMate backup."))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text(t("Στον δικό σου χρόνο.", "On your own time."), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(7.dp))
            Text(t("Εσύ διαλέγεις πότε θα χτυπήσει το κουδουνάκι.", "You choose when the bell rings."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SwitchSetting(t("Ειδοποιήσεις", "Notifications"),
                    t("Υπενθυμίσεις για τις επιλογές σου και τις μεταδόσεις με κουδουνάκι.", "Reminders for your selections and belled broadcasts."), p.enabled) { checked ->
                    update { it.copy(enabled = checked) }
                    if (checked && !state.notificationsAllowed) notificationPermission()
                }
                HorizontalDivider(Modifier.padding(vertical = 15.dp))
                PermissionRow(t("Άδεια ειδοποιήσεων", "Notification permission"), state.notificationsAllowed,
                    t("Να εμφανίζονται στο κινητό σου.", "So they appear on your phone."), notificationPermission)
                Spacer(Modifier.height(12.dp))
                PermissionRow(t("Ακριβής χρόνος", "Exact timing"), state.exactAllowed,
                    t("Για υπενθύμιση στην επιλεγμένη ώρα, ακόμη και με κλειστή οθόνη.", "Reminders on time, even with the screen off."), exactPermission)
                if (!state.exactAllowed) {
                    Spacer(Modifier.height(9.dp))
                    Text(t("Χωρίς αυτή την άδεια, το Android μπορεί να καθυστερήσει τις υπενθυμίσεις.", "Without it, Android may delay reminders."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle(t("Πόση ώρα πριν;", "How long before?"),
                    t("Διάλεξε έναν ή περισσότερους χρόνους πριν από την έναρξη της μετάδοσης.", "Pick one or more times before the broadcast starts."))
                Spacer(Modifier.height(13.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    (listOf(0, 5, 10, 15, 30, 60, 120, 180) + p.offsets).distinct().sorted().forEach { minutes ->
                        FilterChip(minutes in p.offsets, {
                            if (minutes in p.offsets && p.offsets.size == 1) toast(t("Χρειάζεται τουλάχιστον ένας χρόνος.", "Keep at least one time."))
                            else update { prefs ->
                                val offsets = if (minutes in prefs.offsets && prefs.offsets.size > 1) prefs.offsets - minutes else prefs.offsets + minutes
                                prefs.copy(offsets = offsets)
                            }
                        }, label = { Text(t.offset(minutes)) }, leadingIcon = if (minutes in p.offsets) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text(t("Δικός σου χρόνος", "Custom time")) }, suffix = { Text(t("λεπτά", "min")) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp),
                        isError = custom.isNotEmpty() && validCustom == null)
                    Spacer(Modifier.width(8.dp))
                    IconButton(enabled = validCustom != null, onClick = {
                        validCustom?.let { value -> update { it.copy(offsets = it.offsets + value) } }; custom = ""
                    }) { Icon(Icons.Rounded.AddCircle, t("Προσθήκη χρόνου", "Add time"), tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.height(7.dp))
                Text(t("Έως 24 ώρες (1440′) πριν. Παραμένει τουλάχιστον ένας χρόνος ενεργός.", "Up to 24 hours (1440′) before. At least one time stays on."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(13.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(13.dp)) {
                    Column(Modifier.padding(13.dp)) {
                        Text(t("ΠΑΡΑΔΕΙΓΜΑ · ΜΕΤΑΔΟΣΗ ΣΤΙΣ 21:00", "EXAMPLE · BROADCAST AT 21:00"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(5.dp))
                        Text(p.offsets.sortedDescending().joinToString("  ·  ") { minutes ->
                            val total = Math.floorMod(21 * 60 - minutes, 1440)
                            "%02d:%02d".format(total / 60, total % 60) + if (minutes > 21 * 60) t(" (προηγ. ημέρα)", " (day before)") else ""
                        }, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SwitchSetting(t("Ώρες ησυχίας", "Quiet hours"),
                    t("Οι υπενθυμίσεις αυτού του διαστήματος παραλείπονται. Ώρες Ελλάδας.", "Reminders in this window are skipped. Greek time."), p.quietEnabled) { value -> update { it.copy(quietEnabled = value) } }
                if (p.quietEnabled) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            TimePickerDialog(context, { _, h, m -> update { it.copy(quietStart = h * 60 + m) } }, p.quietStart / 60, p.quietStart % 60, true).show()
                        }, Modifier.weight(1f)) { Text(t("Από ", "From ") + clock(p.quietStart)) }
                        OutlinedButton(onClick = {
                            TimePickerDialog(context, { _, h, m -> update { it.copy(quietEnd = h * 60 + m) } }, p.quietEnd / 60, p.quietEnd % 60, true).show()
                        }, Modifier.weight(1f)) { Text(t("Έως ", "To ") + clock(p.quietEnd)) }
                    }
                    if (p.quietStart == p.quietEnd) Text(t("Ίδιες ώρες σημαίνουν χωρίς διάστημα ησυχίας.", "Equal times mean no quiet window."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item { AppearancePanel(p, update) }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle(t("Ήχος ειδοποιήσεων", "Notification sound"),
                    t("Οι ήχοι, η δόνηση και οι κατηγορίες ελέγχονται από τις ρυθμίσεις Android.", "Sound, vibration and categories are managed in Android settings."))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = systemSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(t("Ρυθμίσεις ειδοποιήσεων Android", "Android notification settings"))
                }
                Button(onClick = test, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(t("Δοκιμαστική ειδοποίηση", "Test notification"))
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle(t("Ανανέωση προγράμματος", "Schedule refresh"),
                    t("Στο παρασκήνιο μόνο για ενεργές υπενθυμίσεις, όταν υπάρχει σύνδεση και η μπαταρία δεν είναι χαμηλή.",
                        "In the background only for active reminders, when online and battery isn't low."))
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(1L, 3L, 6L, 12L).forEach { hours -> FilterChip(p.refreshHours == hours,
                        { update { it.copy(refreshHours = hours) } }, label = { Text(if (hours == 1L) t("Κάθε ώρα", "Hourly") else t("$hours ώρες", "$hours hours")) }) }
                }
                Spacer(Modifier.height(8.dp))
                Text(t("6 ή 12 ώρες για λιγότερες ανανεώσεις. Οι αποθηκευμένες υπενθυμίσεις λειτουργούν ανεξάρτητα, ακόμη και χωρίς σύνδεση.",
                    "6 or 12 hours saves battery. Saved reminders work on their own, even offline."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.lastSync?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(t("Τελευταία ενημέρωση: ", "Last updated: ") + it.atZone(Athens).format(t.pattern("dd/MM/yyyy HH:mm")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (Duration.between(it, Instant.now()).toHours() >= 12) Text(t("Το πρόγραμμα χρειάζεται ανανέωση.", "The schedule needs a refresh."), color = MaterialTheme.colorScheme.secondary)
                }
                TextButton(onClick = refresh, enabled = !state.loading) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp))
                    Text(if (state.loading) t("Γίνεται ανανέωση…", "Refreshing…") else t("Ανανέωση τώρα", "Refresh now"))
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SwitchSetting(t("Σχετικές εκπομπές", "Related shows"),
                    t("Να περιλαμβάνονται εκπομπές που ταιριάζουν στις επιλογές σου. Μεμονωμένο κουδουνάκι ισχύει πάντα.",
                        "Include shows matching your selections. A single bell always works."), p.includeShows) { value -> update { it.copy(includeShows = value) } }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle(t("Αντίγραφο ασφαλείας", "Backup"),
                    t("Αποθήκευσε ομάδες, διοργανώσεις και ρυθμίσεις σε αρχείο, για νέο κινητό ή επανεγκατάσταση.",
                        "Save teams, competitions and settings to a file — for a new phone or a reinstall."))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exporter.launch("MatchMate-${LocalDate.now(Athens)}.json") }, Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Εξαγωγή", "Export"))
                    }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Εισαγωγή", "Import"))
                    }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle(t("Σχετικά", "About"), t("MatchMate 0.3.0 · Ανεξάρτητη εφαρμογή", "MatchMate 0.3.0 · Independent app"))
                Spacer(Modifier.height(12.dp))
                LanguageSwitch(p, update)
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                UpdatePanel()
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = openAbout) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Περισσότερα για την εφαρμογή", "More about the app"))
                }
                TextButton(onClick = { reset = true }) { Text(t("Επαναφορά επιλογών και ρυθμίσεων", "Reset selections and settings"), color = MaterialTheme.colorScheme.error) }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text(t("Επαναφορά ρυθμίσεων;", "Reset settings?")) },
        text = { Text(t("Θα αφαιρεθούν οι ομάδες, οι διοργανώσεις, τα αγαπημένα και οι υπενθυμίσεις σου. Η γλώσσα και το θέμα παραμένουν.",
            "Your teams, competitions, favourites and reminders will be removed. Language and theme are kept.")) },
        confirmButton = { TextButton(onClick = {
            update { Preferences(theme = it.theme, palette = it.palette, language = it.language) }; reset = false
        }) { Text(t("Επαναφορά", "Reset")) } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text(t("Ακύρωση", "Cancel")) } })
}

@Composable private fun AppearancePanel(p: Preferences, update: UpdatePrefs) {
    val t = LocalTxt.current
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle(t("Εμφάνιση", "Appearance"), t("Οι αλλαγές εφαρμόζονται αμέσως.", "Changes apply instantly."))
        Spacer(Modifier.height(14.dp))
        Text(t("Λειτουργία", "Mode"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("system", t("Σύστημα", "System"), Icons.Rounded.BrightnessAuto),
                Triple("light", t("Φωτεινό", "Light"), Icons.Rounded.LightMode),
                Triple("dark", t("Σκοτεινό", "Dark"), Icons.Rounded.DarkMode),
                Triple("amoled", "AMOLED", Icons.Rounded.Contrast)).forEach { (key, label, icon) ->
                FilterChip(p.theme == key, { update { it.copy(theme = key) } }, label = { Text(label) },
                    leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) })
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(t("Χρώματα", "Colours"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Palettes.forEach { palette ->
                Swatch(p.palette == palette.id, t(palette.nameEl, palette.nameEn),
                    Brush.sweepGradient(listOf(palette.darkPrimary, palette.darkSecondary, palette.lightPrimary, palette.darkPrimary))) {
                    update { it.copy(palette = palette.id) }
                }
            }
            if (DynamicSupported) Swatch(p.palette == "dynamic", "Material You",
                Brush.sweepGradient(listOf(Color(0xFFEF9A9A), Color(0xFFFFF59D), Color(0xFFA5D6A7), Color(0xFF90CAF9), Color(0xFFCE93D8), Color(0xFFEF9A9A)))) {
                update { it.copy(palette = "dynamic") }
            }
        }
        if (DynamicSupported) {
            Spacer(Modifier.height(8.dp))
            Text(t("Το Material You παίρνει χρώματα από την ταπετσαρία σου.", "Material You takes colours from your wallpaper."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun Swatch(selected: Boolean, label: String, brush: Brush, onClick: () -> Unit) {
    // Dp 0 would draw a hairline, so an unselected ring is transparent instead.
    val ring by animateDpAsState(if (selected) 3.dp else 1.dp, label = "ring")
    val ringColor = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Column(Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).border(BorderStroke(ring, ringColor), CircleShape).padding(5.dp)
            .clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable private fun SwitchSetting(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(5.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp)); Switch(checked, onChange)
    }
}
@Composable private fun PermissionRow(title: String, allowed: Boolean, detail: String, action: () -> Unit) {
    val t = LocalTxt.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (allowed) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, Modifier.size(20.dp),
            tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(if (allowed) t("Ενεργή", "Granted") else detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!allowed) TextButton(onClick = action) { Text(t("Ρύθμιση", "Set up")) }
    }
}
private fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
