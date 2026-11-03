package gr.matchbell.app

import java.text.Normalizer
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

val Athens: ZoneId = ZoneId.of("Europe/Athens")
val Greek: Locale = Locale.forLanguageTag("el-GR")
val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
val FeedDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private val CombiningMarks = Regex("\\p{M}+")
private val NonLetters = Regex("[^\\p{L}\\p{N}]+")
private val Season = Regex("\\b20\\d{2}\\s*[-/]\\s*(?:20)?\\d{2}\\b")
private val Whitespace = Regex("\\s+")
private val Sides = Regex("\\s+[-–—]\\s+|\\s+[vV][sS]\\.?\\s+")
private val FormulaOne = Regex("(?<![\\p{L}\\p{N}])(formula 1|formula one|f1|φορμουλα 1)(?![\\p{L}\\p{N}])")
private val MotoGP = Regex("\\b(motogp|moto gp)\\b")
/**
 * Women's sport markers, checked on the normalised title + tag labels (not the description,
 * which can mention other teams). Confirmed on the real SPORT24 feed (Nov 2026):
 * "Ευρωλίγκα Γυναικών", "Α1 Γυναικών Volley League", "UEFA Champions League Γυναικών",
 * "Πρωτάθλημα Α1 Γυναικών", titles "WTA 250 2026" / "WTA Finals". Men's events carry no marker
 * or "Ανδρών" / "ATP", so anything without a women's marker counts as men's.
 */
private const val NotLetterBefore = "(?<![\\p{L}\\p{N}])"
private const val NotLetterAfter = "(?![\\p{L}\\p{N}])"
private val WomenMarker = Regex(NotLetterBefore + "(γυναικ\\p{L}*|κορασιδ\\p{L}*|νεανιδ\\p{L}*|wta|wnba|nwsl|wsl|women\\p{L}*|ladies|liga f|feminin\\p{L}*|femenin\\p{L}*|femminile|frauen\\p{L}*)" + NotLetterAfter)
private val WomenInLabel = Regex(NotLetterBefore + "(γυναικ\\p{L}*|women\\p{L}*|wta|wnba)" + NotLetterAfter)

fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(CombiningMarks, "").lowercase(Locale.ROOT)
    .replace('ς', 'σ').replace(NonLetters, " ").trim()

data class Tag(val key: String, val label: String)
data class Broadcast(
    val id: String,
    val startsAt: Instant,
    val title: String,
    val channel: String,
    val tags: List<Tag>,
    val description: String = ""
) {
    val local: ZonedDateTime = startsAt.atZone(Athens)
    val sport: String = tags.firstOrNull()?.key ?: "other"
    val sportLabel: String = SportLabels[sport] ?: tags.firstOrNull()?.label ?: "Άλλα αθλήματα"
    val isShow: Boolean = sport == "show"
    /** True for women's competitions; see [WomenMarker]. */
    val isWomen: Boolean = !isShow && WomenMarker.containsMatchIn(normalized(title + " " + tags.joinToString(" ") { it.label }))
    /** "women" or "men"; shows have no gender. */
    val gender: String = if (isWomen) "women" else "men"
    val competitions: List<Tag> = tags.drop(1).filter {
        it.key !in SportLabels && normalized(it.key) !in setOf("live", "agonas", "αγωνασ")
    }.map {
        // Keep a women's competition apart from the men's one even if the tag forgot to say so.
        val label = competitionLabel(it.label).let { l -> if (isWomen && !WomenInLabel.containsMatchIn(normalized(l))) "$l (Γυναικών)" else l }
        Tag(competitionKey(label), label)
    }.distinctBy { it.key }
    val series: String? = run {
        val text = normalized(title + " " + tags.joinToString { it.label })
        when {
            FormulaOne.containsMatchIn(text) -> "f1"
            MotoGP.containsMatchIn(text) -> "motogp"
            else -> null
        }
    }
    val localDay: String = local.toLocalDate().toString()
    val teamSides: Set<String> = title.split(Sides).map(::normalized).toSet()
    /** Team names as written ("ΠΑΟΚ", "Ρόμα"); empty when the title is not "A - B". */
    val teamNames: List<String> = title.split(Sides).map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size == 2 } ?: emptyList()
    val showTeamSides: Set<String> = if (isShow) teamSides + tags.flatMap { it.label.split(Sides) }.map(::normalized) else teamSides
    val searchText: String = normalized(title + " " + channel + " " + competitions.joinToString { it.label } +
        if (isWomen) " γυναικων γυναικεια women" else "")
    // Distinct broadcasts stay distinct: the feed has no reliable match ID.
    val identity: String get() = id
}

