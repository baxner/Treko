package com.treko.assistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treko.assistant.R
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.ui.components.testHaConnection
import com.treko.assistant.ui.components.testXiaozhiConnection
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.FullDuplexMode
import com.treko.core.common.model.TrekoSkills
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    prefs: TrekoPreferences,
    onReopenWizard: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRequestMicPermission: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var xiaozhiUrl by remember { mutableStateOf(prefs.xiaozhiWsUrl) }
    var xiaozhiToken by remember { mutableStateOf(prefs.xiaozhiToken) }
    var haUrl by remember { mutableStateOf(prefs.haUrl) }
    var haToken by remember { mutableStateOf(prefs.haToken) }
    var customWakeWord by remember { mutableStateOf(prefs.customWakeWord) }
    var wakeWordBoost by remember { mutableFloatStateOf(prefs.wakeWordBoost) }
    var sensitivity by remember { mutableFloatStateOf(prefs.wakeWordSensitivity) }
    var alwaysListenInBackground by remember { mutableStateOf(prefs.isAlwaysListeningInBackground) }
    var vadAdaptiveEnabled by remember { mutableStateOf(prefs.vadAdaptiveEnabled) }
    var speakerVerifyEnabled by remember { mutableStateOf(prefs.speakerVerificationEnabled) }
    var echoModeEnabled by remember { mutableStateOf(prefs.audioEchoModeEnabled) }
    var isCalibrated by remember { mutableStateOf(prefs.isWakeWordCalibrated) }
    var calibratedSnrDb by remember { mutableFloatStateOf(prefs.calibratedSnrDb) }

    val serviceCinemaMode by TrekoAssistantService.isCinemaMode.collectAsState()
    var cinemaModeEnabled by remember { mutableStateOf(prefs.cinemaModeEnabled) }
    var autoFollowUpEnabled by remember { mutableStateOf(prefs.autoFollowUpEnabled) }
    var voiceBargeInEnabled by remember { mutableStateOf(prefs.voiceBargeInEnabled) }
    var fullDuplexMode by remember { mutableStateOf(prefs.fullDuplexMode) }
    var softwareAec3Enabled by remember { mutableStateOf(prefs.softwareAec3Enabled) }
    var nearFieldGateEnabled by remember { mutableStateOf(prefs.nearFieldGateEnabled) }
    var nearFieldMinSnrDb by remember { mutableFloatStateOf(prefs.nearFieldMinSnrDb) }
    var speechDenoiserEnabled by remember { mutableStateOf(prefs.speechDenoiserEnabled) }
    var biometricEnrolled by remember { mutableStateOf(prefs.isBiometricVoiceEnrolled) }
    var biometricGateEnabled by remember { mutableStateOf(prefs.biometricSpeakerGateEnabled) }
    var biometricThreshold by remember { mutableFloatStateOf(prefs.biometricSpeakerThreshold) }

    var proactiveEnabled by remember { mutableStateOf(prefs.proactiveEnabled) }
    var proactiveSpeakOutLoud by remember { mutableStateOf(prefs.proactiveSpeakOutLoud) }
    var proactiveCameraAnomalyEnabled by remember { mutableStateOf(prefs.proactiveCameraAnomalyEnabled) }
    var cameraSentinelEnabled by remember { mutableStateOf(prefs.cameraSentinelEnabled) }
    val frigateDetected = remember { TrekoSkills.FRIGATE in prefs.detectedSkills }
    val llmVisionDetected = remember { TrekoSkills.LLM_VISION in prefs.detectedSkills }
    /** The periodic camera scan is offered until detection says LLM Vision is missing. */
    val cameraScanAvailable = remember { llmVisionDetected || prefs.detectedSkills.isEmpty() }
    var proactiveQuietHoursEnabled by remember { mutableStateOf(prefs.proactiveQuietHoursEnabled) }
    var proactiveIntervalMinutes by remember { mutableIntStateOf(prefs.proactiveIntervalMinutes) }
    val isProactiveRunning by TrekoAssistantService.isProactiveCheckRunning.collectAsState()

    LaunchedEffect(serviceCinemaMode) {
        cinemaModeEnabled = serviceCinemaMode
    }

    var isXiaozhiTokenVisible by remember { mutableStateOf(false) }
    var isHaTokenVisible by remember { mutableStateOf(false) }

    var isTestingXiaozhi by remember { mutableStateOf(false) }
    var xiaozhiResult by remember { mutableStateOf<String?>(null) }
    var isTestingHa by remember { mutableStateOf(false) }
    var haResult by remember { mutableStateOf<String?>(null) }

    var showTrainingDialog by remember { mutableStateOf(false) }
    var showBiometricDialog by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.settings_title), color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        // Xiaozhi Server Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.settings_xiaozhi_title), color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MAC Address", prefs.macAddress))
                            Toast.makeText(
                                context,
                                context.getString(R.string.wizard_mac_copied, prefs.macAddress),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.wizard_copy_mac),
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(R.string.settings_device_id, prefs.macAddress),
                            fontSize = 12.sp,
                            color = NeonCyan
                        )
                    }
                )

                OutlinedTextField(
                    value = xiaozhiUrl,
                    onValueChange = { xiaozhiUrl = it },
                    label = { Text(stringResource(R.string.settings_xiaozhi_ws_url), fontSize = 12.sp) },
                    placeholder = { Text(TrekoPreferences.DEFAULT_XIAOZHI_WS_URL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = xiaozhiToken,
                    onValueChange = { xiaozhiToken = it },
                    label = { Text(stringResource(R.string.settings_xiaozhi_token), fontSize = 12.sp) },
                    visualTransformation = if (isXiaozhiTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isXiaozhiTokenVisible = !isXiaozhiTokenVisible }) {
                            Icon(
                                imageVector = if (isXiaozhiTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (xiaozhiResult != null) {
                        Text(
                            text = xiaozhiResult ?: "",
                            color = if (xiaozhiResult?.startsWith("✓") == true) Color(0xFF00E676) else Color(0xFFFF5252),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isTestingXiaozhi = true
                                xiaozhiResult = null
                                xiaozhiResult = testXiaozhiConnection(
                                    context,
                                    xiaozhiUrl,
                                    xiaozhiToken,
                                    prefs.macAddress,
                                    prefs.clientId
                                )
                                isTestingXiaozhi = false
                            }
                        },
                        enabled = !isTestingXiaozhi
                    ) {
                        if (isTestingXiaozhi) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.settings_test_server), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Home Assistant Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_ha_title), color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                OutlinedTextField(
                    value = haUrl,
                    onValueChange = { haUrl = it },
                    label = { Text(stringResource(R.string.settings_ha_url), fontSize = 12.sp) },
                    placeholder = { Text("http://homeassistant.local:8123") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = haToken,
                    onValueChange = { haToken = it },
                    label = { Text(stringResource(R.string.settings_ha_token), fontSize = 12.sp) },
                    visualTransformation = if (isHaTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isHaTokenVisible = !isHaTokenVisible }) {
                            Icon(
                                imageVector = if (isHaTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (haResult != null) {
                        Text(
                            text = haResult ?: "",
                            color = if (haResult?.startsWith("✓") == true) Color(0xFF00E676) else Color(0xFFFF5252),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isTestingHa = true
                                haResult = null
                                haResult = testHaConnection(context, haUrl, haToken)
                                isTestingHa = false
                            }
                        },
                        enabled = !isTestingHa
                    ) {
                        if (isTestingHa) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.settings_test_ha), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Microphone, Voice Training & Diagnostics Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.settings_mic_title), color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = if (isCalibrated) {
                            stringResource(R.string.settings_calibrated_badge, calibratedSnrDb)
                        } else {
                            stringResource(R.string.settings_not_calibrated_badge)
                        },
                        color = if (isCalibrated) Color(0xFF00E676) else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = {
                        if (TrekoAssistantService.hasMicPermission(context)) {
                            showTrainingDialog = true
                        } else {
                            onRequestMicPermission()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan.copy(alpha = 0.16f), contentColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_mic_test), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(stringResource(R.string.settings_mic_test_desc), color = TextSecondary, fontSize = 11.sp)
            }
        }

        // Living Room, Cinema Mode & Biometric Anti-TV Card (Essential Controls)
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_room_title),
                        color = NeonPurple,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (biometricEnrolled) {
                            stringResource(R.string.settings_biometric_enrolled_badge, biometricThreshold * 100f)
                        } else {
                            stringResource(R.string.settings_biometric_not_enrolled_badge)
                        },
                        color = if (biometricEnrolled) Color(0xFF00E676) else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = {
                        if (TrekoAssistantService.hasMicPermission(context)) {
                            showBiometricDialog = true
                        } else {
                            onRequestMicPermission()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonPurple.copy(alpha = 0.22f),
                        contentColor = NeonCyan
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_biometric_enroll_btn),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Text(
                    stringResource(
                        R.string.settings_biometric_enroll_desc,
                        TrekoAssistantService.formatAssistantName(customWakeWord, stringResource(R.string.wake_word))
                    ),
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                SettingsToggleRow(
                    title = stringResource(R.string.settings_biometric_gate_title),
                    subtitle = stringResource(R.string.settings_biometric_gate_desc),
                    checked = biometricGateEnabled,
                    enabled = biometricEnrolled,
                    onCheckedChange = { biometricGateEnabled = it }
                )

                SettingsToggleRow(
                    title = stringResource(R.string.settings_cinema_mode_title),
                    subtitle = stringResource(R.string.settings_cinema_mode_desc),
                    checked = cinemaModeEnabled,
                    onCheckedChange = { cinemaModeEnabled = it }
                )

                SettingsToggleRow(
                    title = stringResource(R.string.settings_auto_follow_up_title),
                    subtitle = stringResource(
                        R.string.settings_auto_follow_up_desc,
                        TrekoAssistantService.formatAssistantName(customWakeWord, stringResource(R.string.wake_word))
                    ),
                    checked = autoFollowUpEnabled && !cinemaModeEnabled,
                    enabled = !cinemaModeEnabled,
                    onCheckedChange = { autoFollowUpEnabled = it }
                )

                HorizontalDivider(color = CardBorder)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.settings_full_duplex_title),
                        color = if (!cinemaModeEnabled) TextPrimary else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_full_duplex_desc,
                            TrekoAssistantService.formatAssistantName(customWakeWord, stringResource(R.string.wake_word))
                        ),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    val effectiveMode = TrekoPreferences.resolveEffectiveFullDuplexMode(fullDuplexMode, cinemaModeEnabled)
                    listOf(
                        FullDuplexMode.COMPLEMENTARY to stringResource(R.string.settings_duplex_complementary),
                        FullDuplexMode.BARGE_IN_INTERRUPT to stringResource(R.string.settings_duplex_barge_in),
                        FullDuplexMode.HALF_DUPLEX to stringResource(R.string.settings_duplex_half)
                    ).forEach { (mode, label) ->
                        val selected = effectiveMode == mode
                        OutlinedButton(
                            onClick = { fullDuplexMode = mode },
                            enabled = !cinemaModeEnabled,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (selected) NeonCyan else CardBorder
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) NeonCyan.copy(alpha = 0.14f) else Color.Transparent,
                                contentColor = if (selected) NeonCyan else TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = (if (selected) "✓ " else "") + label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Wake Word Card (Essential)
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_kws_title), color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stringResource(R.string.settings_kws_desc), color = TextSecondary, fontSize = 12.sp)

                OutlinedTextField(
                    value = customWakeWord,
                    onValueChange = { customWakeWord = it },
                    label = { Text(stringResource(R.string.settings_custom_wake_word), fontSize = 12.sp) },
                    placeholder = { Text(stringResource(R.string.settings_custom_wake_word_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        // Proactive Companion & Sentinel Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.proactive_settings_title),
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    stringResource(R.string.proactive_settings_desc),
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                SettingsToggleRow(
                    title = stringResource(R.string.proactive_enable_title),
                    subtitle = stringResource(R.string.proactive_enable_sub),
                    checked = proactiveEnabled,
                    onCheckedChange = { checked ->
                        proactiveEnabled = checked
                        prefs.proactiveEnabled = checked
                    }
                )

                HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))

                SettingsToggleRow(
                    title = stringResource(R.string.proactive_speak_title),
                    subtitle = stringResource(
                        R.string.proactive_speak_sub,
                        TrekoAssistantService.formatAssistantName(customWakeWord, stringResource(R.string.wake_word))
                    ),
                    checked = proactiveSpeakOutLoud,
                    enabled = proactiveEnabled,
                    onCheckedChange = { checked ->
                        proactiveSpeakOutLoud = checked
                        prefs.proactiveSpeakOutLoud = checked
                    }
                )

                SettingsToggleRow(
                    title = stringResource(R.string.proactive_camera_title),
                    subtitle = stringResource(
                        if (cameraScanAvailable) R.string.proactive_camera_sub else R.string.proactive_sentinel_needs_llm_vision
                    ),
                    checked = proactiveCameraAnomalyEnabled,
                    enabled = proactiveEnabled && cameraScanAvailable,
                    onCheckedChange = { checked ->
                        proactiveCameraAnomalyEnabled = checked
                        prefs.proactiveCameraAnomalyEnabled = checked
                    }
                )

                // Not tied to the proactive check above: it has its own switch.
                SettingsToggleRow(
                    title = stringResource(R.string.proactive_sentinel_title),
                    subtitle = stringResource(
                        when {
                            !frigateDetected -> R.string.proactive_sentinel_needs_frigate
                            !llmVisionDetected -> R.string.proactive_sentinel_needs_llm_vision
                            else -> R.string.proactive_sentinel_sub
                        }
                    ),
                    checked = cameraSentinelEnabled,
                    enabled = frigateDetected && llmVisionDetected,
                    onCheckedChange = { checked ->
                        cameraSentinelEnabled = checked
                        prefs.cameraSentinelEnabled = checked
                    }
                )

                SettingsToggleRow(
                    title = stringResource(R.string.proactive_quiet_title),
                    subtitle = stringResource(R.string.proactive_quiet_sub),
                    checked = proactiveQuietHoursEnabled,
                    enabled = proactiveEnabled,
                    onCheckedChange = { checked ->
                        proactiveQuietHoursEnabled = checked
                        prefs.proactiveQuietHoursEnabled = checked
                    }
                )

                Text(
                    stringResource(R.string.proactive_interval_label),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 60).forEach { minutes ->
                        val selected = proactiveIntervalMinutes == minutes
                        OutlinedButton(
                            onClick = {
                                proactiveIntervalMinutes = minutes
                                prefs.proactiveIntervalMinutes = minutes
                            },
                            enabled = proactiveEnabled,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (selected) NeonCyan else CardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) NeonCyan.copy(alpha = 0.14f) else Color.Transparent,
                                contentColor = if (selected) NeonCyan else TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = (if (selected) "✓ " else "") + "$minutes min",
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        TrekoAssistantService.triggerProactiveCheckNow(context)
                        Toast.makeText(context, context.getString(R.string.proactive_test_started), Toast.LENGTH_SHORT).show()
                    },
                    enabled = !isProactiveRunning,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isProactiveRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = NeonCyan
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.proactive_test_now),
                        color = NeonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Collapsible Technical & Advanced Options Card
        OutlinedButton(
            onClick = { showAdvancedSettings = !showAdvancedSettings },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = NeonPurple)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = (if (showAdvancedSettings) "▲ " else "▼ ") + stringResource(R.string.settings_advanced_toggle),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (showAdvancedSettings) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onOpenDiagnostics,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = NeonPurple)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_diagnostics), color = TextPrimary, fontSize = 13.sp)
                    }
                    Text(stringResource(R.string.settings_diagnostics_desc), color = TextSecondary, fontSize = 11.sp)

                    HorizontalDivider(color = CardBorder)

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_biometric_threshold_label),
                                color = if (biometricEnrolled) TextPrimary else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${(biometricThreshold * 100f).roundToInt()}%",
                                color = NeonCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Text(stringResource(R.string.settings_biometric_threshold_desc), color = TextSecondary, fontSize = 11.sp)
                        Slider(
                            value = biometricThreshold,
                            onValueChange = { biometricThreshold = it },
                            valueRange = 0.40f..0.80f,
                            enabled = biometricEnrolled,
                            colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                        )
                    }

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_denoiser_title),
                        subtitle = stringResource(R.string.settings_denoiser_desc),
                        checked = speechDenoiserEnabled,
                        onCheckedChange = { speechDenoiserEnabled = it }
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_near_field_title),
                        subtitle = stringResource(R.string.settings_near_field_desc),
                        checked = nearFieldGateEnabled,
                        onCheckedChange = { nearFieldGateEnabled = it }
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_near_field_snr_label),
                                color = if (nearFieldGateEnabled) TextPrimary else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f dB", nearFieldMinSnrDb),
                                color = NeonCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Text(stringResource(R.string.settings_near_field_snr_desc), color = TextSecondary, fontSize = 11.sp)
                        Slider(
                            value = nearFieldMinSnrDb,
                            onValueChange = { nearFieldMinSnrDb = it },
                            valueRange = 3.0f..18.0f,
                            enabled = nearFieldGateEnabled,
                            colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                        )
                    }

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_voice_barge_in_title),
                        subtitle = stringResource(
                            R.string.settings_voice_barge_in_desc,
                            customWakeWord.ifBlank { prefs.customWakeWord }
                        ),
                        checked = voiceBargeInEnabled && !cinemaModeEnabled,
                        enabled = !cinemaModeEnabled,
                        onCheckedChange = { voiceBargeInEnabled = it }
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_vad_gating_title),
                        subtitle = stringResource(R.string.settings_vad_gating_desc),
                        checked = vadAdaptiveEnabled,
                        onCheckedChange = { vadAdaptiveEnabled = it }
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_speaker_verify_title),
                        subtitle = stringResource(R.string.settings_speaker_verify_desc),
                        checked = speakerVerifyEnabled,
                        enabled = isCalibrated && prefs.wakeWordVoicePrintJson.isNotBlank(),
                        onCheckedChange = { speakerVerifyEnabled = it }
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_sw_aec3_title),
                        subtitle = stringResource(R.string.settings_sw_aec3_desc),
                        checked = softwareAec3Enabled,
                        onCheckedChange = { softwareAec3Enabled = it }
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_echo_mode_title),
                        subtitle = stringResource(R.string.settings_echo_mode_desc),
                        checked = echoModeEnabled,
                        onCheckedChange = { echoModeEnabled = it }
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.settings_boost_label), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(String.format(Locale.US, "%.1fx", wakeWordBoost), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Text(stringResource(R.string.settings_boost_desc), color = TextSecondary, fontSize = 11.sp)
                    }
                    Slider(
                        value = wakeWordBoost,
                        onValueChange = { wakeWordBoost = it },
                        valueRange = 1.0f..5.0f,
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.settings_threshold_label), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(String.format(Locale.US, "%.2f", sensitivity), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Text(stringResource(R.string.settings_threshold_desc), color = TextSecondary, fontSize = 11.sp)
                    }
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        valueRange = 0.01f..0.20f,
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
                }
            }
        }

        // Background Listening Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            stringResource(R.string.settings_bg_listen_title),
                            color = NeonPurple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (alwaysListenInBackground) {
                                stringResource(
                                    R.string.settings_bg_listen_on,
                                    TrekoAssistantService.formatAssistantName(customWakeWord, stringResource(R.string.wake_word))
                                )
                            } else {
                                stringResource(
                                    R.string.settings_bg_listen_off,
                                    TrekoAssistantService.formatAssistantName(customWakeWord, stringResource(R.string.wake_word))
                                )
                            },
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = alwaysListenInBackground,
                        onCheckedChange = { checked ->
                            alwaysListenInBackground = checked
                            prefs.isAlwaysListeningInBackground = checked
                            if (checked) {
                                if (TrekoAssistantService.hasMicPermission(context)) {
                                    TrekoAssistantService.startService(context)
                                } else {
                                    onRequestMicPermission()
                                }
                                Toast.makeText(context, context.getString(R.string.settings_bg_listen_toast_on), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.settings_bg_listen_toast_off), Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonPurple.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        Button(
            onClick = {
                prefs.xiaozhiWsUrl = xiaozhiUrl
                prefs.xiaozhiToken = xiaozhiToken
                prefs.haUrl = haUrl
                prefs.haToken = haToken
                prefs.customWakeWord = customWakeWord
                customWakeWord = prefs.customWakeWord
                prefs.wakeWordBoost = wakeWordBoost
                prefs.wakeWordSensitivity = sensitivity
                prefs.isAlwaysListeningInBackground = alwaysListenInBackground
                prefs.vadAdaptiveEnabled = vadAdaptiveEnabled
                prefs.speakerVerificationEnabled = speakerVerifyEnabled
                prefs.audioEchoModeEnabled = echoModeEnabled
                prefs.cinemaModeEnabled = cinemaModeEnabled
                prefs.autoFollowUpEnabled = autoFollowUpEnabled
                prefs.voiceBargeInEnabled = voiceBargeInEnabled
                prefs.fullDuplexMode = fullDuplexMode
                prefs.softwareAec3Enabled = softwareAec3Enabled
                prefs.nearFieldGateEnabled = nearFieldGateEnabled
                prefs.nearFieldMinSnrDb = nearFieldMinSnrDb
                prefs.speechDenoiserEnabled = speechDenoiserEnabled
                prefs.biometricSpeakerGateEnabled = biometricGateEnabled
                prefs.biometricSpeakerThreshold = biometricThreshold
                prefs.proactiveEnabled = proactiveEnabled
                prefs.proactiveSpeakOutLoud = proactiveSpeakOutLoud
                prefs.proactiveCameraAnomalyEnabled = proactiveCameraAnomalyEnabled
                prefs.cameraSentinelEnabled = cameraSentinelEnabled
                prefs.proactiveQuietHoursEnabled = proactiveQuietHoursEnabled
                prefs.proactiveIntervalMinutes = proactiveIntervalMinutes

                TrekoAssistantService.reloadConfiguration(context)
                if (alwaysListenInBackground && TrekoAssistantService.hasMicPermission(context)) {
                    TrekoAssistantService.startService(context)
                }

                Toast.makeText(context, context.getString(R.string.saved_success), Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        OutlinedButton(
            onClick = onReopenWizard,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = NeonPurple)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_reopen_wizard), color = NeonPurple, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))
    }

    if (showTrainingDialog) {
        WakeWordTrainingDialog(
            prefs = prefs,
            keyword = customWakeWord.ifBlank { prefs.customWakeWord },
            onCalibrationApplied = { newBoost, newThreshold ->
                wakeWordBoost = newBoost
                sensitivity = newThreshold
                isCalibrated = prefs.isWakeWordCalibrated
                calibratedSnrDb = prefs.calibratedSnrDb
                speakerVerifyEnabled = prefs.speakerVerificationEnabled
                TrekoAssistantService.reloadConfiguration(context)
            },
            onCalibrationReset = {
                wakeWordBoost = prefs.wakeWordBoost
                sensitivity = prefs.wakeWordSensitivity
                isCalibrated = prefs.isWakeWordCalibrated
                calibratedSnrDb = prefs.calibratedSnrDb
                speakerVerifyEnabled = prefs.speakerVerificationEnabled
            },
            onDismiss = {
                wakeWordBoost = prefs.wakeWordBoost
                sensitivity = prefs.wakeWordSensitivity
                isCalibrated = prefs.isWakeWordCalibrated
                calibratedSnrDb = prefs.calibratedSnrDb
                speakerVerifyEnabled = prefs.speakerVerificationEnabled
                showTrainingDialog = false
            }
        )
    }

    if (showBiometricDialog) {
        BiometricVoiceEnrollmentDialog(
            onDismiss = { showBiometricDialog = false },
            onEnrollmentApplied = { profileJson, recommendedThreshold ->
                prefs.applyBiometricVoicePrint(profileJson, recommendedThreshold)
                biometricEnrolled = prefs.isBiometricVoiceEnrolled
                biometricThreshold = recommendedThreshold
                biometricGateEnabled = true
                TrekoAssistantService.reloadConfiguration(context)
            },
            onResetProfile = {
                prefs.resetBiometricVoicePrint()
                biometricEnrolled = false
                biometricGateEnabled = false
                biometricThreshold = prefs.biometricSpeakerThreshold
                TrekoAssistantService.reloadConfiguration(context)
            }
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NeonCyan,
                checkedTrackColor = NeonPurple.copy(alpha = 0.5f)
            )
        )
    }
}
