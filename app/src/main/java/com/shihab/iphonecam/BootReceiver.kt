package com.shihab.iphonecam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the default-camera watcher after a reboot, when enabled.
 * The foreground-service start is best-effort — on newer Android the
 * system may refuse a foreground-service start from the background, in
 * which case the 15-minute WorkManager sweep (always enqueued here) is
 * the backstop until the user opens the app again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!WatcherStore(context).enabled) return
        WatcherWorker.enqueue(context)
        try {
            WatcherService.start(context)
        } catch (e: Exception) {
            // Backstop sweep above covers it.
        }
    }
}
