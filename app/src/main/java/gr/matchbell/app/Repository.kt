package gr.matchbell.app

import android.content.Context
import android.annotation.SuppressLint
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

object FeedParser {
    fun parse(text: String): List<Broadcast> {
        val array = JSONArray(text)
        val events = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            require(item.has("id") && item.has("scheduleDate") && item.has("title")) {
                "Η μορφή των δεδομένων έχει αλλάξει."
            }
            val date = LocalDateTime.parse(item.getString("scheduleDate"), FeedDateFormat)
                .atZone(Athens).toInstant()
            val rawTags = item.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until rawTags.length()).map { i ->
                val t = rawTags.getJSONObject(i)
                val label = t.optString("displayName", t.optString("name"))
                val key = sportTag(t.optString("name"), label)
                Tag(key, label)
            }
            Broadcast(item.get("id").toString(), date, item.getString("title").trim(),
                item.optJSONObject("channel")?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: "Κανάλι μη διαθέσιμο", tags, item.optString("description"))
        }
        return events.distinctBy { it.id }.sortedBy { it.startsAt }
    }
}

class FeedClient {
    fun load(date: LocalDate): List<Broadcast> {
        val endpoint = "https://tv.24media.gr/service/events?accept=json&date=$date&days=7&pId=3"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "MatchMate/0.2 Android")
        try {
            check(connection.responseCode == 200) { "Η πηγή απάντησε με HTTP ${connection.responseCode}." }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                val out = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    out.append(buffer, 0, count)
                    check(out.length <= 4_000_000) { "Το πρόγραμμα είναι υπερβολικά μεγάλο." }
                }
                out.toString()
            }
            return FeedParser.parse(text)
        } finally { connection.disconnect() }
    }
}

private fun strings(values: Set<String>): JSONArray = JSONArray(values.toList())
private fun stringSet(o: JSONObject, key: String): Set<String> {
    val a = o.optJSONArray(key) ?: return emptySet()
    return (0 until a.length()).map { a.getString(it) }.toSet()
}

