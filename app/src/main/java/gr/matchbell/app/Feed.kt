package gr.matchbell.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime

/** org.json returns the text "null" for JSON nulls; treat it, and blanks, as missing. */
internal fun JSONObject.text(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
}

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
            val tags = (0 until rawTags.length()).mapNotNull { i ->
                val t = rawTags.optJSONObject(i) ?: return@mapNotNull null
                val name = t.text("name") ?: ""
                val label = t.text("displayName") ?: name
                if (name.isEmpty() && label.isEmpty()) return@mapNotNull null
                Tag(sportTag(name, label), label)
            }
            Broadcast(item.get("id").toString(), date, item.text("title") ?: "—",
                item.optJSONObject("channel")?.text("name") ?: "Κανάλι μη διαθέσιμο",
                tags, item.text("description") ?: "")
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
        connection.setRequestProperty("User-Agent", "MatchMate/0.3 Android")
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
