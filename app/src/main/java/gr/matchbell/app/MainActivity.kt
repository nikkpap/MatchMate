package gr.matchbell.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openedEvent by mutableStateOf<String?>(null)
    private var openUpdate by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        val graph = AppGraph.get(this)
        // Avoid a dark flash before Compose draws when the user picked the light theme.
        val prefs = graph.state.value.prefs
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val lightStart = prefs.theme == "light" || (prefs.theme == "system" && !systemDark)
        window.setBackgroundDrawable(ColorDrawable(if (lightStart) 0xFFF4F7FB.toInt() else if (prefs.theme == "amoled") 0xFF000000.toInt() else 0xFF0B1020.toInt()))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Fix: after rotation the launch intent is redelivered; do not reopen an already handled event.
        if (savedInstanceState == null) {
            openedEvent = intent.getStringExtra("eventId")
            openUpdate = intent.getBooleanExtra("openUpdate", false)
        }
        val updates = UpdateManager.get(this)
        setContent {
            val state by graph.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                scope.launch { graph.restore() }
            }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        scope.launch { graph.onResume() }
                        scope.launch { updates.checkIfDue() }
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            MatchMateTheme(state.prefs) {
                val background = MaterialTheme.colorScheme.background
                val lightBars = background.luminance() > 0.5f
                SideEffect {
                    window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                MatchMateApp(state, openedEvent, { openedEvent = null }, openUpdate, { openUpdate = false },
                    onUpdate = { transform -> scope.launch { graph.update(transform) } },
                    onRefresh = { scope.launch { graph.refresh() } },
                    onDismissError = { graph.clearError() },
                    onNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33 && !state.notificationsAllowed) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else openNotificationSettings()
                    },
                    onExactPermission = {
                        if (Build.VERSION.SDK_INT >= 31) safeStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")))
                    },
                    onSystemSettings = { openNotificationSettings() },
                    onTest = { graph.testNotification() })
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedEvent = intent.getStringExtra("eventId")
        if (intent.getBooleanExtra("openUpdate", false)) openUpdate = true
    }
    private fun safeStart(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
    private fun openNotificationSettings() {
        safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
}
