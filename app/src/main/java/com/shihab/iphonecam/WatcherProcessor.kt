package com.shihab.iphonecam

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Default-camera watcher: finds new photos in the phone's default camera
 * folder (DCIM/Camera), grades them with the same v4 preset on-device,
 * saves ONLY the graded copy to the gallery (DCIM/PresetCamera), and
 * deletes the original silently — but only when the one-time delete grant
 * was given in onboarding (see DeleteHelper). Without the grant the
 * original stays queued and is retried on later sweeps; the app never
 * shows a per-photo confirmation dialog.
 *
 * Used by both the foreground service (near real-time, via ContentObserver)
 * and the periodic WorkManager sweep (backstop for missed photos).
 *
 * Honest limits (also shown in the app's onboarding screen):
 *  - The graded copy appears a few seconds after the shot.
 *  - The original stays visible in the gallery until it is deleted
 *    (until the one-time delete permission is granted, if not yet).
 */
object WatcherProcessor {

    data class Result(val processed: Int, val pendingDeletes: Int, val failed: Int)

    private data class Candidate(val id: Long, val uri: Uri, val dateAdded: Long)

    suspend fun processNewCameraPhotos(context: Context): Result =
        withContext(Dispatchers.Default) {
            val store = WatcherStore(context)
            val candidates = queryNewCameraPhotos(context, store.lastSeenAdded)
            var processed = 0
            var failed = 0
            var watermark = store.lastSeenAdded
            for (candidate in candidates) {
                if (store.isProcessed(candidate.id)) {
                    watermark = maxOf(watermark, candidate.dateAdded)
                    continue
                }
                // Wait until the camera app has finished writing the file.
                if (!isStable(context, candidate.uri)) {
                    continue // do not advance the watermark past it; retry next sweep
                }
                try {
                    val graded = gradeMediaImage(context, candidate.uri)
                    try {
                        saveToGallery(context, graded)
                    } finally {
                        graded.recycle()
                    }
                    store.markProcessed(candidate.id)
                    store.clearFailure(candidate.id)
                    // Delete the original silently when the one-time grant
                    // exists; otherwise queue it for a later sweep (never a
                    // per-photo confirmation dialog).
                    if (!DeleteHelper.deleteSilently(context, candidate.uri)) {
                        store.addPendingDelete(candidate.uri.toString())
                    }
                    watermark = maxOf(watermark, candidate.dateAdded)
                    processed++
                } catch (e: Exception) {
                    failed++
                    val attempts = store.recordFailure(candidate.id)
                    if (attempts >= 3) {
                        // Permanently unreadable; stop retrying it.
                        store.markProcessed(candidate.id)
                        watermark = maxOf(watermark, candidate.dateAdded)
                    }
                }
            }
            store.lastSeenAdded = watermark
            Result(processed, store.pendingDeleteUris().size, failed)
        }

    private fun queryNewCameraPhotos(context: Context, sinceAdded: Long): List<Candidate> {
        val out = ArrayList<Candidate>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.Images.Media.DATE_ADDED + " > ? AND " +
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
            selectionArgs = arrayOf(sinceAdded.toString(), "DCIM/Camera%")
        } else {
            selection = MediaStore.Images.Media.DATE_ADDED + " > ? AND " +
                MediaStore.Images.Media.DATA + " LIKE ?"
            selectionArgs = arrayOf(sinceAdded.toString(), "%/DCIM/Camera/%")
        }
        try {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                MediaStore.Images.Media.DATE_ADDED + " ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val dateAdded = cursor.getLong(dateCol)
                    out.add(Candidate(id, ContentUris.withAppendedId(uri, id), dateAdded))
                }
            }
        } catch (e: Exception) {
            // MediaStore unavailable; the next sweep retries.
        }
        return out
    }

    /** True when the file size is unchanged across a short delay. */
    private suspend fun isStable(context: Context, uri: Uri): Boolean {
        val first = fileSize(context, uri) ?: return false
        delay(800)
        val second = fileSize(context, uri) ?: return false
        return first == second
    }

    private fun fileSize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
