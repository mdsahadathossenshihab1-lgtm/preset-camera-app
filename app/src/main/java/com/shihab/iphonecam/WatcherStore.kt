package com.shihab.iphonecam

import android.content.Context

/**
 * Persistent state for the default-camera watcher, kept in SharedPreferences.
 *
 * - lastSeenAdded: MediaStore DATE_ADDED (seconds) watermark. Photos at or
 *   below it were already considered, so sweeps only look at newer photos.
 * - processedIds: source image IDs already graded (guards against
 *   re-processing when the watermark has not advanced past them).
 * - pendingDeleteUris: original (ungraded) photo URIs not yet deleted.
 *   Deletion is silent once the one-time delete grant is given
 *   (media management on Android 12+, all-files access on Android 11 —
 *   see DeleteHelper). Until the grant exists the URIs stay queued and
 *   are retried on later sweeps; the app never asks per photo.
 * - failureCounts: "id:count" entries so a permanently unreadable file
 *   is skipped after 3 failed attempts instead of retrying forever.
 */
class WatcherStore(context: Context) {
    private val prefs = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    var lastSeenAdded: Long
        get() = prefs.getLong("last_seen_added", 0L)
        set(value) = prefs.edit().putLong("last_seen_added", value).apply()

    fun isProcessed(id: Long): Boolean =
        prefs.getStringSet("processed_ids", emptySet())?.contains(id.toString()) == true

    fun markProcessed(id: Long) {
        val set = prefs.getStringSet("processed_ids", emptySet())?.toMutableSet()
            ?: mutableSetOf()
        set.add(id.toString())
        if (set.size > 500) set.clear()
        prefs.edit().putStringSet("processed_ids", set).apply()
    }

    /** Returns the new attempt count for this id. */
    fun recordFailure(id: Long): Int {
        val map = failureMap().toMutableMap()
        val count = (map[id] ?: 0) + 1
        map[id] = count
        prefs.edit()
            .putStringSet("failures", map.entries.map { "${it.key}:${it.value}" }.toSet())
            .apply()
        return count
    }

    fun clearFailure(id: Long) {
        val map = failureMap().toMutableMap()
        map.remove(id)
        prefs.edit()
            .putStringSet("failures", map.entries.map { "${it.key}:${it.value}" }.toSet())
            .apply()
    }

    private fun failureMap(): Map<Long, Int> {
        val out = mutableMapOf<Long, Int>()
        prefs.getStringSet("failures", emptySet())?.forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                parts[0].toLongOrNull()?.let { id ->
                    parts[1].toIntOrNull()?.let { count -> out[id] = count }
                }
            }
        }
        return out
    }

    fun pendingDeleteUris(): Set<String> =
        prefs.getStringSet("pending_delete_uris", emptySet()) ?: emptySet()

    fun addPendingDelete(uri: String) {
        val set = pendingDeleteUris().toMutableSet()
        set.add(uri)
        prefs.edit().putStringSet("pending_delete_uris", set).apply()
    }

    fun removePendingDelete(uris: Collection<String>) {
        val set = pendingDeleteUris().toMutableSet()
        set.removeAll(uris.toSet())
        prefs.edit().putStringSet("pending_delete_uris", set).apply()
    }
}
