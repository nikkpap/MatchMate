package gr.matchbell.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Over-the-air updates from public GitHub Releases:
 * check → download the APK → verify SHA-256 → install through PackageInstaller.
 *
 * Fully automatic mode: on Android 12+ an app may update ITSELF without a confirmation tap
 * (USER_ACTION_NOT_REQUIRED), once the user has allowed "Install unknown apps" for MatchMate.
 * In the foreground the update is downloaded right away and installed when the user leaves the app,
 * so the app is never restarted under the user's finger. Older Android versions get one tap.
 * Android itself refuses an APK signed with a different key, so only builds from the same
 * signing key can replace the installed app.
 */
@SuppressLint("StaticFieldLeak")
class UpdateManager private constructor(context: Context) {
    private val context = context.applicationContext
    private val store = this.context.getSharedPreferences("matchbell-updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    private val autoMutable = MutableStateFlow(store.getBoolean("auto", true))
    val autoCheck = autoMutable.asStateFlow()
    private val autoInstallMutable = MutableStateFlow(store.getBoolean("autoInstall", true))
    val autoInstall = autoInstallMutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Set by MainActivity: true while the app is on screen. */
    @Volatile var foreground = false
    private val dir = File(this.context.cacheDir, "updates")

    val currentVersion: String = try {
        this.context.packageManager.getPackageInfo(this.context.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    init { cleanup() }

    fun setAutoCheck(on: Boolean) {
        store.edit().putBoolean("auto", on).apply()
        autoMutable.value = on
        schedule()
    }

    fun setAutoInstall(on: Boolean) {
        store.edit().putBoolean("autoInstall", on).apply()
        autoInstallMutable.value = on
    }

    /** Can Android install our updates without asking? (permission granted once by the user) */
    fun installAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    val silentSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Background check every 6 h, only when the user keeps it on. */
    fun schedule() {
        val wm = WorkManager.getInstance(context)
        if (!autoMutable.value) { wm.cancelUniqueWork(WORK); return }
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build()).build())
    }

    /** Foreground check at most every hour; with auto-install the APK is fetched right away. */
    suspend fun checkIfDue() {
        if (!autoMutable.value) return
        if (System.currentTimeMillis() - store.getLong("checked", 0) > 3600_000L) check()
        if (autoInstallMutable.value && mutable.value is UpdateState.Available) download()
    }

    /** Called when the user leaves the app: a ready update installs now (process restarts invisibly). */
    fun onAppBackground() {
        if (!autoInstallMutable.value || !installAllowed()) return
        if (mutable.value is UpdateState.Ready) scope.launch { installSession(userInitiated = false) }
    }

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = mutable.value
            if (current is UpdateState.Downloading) return@withLock current.info
            if (current is UpdateState.Ready) return@withLock current.info
            if (current is UpdateState.Installing) return@withLock current.info
            mutable.value = UpdateState.Checking
            try {
                val info = fetchLatest()
                store.edit().putLong("checked", System.currentTimeMillis()).apply()
                if (info != null && isNewerVersion(info.version, currentVersion)) {
                    val cached = apkFile(info)
                    mutable.value = if (cached.exists() && verified(cached, info)) UpdateState.Ready(info, cached) else UpdateState.Available(info)
                    info
                } else { mutable.value = UpdateState.UpToDate(System.currentTimeMillis()); null }
            } catch (_: Exception) {
                mutable.value = UpdateState.Failed("network"); null
            }
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        val c = URL(UpdateSource.LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MatchMate-Android")
        try {
            if (c.responseCode == 404) return null // no release published yet
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return parseRelease(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    private fun apkFile(info: UpdateInfo) = File(dir, "MatchMate-${info.version.replace(Regex("[^0-9A-Za-z.]"), "_")}.apk")

    private fun verified(file: File, info: UpdateInfo): Boolean {
        val expected = info.sha256 ?: return file.length() > 0 && (info.apkSize <= 0 || file.length() == info.apkSize)
        return sha256(file) == expected
    }

    suspend fun download() = withContext(Dispatchers.IO) {
        val info = (mutable.value as? UpdateState.Available)?.info ?: return@withContext
        dir.mkdirs()
        val target = apkFile(info)
        val part = File(dir, target.name + ".part")
        mutable.value = UpdateState.Downloading(info, 0f)
        try {
            val c = URL(info.apkUrl).openConnection() as HttpURLConnection // follows the https redirect to GitHub storage
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "MatchMate-Android")
            try {
                check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                check(total <= 100L * 1024 * 1024) { "too large" }
                var read = 0L
                var lastShown = -1
                c.inputStream.use { input -> part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n); read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else 0
                        if (pct != lastShown) { lastShown = pct; mutable.value = UpdateState.Downloading(info, pct / 100f) }
                    }
                } }
            } finally { c.disconnect() }
            check(verified(part, info)) { "checksum" }
            target.delete(); check(part.renameTo(target))
            mutable.value = UpdateState.Ready(info, target)
        } catch (e: Exception) {
            part.delete()
            mutable.value = UpdateState.Failed(if (e.message == "checksum") "checksum" else "download")
        }
    }