val SportLabels = linkedMapOf(
    "podosfairo" to "Ποδόσφαιρο", "basket" to "Μπάσκετ", "tennis" to "Τένις",
    "volley" to "Βόλεϊ", "handball" to "Χάντμπολ", "polo" to "Πόλο",
    "mixanokinitos-athlitismos" to "Μηχανοκίνητα", "stivos" to "Στίβος",
    "polemikes-texnes" to "Πολεμικές τέχνες", "podosfairo_salas" to "Ποδόσφαιρο σάλας",
    "show" to "Εκπομπές", "other" to "Άλλα αθλήματα"
)
val SelectableSports = SportLabels.filterKeys { it != "show" }.toMutableMap().apply {
    put("f1", "Formula 1"); put("motogp", "MotoGP")
}
fun sportTag(key: String, label: String): String {
    if (key in SportLabels) return key
    return when (normalized(label)) {
        "ποδοσφαιρο" -> "podosfairo"
        "μπασκετ" -> "basket"
        "τενισ" -> "tennis"
        "βολει" -> "volley"
        "χαντμπολ" -> "handball"
        "πολο" -> "polo"
        "μηχανοκινητα", "motorsports" -> "mixanokinitos-athlitismos"
        "στιβοσ" -> "stivos"
        "εκπομπη", "εκπομπεσ" -> "show"
        else -> key
    }
}
fun competitionLabel(value: String): String = value
    .replace(Season, "")
    .replace(Whitespace, " ").trim().trim('-', '/', ' ')
fun competitionKey(value: String): String = normalized(competitionLabel(value))

/** [gender]: "all", "men" or "women" — e.g. follow only ΠΑΟΚ's men's or women's teams. */
data class TeamRule(val name: String, val sport: String = "all", val gender: String = "all") {
    internal val aliases: Set<String> = (TeamAliases[normalized(name)] ?: listOf(name)).map(::normalized).toSet()
    // "all" keeps the 0.3.0 key, so older saved selections stay identical.
    val key: String = "${normalized(name)}|$sport" + if (gender == "all") "" else "|$gender"
    val label: String = name + (if (sport == "all") " · Όλα" else " · ${SportLabels[sport] ?: sport}") +
        when (gender) { "men" -> " · Ανδρών"; "women" -> " · Γυναικών"; else -> "" }
}
val Genders = listOf("all", "men", "women")
fun genderMatches(filter: String, e: Broadcast): Boolean = when (filter) {
    "women" -> e.isWomen
    "men" -> !e.isWomen
    else -> true
}

/** Sport selections are stored as "basket" (all) or "basket@women" / "basket@men". */
fun sportSelection(key: String, gender: String) = if (gender == "all") key else "$key@$gender"
fun selectionSport(entry: String) = entry.substringBefore('@')
fun selectionGender(entry: String) = entry.substringAfter('@', "all")
val CommonTeams = listOf("ΠΑΟΚ", "Ολυμπιακός", "Παναθηναϊκός", "ΑΕΚ", "Άρης", "ΟΦΗ",
    "Ατρόμητος", "Αστέρας Τρίπολης", "Λίβερπουλ", "Μάντσεστερ Σίτι", "Ρεάλ Μαδρίτης",
    "Μπαρτσελόνα", "Μπάγερν Μονάχου", "Γιουβέντους", "Μιλγουόκι Μπακς", "Λος Άντζελες Λέικερς")