// All writes run on Dispatchers.IO; commit keeps the alarm registry durable.
@SuppressLint("ApplySharedPref")
class Store(context: Context) {
    private val preferences = context.getSharedPreferences("matchbell-v1", Context.MODE_PRIVATE)
    private val cache = context.getSharedPreferences("matchbell-feed-v2", Context.MODE_PRIVATE)
    private val reminders = context.getSharedPreferences("matchbell-reminders-v2", Context.MODE_PRIVATE)
    fun load(): AppSnapshot = try {
        val o = JSONObject(cache.getString("feed", null) ?: preferences.getString("state", "{}") ?: "{}")
        val raw = o.optJSONArray("events") ?: JSONArray()
        val events = (0 until raw.length()).map { i ->
            val e = raw.getJSONObject(i)
            val t = e.optJSONArray("tags") ?: JSONArray()
            Broadcast(e.getString("id"), Instant.ofEpochMilli(e.getLong("at")), e.getString("title"),
                e.getString("channel"), (0 until t.length()).map { j ->
                    val tag = t.getJSONObject(j); Tag(tag.getString("key"), tag.getString("label"))
                }, e.optString("description"))
        }
        val p = preferences.getString("prefs", null)?.let(::JSONObject) ?: o.optJSONObject("prefs") ?: JSONObject()
        val teams = p.optJSONArray("teams") ?: JSONArray()
        val offsets = p.optJSONArray("offsets")
        AppSnapshot(events, Preferences(
            teams = (0 until teams.length()).map { i ->
                val t = teams.getJSONObject(i); TeamRule(t.getString("name"), t.optString("sport", "all"))
            }, sports = stringSet(p, "sports"), competitions = stringSet(p, "competitions"),
            manual = stringSet(p, "manual"), muted = stringSet(p, "muted"), favorites = stringSet(p, "favorites"),
            enabled = p.optBoolean("enabled", true),
            offsets = offsets?.let { a -> (0 until a.length()).map { a.getInt(it) }.filter { it in 0..1440 }.toSet() }
                ?.takeIf { it.isNotEmpty() } ?: setOf(30),
            quietEnabled = p.optBoolean("quietEnabled"), quietStart = p.optInt("quietStart", 1380).coerceIn(0, 1439),
            quietEnd = p.optInt("quietEnd", 480).coerceIn(0, 1439), includeShows = p.optBoolean("includeShows"),
            refreshHours = p.optLong("refreshHours", 6).takeIf { it in setOf(1L, 3L, 6L, 12L) } ?: 6,
            theme = p.optString("theme", "dark").takeIf { it in setOf("dark", "light", "system") } ?: "dark"
        ), o.optLong("lastSync", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    } catch (_: Exception) {
        AppSnapshot(error = "Δεν ήταν δυνατή η ανάκτηση των αποθηκευμένων δεδομένων.")
    }

    private fun encodePrefs(p: Preferences): JSONObject = JSONObject().put("teams", JSONArray(p.teams.map {
            JSONObject().put("name", it.name).put("sport", it.sport)
        })).put("sports", strings(p.sports)).put("competitions", strings(p.competitions))
            .put("manual", strings(p.manual)).put("muted", strings(p.muted))
            .put("favorites", strings(p.favorites)).put("enabled", p.enabled)
            .put("offsets", JSONArray(p.offsets.sorted())).put("quietEnabled", p.quietEnabled)
            .put("quietStart", p.quietStart).put("quietEnd", p.quietEnd)
            .put("includeShows", p.includeShows).put("refreshHours", p.refreshHours).put("theme", p.theme)

    fun savePreferences(p: Preferences) {
        val value = encodePrefs(p).toString()
        if (preferences.getString("prefs", null) == value) return
        check(preferences.edit().putString("prefs", value).commit()) { "Δεν αποθηκεύτηκαν οι αλλαγές." }
    }
    fun save(state: AppSnapshot) {
        savePreferences(state.prefs)
        val events = JSONArray(state.events.map { e ->
            JSONObject().put("id", e.id).put("at", e.startsAt.toEpochMilli()).put("title", e.title)
                .put("channel", e.channel).put("description", e.description).put("tags", JSONArray(e.tags.map {
                    JSONObject().put("key", it.key).put("label", it.label)
                }))
        })
        check(cache.edit().putString("feed", JSONObject().put("events", events)
            .put("lastSync", state.lastSync?.toEpochMilli() ?: 0).toString()).commit()) {
            "Δεν αποθηκεύτηκαν οι αλλαγές."
        }
        // Retire the old combined blob only after both new files are committed.
        if (preferences.contains("state")) check(preferences.edit().remove("state").commit())
    }
    fun legacyAlarmKeys(): Set<String> = preferences.getStringSet("alarms", emptySet())!!.toSet()
    fun alarmRegistry(): Map<String, String> = try {
        val o = JSONObject(reminders.getString("alarm-registry", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) { emptyMap() }
    fun saveAlarmRegistry(values: Map<String, String>) {
        if (values == alarmRegistry() && legacyAlarmKeys().isEmpty()) return
        check(reminders.edit().putString("alarm-registry", JSONObject(values as Map<*, *>).toString()).commit())
        if (legacyAlarmKeys().isNotEmpty()) check(preferences.edit().remove("alarms").commit())
    }
    fun syncHours(): Long = preferences.getLong("work-hours", -1)
    fun saveSyncHours(hours: Long) { check(preferences.edit().putLong("work-hours", hours).commit()) }
    fun delivered(): Map<String, Long> = try {
        val o = JSONObject((reminders.getString("delivered", null) ?: preferences.getString("delivered", "{}"))!!)
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (_: Exception) { emptyMap() }
    fun markDelivered(keys: List<String>, now: Instant) {
        val values = delivered().filterValues { it > now.minusSeconds(14 * 86400L).toEpochMilli() }.toMutableMap()
        keys.forEach { values[it] = now.toEpochMilli() }
        check(reminders.edit().putString("delivered", JSONObject(values as Map<*, *>).toString()).commit())
        if (preferences.contains("delivered")) check(preferences.edit().remove("delivered").commit())
    }
}
