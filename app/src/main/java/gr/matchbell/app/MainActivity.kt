package gr.matchbell.app

import android.Manifest
import android.content.Intent
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openedEvent by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openedEvent = intent.getStringExtra("eventId")
        val graph = AppGraph.get(this)
        setContent {
            val state by graph.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                scope.launch { graph.restore() }
            }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) scope.launch { graph.onResume() }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            MatchMateTheme(state.prefs.theme) {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                MatchMateApp(state, openedEvent, { openedEvent = null },
                    onUpdate = { transform -> scope.launch { graph.update(transform) } },
                    onRefresh = { scope.launch { graph.refresh() } },
                    onNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33 && !state.notificationsAllowed) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else openNotificationSettings()
                    },
                    onExactPermission = {
                        if (Build.VERSION.SDK_INT >= 31) {
                            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:$packageName")))
                        }
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
    }
    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
}
