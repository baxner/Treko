package com.treko.core.common.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Pictures of the alerts, under `filesDir/avisos/snaps` (excluded from cloud backup and
 * device transfer). Each alert keeps a full picture (long side at most [FULL_MAX_SIDE_PX])
 * and a thumbnail ([THUMB_MAX_SIDE_PX]). Total size is capped at [capBytes]: the oldest
 * pictures go first and [onEvicted] is told which files were removed.
 */
class AlertSnapshotStore(
    private val dir: File,
    private val capBytes: Long = DEFAULT_CAP_BYTES,
    private val onEvicted: (List<String>) -> Unit = {}
) {
    data class Saved(val fullPath: String, val thumbPath: String?)

    /** Decodes [imageBytes] and writes both JPEGs off the main thread. Null if it is not an image. */
    suspend fun save(alertId: Long, imageBytes: ByteArray): Saved? = withContext(Dispatchers.Default) {
        try {
            saveBlocking(alertId, imageBytes)
        } catch (e: Exception) {
            TrekoLogBuffer.w(TAG, "Cannot save the picture of alert $alertId: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            TrekoLogBuffer.w(TAG, "Picture of alert $alertId too large to decode")
            null
        }
    }

    private fun saveBlocking(alertId: Long, imageBytes: ByteArray): Saved? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, FULL_MAX_SIDE_PX)
        }
        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options) ?: return null
        dir.mkdirs()
        val fullFile = File(dir, "$alertId$FULL_SUFFIX")
        val thumbFile = File(dir, "$alertId$THUMB_SUFFIX")
        val full = scaleDown(decoded, FULL_MAX_SIDE_PX)
        try {
            writeJpeg(full, fullFile, FULL_QUALITY)
            val thumb = scaleDown(full, THUMB_MAX_SIDE_PX)
            val thumbOk = try {
                writeJpeg(thumb, thumbFile, THUMB_QUALITY)
            } finally {
                if (thumb !== full) thumb.recycle()
            }
            enforceCap(protectedGroup = alertId.toString())
            return Saved(fullFile.absolutePath, thumbFile.absolutePath.takeIf { thumbOk })
        } finally {
            if (full !== decoded) full.recycle()
            decoded.recycle()
        }
    }

    /** Removes picture files (an alert evicted or deleted). Missing files are fine. */
    fun deleteFiles(paths: List<String>) {
        paths.forEach { path ->
            val file = File(path)
            // Only files of this store, never anything else the path might point to.
            if (file.parentFile?.absolutePath == dir.absolutePath) file.delete()
        }
    }

    /** Total size of the stored pictures. */
    fun totalBytes(): Long = listEntries().sumOf { it.sizeBytes }

    /**
     * Deletes pictures no entry refers to (left behind by a crash between saving and
     * recording), older than an hour so a save in progress is never touched.
     */
    fun sweepOrphans(referencedPaths: Set<String>, nowMs: Long = System.currentTimeMillis()) {
        val orphans = listEntries().filter { it.path !in referencedPaths && nowMs - it.modifiedMs > ORPHAN_AGE_MS }
        orphans.forEach { File(it.path).delete() }
        if (orphans.isNotEmpty()) TrekoLogBuffer.i(TAG, "Removed ${orphans.size} pictures no alert refers to.")
    }

    private fun enforceCap(protectedGroup: String) {
        val victims = pickEvictions(listEntries(), capBytes, protectedGroup)
        if (victims.isEmpty()) return
        victims.forEach { File(it.path).delete() }
        TrekoLogBuffer.i(TAG, "Picture storage over ${capBytes / (1024 * 1024)} MB: removed ${victims.size} old pictures.")
        onEvicted(victims.map { it.path })
    }

    private fun listEntries(): List<Entry> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }.orEmpty().map { f ->
            Entry(f.absolutePath, f.length(), f.lastModified(), groupOf(f.name))
        }

    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int): Boolean =
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val (w, h) = scaledSize(bitmap.width, bitmap.height, maxSide)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** One stored file; [group] is the alert id it belongs to (full and thumbnail share it). */
    internal data class Entry(val path: String, val sizeBytes: Long, val modifiedMs: Long, val group: String)

    companion object {
        private const val TAG = "AlertSnapshots"
        const val DIR_NAME = "avisos"
        const val SNAPSHOTS_DIR_NAME = "snaps"
        const val DEFAULT_CAP_BYTES = 50L * 1024 * 1024
        const val FULL_MAX_SIDE_PX = 1280
        const val THUMB_MAX_SIDE_PX = 320
        private const val FULL_QUALITY = 80
        private const val THUMB_QUALITY = 70
        private const val FULL_SUFFIX = ".jpg"
        private const val THUMB_SUFFIX = "_t.jpg"
        private const val ORPHAN_AGE_MS = 60 * 60_000L

        internal fun groupOf(fileName: String): String =
            fileName.removeSuffix(THUMB_SUFFIX).removeSuffix(FULL_SUFFIX)

        /** Largest power of two that keeps the decoded long side at least [targetMaxSide]. */
        internal fun sampleSizeFor(width: Int, height: Int, targetMaxSide: Int): Int {
            var sample = 1
            val longSide = maxOf(width, height)
            while (longSide / (sample * 2) >= targetMaxSide) sample *= 2
            return sample
        }

        /** Size that fits [maxSide] on the long side, keeping the aspect ratio; never upscales. */
        internal fun scaledSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
            val longSide = maxOf(width, height)
            if (longSide <= maxSide) return width to height
            val ratio = maxSide.toDouble() / longSide
            return (width * ratio).roundToInt().coerceAtLeast(1) to (height * ratio).roundToInt().coerceAtLeast(1)
        }

        /**
         * Which files to delete so the total fits [capBytes]: whole alerts (full and
         * thumbnail together), oldest first, never [protectedGroup] (the picture just saved).
         */
        internal fun pickEvictions(entries: List<Entry>, capBytes: Long, protectedGroup: String?): List<Entry> {
            var total = entries.sumOf { it.sizeBytes }
            if (total <= capBytes) return emptyList()
            val groups = entries.filter { it.group != protectedGroup }
                .groupBy { it.group }
                .values
                .sortedBy { files -> files.minOf { it.modifiedMs } }
            val victims = ArrayList<Entry>()
            for (files in groups) {
                if (total <= capBytes) break
                victims += files
                total -= files.sumOf { it.sizeBytes }
            }
            return victims
        }
    }
}
