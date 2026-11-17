package com.shihab.iphonecam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run onboarding for the default-camera watcher, and the watcher
 * settings screen (reached from the 6-dot controls button on the camera).
 *
 * DELETE PERMISSION — ONE TIME ONLY, never a per-photo dialog:
 * the app asks once here for the system's media-management grant
 * (Android 12+) or all-files access (Android 11). After the user grants
 * it once, the watcher deletes originals silently. If the grant is
 * missing, originals stay queued — the app never shows a per-photo
 * confirmation dialog as the normal path.
 */

@Composable
fun WatcherOnboardingScreen(onFinish: (watcherEnabled: Boolean) -> Unit) {
    val context = LocalContext.current
    var grantState by remember { mutableStateOf(DeleteHelper.canDeleteSilently(context)) }
    var enabling by remember { mutableStateOf(false) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { grantState = DeleteHelper.canDeleteSilently(context) }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { grantState = DeleteHelper.canDeleteSilently(context) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification is optional; the watcher works without it */ }

    fun requestGrant() {
        val intent = DeleteHelper.grantIntent(context)
        if (intent != null) {
            try {
                settingsLauncher.launch(intent)
            } catch (e: Exception) {
                grantState = DeleteHelper.canDeleteSilently(context)
            }
        } else {
            // API < 30: the grant is a normal runtime permission.
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun enableWatcher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enabling = true
        onFinish(true)
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            "Auto-preset for your default camera",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "When you take a photo with your phone's normal camera app, " +
                "Preset Camera notices it, applies the same preset on-device, " +
                "and saves only the graded copy to your gallery.",
            color = Color.White,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(16.dp))
        Text("Please note", fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "• The graded copy appears a few seconds after you take the shot.\n" +
                "• The original is briefly visible in the gallery until it is deleted.\n" +
                "• The watcher keeps a small notification while it runs, so Android " +
                "does not stop it. A check also runs every 15 minutes as a backstop.",
            color = Color.White,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = Color(0xFF3A3A3A))
        Spacer(Modifier.height(20.dp))
        Text(
            "Delete permission — one time only",
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "So the watcher can delete the original photo after grading it, " +
                "Android needs you to allow it ONCE. After that, originals are " +
                "deleted silently — you will never be asked again for each photo.",
            color = Color.White,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Delete permission (${DeleteHelper.grantName()}): " +
                if (grantState) "Granted" else "Not granted yet",
            color = if (grantState) Color(0xFF4CD964) else Color(0xFFFFD60A),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        if (!grantState) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { requestGrant() }) {
                Text("Grant one-time delete permission")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "You can also turn the watcher on now and grant this later " +
                    "from the watcher settings — originals will wait until then.",
                color = Color(0xFFBBBBBB),
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { enableWatcher() },
            enabled = !enabling,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Turn on auto-preset")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { onFinish(false) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Skip for now", color = Color.White)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun WatcherSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { WatcherStore(context) }
    var watcherOn by remember { mutableStateOf(store.enabled) }
    var grantState by remember { mutableStateOf(DeleteHelper.canDeleteSilently(context)) }
    var batteryState by remember {
        mutableStateOf(DeleteHelper.isIgnoringBatteryOptimizations(context))
    }
    var pendingCount by remember { mutableStateOf(store.pendingDeleteUris().size) }
    var deleting by remember { mutableStateOf(false) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        grantState = DeleteHelper.canDeleteSilently(context)
        batteryState = DeleteHelper.isIgnoringBatteryOptimizations(context)
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { grantState = DeleteHelper.canDeleteSilently(context) }

    fun refresh() {
        grantState = DeleteHelper.canDeleteSilently(context)
        batteryState = DeleteHelper.isIgnoringBatteryOptimizations(context)
        pendingCount = store.pendingDeleteUris().size
    }

    fun requestGrant() {
        val intent = DeleteHelper.grantIntent(context)
        if (intent != null) {
            try {
                settingsLauncher.launch(intent)
            } catch (e: Exception) {
                refresh()
            }
        } else {
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun setWatcher(on: Boolean) {
        watcherOn = on
        store.enabled = on
        if (on) {
            WatcherService.start(context)
            WatcherWorker.enqueue(context)
        } else {
            WatcherService.stop(context)
            WatcherWorker.cancel(context)
        }
    }

    fun deleteNow() {
        deleting = true
        scope.launch(Dispatchers.Default) {
            val (deleted, remaining) = DeleteHelper.deletePendingOriginals(context)
            withContext(Dispatchers.Main) {
                deleting = false
                refresh()
                Toast.makeText(
                    context,
                    if (deleted > 0) {
                        "Deleted $deleted original(s)" +
                            if (remaining > 0) " — $remaining still waiting" else ""
                    } else {
                        "Nothing deleted — grant the one-time delete permission first"
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            "Auto-preset settings",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Photos you take with your phone's normal camera app are graded " +
                "with the same preset and saved to DCIM/PresetCamera. " +
                "The graded copy appears a few seconds after the shot; the " +
                "original is briefly visible until it is deleted.",
            color = Color.White,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Auto-preset watcher",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = watcherOn, onCheckedChange = { setWatcher(it) })
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = Color(0xFF3A3A3A))
        Spacer(Modifier.height(20.dp))

        Text(
            "Delete permission (${DeleteHelper.grantName()}) — one time only",
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Status: " + if (grantState) "Granted — originals are deleted silently"
            else "Not granted yet",
            color = if (grantState) Color(0xFF4CD964) else Color(0xFFFFD60A),
            fontSize = 15.sp
        )
        if (!grantState) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Without this, graded copies are still saved, but originals " +
                    "stay in your gallery until you grant the permission. " +
                    "You are never asked once per photo.",
                color = Color(0xFFBBBBBB),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { requestGrant() }) {
                Text("Grant one-time delete permission")
            }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = Color(0xFF3A3A3A))
        Spacer(Modifier.height(20.dp))

        Text(
            "Originals waiting to be deleted: $pendingCount",
            color = Color.White,
            fontSize = 15.sp
        )
        if (pendingCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "These are deleted automatically once the permission above " +
                    "is granted. You can also try now:",
                color = Color(0xFFBBBBBB),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { deleteNow() }, enabled = !deleting) {
                Text(if (deleting) "Deleting…" else "Delete originals now")
            }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = Color(0xFF3A3A3A))
        Spacer(Modifier.height(20.dp))

        Text("Battery optimisation", fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "Status: " + if (batteryState) "Watcher is exempt — it keeps running"
            else "Not exempt — Android may pause the watcher to save battery",
            color = Color.White,
            fontSize = 15.sp
        )
        if (!batteryState) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                try {
                    settingsLauncher.launch(DeleteHelper.batteryIntent(context))
                } catch (e: Exception) {
                    refresh()
                }
            }) {
                Text("Allow running in background", color = Color.White)
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to camera", color = Color.White)
        }
        Spacer(Modifier.height(24.dp))
    }
}
