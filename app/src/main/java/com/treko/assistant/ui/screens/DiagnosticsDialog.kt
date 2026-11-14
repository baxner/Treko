package com.treko.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.treko.assistant.R
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.service.WsConnectionStatus
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.DeepBackground
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DiagnosticsDialog(
    prefs: TrekoPreferences,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val connectionStatus by TrekoAssistantService.connectionStatus.collectAsState()
    val sessionId by TrekoAssistantService.activeSessionId.collectAsState()
    val isMuted by TrekoAssistantService.isMuted.collectAsState()
    val isCinemaMode by TrekoAssistantService.isCinemaMode.collectAsState()
    val fullDuplexIndicator by TrekoAssistantService.fullDuplexIndicator.collectAsState()
    val logEntries by TrekoLogBuffer.entries.collectAsState()
    var audioDiag by remember { mutableStateOf(NativeAudioEngine.getDiagnosticsSnapshot()) }
    var kwsDiag by remember { mutableStateOf(TrekoAssistantService.getKwsDiagnostics()) }
    var denoiserDiag by remember { mutableStateOf(TrekoAssistantService.getDenoiserDiagnostics()) }
    var bioDiag by remember { mutableStateOf(TrekoAssistantService.getBiometricDiagnostics()) }

    LaunchedEffect(Unit) {
        while (true) {
            audioDiag = NativeAudioEngine.getDiagnosticsSnapshot()
            kwsDiag = TrekoAssistantService.getKwsDiagnostics()
            denoiserDiag = TrekoAssistantService.getDenoiserDiagnostics()
            bioDiag = TrekoAssistantService.getBiometricDiagnostics()
            delay(500L)
        }
    }

    val micGranted = TrekoAssistantService.hasMicPermission(context)
    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
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
                        Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = NeonCyan)
                        Column {
                            Text(stringResource(R.string.diag_title), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(stringResource(R.string.diag_subtitle), color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = TextSecondary)
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = DeepBackground),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DiagRow(
                            label = stringResource(R.string.diag_perm_mic),
                            value = if (micGranted) stringResource(R.string.diag_granted) else stringResource(R.string.diag_denied),
                            ok = micGranted
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_perm_notif),
                            value = if (notifGranted) stringResource(R.string.diag_granted) else stringResource(R.string.diag_denied),
                            ok = notifGranted
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_service_status),
                            value = if (TrekoAssistantService.isServiceRunning) stringResource(R.string.diag_active) else stringResource(R.string.diag_inactive),
                            ok = TrekoAssistantService.isServiceRunning
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_ws_status),
                            value = when (connectionStatus) {
                                WsConnectionStatus.CONNECTED -> stringResource(R.string.conn_connected)
                                WsConnectionStatus.CONNECTING -> stringResource(R.string.conn_connecting)
                                WsConnectionStatus.ERROR -> stringResource(R.string.conn_error)
                                WsConnectionStatus.DISCONNECTED -> stringResource(R.string.conn_disconnected)
                            },
                            ok = connectionStatus == WsConnectionStatus.CONNECTED
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_ws_session),
                            value = sessionId ?: "—",
                            ok = !sessionId.isNullOrBlank()
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_kws_engine),
                            value = if (kwsDiag?.isInitialized == true) stringResource(R.string.diag_active) else stringResource(R.string.diag_inactive),
                            ok = kwsDiag?.isInitialized == true
                        )
                        kwsDiag?.let { k ->
                            DiagRow(
                                label = stringResource(R.string.diag_kws_config),
                                value = String.format(Locale.US, "%s (%.1fx / %.2f)", k.keyword, k.boost, k.threshold),
                                ok = k.isInitialized
                            )
                            DiagRow(
                                label = stringResource(R.string.diag_kws_stats),
                                value = "${k.totalFramesProcessed} / ${k.vadSkippedFrames}",
                                ok = true
                            )
                            DiagRow(
                                label = stringResource(R.string.diag_kws_dtw),
                                value = if (k.hasAcousticMatcher) {
                                    String.format(Locale.US, "ON (≤%.3f) · %d", k.dtwThreshold, k.dtwDetectionsCount)
                                } else {
                                    stringResource(R.string.diag_not_configured)
                                },
                                ok = k.hasAcousticMatcher
                            )
                        }
                        DiagRow(
                            label = stringResource(R.string.diag_audio_hw),
                            value = "AEC:${onOff(audioDiag.aecActive)} NS:${onOff(audioDiag.nsActive)} AGC:${onOff(audioDiag.agcActive)}",
                            ok = audioDiag.isRecording
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_sw_aec3),
                            value = if (audioDiag.swAec3Active) {
                                String.format(Locale.US, "ON (%d ms · ERLE %.1f dB)", audioDiag.aecDelayMs, audioDiag.erleDb)
                            } else {
                                stringResource(R.string.diag_inactive)
                            },
                            ok = audioDiag.swAec3Active
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_full_duplex),
                            value = "${prefs.effectiveFullDuplexMode().name} · ${fullDuplexIndicator.name}",
                            ok = true
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_live_rms),
                            value = String.format(Locale.US, "%.4f · %+.1f dB", audioDiag.lastRms, audioDiag.lastSnrDb),
                            ok = audioDiag.isRecording
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_mic_mute),
                            value = stringResource(if (isMuted) R.string.diag_muted_yes else R.string.diag_muted_no),
                            ok = !isMuted
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_cinema_mode),
                            value = onOff(isCinemaMode),
                            ok = true
                        )
                        DiagRow(
                            label = stringResource(R.string.diag_denoiser),
                            value = denoiserDiag?.let { d ->
                                if (d.isInitialized) {
                                    "${onOff(d.isEnabled)} (${d.frameShiftSamples}s · ${d.totalFramesProcessed}f)"
                                } else {
                                    stringResource(R.string.diag_inactive)
                                }
                            } ?: stringResource(R.string.diag_inactive),
                            ok = denoiserDiag?.isInitialized == true
                        )
                        val bDiag = bioDiag
                        DiagRow(
                            label = stringResource(R.string.diag_biometric),
                            value = when {
                                bDiag == null || !bDiag.isInitialized -> stringResource(R.string.diag_inactive)
                                !bDiag.isEnrolled -> stringResource(R.string.diag_not_configured)
                                else -> {
                                    val simText = bDiag.lastSimilarity?.let { "${(it * 100f).roundToInt()}%" } ?: "—"
                                    "${onOff(bDiag.isGateEnabled)} ($simText · ${bDiag.verifiedTurnsCount}✓/${bDiag.rejectedTurnsCount}✗)"
                                }
                            },
                            ok = bDiag?.isInitialized == true && bDiag.isEnrolled
                        )
                        val haOk = prefs.haUrl.isNotBlank() && prefs.haToken.isNotBlank()
                        DiagRow(
                            label = stringResource(R.string.diag_ha_status),
                            value = if (haOk) stringResource(R.string.diag_configured) else stringResource(R.string.diag_not_configured),
                            ok = haOk
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { TrekoAssistantService.reconnectWebSocket() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.diag_reconnect_ws), fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            TrekoAssistantService.stopService(context)
                            TrekoAssistantService.startService(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.diag_restart_service), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                HorizontalDivider(color = CardBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.diag_logs_title, logEntries.size),
                        color = NeonPurple,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    if (logEntries.isNotEmpty()) {
                        TextButton(onClick = { TrekoLogBuffer.clear() }) {
                            Text(stringResource(R.string.face_clear_history), color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DeepBackground)
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (logEntries.isEmpty()) {
                        Text(stringResource(R.string.diag_logs_empty), color = TextSecondary, fontSize = 11.sp)
                    } else {
                        val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                        logEntries.takeLast(25).reversed().forEach { entry ->
                            val color = when (entry.level) {
                                TrekoLogBuffer.Level.ERROR -> Color(0xFFFF5252)
                                TrekoLogBuffer.Level.WARN -> Color(0xFFFFD54F)
                                TrekoLogBuffer.Level.INFO -> NeonCyan
                                TrekoLogBuffer.Level.DEBUG -> TextSecondary
                            }
                            Text(
                                text = "${sdf.format(Date(entry.timestampMs))} [${entry.tag}] ${entry.message}",
                                color = color,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = if (ok) Color(0xFF00E676) else Color(0xFFFF8A65),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun onOff(flag: Boolean): String = if (flag) "ON" else "OFF"
