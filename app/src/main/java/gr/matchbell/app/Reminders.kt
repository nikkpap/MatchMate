package gr.matchbell.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.time.Instant

/** Longest "Home – Away" that still fits one bold title line on a phone. */
private const val MaxTitle = 30

class ReminderEngine(private val context: Context, private val store: Store) {
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val createdChannels = mutableMapOf<String, String>()
    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun createChannels(events: List<Broadcast> = emptyList(), language: String = "el") {
        val t = txtFor(language)
        val keys = SportLabels.keys + events.map { it.sport } + listOf("f1", "motogp")
        (keys.associateWith { key -> t.sport(key, events.firstOrNull { it.sport == key }?.sportLabel) } +
            mapOf("test" to t("Δοκιμαστική ειδοποίηση", "Test notification")))
            .forEach { (key, name) ->
                if (createdChannels[key] == name) return@forEach
                // Re-creating an existing channel only updates its name/description (user settings are kept).
                manager.createNotificationChannel(NotificationChannel("sport-$key", name, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = t("Υπενθυμίσεις μεταδόσεων: $name", "Broadcast reminders: $name")
                    enableVibration(true)
                })
                createdChannels[key] = name
            }
    }
    private fun pending(key: String, payload: String? = null): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            data = Uri.Builder().scheme("matchbell").authority("reminder").appendPath(key).build()
            payload?.let { putExtra("batch", it) }
        }
        val flags = if (payload == null) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun cancel(key: String) {
        pending(key)?.let { alarm.cancel(it); it.cancel() }
    }
    fun reconcile(events: List<Broadcast>, prefs: Preferences, now: Instant, force: Boolean = false): Int {
        createChannels(events, prefs.language)
        val delivered = store.delivered()
        val planned = if (notificationsAllowed()) planReminders(events, prefs, now)
            .filter { it.deliveryKey !in delivered } else emptyList()
        // A rolling window stays below Android's per-app alarm limit. Each delivery fills it again.
        val batches = activeAlarmBatches(planned)
        val exact = exactAllowed()
        val payloads = batches.associate { it.key to AlarmPayload.encode(it.tokens) }
        val signatures = batches.associate { it.key to "$exact|${payloads.getValue(it.key)}" }
        val previous = store.alarmRegistry()
        val changes = alarmChanges(previous, signatures, force)
        store.legacyAlarmKeys().forEach(::cancel)
        changes.cancel.forEach(::cancel)
        val scheduled = previous.filterKeys { it in signatures }.toMutableMap()
        batches.filter { it.key in changes.schedule }.forEach { batch ->
            val intent = requireNotNull(pending(batch.key, payloads.getValue(batch.key)))
            try {
                if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, batch.atMillis, intent)
                else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, batch.atMillis, intent)
                scheduled[batch.key] = signatures.getValue(batch.key)
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and the call.
                try {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, batch.atMillis, intent)
                    scheduled[batch.key] = "false|${payloads.getValue(batch.key)}"
                } catch (_: SecurityException) { scheduled.remove(batch.key) }
            }
        }
        store.saveAlarmRegistry(scheduled)
        return batches.filter { it.key in scheduled }.sumOf { it.tokens.size }
    }
    fun deliverBatch(events: List<Broadcast>, prefs: Preferences, tokens: List<ReminderToken>) {
        createChannels(events, prefs.language)
        val now = Instant.now()
        val delivered = store.delivered().keys.toMutableSet()
        val newKeys = tokens.distinct().mapNotNull { token ->
            deliver(events, prefs, token, now, delivered)?.also { delivered += it }
        }
        if (newKeys.isNotEmpty()) store.markDelivered(newKeys, now)
    }
    private fun deliver(events: List<Broadcast>, prefs: Preferences, token: ReminderToken, now: Instant, delivered: Set<String>): String? {
        val (id, minutes, expectedStart) = token
        val event = events.firstOrNull { it.id == id } ?: return null
        if (event.startsAt.toEpochMilli() != expectedStart || minutes !in prefs.offsets || !prefs.wantsReminder(event)) return null
        val reminder = Reminder(event, minutes, event.startsAt.minusSeconds(minutes * 60L))
        if (reminder.deliveryKey in delivered || inQuietHours(now, prefs) || !notificationsAllowed()) return null
        if (now < reminder.at || now > event.startsAt.plusSeconds(120)) return null
        val t = txtFor(prefs.language)
        // Round up so a 30-minute reminder fired a few seconds late still says "30", not "29".
        val remaining = ((event.startsAt.toEpochMilli() - now.toEpochMilli() + 59_999) / 60000).coerceAtLeast(0)
        val whenText = if (remaining == 0L) t("Ξεκινά τώρα!", "Starting now!")
            else t("Ξεκινά σε $remaining λεπτά", "Starts in $remaining minutes")
        val channelId = "sport-${event.series ?: event.sport}"
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return null
        val intent = Intent(context, MainActivity::class.java).putExtra("eventId", event.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.Builder().scheme("matchbell").authority("event").appendPath(event.id).build())
        val content = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mute = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_MUTE).putExtra("eventId", event.id)
            .setData(Uri.Builder().scheme("matchbell").authority("mute").appendPath(event.id).build()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val emoji = sportEmoji(event.series ?: event.sport, event.sportLabel)
        val details = "🕒 ${event.local.format(TimeFormat)} · 📺 ${event.channel}"
        val competition = listOfNotNull(event.competitions.firstOrNull()?.label ?: t.sport(event.series ?: event.sport, event.sportLabel),
            if (event.isWomen) "♀ " + t("Γυναικών", "Women") else null).joinToString(" · ")
        val notification = matchNotification(channelId, emoji, event.teamNames, event.title, competition, details, "⏰ $whenText",
            sportSmallIcon(event.series ?: event.sport))
            .setContentIntent(content)
            .setColor(paletteOf(prefs.palette).darkPrimary.toArgb())
            .setGroup("matchbell-events").setWhen(event.startsAt.toEpochMilli()).setShowWhen(true)
            .addAction(R.drawable.ic_notification, t("Σίγαση αγώνα", "Mute this match"), mute)
            .build()
        try {
            // The string tag prevents hash collisions between source IDs.
            manager.notify(event.id, 1, notification)
            return reminder.deliveryKey
        } catch (_: SecurityException) { return null }
    }
    /**
     * Reminder look (standard template; Samsung ignores size/bold spans in the body, but always
     * shows the TITLE big and bold — so the title is the match itself):
     *   **⚽ Παναθηναϊκός – ΤΣΣΚΑ Σόφιας**
     *   🏆 UEFA Conference League
     *   🕒 22:00 · 📺 Magenta Sport 4
     *   ⏰ Ξεκινά σε 30 λεπτά
     * A pairing too long for one title line becomes "⚽ Home" + "🆚 Away" so nothing is cut.
     */
    private fun matchNotification(channelId: String, emoji: String, sides: List<String>, title: String, competition: String,
                                  details: String, whenText: String, smallIcon: Int = R.drawable.ic_sport_soccer): NotificationCompat.Builder {
        val full = if (sides.size == 2) sides.joinToString(" – ") else title
        val split = sides.size == 2 && full.length > MaxTitle
        val bigTitle = "$emoji " + if (split) sides[0] else full
        // A lone dot acts as a blank-line placeholder after the teams (Samsung drops truly empty lines).
        val lines = listOfNotNull(if (split) "🆚 ${sides[1]}" else null, ".", "🏆 $competition", details, whenText)
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle("$emoji $full")
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(bigTitle).bigText(lines.joinToString("\n")))
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
    }

    /** Monochrome status-bar icon per sport (Android requires a white silhouette here). */
    private fun sportSmallIcon(key: String): Int = when (key) {
        "podosfairo", "podosfairo_salas" -> R.drawable.ic_sport_soccer
        "basket" -> R.drawable.ic_sport_basket
        "tennis" -> R.drawable.ic_sport_tennis
        "volley", "handball", "polo" -> R.drawable.ic_sport_volley
        "f1", "motogp", "mixanokinitos-athlitismos" -> R.drawable.ic_sport_flag
        "show" -> R.drawable.ic_notification
        else -> R.drawable.ic_sport_trophy
    }

    fun testNotification(language: String = "el"): Boolean {
        val t = txtFor(language)
        createChannels(language = language)
        if (!notificationsAllowed()) return false
        val channel = manager.getNotificationChannel("sport-test")
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val content = PendingIntent.getActivity(context, 991, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            // Preview of a real reminder, so the user sees the final look.
            manager.notify("test", 1, matchNotification("sport-test", "⚽", listOf("ΠΑΟΚ", "ΑΕΚ"), "ΠΑΟΚ - ΑΕΚ",
                t("Δοκιμαστική ειδοποίηση · Stoiximan Super League", "Test notification · Stoiximan Super League"),
                "🕒 21:00 · 📺 Novasports Prime", "⏰ " + t("Ξεκινά σε 30 λεπτά", "Starts in 30 minutes"))
                .setContentIntent(content).build())
            true
        } catch (_: SecurityException) { false }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    companion object { const val ACTION_MUTE = "gr.matchbell.app.action.MUTE" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MUTE) {
            val id = intent.getStringExtra("eventId") ?: return
            context.getSystemService(NotificationManager::class.java).cancel(id, 1)
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { AppGraph.get(context).mute(id) } finally { pending.finish() }
            }
            return
        }
        // The old single-event payload remains readable during an in-place upgrade.
        val tokens = intent.getStringExtra("batch")?.let(AlarmPayload::decode) ?: intent.getStringExtra("eventId")?.let {
            listOf(ReminderToken(it, intent.getIntExtra("minutes", -1), intent.getLongExtra("start", 0)))
        } ?: return
        if (tokens.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { AppGraph.get(context).deliver(tokens) }
            finally { pending.finish() }
        }
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actions = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        if (intent.action !in actions) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val graph = AppGraph.get(context)
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) UpdateManager.get(context).onPackageReplaced()
                graph.restore(force = true)
                graph.enqueueRefresh()
            } finally { pending.finish() }
        }
    }
}
