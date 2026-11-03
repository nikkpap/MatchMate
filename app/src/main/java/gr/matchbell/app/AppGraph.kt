package gr.matchbell.app

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MatchMateApplication : Application() {
    override fun onCreate() { super.onCreate(); AppGraph.get(this) }
}

class AppGraph private constructor(context: Context) {
    val context: Context = context.applicationContext
    private val store = Store(this.context)
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reminders = ReminderEngine(this.context, store)
    private val mutable = MutableStateFlow(store.load())
    private var configuredHours: Long? = null
    private var foregroundRestored = false
    val state = mutable.asStateFlow()
    init { scope.launch { restore() } }

    private suspend fun publish(snapshot: AppSnapshot, reschedule: Boolean = true, force: Boolean = false) {
        val allowed = reminders.notificationsAllowed()
        val exact = reminders.exactAllowed()
        val count = if (reschedule) reminders.reconcile(snapshot.events, snapshot.prefs, Instant.now(), force)
            else snapshot.scheduled
        mutable.value = snapshot.copy(notificationsAllowed = allowed, exactAllowed = exact, scheduled = count)
        configureSync(mutable.value)
    }
    suspend fun restore(force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock { publish(mutable.value, force = force) }
    }
    suspend fun refresh(force: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        // Single-flight: a caller arriving during a successful download reuses that result.
        val observedSync = mutable.value.lastSync
        refreshMutex.withLock {
            if (mutable.value.lastSync != observedSync && mutable.value.lastSync != null) return@withLock true
            if (!force && !needsRefresh(mutable.value.lastSync, Instant.now(), mutable.value.prefs.refreshHours)) return@withLock true
            mutex.withLock { mutable.value = mutable.value.copy(loading = true, error = null) }
            try {
                val data = FeedClient().load(LocalDate.now(Athens))
                mutex.withLock {
                    val current = mutable.value
                    // Drop bell / mute / star IDs of broadcasts that left the programme.
                    val snapshot = current.copy(events = if (data == current.events) current.events else data,
                        prefs = current.prefs.pruned(data), lastSync = Instant.now(), loading = false, error = null)
                    store.save(snapshot)
                    publish(snapshot)
                }
                true
            } catch (cancel: CancellationException) {
                mutex.withLock { mutable.value = mutable.value.copy(loading = false) }
                throw cancel
            } catch (_: Exception) {
                mutex.withLock {
                    mutable.value = mutable.value.copy(loading = false,
                        error = "Αδυναμία ανανέωσης. ${if (mutable.value.events.isNotEmpty()) "Προβάλλεται το αποθηκευμένο πρόγραμμα." else "Έλεγξε τη σύνδεσή σου και δοκίμασε ξανά."}")
                }
                false
            }
        }
    }
    suspend fun update(transform: (Preferences) -> Preferences) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = mutable.value
            val prefs = transform(previous.prefs)
            if (prefs == previous.prefs) return@withLock
            try {
                store.savePreferences(prefs)
            } catch (_: Exception) {
                mutable.value = previous.copy(error = "Δεν αποθηκεύτηκαν οι αλλαγές. Δοκίμασε ξανά.")
                return@withLock
            }
            if (prefs.language != previous.prefs.language) reminders.createChannels(previous.events, prefs.language)
            publish(previous.copy(prefs = prefs, error = null), reschedule = prefs.reminderSettings() != previous.prefs.reminderSettings())
        }
    }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    /** Notification action: silence one broadcast without opening the app. */
    suspend fun mute(eventId: String) = update { p -> p.copy(muted = p.muted + eventId, manual = p.manual - eventId) }
    suspend fun onResume() {
        // Android clears alarms on force-stop. Repair once per fresh foreground process,
        // while alarm/worker process starts and subsequent resumes keep unchanged registrations.
        withContext(Dispatchers.IO) {
            mutex.withLock {
                publish(mutable.value, force = !foregroundRestored)
                foregroundRestored = true
            }
        }
        if (needsRefresh(state.value.lastSync, Instant.now(), state.value.prefs.refreshHours)) refresh(force = false)
    }
    suspend fun deliver(tokens: List<ReminderToken>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            reminders.deliverBatch(mutable.value.events, mutable.value.prefs, tokens)
            publish(mutable.value)
        }
    }
    fun testNotification(): Boolean = reminders.testNotification(mutable.value.prefs.language)

    private suspend fun configureSync(snapshot: AppSnapshot) {
        val active = snapshot.notificationsAllowed && backgroundSyncNeeded(snapshot.prefs, snapshot.events, Instant.now())
        val hours = if (active) snapshot.prefs.refreshHours else 0L
        if (configuredHours == hours) return
        try {
            val workManager = WorkManager.getInstance(context)
            val previous = store.syncHours()
            val operation = if (hours == 0L) {
                if (previous == 0L) null else workManager.cancelUniqueWork("programme-sync")
            } else {
                val work = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
                // KEEP on process starts preserves the original interval and system scheduling.
                workManager.enqueueUniquePeriodicWork("programme-sync",
                    if (previous == hours) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE, work)
            }
            operation?.let { awaitOperation(it) }
            if (previous != hours) store.saveSyncHours(hours)
            configuredHours = hours
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Retry configuration on the next restore; local alarms remain available. */ }
    }
    fun backgroundActive(): Boolean = reminders.notificationsAllowed() &&
        backgroundSyncNeeded(state.value.prefs, state.value.events, Instant.now())
    fun enqueueRefresh() {
        if (!backgroundActive()) return
        WorkManager.getInstance(context).enqueueUniqueWork("programme-refresh", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build())
    }
    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AppGraph? = null
        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
private suspend fun awaitOperation(operation: Operation) = suspendCancellableCoroutine<Unit> { continuation ->
    val future = operation.result
    future.addListener({
        try { future.get(); continuation.resume(Unit) }
        catch (e: Exception) { continuation.resumeWithException(e) }
    }, Executor { it.run() })
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        if (!graph.backgroundActive()) return Result.success()
        val ok = graph.refresh(force = false)
        return if (ok) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
