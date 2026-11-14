package com.treko.assistant.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.treko.assistant.R
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.model.CameraFeedState
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal val sharedCameraHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
}

internal data class CameraFrameResult(
    val bitmap: ImageBitmap? = null,
    val errorMessage: String? = null
)

@Composable
fun CameraViewerDialog(
    feed: CameraFeedState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(feed.entityId) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(feed.entityId) { mutableStateOf(true) }
    var errorMessage by remember(feed.entityId) { mutableStateOf<String?>(null) }
    var isLiveMode by remember(feed.entityId, feed.isLive) { mutableStateOf(feed.isLive) }
    var refreshTrigger by remember(feed.entityId) { mutableIntStateOf(0) }
    var updatedAtText by remember(feed.entityId) { mutableStateOf<String?>(null) }

    val effectiveEntityId = resolveEffectiveCameraEntityId(feed, isLiveMode)

    LaunchedEffect(feed.entityId, effectiveEntityId, isLiveMode, refreshTrigger) {
        if (bitmap == null) {
            isLoading = true
        }
        val result = fetchCameraFrame(
            context = context,
            feed = feed,
            isLiveMode = isLiveMode,
            maxDimension = 1280,
            logTag = "CameraViewer"
        )
        if (result.bitmap != null) {
            bitmap = result.bitmap
            errorMessage = null
            updatedAtText = formatCameraTimestamp()
        } else {
            errorMessage = result.errorMessage ?: context.getString(R.string.camera_no_signal)
        }
        isLoading = false
    }

    LaunchedEffect(feed.entityId, isLiveMode) {
        while (isLiveMode) {
            delay(2000L)
            refreshTrigger++
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, NeonCyan),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, tint = NeonCyan)
                        Column {
                            Text(feed.friendlyName, color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                text = buildString {
                                    append(
                                        if (isLiveMode) {
                                            stringResource(R.string.camera_live_status)
                                        } else {
                                            stringResource(R.string.camera_snapshot_status)
                                        }
                                    )
                                    updatedAtText?.let {
                                        append(" · ")
                                        append(stringResource(R.string.camera_updated_at, it))
                                    }
                                },
                                color = if (isLiveMode) Color(0xFF00E676) else TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = TextSecondary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Crossfade(targetState = bmp, animationSpec = tween(180), label = "CameraFrameFade") { frame ->
                            Image(
                                bitmap = frame,
                                contentDescription = feed.friendlyName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else if (isLoading) {
                        CircularProgressIndicator(color = NeonCyan)
                    } else {
                        Text(
                            text = errorMessage ?: stringResource(R.string.camera_no_signal),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { refreshTrigger++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_refresh), color = NeonCyan, fontSize = 12.sp)
                        }

                        TextButton(onClick = { isLiveMode = !isLiveMode }) {
                            Icon(
                                imageVector = if (isLiveMode) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isLiveMode) {
                                    stringResource(R.string.camera_toggle_snapshot)
                                } else {
                                    stringResource(R.string.camera_toggle_live)
                                },
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

internal fun formatCameraTimestamp(nowMillis: Long = System.currentTimeMillis()): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMillis))
}

internal fun resolveEffectiveCameraEntityId(feed: CameraFeedState, isLiveMode: Boolean): String {
    val liveId = feed.liveEntityId?.trim()
    return if (isLiveMode && !liveId.isNullOrEmpty()) liveId else feed.entityId
}

internal fun resolveCameraSnapshotUrl(feed: CameraFeedState, isLiveMode: Boolean): String {
    val effectiveEntityId = resolveEffectiveCameraEntityId(feed, isLiveMode)
    val useLiveEntity = isLiveMode && !feed.liveEntityId.isNullOrBlank()
    val cleanBase = feed.haUrl.trim().removeSuffix("/").removeSuffix("/api")
    if (useLiveEntity) {
        if (cleanBase.isNotBlank()) {
            return "$cleanBase/api/camera_proxy/$effectiveEntityId"
        }
        if (feed.snapshotUrl.isNotBlank() && feed.entityId.isNotBlank() && feed.snapshotUrl.contains(feed.entityId)) {
            return feed.snapshotUrl.replace(feed.entityId, effectiveEntityId)
        }
    }
    return feed.snapshotUrl.ifBlank {
        if (cleanBase.isNotBlank() && effectiveEntityId.isNotBlank()) {
            "$cleanBase/api/camera_proxy/$effectiveEntityId"
        } else {
            ""
        }
    }
}

internal suspend fun fetchCameraFrame(
    context: Context,
    feed: CameraFeedState,
    isLiveMode: Boolean,
    maxDimension: Int? = 1280,
    logTag: String = "CameraViewer"
): CameraFrameResult = withContext(Dispatchers.IO) {
    val effectiveEntityId = resolveEffectiveCameraEntityId(feed, isLiveMode)
    val useLiveEntity = isLiveMode && effectiveEntityId != feed.entityId
    val job = coroutineContext[Job]
    try {
        var decoded: Bitmap? = null
        var failReason: String? = null

        val primaryUrl = resolveCameraSnapshotUrl(feed, isLiveMode)
        if (primaryUrl.isNotBlank()) {
            val (bmp, reason) = fetchSnapshotBitmap(
                url = primaryUrl,
                token = feed.haToken,
                entityId = effectiveEntityId,
                context = context,
                maxDimension = maxDimension,
                logTag = logTag,
                job = job
            )
            decoded = bmp
            failReason = reason
        }

        // Fallback to Home Assistant MJPEG proxy stream for the active entity (uses liveEntityId when live)
        if (decoded == null && feed.haUrl.isNotBlank() && effectiveEntityId.isNotBlank()) {
            coroutineContext.ensureActive()
            decoded = fetchFirstFrameFromStream(
                haUrl = feed.haUrl,
                entityId = effectiveEntityId,
                token = feed.haToken,
                maxDimension = maxDimension,
                job = job
            )
            coroutineContext.ensureActive()
        }

        // If dedicated live entity failed to yield a frame, fall back to base snapshot entity before giving up
        if (decoded == null && useLiveEntity && feed.snapshotUrl.isNotBlank()) {
            coroutineContext.ensureActive()
            val (fallbackBmp, _) = fetchSnapshotBitmap(
                url = feed.snapshotUrl,
                token = feed.haToken,
                entityId = feed.entityId,
                context = context,
                maxDimension = maxDimension,
                logTag = logTag,
                job = job
            )
            if (fallbackBmp != null) {
                TrekoLogBuffer.w(logTag, "Live entity $effectiveEntityId unavailable, fell back to ${feed.entityId}")
                decoded = fallbackBmp
            }
        }

        if (decoded != null) {
            CameraFrameResult(bitmap = decoded.asImageBitmap(), errorMessage = null)
        } else {
            CameraFrameResult(
                bitmap = null,
                errorMessage = failReason ?: context.getString(R.string.camera_no_signal)
            )
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        coroutineContext.ensureActive()
        TrekoLogBuffer.w(logTag, "$effectiveEntityId error: ${e.message}")
        CameraFrameResult(
            bitmap = null,
            errorMessage = e.message ?: context.getString(R.string.camera_no_signal)
        )
    }
}

private fun fetchSnapshotBitmap(
    url: String,
    token: String,
    entityId: String,
    context: Context,
    maxDimension: Int?,
    logTag: String,
    job: Job?
): Pair<Bitmap?, String?> {
    val reqBuilder = Request.Builder().url(url).get()
    if (token.isNotBlank()) {
        reqBuilder.addHeader("Authorization", "Bearer $token")
    }
    val call = sharedCameraHttpClient.newCall(reqBuilder.build())
    val cancelHandle = job?.invokeOnCompletion { cause ->
        if (cause != null) call.cancel()
    }
    return try {
        call.execute().use { resp ->
            val contentType = resp.header("Content-Type").orEmpty()
            if (resp.isSuccessful) {
                val bytes = resp.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = extractAndDecodeBitmap(bytes, maxDimension)
                    if (bmp != null) {
                        bmp to null
                    } else {
                        val reason = "Unsupported image (${contentType.ifBlank { "unknown" }}, ${bytes.size}B)"
                        TrekoLogBuffer.w(logTag, "$entityId: $reason")
                        null to reason
                    }
                } else {
                    null to context.getString(R.string.camera_no_signal)
                }
            } else {
                val reason = context.getString(R.string.camera_http_error, resp.code)
                TrekoLogBuffer.w(logTag, "$entityId: HTTP ${resp.code}")
                null to reason
            }
        }
    } finally {
        cancelHandle?.dispose()
    }
}

private fun decodeBitmapSlice(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    maxDimension: Int?
): Bitmap? {
    if (offset < 0 || length <= 0 || offset + length > bytes.size) return null
    if (maxDimension == null || maxDimension <= 0) {
        return BitmapFactory.decodeByteArray(bytes, offset, length)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
    val outW = bounds.outWidth
    val outH = bounds.outHeight
    if (outW <= 0 || outH <= 0) {
        return BitmapFactory.decodeByteArray(bytes, offset, length)
    }
    var sampleSize = 1
    while (outW / sampleSize > maxDimension || outH / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, offset, length, opts)
}

internal fun extractAndDecodeBitmap(bytes: ByteArray, maxDimension: Int? = null): Bitmap? {
    decodeBitmapSlice(bytes, 0, bytes.size, maxDimension)?.let { return it }

    // Scan for embedded JPEG SOI (FF D8 FF) inside multipart/x-mixed-replace or wrapper payloads
    var jpegStart = -1
    for (i in 0 until bytes.size - 2) {
        if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() && bytes[i + 2] == 0xFF.toByte()) {
            jpegStart = i
            break
        }
    }
    if (jpegStart >= 0) {
        var jpegEnd = bytes.size
        for (i in jpegStart + 3 until bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) {
                jpegEnd = i + 2
                break
            }
        }
        val len = jpegEnd - jpegStart
        if (len > 64) {
            decodeBitmapSlice(bytes, jpegStart, len, maxDimension)?.let { return it }
        }
    }

    // Scan for embedded PNG signature (89 50 4E 47)
    for (i in 0 until bytes.size - 4) {
        if (bytes[i] == 0x89.toByte() &&
            bytes[i + 1] == 0x50.toByte() &&
            bytes[i + 2] == 0x4E.toByte() &&
            bytes[i + 3] == 0x47.toByte()
        ) {
            decodeBitmapSlice(bytes, i, bytes.size - i, maxDimension)?.let { return it }
        }
    }
    return null
}

internal fun fetchFirstFrameFromStream(
    haUrl: String,
    entityId: String,
    token: String,
    maxDimension: Int? = null,
    job: Job? = null
): Bitmap? {
    var cancelHandle: kotlinx.coroutines.DisposableHandle? = null
    return try {
        val cleanBase = haUrl.trim().removeSuffix("/").removeSuffix("/api")
        val streamUrl = "$cleanBase/api/camera_proxy_stream/$entityId"
        val reqBuilder = Request.Builder().url(streamUrl).get()
        if (token.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }
        val call = sharedCameraHttpClient.newCall(reqBuilder.build())
        cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        call.execute().use { resp ->
            if (!resp.isSuccessful) return null
            val input = resp.body?.byteStream() ?: return null
            val buffer = ByteArray(16 * 1024)
            val collected = ByteArrayOutputStream(128 * 1024)
            val maxBytes = 1500 * 1024
            var soiIdx = -1
            while (collected.size() < maxBytes) {
                if (job?.isCancelled == true) return null
                val prevSize = collected.size()
                val read = input.read(buffer)
                if (read <= 0) break
                collected.write(buffer, 0, read)
                val current = collected.toByteArray()
                if (soiIdx < 0) {
                    val searchStart = (prevSize - 2).coerceAtLeast(0)
                    for (i in searchStart until current.size - 2) {
                        if (current[i] == 0xFF.toByte() &&
                            current[i + 1] == 0xD8.toByte() &&
                            current[i + 2] == 0xFF.toByte()
                        ) {
                            soiIdx = i
                            break
                        }
                    }
                }
                if (soiIdx >= 0) {
                    val eoiSearchStart = (prevSize - 1).coerceAtLeast(soiIdx + 3)
                    for (i in eoiSearchStart until current.size - 1) {
                        if (current[i] == 0xFF.toByte() && current[i + 1] == 0xD9.toByte()) {
                            val frameLen = (i + 2) - soiIdx
                            if (frameLen > 64) {
                                decodeBitmapSlice(current, soiIdx, frameLen, maxDimension)?.let { return it }
                            }
                        }
                    }
                }
            }
            val finalBytes = collected.toByteArray()
            if (finalBytes.isNotEmpty()) extractAndDecodeBitmap(finalBytes, maxDimension) else null
        }
    } catch (_: Exception) {
        null
    } finally {
        cancelHandle?.dispose()
    }
}
