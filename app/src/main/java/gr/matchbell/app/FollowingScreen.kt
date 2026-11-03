@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package gr.matchbell.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun FollowingScreen(state: AppSnapshot, update: UpdatePrefs) {
    val t = LocalTxt.current
    var section by rememberSaveable { mutableIntStateOf(0) }
    var teamSport by rememberSaveable { mutableStateOf("all") }
    var customName by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val prefs = state.prefs
    val availableSports = remember(state.events) { LinkedHashMap(SelectableSports).apply {
        state.events.filterNot { it.isShow }.forEach { put(it.sport, it.sportLabel) }
    } }
    val competitions = remember(state.events, prefs.includeShows) {
        state.events.filter { !it.isShow || prefs.includeShows }.flatMap { it.competitions }
            .distinctBy { it.key }.sortedBy { normalized(it.label) }
    }
    // Channels seen in the programme + any hidden one no longer in it (so it can still be restored).
    val channels = remember(state.events, prefs.hiddenChannels) {
        val counts = state.events.groupingBy { it.channel }.eachCount()
        (counts.keys + prefs.hiddenChannels).distinct().sortedBy { normalized(it) }.map { it to (counts[it] ?: 0) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text(t("Το δικό σου παιχνίδι.", "Your own game."), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(7.dp))
            Text(t("Διάλεξε όσα σε ενδιαφέρουν. Θα ειδοποιείσαι για οποιαδήποτε από τις επιλογές σου.",
                "Pick what you care about. You'll be reminded about any of your selections."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            val counts = listOf(prefs.teams.size, prefs.competitions.size, prefs.sports.size, prefs.hiddenChannels.size)
            ScrollableTabRow(selectedTabIndex = section, edgePadding = 0.dp, containerColor = MaterialTheme.colorScheme.background,
                divider = {}) {
                listOf(t("Ομάδες", "Teams"), t("Διοργανώσεις", "Competitions"), t("Αθλήματα", "Sports"), t("Κανάλια", "Channels"))
                    .forEachIndexed { index, label ->
                        Tab(selected = section == index, onClick = { section = index; query = "" }, text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label)
                                if (counts[index] > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Badge(containerColor = if (index == 3) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary) {
                                        Text("${counts[index]}")
                                    }
                                }
                            }
                        })
                    }
            }
        }
        when (section) {
            0 -> {
                item {
                    SectionTitle(t("Οι ομάδες μου", "My teams"),
                        t("Διάλεξε πρώτα άθλημα και μετά ομάδα. Η επιλογή «Όλα» καλύπτει όλα τα τμήματα που αναγνωρίζονται στον τίτλο.",
                            "Pick a sport first, then a team. “All” covers every section recognised in the title."))
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(listOf("all", "podosfairo", "basket", "volley", "handball", "polo")) { key ->
                            FilterChip(teamSport == key, { teamSport = key }, label = { Text(if (key == "all") t("Όλα", "All") else t.sport(key)) },
                                leadingIcon = if (key == "all") null else ({ Icon(sportIcon(key), null, Modifier.size(16.dp)) }))
                        }
                    }
                }
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            CommonTeams.forEach { name ->
                                val rule = remember(name, teamSport) { TeamRule(name, teamSport) }
                                val selected = prefs.teams.any { it.key == rule.key }
                                FilterChip(selected, {
                                    update { p -> p.copy(teams = if (p.teams.any { it.key == rule.key }) p.teams.filterNot { it.key == rule.key } else p.teams + rule) }
                                }, label = { Text(name) }, leadingIcon = if (selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
                            }
                        }
                        Spacer(Modifier.height(15.dp))
                        Text(t("Άλλη ομάδα", "Another team"), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(t("Γράψε το όνομα όπως εμφανίζεται στο πρόγραμμα. Π.χ. «ΠΑΟΚ Β» για τη δεύτερη ομάδα.",
                            "Type the name as it appears in the schedule (usually Greek), e.g. “ΠΑΟΚ Β” for the reserve team."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        val addCustom = {
                            val rule = TeamRule(customName.trim(), teamSport)
                            update { p -> p.copy(teams = (p.teams + rule).distinctBy { it.key }) }
                            customName = ""
                        }
                        OutlinedTextField(customName, { customName = it.take(80) }, label = { Text(t("Όνομα ομάδας", "Team name")) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                            trailingIcon = { IconButton(enabled = normalized(customName).isNotBlank(), onClick = addCustom) {
                                Icon(Icons.Rounded.AddCircle, t("Προσθήκη ομάδας", "Add team"), tint = MaterialTheme.colorScheme.primary) } })
                        // Live preview: how many broadcasts this name would match right now.
                        if (normalized(customName).isNotBlank()) {
                            val preview = remember(customName, teamSport, state.events) {
                                val rule = TeamRule(customName.trim(), teamSport); state.events.count { teamMatches(rule, it) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(t("Ταιριάζει με $preview μεταδόσεις αυτής της εβδομάδας.", "Matches $preview broadcasts this week."),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (preview > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                if (prefs.teams.isNotEmpty()) item {
                    SectionTitle(t("Ακολουθείς ${prefs.teams.size} επιλογές", "Following ${prefs.teams.size}"))
                    Spacer(Modifier.height(9.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        prefs.teams.forEach { rule ->
                            InputChip(true, { update { p -> p.copy(teams = p.teams.filterNot { it.key == rule.key }) } },
                                label = { Text(t.teamLabel(rule)) },
                                trailingIcon = { Icon(Icons.Rounded.Close, t("Αφαίρεση ", "Remove ") + rule.name, Modifier.size(15.dp)) })
                        }
                    }
                }
            }
            1 -> {
                item {
                    SectionTitle(t("Οι διοργανώσεις μου", "My competitions"),
                        t("Οι διαθέσιμες επιλογές προκύπτουν από το τρέχον πρόγραμμα. Η επιλογή σου καλύπτει όλες τις ομάδες της διοργάνωσης.",
                            "Options come from the current schedule. A competition covers all of its teams."))
                    Spacer(Modifier.height(12.dp))
                    SearchField(query, { query = it }, t("Αναζήτηση διοργάνωσης", "Search competitions"))
                }
                if (prefs.competitions.isNotEmpty()) item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(t("Οι επιλογές σου", "Your picks"), style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            prefs.competitions.sorted().forEach { key ->
                                InputChip(true, { update { it.copy(competitions = it.competitions - key) } },
                                    label = { Text(competitions.firstOrNull { it.key == key }?.label ?: key) },
                                    trailingIcon = { Icon(Icons.Rounded.Close, t("Αφαίρεση διοργάνωσης", "Remove competition"), Modifier.size(15.dp)) })
                            }
                        }
                    }
                }
                if (competitions.isEmpty()) item {
                    EmptyState(Icons.Rounded.EmojiEvents, t("Περιμένουμε το πρόγραμμα", "Waiting for the schedule"),
                        t("Ανανέωσε τις μεταδόσεις στην αρχική οθόνη για να εμφανιστούν οι διοργανώσεις.", "Refresh the schedule on the home screen to see competitions."))
                }
                items(competitions.filter { normalized(it.label).contains(normalized(query)) }, key = { it.key }) { tag ->
                    val checked = tag.key in prefs.competitions
                    SelectRow(Icons.Rounded.EmojiEvents, tag.label, null, checked) {
                        update { it.copy(competitions = if (tag.key in it.competitions) it.competitions - tag.key else it.competitions + tag.key) }
                    }
                }
            }
            2 -> {
                item {
                    SectionTitle(t("Τα αθλήματά μου", "My sports"),
                        t("Μια επιλογή εδώ καλύπτει όλες τις αντίστοιχες μεταδόσεις. Formula 1 και MotoGP επιλέγονται και χωριστά.",
                            "A sport covers all of its broadcasts. Formula 1 and MotoGP can be picked separately."))
                }
                items(availableSports.entries.toList(), key = { it.key }) { (key, label) ->
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(sportIcon(key), null, Modifier.size(25.dp), tint = sportAccent(key))
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.sport(key, label), style = MaterialTheme.typography.titleMedium)
                                if (key == "mixanokinitos-athlitismos") Text(t("Περιλαμβάνει F1, MotoGP και άλλες σειρές", "Includes F1, MotoGP and other series"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(key in prefs.sports, { checked -> update { it.copy(sports = if (checked) it.sports + key else it.sports - key) } })
                        }
                    }
                }
            }
            3 -> {
                item {
                    SectionTitle(t("Τα κανάλια μου", "My channels"),
                        t("Ξετσέκαρε όσα κανάλια δεν έχεις (π.χ. συνδρομητικά). Κρύβονται από το πρόγραμμα και δεν δίνουν αυτόματες υπενθυμίσεις. Το μεμονωμένο κουδουνάκι ισχύει πάντα.",
                            "Untick channels you don't have (e.g. pay-TV). They're hidden from the schedule and skip automatic reminders. A single bell always works."))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { update { it.copy(hiddenChannels = emptySet()) } }, enabled = prefs.hiddenChannels.isNotEmpty()) {
                            Text(t("Εμφάνιση όλων", "Show all"))
                        }
                    }
                }
                if (channels.isEmpty()) item {
                    EmptyState(Icons.Rounded.Tv, t("Περιμένουμε το πρόγραμμα", "Waiting for the schedule"),
                        t("Ανανέωσε τις μεταδόσεις για να εμφανιστούν τα κανάλια.", "Refresh the schedule to see channels."))
                }
                items(channels, key = { it.first }) { (channel, count) ->
                    val visible = channel !in prefs.hiddenChannels
                    SelectRow(Icons.Rounded.Tv, channel, t("$count μεταδόσεις", "$count broadcasts"), visible) {
                        update { it.copy(hiddenChannels = if (channel in it.hiddenChannels) it.hiddenChannels - channel else it.hiddenChannels + channel) }
                    }
                }
            }
        }
        item {
            Text(t("Οι ομάδες αναγνωρίζονται από τον τίτλο της μετάδοσης. Αν μια εγγραφή δεν αντιστοιχιστεί, μπορείς πάντα να πατήσεις το κουδουνάκι της.",
                "Teams are recognised from the broadcast title. If one isn't matched, you can always tap its bell."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(25.dp))
        }
    }
}

@Composable private fun SearchField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onChange, label = { Text(label) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Clear, null) } }) else null,
        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(15.dp))
}

/** Whole row is tappable, not only the checkbox. */
@Composable private fun SelectRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).clickable(onClick = onToggle)) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(21.dp), tint = if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked, { onToggle() })
        }
    }
}