private val TeamAliases = mapOf(
    "παοκ" to listOf("ΠΑΟΚ", "PAOK", "ΠΑΟΚ mateco"),
    "παναθηναικοσ" to listOf("Παναθηναϊκός", "Panathinaikos", "Παναθηναϊκός AKTOR"),
    "ολυμπιακοσ" to listOf("Ολυμπιακός", "Olympiacos", "Olympiakos"),
    "αεκ" to listOf("ΑΕΚ", "AEK", "ΑΕΚ Betsson"),
    "αρησ" to listOf("Άρης", "Aris", "Άρης Midea"),
    "ρεαλ μαδριτησ" to listOf("Ρεάλ Μαδρίτης", "Real Madrid"),
    "μπαρτσελονα" to listOf("Μπαρτσελόνα", "Barcelona", "FC Barcelona")
)
fun teamMatches(rule: TeamRule, event: Broadcast, allowShows: Boolean = false): Boolean {
    if (rule.sport != "all" && event.sport != rule.sport) return false
    if (event.isShow && !allowShows) return false
    if (!event.isShow && !genderMatches(rule.gender, event)) return false
    val sides = if (event.isShow && allowShows) event.showTeamSides else event.teamSides
    return sides.any { it in rule.aliases }
}

data class Preferences(
    val teams: List<TeamRule> = emptyList(),
    val sports: Set<String> = emptySet(),
    val competitions: Set<String> = emptySet(),
    val manual: Set<String> = emptySet(),
    val muted: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val offsets: Set<Int> = setOf(30),
    val quietEnabled: Boolean = false,
    val quietStart: Int = 23 * 60,
    val quietEnd: Int = 8 * 60,
    val includeShows: Boolean = false,
    val refreshHours: Long = 6,
    /** dark · light · system · amoled */
    val theme: String = "dark",
    /** Colour palette id, see [Palettes]; "dynamic" = Material You on Android 12+. */
    val palette: String = "mint",
    /** el · en */
    val language: String = "el",
    /** TV channels the user cannot watch. Hidden from the schedule and automatic reminders. */
    val hiddenChannels: Set<String> = emptySet(),
    /**
     * Saved profile "what I watch": applies to the schedule AND to automatic reminders.
     * scopeGender: all / men / women. scopeSports: empty = every sport, else e.g. {"podosfairo"}.
     */
    val scopeGender: String = "all",
    val scopeSports: Set<String> = emptySet(),
    /** Last schedule view (all / mine / stars), remembered between launches. */
    val programmeView: String = "all",
    /**
     * Optional team filter per competition: competition key -> team names.
     * Missing / empty = every team of that competition (the 0.3 behaviour).
     */
    val competitionTeams: Map<String, Set<String>> = emptyMap()
) {
    private val competitionRules: Map<String, List<TeamRule>> =
        competitionTeams.filterValues { it.isNotEmpty() }.mapValues { (_, names) -> names.map { TeamRule(it) } }
    /** Competition selected, and (if a team filter exists) one of its teams plays. */
    fun competitionMatches(e: Broadcast): Boolean = e.competitions.any { c ->
        c.key in competitions && (competitionRules[c.key]?.any { teamMatches(it, e, includeShows) } ?: true)
    }
    fun inScope(e: Broadcast): Boolean =
        (scopeSports.isEmpty() || e.isShow || e.sport in scopeSports || e.series in scopeSports) &&
            (scopeGender == "all" || (!e.isShow && genderMatches(scopeGender, e)) || (e.isShow && scopeGender != "women"))
    val hasScope: Boolean get() = scopeGender != "all" || scopeSports.isNotEmpty()
    fun channelVisible(e: Broadcast): Boolean = e.channel !in hiddenChannels
    fun interested(e: Broadcast): Boolean = e.id in manual ||
        (channelVisible(e) && inScope(e) && (!e.isShow || includeShows) && (teams.any { teamMatches(it, e, includeShows) } || sportSelected(e.sport, e) ||
            e.series?.let { sportSelected(it, e) } == true || competitionMatches(e)))
    private fun sportSelected(key: String, e: Broadcast): Boolean =
        key in sports || (!e.isShow && "$key@${e.gender}" in sports)
    /** The gender chosen for a sport: "all" / "men" / "women", or null when the sport is off. */
    fun sportGender(key: String): String? = sports.firstOrNull { selectionSport(it) == key }?.let(::selectionGender)
    fun wantsReminder(e: Broadcast): Boolean = enabled && interested(e) && e.id !in muted &&
        (!e.isShow || includeShows || e.id in manual)
    /** What the bell icon shows: same rule as [wantsReminder] without the global pause. */
    fun bellOn(e: Broadcast): Boolean = interested(e) && e.id !in muted
    val hasSelections: Boolean get() = teams.isNotEmpty() || sports.isNotEmpty() || competitions.isNotEmpty() || manual.isNotEmpty()

    /**
     * Per-broadcast IDs (manual / muted / favourites) used to grow forever.
     * After a successful download, keep only IDs still present in the programme.
     */
    fun pruned(events: List<Broadcast>): Preferences {
        if (events.isEmpty()) return this
        val ids = events.mapTo(HashSet()) { it.id }
        val next = copy(manual = manual.filterTo(HashSet()) { it in ids },
            muted = muted.filterTo(HashSet()) { it in ids },
            favorites = favorites.filterTo(HashSet()) { it in ids })
        return if (next.manual == manual && next.muted == muted && next.favorites == favorites) this else next
    }
}
/** Bell icon: mute an automatic match, or add / remove a manual one. */
fun toggleReminder(event: Broadcast, prefs: Preferences): Preferences =
    if (prefs.bellOn(event)) prefs.copy(muted = prefs.muted + event.id, manual = prefs.manual - event.id)
    else prefs.copy(manual = prefs.manual + event.id, muted = prefs.muted - event.id)
