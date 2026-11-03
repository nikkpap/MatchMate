package gr.matchbell.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.time.Instant

class ReminderEngine(private val context: Context, private val store: Store) {
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val createdChannels = mutableMapOf<String, String>()
    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun createChannels(events: List<Broadcast> = emptyList()) {
        (SportLabels + events.associate { it.sport to it.sportLabel } + mapOf("f1" to "Formula 1", "motogp" to "MotoGP", "test" to "Δοκιμαστική ειδοποίηση"))
            .forEach { (key, name) ->
                if (createdChannels[key] == name) return@forEach
                manager.createNotificationChannel(NotificationChannel("sport-$key", name, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Υπενθυμίσεις μεταδόσεων: $name"
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
        createChannels(events)
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
        createChannels(events)
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
        val remaining = ((event.startsAt.toEpochMilli() - now.toEpochMilli()) / 60000).coerceAtLeast(0)
        val whenText = if (remaining == 0L) "Η μετάδοση ξεκινά τώρα" else "Η μετάδοση ξεκινά σε $remaining λεπτά"
        val text = "${event.local.format(TimeFormat)} · ${event.channel}\n$whenText"
        val channelId = "sport-${event.series ?: event.sport}"
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return null
        val intent = Intent(context, MainActivity::class.java).putExtra("eventId", event.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.Builder().scheme("matchbell").authority("event").appendPath(event.id).build())
        val content = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(event.title).setContentText(text.replace('\n', ' '))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(content)
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setColor(0xff4de0c4.toInt())
            .setGroup("matchbell-events").setWhen(event.startsAt.toEpochMilli())
            .build()
        try {
            // The string tag prevents hash collisions between source IDs.
            manager.notify(event.id, 1, notification)
            return reminder.deliveryKey
        } catch (_: SecurityException) { return null }
    }
    fun testNotification(): Boolean {
        createChannels()
        if (!notificationsAllowed()) return false
        val channel = manager.getNotificationChannel("sport-test")
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val content = PendingIntent.getActivity(context, 991, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            manager.notify("test", 1, NotificationCompat.Builder(context, "sport-test")
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Το MatchMate είναι έτοιμο 🔔")
                .setContentText("Εδώ θα βλέπεις ώρα, αγώνα και κανάλι μετάδοσης.")
                .setContentIntent(content).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
            true
        } catch (_: SecurityException) { false }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
                graph.restore(force = true)
                graph.enqueueRefresh()
            } finally { pending.finish() }
        }
    }
}
