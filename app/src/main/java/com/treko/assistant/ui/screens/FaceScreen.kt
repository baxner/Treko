package com.treko.assistant.ui.screens

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treko.assistant.R
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.service.WsConnectionStatus
import com.treko.assistant.ui.components.ReactiveEqualizer
import com.treko.assistant.ui.components.TrekoFace
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.model.AudioLevel
import com.treko.core.common.model.ChatMessage
import com.treko.core.common.model.FullDuplexIndicator
import com.treko.core.common.model.TrekoEmotion
import com.treko.core.common.model.TrekoState

@Composable
fun FaceScreen(
    emotion: TrekoEmotion,
    state: TrekoState,
    connectionStatus: WsConnectionStatus,
    subtitle: String,
    wakeWord: String,
    hasMicPermission: Boolean,
    micAudioLevelState: State<AudioLevel>,
    playbackAudioLevelState: State<AudioLevel>,
    onToggleListen: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    isMuted: Boolean = false,
    isCinemaMode: Boolean = false,
    onToggleMute: () -> Unit = {},
    onToggleCinemaMode: () -> Unit = {},
    onOpenMultiCamera: () -> Unit = {}
) {
    var showHistory by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val history by TrekoAssistantService.conversationHistory.collectAsState()
    val fullDuplexIndicator by TrekoAssistantService.fullDuplexIndicator.collectAsState()
    val pendingInsight by TrekoAssistantService.pendingProactiveInsight.collectAsState()
    val watchAreas by TrekoAssistantService.activeWatchAreas.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(history.size, showHistory) {
        if (showHistory && history.isNotEmpty()) {
            listState.animateScrollToItem(history.size - 1)
        }
    }

    // Use TTS output energy during SPEAKING, mic input energy otherwise
    val activeAudioLevelState = if (state == TrekoState.SPEAKING) {
        playbackAudioLevelState
    } else {
        micAudioLevelState
    }

    val dynamicEmotion = if (isMuted && state == TrekoState.IDLE) {
        TrekoEmotion.SLEEPY
    } else {
        when (state) {
            TrekoState.SPEAKING -> emotion
            TrekoState.THINKING -> TrekoEmotion.PIENSA
            TrekoState.LISTENING -> if (emotion == TrekoEmotion.FELIZ_ABIERTO) emotion else TrekoEmotion.ESCUCHA
            TrekoState.ERROR -> TrekoEmotion.CONFUSED
            TrekoState.IDLE -> emotion
        }
    }

    val stateLabel = if (isMuted) {
        stringResource(R.string.state_muted)
    } else {
        when (state) {
            TrekoState.IDLE -> stringResource(R.string.state_idle)
            TrekoState.LISTENING -> stringResource(R.string.state_listening)
            TrekoState.THINKING -> stringResource(R.string.state_thinking)
            TrekoState.SPEAKING -> stringResource(R.string.state_speaking)
            TrekoState.ERROR -> stringResource(R.string.state_error)
        }
    }

    val connDotColor = when (connectionStatus) {
        WsConnectionStatus.CONNECTED -> Color(0xFF00E676)
        WsConnectionStatus.CONNECTING -> Color(0xFFFFD54F)
        WsConnectionStatus.ERROR -> Color(0xFFFF5252)
        WsConnectionStatus.DISCONNECTED -> TextSecondary
    }
    val connLabel = when (connectionStatus) {
        WsConnectionStatus.CONNECTED -> stringResource(R.string.conn_connected)
        WsConnectionStatus.CONNECTING -> stringResource(R.string.conn_connecting)
        WsConnectionStatus.ERROR -> stringResource(R.string.conn_error)
        WsConnectionStatus.DISCONNECTED -> stringResource(R.string.conn_disconnected)
    }

    val headerBlock: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_treko_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_header_title,
                            TrekoAssistantService.formatAssistantName(wakeWord, stringResource(R.string.wake_word))
                        ),
                        color = NeonCyan,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = if (isMuted) Color(0xFFEF4444).copy(alpha = 0.16f) else SurfaceDark,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isMuted) Color(0xFFEF4444).copy(alpha = 0.6f) else CardBorder
                        )
                    ) {
                        Text(
                            text = stateLabel,
                            color = if (isMuted) Color(0xFFFCA5A5) else NeonPurple,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, connDotColor.copy(alpha = 0.45f)),
                        modifier = Modifier.clickable { onOpenDiagnostics() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(connDotColor)
                            )
                            Text(
                                text = connLabel,
                                color = TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                    val cinemaTint = if (isCinemaMode) Color(0xFFF59E0B) else TextSecondary
                    Surface(
                        color = if (isCinemaMode) Color(0xFFF59E0B).copy(alpha = 0.16f) else SurfaceDark,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isCinemaMode) Color(0xFFF59E0B).copy(alpha = 0.7f) else CardBorder
                        ),
                        modifier = Modifier.clickable { onToggleCinemaMode() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = stringResource(R.string.face_cinema_cd),
                                tint = cinemaTint,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = stringResource(
                                    if (isCinemaMode) R.string.chip_cinema_on else R.string.chip_cinema_off
                                ),
                                color = cinemaTint,
                                fontSize = 10.sp,
                                fontWeight = if (isCinemaMode) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
                if (watchAreas.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = Color(0xFF22C55E).copy(alpha = 0.14f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                tint = Color(0xFF86EFAC),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = stringResource(R.string.watch_chip, watchAreas.joinToString(", ")),
                                color = Color(0xFF86EFAC),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp)
                            )
                            IconButton(
                                onClick = { TrekoAssistantService.stopAllWatches() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.watch_stop_cd),
                                    tint = Color(0xFF86EFAC),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(onClick = onOpenMultiCamera) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = stringResource(R.string.face_cameras_cd),
                        tint = TextSecondary
                    )
                }

                IconButton(onClick = onOpenDiagnostics) {
                    Icon(
                        imageVector = Icons.Default.MonitorHeart,
                        contentDescription = stringResource(R.string.face_diagnostics_cd),
                        tint = TextSecondary
                    )
                }

                FilledTonalIconButton(
                    onClick = { showHistory = !showHistory },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (showHistory) NeonCyan.copy(alpha = 0.2f) else SurfaceDark,
                        contentColor = if (showHistory) NeonCyan else TextSecondary
                    )
                ) {
                    BadgedBox(
                        badge = {
                            if (history.isNotEmpty() && !showHistory) {
                                Badge(containerColor = NeonPurple, contentColor = Color.White) {
                                    Text(history.size.toString(), fontSize = 9.sp)
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (showHistory) Icons.Default.Face else Icons.Default.Forum,
                            contentDescription = if (showHistory) {
                                stringResource(R.string.face_hide_history)
                            } else {
                                stringResource(R.string.face_show_history)
                            }
                        )
                    }
                }
            }
        }
    }

    val micWarningBanner: @Composable () -> Unit = {
        AnimatedVisibility(visible = !hasMicPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1820)),
                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.perm_mic_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Text(
                        text = stringResource(R.string.perm_mic_desc),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRequestMicPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.perm_mic_grant), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onOpenAppSettings,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.perm_open_settings), fontSize = 12.sp, color = TextPrimary)
                        }
                    }
                }
            }
        }
    }

    val historyCard: @Composable (Modifier) -> Unit = { cardModifier ->
        Card(
            modifier = cardModifier,
            colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.90f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.face_conversation_count, history.size),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (history.isNotEmpty()) {
                        TextButton(
                            onClick = { TrekoAssistantService.clearHistory() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(stringResource(R.string.face_clear_history), color = Color(0xFFFF5252), fontSize = 11.sp)
                        }
                    }
                }

                if (history.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.face_empty_history, wakeWord),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(history, key = { it.id }) { msg ->
                            ChatBubble(
                                msg = msg,
                                assistantName = TrekoAssistantService.formatAssistantName(wakeWord, stringResource(R.string.wake_word))
                            )
                        }
                    }
                }
            }
        }
    }

    val proactiveInsightBanner: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = pendingInsight != null,
            enter = expandVertically(tween(240)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(160))
        ) {
            pendingInsight?.let { insight ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.96f)),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.72f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(
                                        R.string.proactive_notif_title,
                                        TrekoAssistantService.formatAssistantName(wakeWord, stringResource(R.string.wake_word))
                                    ),
                                    color = NeonCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { TrekoAssistantService.dismissPendingProactiveInsight(context) },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.proactive_action_dismiss),
                                    tint = TextSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        Surface(
                            color = NeonPurple.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = insight.title,
                                color = NeonPurple,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }

                        Text(
                            text = insight.summaryText,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        Button(
                            onClick = { TrekoAssistantService.consumePendingProactiveInsight(context) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.proactive_banner_listen),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    val statusTextBlock: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = NeonCyan,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            } else {
                Text(
                    text = when {
                        !hasMicPermission -> stringResource(R.string.status_mic_permission_missing)
                        isMuted -> stringResource(R.string.status_muted)
                        state == TrekoState.IDLE -> when {
                            isCinemaMode -> stringResource(R.string.status_cinema_idle, wakeWord)
                            dynamicEmotion == TrekoEmotion.SLEEPY -> stringResource(R.string.status_sleepy, wakeWord)
                            else -> stringResource(R.string.status_idle, wakeWord)
                        }
                        state == TrekoState.LISTENING -> stringResource(R.string.status_listening)
                        state == TrekoState.THINKING -> stringResource(R.string.status_thinking)
                        state == TrekoState.SPEAKING -> stringResource(R.string.status_speaking)
                        else -> stringResource(R.string.status_error)
                    },
                    color = if (isMuted) Color(0xFFFCA5A5) else TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (fullDuplexIndicator != FullDuplexIndicator.INACTIVE) {
                val (badgeText, badgeColor) = when (fullDuplexIndicator) {
                    FullDuplexIndicator.CAPTURING_COMPLEMENTARY ->
                        stringResource(R.string.full_duplex_capturing) to NeonCyan
                    FullDuplexIndicator.QUEUED_COMPLEMENTARY ->
                        stringResource(R.string.full_duplex_queued) to Color(0xFF00E676)
                    FullDuplexIndicator.FLUSHING_COMPLEMENTARY ->
                        stringResource(R.string.full_duplex_flushing) to NeonPurple
                    FullDuplexIndicator.INACTIVE -> "" to NeonCyan
                }
                Surface(
                    color = badgeColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f))
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    val fabColor by animateColorAsState(
        targetValue = when (state) {
            TrekoState.LISTENING -> NeonPurple
            TrekoState.SPEAKING, TrekoState.THINKING -> Color(0xFFFF5252)
            else -> if (hasMicPermission) NeonCyan else CardBorder
        },
        animationSpec = tween(220),
        label = "MicFabColor"
    )

    val controlsDock: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Surface(
                onClick = onToggleCinemaMode,
                shape = RoundedCornerShape(16.dp),
                color = if (isCinemaMode) Color(0xFFF59E0B).copy(alpha = 0.22f) else SurfaceDark,
                border = BorderStroke(
                    1.dp,
                    if (isCinemaMode) Color(0xFFF59E0B) else CardBorder
                ),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = stringResource(R.string.face_cinema_cd),
                        tint = if (isCinemaMode) Color(0xFFFBBF24) else TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            FloatingActionButton(
                onClick = {
                    if (hasMicPermission) {
                        onToggleListen()
                    } else {
                        onRequestMicPermission()
                    }
                },
                containerColor = fabColor,
                contentColor = Color.Black,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(62.dp)
            ) {
                Icon(
                    imageVector = when (state) {
                        TrekoState.LISTENING, TrekoState.THINKING -> Icons.Default.Stop
                        TrekoState.SPEAKING -> Icons.Default.Mic
                        else -> Icons.Default.Mic
                    },
                    contentDescription = if (state == TrekoState.LISTENING || state == TrekoState.THINKING) {
                        stringResource(R.string.face_stop_cd)
                    } else {
                        stringResource(R.string.face_mic_cd)
                    }
                )
            }

            Surface(
                onClick = {
                    if (hasMicPermission) {
                        onToggleMute()
                    } else {
                        onRequestMicPermission()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                color = if (isMuted) Color(0xFFEF4444).copy(alpha = 0.22f) else SurfaceDark,
                border = BorderStroke(
                    1.dp,
                    if (isMuted) Color(0xFFEF4444) else CardBorder
                ),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Hearing,
                        contentDescription = if (isMuted) {
                            stringResource(R.string.face_unmute_cd)
                        } else {
                            stringResource(R.string.face_mute_cd)
                        },
                        tint = if (isMuted) Color(0xFFFCA5A5) else NeonCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        if (isLandscape) {
            val faceSize = (maxHeight * 0.62f).coerceIn(120.dp, 220.dp)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.clickable(enabled = hasMicPermission) { onToggleListen() }
                    ) {
                        TrekoFace(
                            emotion = dynamicEmotion,
                            size = faceSize,
                            state = state,
                            audioLevelState = activeAudioLevelState
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    ReactiveEqualizer(
                        audioLevelState = activeAudioLevelState,
                        state = state
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    headerBlock()
                    micWarningBanner()

                    if (showHistory) {
                        historyCard(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 6.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            statusTextBlock()
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        proactiveInsightBanner()
                        if (showHistory) {
                            statusTextBlock()
                        }
                        controlsDock()
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                headerBlock()
                micWarningBanner()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clickable(enabled = hasMicPermission) { onToggleListen() }
                            .padding(vertical = if (showHistory) 4.dp else 12.dp)
                    ) {
                        TrekoFace(
                            emotion = dynamicEmotion,
                            size = if (showHistory) 108.dp else 248.dp,
                            state = state,
                            audioLevelState = activeAudioLevelState
                        )
                    }

                    AnimatedVisibility(
                        visible = showHistory,
                        enter = expandVertically(tween(260)) + fadeIn(tween(220)),
                        exit = shrinkVertically(tween(220)) + fadeOut(tween(180)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        historyCard(
                            Modifier
                                .fillMaxWidth()
                                .height(270.dp)
                                .padding(top = 6.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    proactiveInsightBanner()
                    ReactiveEqualizer(
                        audioLevelState = activeAudioLevelState,
                        state = state
                    )
                    statusTextBlock()
                    controlsDock()
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, assistantName: String = "") {
    val isUser = msg.sender.equals("Tú", ignoreCase = true) ||
        msg.sender.equals("You", ignoreCase = true) ||
        msg.sender.equals("user", ignoreCase = true)
    val displaySender = if (isUser) stringResource(R.string.speaker_you) else assistantName.ifBlank { stringResource(R.string.speaker_treko) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) NeonPurple.copy(alpha = 0.22f) else SurfaceDark,
            border = BorderStroke(1.dp, if (isUser) NeonPurple.copy(alpha = 0.5f) else CardBorder),
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 3.dp,
                bottomEnd = if (isUser) 3.dp else 14.dp
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(
                    text = displaySender,
                    color = if (isUser) NeonPurple else NeonCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = msg.text,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
