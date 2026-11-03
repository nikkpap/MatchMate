package gr.matchbell.app

import java.time.Duration
import java.time.Instant

/** One alarm carries every reminder due at the same instant. */
data class ReminderToken(val eventId: String, val minutes: Int, val startMillis: Long)
data class AlarmBatch(val atMillis: Long, val tokens: List<ReminderToken>) {
    val key: String get() = "batch:$atMillis"
}
fun batchReminders(reminders: List<Reminder>): List<AlarmBatch> = reminders
    .groupBy { it.at.toEpochMilli() }.toSortedMap().map { (at, group) ->
        AlarmBatch(at, group.map { ReminderToken(it.event.id, it.minutes, it.event.startsAt.toEpochMilli()) }
            .distinct().sortedWith(compareBy({ it.eventId }, { it.minutes }, { it.startMillis })))
    }

fun activeAlarmBatches(reminders: List<Reminder>): List<AlarmBatch> = batchReminders(reminders).take(400)

data class AlarmChanges(val cancel: Set<String>, val schedule: Set<String>)
fun alarmChanges(previous: Map<String, String>, next: Map<String, String>, force: Boolean = false): AlarmChanges =
    AlarmChanges(previous.keys - next.keys, next.filter { (key, signature) -> force || previous[key] != signature }.keys)

/** Appearance and stars never change a reminder's timing or selection. */
fun Preferences.reminderSettings(): Preferences =
    copy(favorites = emptySet(), theme = "", palette = "", language = "", refreshHours = 0)

fun backgroundSyncNeeded(prefs: Preferences, events: List<Broadcast>, now: Instant): Boolean =
    prefs.enabled && prefs.offsets.any { it in 0..1440 } &&
        (prefs.teams.isNotEmpty() || prefs.sports.isNotEmpty() || prefs.competitions.isNotEmpty() ||
            events.any { it.id in prefs.manual && it.startsAt > now && prefs.wantsReminder(it) })

/** Also refresh across an Athens date boundary, because the request's seven-day window moves. */
fun needsRefresh(last: Instant?, now: Instant, hours: Long): Boolean = last == null ||
    last.atZone(Athens).toLocalDate() != now.atZone(Athens).toLocalDate() ||
    now < last || Duration.between(last, now).toMinutes() >= hours * 60
