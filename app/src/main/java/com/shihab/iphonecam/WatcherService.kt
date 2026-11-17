package com.shihab.iphonecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that watches the phone's default camera folder
 * (DCIM/Camera) in near real time via a MediaStore ContentObserver.
 *
 * A new photo triggers a sweep (debounced 2 s so burst shots batch into
 * one run): grade the photo with the same v4 preset, save only the graded
 * copy to DCIM/PresetCamera, then delete the original silently — but only
 * if the one-time delete grant was given in onboarding (see DeleteHelper).
 * Without the grant the original stays queued and is retried on later
 * sweeps; no per-photo dialog is ever shown.
 *
 * The service is dataSync type and shows a persistent low-priority
 * notification (required by Android for foreground services). Tapping it
 * opens the watcher settings; the "Turn off" action stops the watcher.
 * The 15-minute WorkManager sweep (WatcherWorker) is the backstop for
 * photos taken while the service was stopped.
 */
class WatcherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSweep: Runnable? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleSweep()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        // Sweep once at start, in case photos were taken while stopped.
        scheduleSweep()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WatcherStore(this).enabled = false
            WatcherWorker.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        pendingSweep?.let { handler.removeCallbacks(it) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleSweep() {
        pendingSweep?.let { handler.removeCallbacks(it) }
        val run = Runnable {
            scope.launch {
                WatcherProcessor.processNewCameraPhotos(this@WatcherService)
                DeleteHelper.deletePendingOriginals(this@WatcherService)
            }
        }
        pendingSweep = run
        handler.postDelayed(run, 2000)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto-preset watcher",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Keeps the default-camera auto-preset running"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SCREEN, "watcher_settings")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, WatcherService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Preset Camera watcher is on")
            .setContentText("Photos from your camera get the preset automatically")
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Turn off",
                stopPending
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.shihab.iphonecam.STOP_WATCHER"
        private const val CHANNEL_ID = "watcher"

        fun start(context: Context) {
            val intent = Intent(context, WatcherService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // e.g. background start refused on newer Android;
                // the WorkManager sweep is the backstop.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatcherService::class.java))
        }
    }
}
