package com.shihab.iphonecam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * One-time delete permission for the default-camera watcher.
 *
 * The watcher deletes the ORIGINAL (ungraded) photo after the graded copy
 * is saved. Android never allows silent deletion of another app's photos
 * without a one-time grant from the user, so the app asks ONCE in
 * onboarding and never shows a per-photo confirmation dialog afterwards:
 *
 *  - Android 12+ (API 31+): the MEDIA MANAGEMENT grant
 *    (android.permission.MANAGE_MEDIA). Requested with
 *    Settings.ACTION_REQUEST_MANAGE_MEDIA; checked with
 *    MediaStore.canManageMedia(). This is the narrow, purpose-built grant
 *    for apps that manage media files — it covers only media, not all files.
 *  - Android 11 (API 30): media management does not exist yet, so the
 *    fallback is ALL FILES ACCESS (MANAGE_EXTERNAL_STORAGE), requested
 *    with Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION and
 *    checked with Environment.isExternalStorageManager().
 *  - Android 9 and older (API 26-29): the WRITE_EXTERNAL_STORAGE runtime
 *    permission. (On API 29 scoped storage is already enforced, so the
 *    grant may still not allow silent deletion there — the original then
 *    simply stays queued, see below.)
 *
 * If the grant is missing, deletion is NOT attempted through a dialog:
 * the original's URI stays in WatcherStore.pendingDeleteUris and is
 * retried silently on later sweeps, once the user grants the permission
 * in the watcher settings screen. No per-photo system dialog is ever used
 * as the normal path — that is the whole point of the one-time grant.
 *
 * NOTE: MANAGE_EXTERNAL_STORAGE is restricted by Google Play policy
 * (file-manager-class apps only). This beta APK is sideloaded directly,
 * so it is not a problem here, but if the app ever goes on the Play
 * Store the Android 11 fallback would need a policy justification or
 * removal. The Android 12+ media-management grant has no such restriction.
 */
object DeleteHelper {

    /** True when the watcher may delete other apps' photos silently. */
    fun canDeleteSilently(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            MediaStore.canManageMedia(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Environment.isExternalStorageManager()
        else ->
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
    }

    /** Human-readable name of the one-time grant on this Android version. */
    fun grantName(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "Media management"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "All files access"
        else -> "Storage write access"
    }

    /**
     * Intent that opens the one-time grant screen, or null on API < 30
     * where the grant is a normal runtime permission (WRITE_EXTERNAL_STORAGE)
     * requested with a permission launcher instead.
     */
    fun grantIntent(context: Context): Intent? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        else -> null
    }

    /**
     * Best-effort silent delete of another app's photo. Returns true when
     * the file is gone. Returns false (never throws, never prompts) when
     * the one-time grant is missing or the file is already gone — the
     * caller keeps the URI queued and retries later.
     */
    fun deleteSilently(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Try to delete every queued original now (e.g. after the user granted
     * the one-time permission, or on a later sweep). Returns
     * (deletedCount, stillPendingCount).
     */
    fun deletePendingOriginals(context: Context): Pair<Int, Int> {
        val store = WatcherStore(context)
        val pending = store.pendingDeleteUris().toList()
        val deleted = mutableListOf<String>()
        for (raw in pending) {
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: continue
            if (deleteSilently(context, uri)) deleted.add(raw)
        }
        store.removePendingDelete(deleted)
        return deleted.size to store.pendingDeleteUris().size
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
