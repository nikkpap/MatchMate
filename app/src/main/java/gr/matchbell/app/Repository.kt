package gr.matchbell.app

import android.content.Context
import android.annotation.SuppressLint
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// All writes run on Dispatchers.IO; commit keeps the alarm registry durable.
@SuppressLint("ApplySharedPref")
class Store(context: Context) {
    private val preferences = context.getSharedPreferences("matchbell-v1", Context.MODE_PRIVATE)
    private val cache = context.getSharedPreferences("matchbell-feed-v2", Context.MODE_PRIVATE)
    private val reminders = context.getSharedPreferences("matchbell-reminders-v2", Context.MODE_PRIVATE)

    /**
     * Preferences and the cached programme are read independently.
     * Before 0.3.0 a corrupt programme cache made load() return default preferences,
     * and the next change then overwrote the user's real selections.
     */
    fun load(): AppSnapshot {
        val legacy = try { preferences.getString("state", null)?.let(::JSONObject) } catch (_: Exception) { null }
        var error: String? = null
        val prefs = try {
            val p = preferences.getString("prefs", null)?.let(::JSONObject) ?: legacy?.optJSONObject("prefs") ?: JSONObject()
            PrefsCodec.decode(p)
        } catch (_: Exception) {
            error = "Δεν ήταν δυνατή η ανάκτηση των ρυθμίσεων."
            Preferences()
        }
        var lastSync: Instant? = null
        val events = try {
            val o = cache.getString("feed", null)?.let(::JSONObject) ?: legacy ?: JSONObject()
            lastSync = o.optLong("lastSync", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            decodeEvents(o.optJSONArray("events") ?: JSONArray())
        } catch (_: Exception) {
            // A broken cache only costs one download; preferences stay intact.
            lastSync = null
            emptyList()
        }
        return AppSnapshot(events, prefs, lastSync, error = error)
    }

    private fun decodeEvents(raw: JSONArray): List<Broadcast> = (0 until raw.length()).mapNotNull { i ->
        try {
            val e = raw.getJSONObject(i)
            val t = e.optJSONArray("tags") ?: JSONArray()
            Broadcast(e.getString("id"), Instant.ofEpochMilli(e.getLong("at")), e.getString("title"),
                e.getString("channel"), (0 until t.length()).map { j ->
                    val tag = t.getJSONObject(j); Tag(tag.getString("key"), tag.getString("label"))
                }, e.optString("description"))
        } catch (_: Exception) { null } // skip a single damaged row instead of the whole cache
    }

    fun savePreferences(p: Preferences) {
        val value = PrefsCodec.encode(p).toString()
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
    fun legacyAlarmKeys(): Set<String> = preferences.getStringSet("alarms", emptySet())?.toSet() ?: emptySet()
    fun alarmRegistry(): Map<String, String> = try {
        val o = JSONObject(reminders.getString("alarm-registry", "{}") ?: "{}")
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
        val o = JSONObject(reminders.getString("delivered", null) ?: preferences.getString("delivered", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (_: Exception) { emptyMap() }
    fun markDelivered(keys: List<String>, now: Instant) {
        val values = delivered().filterValues { it > now.minusSeconds(14 * 86400L).toEpochMilli() }.toMutableMap()
        keys.forEach { values[it] = now.toEpochMilli() }
        check(reminders.edit().putString("delivered", JSONObject(values as Map<*, *>).toString()).commit())
        if (preferences.contains("delivered")) check(preferences.edit().remove("delivered").commit())
    }
}
