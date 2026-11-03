@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package gr.matchbell.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.launch
import java.time.Instant

/** "Updates" block used in Settings → About and in the About sheet. */
@Composable fun UpdatePanel(modifier: Modifier = Modifier) {
    val t = LocalTxt.current
    val context = LocalContext.current
    val manager = remember { UpdateManager.get(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    val auto by manager.autoCheck.collectAsStateWithLifecycle()
    val autoInstall by manager.autoInstall.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var needsPermission by remember { mutableStateOf(false) }
    // Re-read the "install unknown apps" permission whenever the user comes back from Settings.
    var installAllowed by remember { mutableStateOf(manager.installAllowed()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { installAllowed = manager.installAllowed(); if (installAllowed) needsPermission = false }
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Ενημερώσεις εφαρμογής", "App updates"), style = MaterialTheme.typography.titleMedium)
                Text(t("Εγκατεστημένη: ", "Installed: ") + manager.currentVersion,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        when (val s = state) {
            UpdateState.Idle -> {}
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text(t("Έλεγχος…", "Checking…"), style = MaterialTheme.typography.bodyMedium)
            }
            is UpdateState.UpToDate -> Text("✓ " + t("Έχεις την τελευταία έκδοση.", "You're on the latest version.") + "  " +
                Instant.ofEpochMilli(s.checkedAt).atZone(Athens).format(t.pattern("dd/MM HH:mm")),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            is UpdateState.Available -> {
                Text(t("Νέα έκδοση ${s.info.version}", "Version ${s.info.version} available"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                ReleaseNotes(s.info)
                Button(onClick = { scope.launch { manager.download() } }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(t("Λήψη", "Download") + if (s.info.apkSize > 0) " · %.1f MB".format(s.info.apkSize / 1_048_576.0) else "")
                }
            }
            is UpdateState.Downloading -> {
                Text(t("Λήψη ${s.info.version}… ${(s.progress * 100).toInt()}%", "Downloading ${s.info.version}… ${(s.progress * 100).toInt()}%"),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> {
                Text(t("Η έκδοση ${s.info.version} κατέβηκε και ελέγχθηκε.", "Version ${s.info.version} downloaded and verified."),
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Button(onClick = { needsPermission = !manager.install(context) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.InstallMobile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(t("Εγκατάσταση", "Install"))
                }
                if (needsPermission) Text(t("Βήμα 1: ενεργοποίησε «Να επιτρέπεται από αυτή την πηγή» για το MatchMate. Βήμα 2: γύρνα πίσω και πάτησε ξανά «Εγκατάσταση».",
                    "Step 1: turn on “Allow from this source” for MatchMate. Step 2: come back and tap “Install” again."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            is UpdateState.Installing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text(t("Εγκατάσταση ${s.info.version}… η εφαρμογή θα ξεκινήσει ξανά.", "Installing ${s.info.version}… the app will restart."),
                    style = MaterialTheme.typography.bodyMedium)
            }
            is UpdateState.Failed -> Text(when (s.reason) {
                "checksum" -> t("Το αρχείο δεν πέρασε τον έλεγχο SHA-256 και διαγράφηκε.", "The file failed the SHA-256 check and was deleted.")
                "download" -> t("Η λήψη απέτυχε. Δοκίμασε ξανά.", "Download failed. Try again.")
                "install" -> t("Η εγκατάσταση δεν ολοκληρώθηκε. Δοκίμασε ξανά.", "Installation didn't finish. Try again.")
                else -> t("Δεν ήταν δυνατός ο έλεγχος. Έλεγξε τη σύνδεση.", "Couldn't check. Check your connection.")
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (state !is UpdateState.Downloading && state !is UpdateState.Ready && state !is UpdateState.Available && state !is UpdateState.Installing) {
            TextButton(onClick = { scope.launch { manager.check() } }, enabled = state != UpdateState.Checking) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(t("Έλεγχος για ενημέρωση", "Check for updates"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Αυτόματος έλεγχος (1 φορά τη μέρα)", "Check automatically (daily)"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(auto, { manager.setAutoCheck(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("Αυτόματη εγκατάσταση (OTA)", "Install automatically (OTA)"), style = MaterialTheme.typography.bodyMedium)
                Text(if (manager.silentSupported) t("Κατεβαίνει μόνη της και εγκαθίσταται όταν βγεις από την εφαρμογή — χωρίς πάτημα.",
                        "Downloads by itself and installs when you leave the app — no taps.")
                    else t("Κατεβαίνει μόνη της· το Android σου ζητά ένα πάτημα για την εγκατάσταση.",
                        "Downloads by itself; your Android asks for one tap to install."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(autoInstall && auto, { manager.setAutoInstall(it) }, enabled = auto)
        }
        if (autoInstall && auto && !installAllowed) {
            Spacer(Modifier.height(6.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(t("Μία φορά μόνο: επίτρεψε στο MatchMate να εγκαθιστά τις ενημερώσεις του.",
                        "One time only: allow MatchMate to install its own updates."), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openUnknownSources(context) }) {
                        Text(t("Άνοιγμα ρύθμισης", "Open setting"))
                    }
                }
            }
        }
        Text(t("Πηγή: github.com/${UpdateSource.OWNER}/${UpdateSource.REPO} · Releases", "Source: github.com/${UpdateSource.OWNER}/${UpdateSource.REPO} · Releases"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ReleaseNotes(info: UpdateInfo) {
    if (info.notes.isBlank()) return
    Spacer(Modifier.height(6.dp))
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Text(info.notes.take(600), Modifier.padding(10.dp).fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
}

/** Slim banner on the schedule screen when a new version exists. */
@Composable fun UpdateBanner(modifier: Modifier, onOpen: () -> Unit) {
    val t = LocalTxt.current
    val context = LocalContext.current
    val manager = remember { UpdateManager.get(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    val version = when (val s = state) {
        is UpdateState.Available -> s.info.version
        is UpdateState.Downloading -> s.info.version
        is UpdateState.Ready -> s.info.version
        else -> return
    }
    Surface(onClick = onOpen, modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(9.dp))
            Text(t("Νέα έκδοση $version — πάτησε για ενημέρωση", "Version $version is out — tap to update"),
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        }
    }
}

fun openUnknownSources(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { }
}
