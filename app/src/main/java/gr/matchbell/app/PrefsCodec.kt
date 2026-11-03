package gr.matchbell.app

import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON codec for preferences: used by the local store and by backup export / import. */
object PrefsCodec {
    const val FORMAT = "matchmate-settings"
    val Themes = setOf("dark", "light", "system", "amoled")
    val Languages = setOf("el", "en")

    private fun strings(values: Set<String>): JSONArray = JSONArray(values.sorted())
    private fun stringSet(o: JSONObject, key: String): Set<String> {
        val a = o.optJSONArray(key) ?: return emptySet()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }.toSet()
    }

    fun encode(p: Preferences): JSONObject = JSONObject().put("teams", JSONArray(p.teams.map {
            JSONObject().put("name", it.name).put("sport", it.sport)
        })).put("sports", strings(p.sports)).put("competitions", strings(p.competitions))
        .put("manual", strings(p.manual)).put("muted", strings(p.muted))
        .put("favorites", strings(p.favorites)).put("enabled", p.enabled)
        .put("offsets", JSONArray(p.offsets.sorted())).put("quietEnabled", p.quietEnabled)
        .put("quietStart", p.quietStart).put("quietEnd", p.quietEnd)
        .put("includeShows", p.includeShows).put("refreshHours", p.refreshHours).put("theme", p.theme)
        .put("palette", p.palette).put("language", p.language).put("hiddenChannels", strings(p.hiddenChannels))

    fun decode(p: JSONObject): Preferences {
        val teams = p.optJSONArray("teams") ?: JSONArray()
        val offsets = p.optJSONArray("offsets")
        return Preferences(
            teams = (0 until teams.length()).mapNotNull { i ->
                val t = teams.optJSONObject(i) ?: return@mapNotNull null
                val name = t.optString("name").trim()
                if (normalized(name).isBlank()) null else TeamRule(name, t.optString("sport", "all").ifBlank { "all" })
            }.distinctBy { it.key },
            sports = stringSet(p, "sports"), competitions = stringSet(p, "competitions"),
            manual = stringSet(p, "manual"), muted = stringSet(p, "muted"), favorites = stringSet(p, "favorites"),
            enabled = p.optBoolean("enabled", true),
            offsets = offsets?.let { a -> (0 until a.length()).map { a.optInt(it, -1) }.filter { it in 0..1440 }.toSet() }
                ?.takeIf { it.isNotEmpty() } ?: setOf(30),
            quietEnabled = p.optBoolean("quietEnabled"),
            quietStart = p.optInt("quietStart", 1380).coerceIn(0, 1439),
            quietEnd = p.optInt("quietEnd", 480).coerceIn(0, 1439),
            includeShows = p.optBoolean("includeShows"),
            refreshHours = p.optLong("refreshHours", 6).takeIf { it in setOf(1L, 3L, 6L, 12L) } ?: 6,
            theme = p.optString("theme", "dark").takeIf { it in Themes } ?: "dark",
            palette = p.optString("palette", "mint").takeIf { id -> PaletteIds.contains(id) } ?: "mint",
            language = p.optString("language", "el").takeIf { it in Languages } ?: "el",
            hiddenChannels = stringSet(p, "hiddenChannels")
        )
    }

    /** Backup file: preferences only, never the cached programme. */
    fun exportBackup(p: Preferences, version: String): String = JSONObject()
        .put("format", FORMAT).put("version", 1).put("app", version)
        .put("prefs", encode(p)).toString(2)

    /** Returns null when the text is not a MatchMate backup. */
    fun importBackup(text: String): Preferences? = try {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT) null else o.optJSONObject("prefs")?.let(::decode)
    } catch (_: Exception) { null }
}

/** Kept here (pure Kotlin) so the codec can validate palette ids without Compose. */
val PaletteIds = listOf("mint", "ocean", "sunset", "violet", "pitch", "gold", "dynamic")
