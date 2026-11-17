package com.shihab.iphonecam

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf

private enum class Screen { CAMERA, ONBOARDING, SETTINGS }

/**
 * Single-activity navigation:
 *  - CAMERA: the in-app CameraX camera (primary path).
 *  - ONBOARDING: first-run watcher onboarding (shown once, skippable).
 *  - SETTINGS: watcher settings (opened from the 6-dot controls button
 *    on the camera, and from the watcher notification).
 *
 * The watcher notification deep-links here with
 * EXTRA_OPEN_SCREEN = "watcher_settings"; launchMode singleTop routes
 * it through onNewIntent when the app is already open.
 */
class MainActivity : ComponentActivity() {
    private val screenState = mutableStateOf(Screen.CAMERA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = WatcherStore(this)
        if (intent?.getStringExtra(EXTRA_OPEN_SCREEN) == "watcher_settings") {
            screenState.value = Screen.SETTINGS
        } else if (!store.onboarded) {
            screenState.value = Screen.ONBOARDING
        }
        if (store.enabled) {
            WatcherWorker.enqueue(this)
            WatcherService.start(this)
        }
        setContent {
            when (screenState.value) {
                Screen.CAMERA -> CameraScreen(
                    onOpenWatcherSettings = { screenState.value = Screen.SETTINGS }
                )
                Screen.ONBOARDING -> WatcherOnboardingScreen(
                    onFinish = { enabled ->
                        store.onboarded = true
                        if (enabled) {
                            store.enabled = true
                            WatcherWorker.enqueue(this)
                            WatcherService.start(this)
                        }
                        screenState.value = Screen.CAMERA
                    }
                )
                Screen.SETTINGS -> WatcherSettingsScreen(
                    onBack = { screenState.value = Screen.CAMERA }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_OPEN_SCREEN) == "watcher_settings") {
            screenState.value = Screen.SETTINGS
        }
    }

    companion object {
        const val EXTRA_OPEN_SCREEN = "open_screen"
    }
}