    /**
     * Manual "Install" button. Returns false when the user must first allow
     * "Install unknown apps" for MatchMate (settings screen is opened).
     */
    fun install(activity: Context): Boolean {
        if (mutable.value !is UpdateState.Ready) return true
        if (!installAllowed()) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) { }
            return false
        }
        scope.launch { installSession(userInitiated = true) }
        return true
    }

    /** Streams the verified APK into a PackageInstaller session; the result arrives in [UpdateInstallReceiver]. */
    private fun installSession(userInitiated: Boolean) {
        val ready = mutable.value as? UpdateState.Ready ?: return
        if (!ready.file.exists() || !verified(ready.file, ready.info)) { ready.file.delete(); mutable.value = UpdateState.Available(ready.info); return }
        mutable.value = UpdateState.Installing(ready.info)
        val installer = context.packageManager.packageInstaller
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(ready.file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("MatchMate.apk", 0, ready.file.length()).use { out ->
                    ready.file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                store.edit().putString("installing", ready.info.version).putBoolean("userInitiated", userInitiated).apply()
                // FLAG_MUTABLE is required: the system fills in the status extras.
                val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(callback.intentSender)
            }
        } catch (_: Exception) {
            mutable.value = UpdateState.Ready(ready.info, ready.file)
            if (userInitiated) legacyInstall(ready.file)
        }
    }

    /** Fallback: classic installer screen through FileProvider. */
    private fun legacyInstall(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { mutable.value = UpdateState.Failed("install") }
    }

    /** PackageInstaller result. On success the process is replaced, so this mostly sees "needs a tap" or errors. */
    internal fun onInstallStatus(status: Int, confirm: Intent?) {
        val info = (mutable.value as? UpdateState.Installing)?.info
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (foreground) try { context.startActivity(confirm); return } catch (_: Exception) { }
                notify(info?.version ?: "", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                store.edit().remove("installing").apply()
                val ready = if (info != null) apkFile(info) else null
                mutable.value = if (info != null && ready != null && ready.exists() && status == PackageInstaller.STATUS_FAILURE_ABORTED)
                    UpdateState.Ready(info, ready) else UpdateState.Failed("install")
            }
        }
    }

    /** After MY_PACKAGE_REPLACED: confirm the silent update with a short notification. */
    fun onPackageReplaced() {
        val version = store.getString("installing", null) ?: return
        store.edit().remove("installing").apply()
        if (version != currentVersion) return
        val t = txtFor(AppGraph.get(context).state.value.prefs.language)
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager, t)
        val open = PendingIntent.getActivity(context, 78, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify("update", 2, NotificationCompat.Builder(context, "updates").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(t("Το MatchMate ενημερώθηκε στην έκδοση $version ✓", "MatchMate updated to $version ✓"))
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: SecurityException) { }
    }

    private fun ensureChannel(manager: NotificationManager, t: Txt) {
        manager.createNotificationChannel(NotificationChannel("updates", t("Ενημερώσεις εφαρμογής", "App updates"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notify(version: String, target: Intent) {
        val t = txtFor(AppGraph.get(context).state.value.prefs.language)
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager, t)
        val pending = PendingIntent.getActivity(context, 79, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            manager.notify("update", 2, NotificationCompat.Builder(context, "updates").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(t("Νέα έκδοση MatchMate $version", "MatchMate $version is available"))
                .setContentText(t("Πάτησε για εγκατάσταση.", "Tap to install."))
                .setContentIntent(pending).setAutoCancel(true).build())
        } catch (_: SecurityException) { }
    }

    fun dismissError() { if (mutable.value is UpdateState.Failed) mutable.value = UpdateState.Idle }

    /** Remove downloaded APKs that are not newer than the running app (after a successful update). */
    private fun cleanup() {
        dir.listFiles()?.forEach { f ->
            val v = f.name.removePrefix("MatchMate-").removeSuffix(".apk").removeSuffix(".apk.part")
            if (f.name.endsWith(".part") || !isNewerVersion(v, currentVersion)) f.delete()
        }
    }

    /** Background: download + install silently when allowed; otherwise notify once per version. */
    suspend fun backgroundCheck() {
        if (!autoMutable.value) return
        val info = check() ?: return
        if (autoInstallMutable.value && installAllowed()) {
            download()
            if (mutable.value is UpdateState.Ready && !foreground) { installSession(userInitiated = false); return }
        }
        if (store.getString("notified", null) == info.version) return
        notify(info.version, Intent(context, MainActivity::class.java).putExtra("openUpdate", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        store.edit().putString("notified", info.version).apply()
    }

    companion object {
        private const val WORK = "app-update-check"
        @Volatile private var instance: UpdateManager? = null
        fun get(context: Context): UpdateManager = instance ?: synchronized(this) {
            instance ?: UpdateManager(context).also { instance = it }
        }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class UpdateInstallReceiver : android.content.BroadcastReceiver() {
    companion object { const val ACTION = "gr.matchbell.app.action.INSTALL_STATUS" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        UpdateManager.get(context).onInstallStatus(status, confirm)
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UpdateManager.get(applicationContext).backgroundCheck()
        return Result.success()
    }
}
