package com.shihab.iphonecam

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared photo pipeline used by BOTH capture paths:
 *  - the in-app CameraX camera (CameraScreen)
 *  - the default-camera folder watcher (WatcherProcessor)
 *
 * The original photo is only ever read from a private temp file or a
 * MediaStore stream; the graded copy is the only thing saved to the
 * gallery, always into DCIM/PresetCamera.
 */

fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

fun Context.mediaPermissionName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun Context.hasMediaPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, mediaPermissionName()) ==
        PackageManager.PERMISSION_GRANTED

/** Decode + EXIF-rotate + grade a captured JPEG file. Caller recycles the result. */
suspend fun processCapturedFile(file: File): Bitmap {
    // Downsample very large sensors to a ~12 MP working size so the
    // preset stays fast (the watcher path already caps at ~16 MP).
    // Without this, a 50/108 MP capture makes grading take many seconds.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val totalPixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
    var sample = 1
    while (totalPixels / (sample.toLong() * sample) > 12_000_000L) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
        ?: throw IllegalStateException("Could not decode captured photo")
    bitmap = rotateUpright(file, bitmap)
    val graded = Preset.applyPreset(bitmap)
    bitmap.recycle()
    return graded
}

/**
 * Grade a photo read from a MediaStore URI (default-camera watcher path).
 * Decodes with sampling so very large sensors (50/108 MP) stay within a
 * ~16 MP working size, then applies the same preset at full working size.
 */
suspend fun gradeMediaImage(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: throw IllegalStateException("Could not open photo")
    val totalPixels = (bounds.outWidth.toLong() * bounds.outHeight.toLong())
    var sample = 1
    while (totalPixels / (sample.toLong() * sample) > 16_000_000L) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bitmap = resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    } ?: throw IllegalStateException("Could not decode photo")
    // Correct EXIF orientation from the stream.
    resolver.openInputStream(uri)?.use { input ->
        val exif = ExifInterface(input)
        val rotation = when (
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            bitmap.recycle()
            bitmap = rotated
        }
    }
    val graded = Preset.applyPreset(bitmap)
    bitmap.recycle()
    return graded
}

private fun rotateUpright(file: File, bitmap: Bitmap): Bitmap {
    val exif = ExifInterface(file)
    val rotation = when (
        exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (rotation == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(rotation) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    bitmap.recycle()
    return rotated
}

/** Save ONLY the graded bitmap to the gallery (DCIM/PresetCamera). Returns its URI. */
fun saveToGallery(context: Context, bitmap: Bitmap): Uri {
    val name = "PresetCamera_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PresetCamera")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Could not create gallery entry")
    resolver.openOutputStream(uri)?.use { out ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
            throw IllegalStateException("JPEG encode failed")
        }
    } ?: throw IllegalStateException("Could not open gallery entry")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val done = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        resolver.update(uri, done, null, null)
    }
    return uri
}

/** The most recent photo in the gallery, for the round thumbnail. */
fun loadLatestThumbnail(context: Context): Bitmap? {
    return try {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val id = cursor.getLong(0)
                val itemUri = ContentUris.withAppendedId(uri, id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(itemUri, Size(256, 256), null)
                } else {
                    context.contentResolver.openInputStream(itemUri)?.use { input ->
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = 8
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeStream(input, null, opts)
                    }
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}
