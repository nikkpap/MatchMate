@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package gr.matchbell.app

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.Duration
import java.time.format.DateTimeFormatter

@Composable fun SettingsScreen(
    state: AppSnapshot, update: UpdatePrefs, notificationPermission: () -> Unit,
    exactPermission: () -> Unit, systemSettings: () -> Unit, test: () -> Unit, refresh: () -> Unit
) {
    val p = state.prefs
    val context = LocalContext.current
    var custom by rememberSaveable { mutableStateOf("") }
    var reset by rememberSaveable { mutableStateOf(false) }
    val validCustom = custom.toIntOrNull()?.takeIf { it in 0..1440 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Στον δικό σου χρόνο.", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(7.dp))
            Text("Εσύ διαλέγεις πότε θα χτυπήσει το κουδουνάκι.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SwitchSetting("Ειδοποιήσεις", "Υπενθυμίσεις για τις επιλογές σου και τις μεταδόσεις με κουδουνάκι.", p.enabled) { checked ->
                    update { it.copy(enabled = checked) }
                    if (checked && !state.notificationsAllowed) notificationPermission()
                }
                HorizontalDivider(Modifier.padding(vertical = 15.dp))
                PermissionRow("Άδεια ειδοποιήσεων", state.notificationsAllowed, "Να εμφανίζονται στο κινητό σου.", notificationPermission)
                Spacer(Modifier.height(12.dp))
                PermissionRow("Ακριβής χρόνος", state.exactAllowed, "Για υπενθύμιση στην επιλεγμένη ώρα, ακόμη και με κλειστή οθόνη.", exactPermission)
                if (!state.exactAllowed) {
                    Spacer(Modifier.height(9.dp))
                    Text("Χωρίς αυτή την άδεια, το Android μπορεί να καθυστερήσει τις υπενθυμίσεις.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Πόση ώρα πριν;", "Διάλεξε έναν ή περισσότερους χρόνους πριν από την έναρξη της μετάδοσης.")
                Spacer(Modifier.height(13.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    (listOf(0, 5, 10, 15, 30, 60, 120, 180) + p.offsets).distinct().sorted().forEach { minutes ->
                        FilterChip(minutes in p.offsets, {
                            update { prefs ->
                                val offsets = if (minutes in prefs.offsets && prefs.offsets.size > 1) prefs.offsets - minutes else prefs.offsets + minutes
                                prefs.copy(offsets = offsets)
                            }
                        }, label = { Text(offsetLabel(minutes)) }, leadingIcon = if (minutes in p.offsets) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text("Δικός σου χρόνος") }, suffix = { Text("λεπτά") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp),
                        isError = custom.isNotEmpty() && validCustom == null)
                    Spacer(Modifier.width(8.dp))
                    IconButton(enabled = validCustom != null, onClick = {
                        validCustom?.let { value -> update { it.copy(offsets = it.offsets + value) } }; custom = ""
                    }) { Icon(Icons.Rounded.AddCircle, "Προσθήκη χρόνου", tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.height(7.dp))
                Text("Έως 24 ώρες πριν. Παραμένει τουλάχιστον ένας χρόνος ενεργός.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(13.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(13.dp)) {
                    Column(Modifier.padding(13.dp)) {
                        Text("ΠΑΡΑΔΕΙΓΜΑ · ΜΕΤΑΔΟΣΗ ΣΤΙΣ 21:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(5.dp))
                        Text(p.offsets.sortedDescending().joinToString("  ·  ") { minutes ->
                            val total = Math.floorMod(21 * 60 - minutes, 1440)
                            "%02d:%02d".format(total / 60, total % 60) + if (minutes > 21 * 60) " (προηγ. ημέρα)" else ""
                        }, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Αν προσθέσεις επιλογή αφού περάσει μια ώρα υπενθύμισης, θα προγραμματιστούν μόνο οι επόμενες.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SwitchSetting("Ώρες ησυχίας", "Οι υπενθυμίσεις αυτού του διαστήματος παραλείπονται. Ώρες Ελλάδας.", p.quietEnabled) { value -> update { it.copy(quietEnabled = value) } }
                if (p.quietEnabled) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            TimePickerDialog(context, { _, h, m -> update { it.copy(quietStart = h * 60 + m) } }, p.quietStart / 60, p.quietStart % 60, true).show()
                        }, Modifier.weight(1f)) { Text("Από ${clock(p.quietStart)}") }
                        OutlinedButton(onClick = {
                            TimePickerDialog(context, { _, h, m -> update { it.copy(quietEnd = h * 60 + m) } }, p.quietEnd / 60, p.quietEnd % 60, true).show()
                        }, Modifier.weight(1f)) { Text("Έως ${clock(p.quietEnd)}") }
                    }
                    if (p.quietStart == p.quietEnd) Text("Ίδιες ώρες σημαίνουν χωρίς διάστημα ησυχίας.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Ήχος και εμφάνιση", "Οι ήχοι, η δόνηση και οι κατηγορίες ελέγχονται από τις ρυθμίσεις Android.")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = systemSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Ρυθμίσεις ειδοποιήσεων Android")
                }
                Button(onClick = test, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Δοκιμαστική ειδοποίηση")
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Θέμα εφαρμογής", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("dark" to "Σκοτεινό", "light" to "Φωτεινό", "system" to "Σύστημα").forEach { (key, label) ->
                        FilterChip(p.theme == key, { update { it.copy(theme = key) } }, label = { Text(label) })
                    }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Ανανέωση προγράμματος", "Στο παρασκήνιο μόνο για ενεργές υπενθυμίσεις, όταν υπάρχει σύνδεση και η μπαταρία δεν είναι χαμηλή.")
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(1L, 3L, 6L, 12L).forEach { hours -> FilterChip(p.refreshHours == hours,
                        { update { it.copy(refreshHours = hours) } }, label = { Text(if (hours == 1L) "Κάθε ώρα" else "$hours ώρες") }) }
                }
                Spacer(Modifier.height(8.dp))
                Text("6 ή 12 ώρες για λιγότερες ανανεώσεις. Οι αποθηκευμένες υπενθυμίσεις λειτουργούν ανεξάρτητα, ακόμη και χωρίς σύνδεση.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.lastSync?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("Τελευταία ενημέρωση: ${it.atZone(Athens).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (Duration.between(it, Instant.now()).toHours() >= 12) Text("Το πρόγραμμα χρειάζεται ανανέωση.", color = MaterialTheme.colorScheme.secondary)
                }
                TextButton(onClick = refresh, enabled = !state.loading) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(if (state.loading) "Γίνεται ανανέωση…" else "Ανανέωση τώρα")
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SwitchSetting("Σχετικές εκπομπές", "Να περιλαμβάνονται εκπομπές που ταιριάζουν στις επιλογές σου. Μεμονωμένο κουδουνάκι ισχύει πάντα.", p.includeShows) { value -> update { it.copy(includeShows = value) } }
                Spacer(Modifier.height(12.dp))
                Text("Οι αλλαγές ώρας ή η αφαίρεση μιας μετάδοσης ενημερώνουν τις υπενθυμίσεις στην επόμενη επιτυχή ανανέωση.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("MatchMate", "Έκδοση 0.2.0 · Ανεξάρτητη εφαρμογή")
                Spacer(Modifier.height(9.dp))
                Text("Πρόγραμμα: SPORT24 / 24MEDIA. Οι επιλογές αποθηκεύονται μόνο στη συσκευή. Χωρίς λογαριασμό, διαφημίσεις ή analytics.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text("Δεν είναι επίσημη εφαρμογή του SPORT24. Οι πληροφορίες αφορούν τηλεοπτικές μεταδόσεις. Δεν υποστηρίζεται φίλτρο «μόνο live», επειδή η αντίστοιχη ένδειξη της πηγής δεν έχει επαληθευτεί.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { reset = true }) { Text("Επαναφορά επιλογών και ρυθμίσεων", color = MaterialTheme.colorScheme.error) }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Επαναφορά ρυθμίσεων;") },
        text = { Text("Θα αφαιρεθούν οι ομάδες, οι διοργανώσεις, τα αγαπημένα και οι υπενθυμίσεις σου.") },
        confirmButton = { TextButton(onClick = { update { Preferences() }; reset = false }) { Text("Επαναφορά") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("Ακύρωση") } })
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (allowed) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, Modifier.size(20.dp),
            tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(if (allowed) "Ενεργή" else detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!allowed) TextButton(onClick = action) { Text("Ρύθμιση") }
    }
}
private fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
private fun offsetLabel(minutes: Int): String = when {
    minutes == 0 -> "Στην έναρξη"
    minutes == 60 -> "1 ώρα"
    minutes == 120 -> "2 ώρες"
    minutes == 180 -> "3 ώρες"
    minutes == 1440 -> "1 ημέρα"
    else -> "$minutes′"
}
