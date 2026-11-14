package com.treko.assistant.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.treko.assistant.R
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.DeepBackground
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.model.CameraFeedState
import com.treko.core.common.model.MultiCameraGridState
import kotlinx.coroutines.delay

@Composable
fun MultiCameraDialog(
    gridState: MultiCameraGridState,
    onDismiss: () -> Unit,
    onSelectCamera: (CameraFeedState) -> Unit,
    onAnalyzeCamera: (CameraFeedState) -> Unit = {},
    onToggleLiveAll: (Boolean) -> Unit = {}
) {
    var globalRefreshTick by remember { mutableIntStateOf(0) }
    val visibleCameras = remember(gridState.cameras) {
        gridState.cameras.filter { it.isVisible }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val glassShape = RoundedCornerShape(24.dp)
        val wallBrush = Brush.verticalGradient(
            colors = listOf(
                SurfaceDark.copy(alpha = 0.96f),
                DeepBackground.copy(alpha = 0.98f)
            )
        )
        val borderBrush = Brush.linearGradient(
            colors = listOf(
                NeonCyan.copy(alpha = 0.55f),
                CardBorder,
                NeonPurple.copy(alpha = 0.45f)
            )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .clip(glassShape)
                .background(wallBrush)
                .border(BorderStroke(1.dp, borderBrush), glassShape)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                MultiCameraTopBar(
                    cameraCount = gridState.cameras.size,
                    isLiveAll = gridState.isLiveAll,
                    onRefreshAll = { globalRefreshTick++ },
                    onToggleLiveAll = { onToggleLiveAll(!gridState.isLiveAll) },
                    onDismiss = onDismiss
                )

                HorizontalDivider(color = CardBorder.copy(alpha = 0.7f))

                if (visibleCameras.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideocamOff,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = stringResource(R.string.multicam_empty),
                                color = TextSecondary,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 230.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(
                            items = visibleCameras,
                            key = { index, feed -> "${feed.entityId}#$index" }
                        ) { _, feed ->
                            MultiCameraTile(
                                feed = feed,
                                isLiveAll = gridState.isLiveAll,
                                globalRefreshTick = globalRefreshTick,
                                onSelectCamera = onSelectCamera,
                                onAnalyzeCamera = onAnalyzeCamera
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiCameraTopBar(
    cameraCount: Int,
    isLiveAll: Boolean,
    onRefreshAll: () -> Unit,
    onToggleLiveAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val liveGreen = Color(0xFF00E676)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NeonCyan.copy(alpha = 0.14f))
                    .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.multicam_title, cameraCount),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        if (isLiveAll) R.string.multicam_subtitle_live else R.string.multicam_subtitle_snap
                    ),
                    color = if (isLiveAll) liveGreen else TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onRefreshAll,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                    tint = NeonCyan
                )
            }

            Surface(
                onClick = onToggleLiveAll,
                shape = RoundedCornerShape(999.dp),
                color = if (isLiveAll) liveGreen.copy(alpha = 0.18f) else SurfaceDark.copy(alpha = 0.85f),
                border = BorderStroke(
                    1.dp,
                    if (isLiveAll) liveGreen.copy(alpha = 0.7f) else CardBorder
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isLiveAll) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isLiveAll) liveGreen else TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(
                            if (isLiveAll) R.string.multicam_pause_all else R.string.multicam_live_all
                        ),
                        color = if (isLiveAll) liveGreen else TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun MultiCameraTile(
    feed: CameraFeedState,
    isLiveAll: Boolean,
    globalRefreshTick: Int,
    onSelectCamera: (CameraFeedState) -> Unit,
    onAnalyzeCamera: (CameraFeedState) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(feed.entityId) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(feed.entityId) { mutableStateOf(true) }
    var errorMessage by remember(feed.entityId) { mutableStateOf<String?>(null) }
    var hasStaleError by remember(feed.entityId) { mutableStateOf(false) }
    var updatedAtText by remember(feed.entityId) { mutableStateOf<String?>(null) }
    var refreshTrigger by remember(feed.entityId) { mutableIntStateOf(0) }
    var isTileLive by remember(feed.entityId, feed.isLive, isLiveAll) {
        mutableStateOf(feed.isLive || isLiveAll)
    }

    val effectiveEntityId = resolveEffectiveCameraEntityId(feed, isTileLive)
    val liveGreen = Color(0xFF00E676)

    LaunchedEffect(feed.entityId, effectiveEntityId, isTileLive, refreshTrigger, globalRefreshTick) {
        if (bitmap == null) {
            isLoading = true
        }
        val result = fetchCameraFrame(
            context = context,
            feed = feed,
            isLiveMode = isTileLive,
            maxDimension = 640,
            logTag = "MultiCameraWall"
        )
        if (result.bitmap != null) {
            bitmap = result.bitmap
            errorMessage = null
            hasStaleError = false
            updatedAtText = formatCameraTimestamp()
        } else {
            hasStaleError = true
            if (bitmap == null) {
                errorMessage = result.errorMessage ?: context.getString(R.string.camera_no_signal)
            }
        }
        isLoading = false
    }

    LaunchedEffect(feed.entityId, isTileLive) {
        val intervalMs = if (isTileLive) 2000L else 4000L
        while (true) {
            delay(intervalMs)
            refreshTrigger++
        }
    }

    Card(
        onClick = {
            val selectedFeed = if (isTileLive != feed.isLive) feed.copy(isLive = isTileLive) else feed
            onSelectCamera(selectedFeed)
        },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.86f)),
        border = BorderStroke(
            width = 1.dp,
            color = if (isTileLive) NeonCyan.copy(alpha = 0.72f) else CardBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val frameBitmap = bitmap
                if (frameBitmap != null) {
                    Crossfade(
                        targetState = frameBitmap,
                        animationSpec = tween(160),
                        label = "MultiCamFrameFade"
                    ) { frame ->
                        Image(
                            bitmap = frame,
                            contentDescription = feed.friendlyName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else if (isLoading) {
                    CircularProgressIndicator(
                        color = NeonCyan,
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = errorMessage ?: stringResource(R.string.camera_no_signal),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                // Live / Snapshot HUD Badge
                Surface(
                    color = Color.Black.copy(alpha = 0.68f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isTileLive) liveGreen.copy(alpha = 0.75f) else NeonCyan.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isTileLive) liveGreen else NeonCyan)
                        )
                        Text(
                            text = stringResource(
                                if (isTileLive) R.string.multicam_badge_live else R.string.multicam_badge_snap
                            ),
                            color = if (isTileLive) liveGreen else TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = feed.friendlyName,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val currentUpdated = updatedAtText
                    val tsLabel = when {
                        currentUpdated != null && hasStaleError ->
                            "${stringResource(R.string.camera_updated_at, currentUpdated)} · !"
                        currentUpdated != null ->
                            stringResource(R.string.camera_updated_at, currentUpdated)
                        isLoading ->
                            stringResource(R.string.multicam_connecting)
                        else ->
                            stringResource(R.string.camera_no_signal)
                    }
                    Text(
                        text = tsLabel,
                        color = if (hasStaleError && currentUpdated != null) Color(0xFFF59E0B) else TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { isTileLive = !isTileLive },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isTileLive) liveGreen.copy(alpha = 0.16f) else DeepBackground.copy(alpha = 0.75f),
                        border = BorderStroke(
                            1.dp,
                            if (isTileLive) liveGreen.copy(alpha = 0.65f) else CardBorder
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isTileLive) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isTileLive) liveGreen else TextPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(
                                    if (isTileLive) R.string.multicam_tile_pause else R.string.multicam_tile_live
                                ),
                                color = if (isTileLive) liveGreen else TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }

                    Surface(
                        onClick = { onAnalyzeCamera(feed) },
                        shape = RoundedCornerShape(10.dp),
                        color = NeonPurple.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.68f)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.multicam_analyze_cd),
                                tint = NeonCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.multicam_ai_button),
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
