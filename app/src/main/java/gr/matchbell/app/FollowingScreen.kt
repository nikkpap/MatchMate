@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package gr.matchbell.app

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun FollowingScreen(state: AppSnapshot, update: UpdatePrefs) {
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Το δικό σου παιχνίδι.", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(7.dp))
            Text("Διάλεξε όσα σε ενδιαφέρουν. Θα ειδοποιείσαι για οποιαδήποτε από τις επιλογές σου.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Ομάδες", "Διοργανώσεις", "Αθλήματα").forEachIndexed { index, label ->
                    SegmentedButton(selected = section == index, onClick = { section = index }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        when (section) {
            0 -> {
                item {
                    SectionTitle("Οι ομάδες μου", "Διάλεξε πρώτα άθλημα και μετά ομάδα. Η επιλογή «Όλα» καλύπτει όλα τα τμήματα που αναγνωρίζονται στον τίτλο.")
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(listOf("all" to "Όλα", "podosfairo" to "Ποδόσφαιρο", "basket" to "Μπάσκετ", "volley" to "Βόλεϊ", "handball" to "Χάντμπολ", "polo" to "Πόλο")) { (key, label) ->
                            FilterChip(teamSport == key, { teamSport = key }, label = { Text(label) })
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
                        Text("Άλλη ομάδα", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Γράψε το όνομα όπως εμφανίζεται στο πρόγραμμα. Π.χ. «ΠΑΟΚ Β» για τη δεύτερη ομάδα.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(customName, { customName = it.take(80) }, label = { Text("Όνομα ομάδας") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                        TextButton(enabled = normalized(customName).isNotBlank(), onClick = {
                            val rule = TeamRule(customName.trim(), teamSport)
                            update { p -> p.copy(teams = (p.teams + rule).distinctBy { it.key }) }
                            customName = ""
                        }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("Προσθήκη ομάδας") }
                    }
                }
                if (prefs.teams.isNotEmpty()) item {
                    SectionTitle("Ακολουθείς ${prefs.teams.size} επιλογές")
                    Spacer(Modifier.height(9.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        prefs.teams.forEach { rule ->
                            InputChip(true, { update { p -> p.copy(teams = p.teams.filterNot { it.key == rule.key }) } },
                                label = { Text(rule.label) }, trailingIcon = { Icon(Icons.Rounded.Close, "Αφαίρεση ${rule.label}", Modifier.size(15.dp)) })
                        }
                    }
                }
            }
            1 -> {
                item {
                    SectionTitle("Οι διοργανώσεις μου", "Οι διαθέσιμες επιλογές προκύπτουν από το τρέχον πρόγραμμα. Η επιλογή σου καλύπτει όλες τις ομάδες της διοργάνωσης.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(query, { query = it }, label = { Text("Αναζήτηση διοργάνωσης") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(15.dp))
                }
                if (prefs.competitions.isNotEmpty()) item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text("Οι επιλογές σου", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            prefs.competitions.sorted().forEach { key ->
                                InputChip(true, { update { it.copy(competitions = it.competitions - key) } },
                                    label = { Text(competitions.firstOrNull { it.key == key }?.label ?: key) },
                                    trailingIcon = { Icon(Icons.Rounded.Close, "Αφαίρεση διοργάνωσης", Modifier.size(15.dp)) })
                            }
                        }
                    }
                }
                if (competitions.isEmpty()) item {
                    EmptyState(Icons.Rounded.EmojiEvents, "Περιμένουμε το πρόγραμμα", "Ανανέωσε τις μεταδόσεις στην αρχική οθόνη για να εμφανιστούν οι διοργανώσεις.")
                }
                items(competitions.filter { normalized(it.label).contains(normalized(query)) }, key = { it.key }) { tag ->
                    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.EmojiEvents, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(12.dp))
                            Text(tag.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Checkbox(tag.key in prefs.competitions, { checked -> update { it.copy(competitions = if (checked) it.competitions + tag.key else it.competitions - tag.key) } })
                        }
                    }
                }
            }
            2 -> {
                item { SectionTitle("Τα αθλήματά μου", "Μια επιλογή εδώ καλύπτει όλες τις αντίστοιχες μεταδόσεις. Formula 1 και MotoGP επιλέγονται και χωριστά.") }
                items(availableSports.entries.toList(), key = { it.key }) { (key, label) ->
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(sportIcon(key), null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                                if (key == "mixanokinitos-athlitismos") Text("Περιλαμβάνει F1, MotoGP και άλλες σειρές", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(key in prefs.sports, { checked -> update { it.copy(sports = if (checked) it.sports + key else it.sports - key) } })
                        }
                    }
                }
            }
        }
        item {
            Text("Οι ομάδες αναγνωρίζονται από τον τίτλο της μετάδοσης. Αν μια εγγραφή δεν αντιστοιχιστεί, μπορείς πάντα να πατήσεις το κουδουνάκι της.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(25.dp))
        }
    }
}
