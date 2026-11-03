package gr.matchbell.app

import org.json.JSONArray
import org.json.JSONObject

/** Data, never commands; retain source start time to reject obsolete reminders. */
object AlarmPayload {
    fun encode(tokens: List<ReminderToken>): String = JSONArray(tokens.map {
        JSONObject().put("id", it.eventId).put("minutes", it.minutes).put("start", it.startMillis)
    }).toString()
    fun decode(value: String): List<ReminderToken> = try {
        val array = JSONArray(value)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ReminderToken(item.getString("id"), item.getInt("minutes"), item.getLong("start"))
        }.filter { it.eventId.isNotBlank() && it.minutes in 0..1440 && it.startMillis > 0 }.distinct()
    } catch (_: Exception) { emptyList() }
}