fun toggleFavorite(event: Broadcast, prefs: Preferences): Preferences = prefs.copy(
    favorites = if (event.id in prefs.favorites) prefs.favorites - event.id else prefs.favorites + event.id)

fun inQuietHours(time: Instant, prefs: Preferences): Boolean {
    if (!prefs.quietEnabled) return false
    val local = time.atZone(Athens).toLocalTime()
    val minute = local.hour * 60 + local.minute
    return when {
        prefs.quietStart == prefs.quietEnd -> false
        prefs.quietStart < prefs.quietEnd -> minute >= prefs.quietStart && minute < prefs.quietEnd
        else -> minute >= prefs.quietStart || minute < prefs.quietEnd
    }
}
data class Reminder(val event: Broadcast, val minutes: Int, val at: Instant) {
    val key: String get() = "${event.id}:$minutes"
    // Changing the source time produces a new delivery key for the same alarm slot.
    val deliveryKey: String get() = "$key:${event.startsAt.epochSecond}"
}
fun planReminders(events: List<Broadcast>, prefs: Preferences, now: Instant): List<Reminder> =
    events.filter { prefs.wantsReminder(it) && it.startsAt > now }.flatMap { event ->
        prefs.offsets.filter { it in 0..1440 }.map { minutes ->
            Reminder(event, minutes, event.startsAt.minusSeconds(minutes * 60L))
        }
    }.filter { it.at > now && !inQuietHours(it.at, prefs) }.sortedBy { it.at }

data class AppSnapshot(
    val events: List<Broadcast> = emptyList(),
    val prefs: Preferences = Preferences(),
    val lastSync: Instant? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val notificationsAllowed: Boolean = false,
    val exactAllowed: Boolean = false,
    val scheduled: Int = 0
)
