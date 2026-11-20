package com.treko.assistant.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treko.assistant.R
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.ui.screens.BiometricVoiceEnrollmentDialog
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.DeepBackground
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.TrekoEmotion
import com.treko.core.common.model.TrekoState
import com.treko.core.network.ActivationState
import com.treko.core.network.XiaozhiActivationManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class WizardStep {
    WELCOME, XIAOZHI, HOME_ASSISTANT, VOICE, COMPLETED
}

@Composable
fun SetupWizardScreen(
    prefs: TrekoPreferences,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentStep by remember { mutableStateOf(WizardStep.WELCOME) }

    var wsUrl by remember { mutableStateOf(prefs.xiaozhiWsUrl) }
    var wsToken by remember { mutableStateOf(prefs.xiaozhiToken) }
    var haUrl by remember { mutableStateOf(prefs.haUrl) }
    var haToken by remember { mutableStateOf(prefs.haToken) }

    var xiaozhiTesting by remember { mutableStateOf(false) }
    var xiaozhiTestResult by remember { mutableStateOf<String?>(null) }
    var haTesting by remember { mutableStateOf(false) }
    var haTestResult by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBackground)
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StepProgressBar(currentStep = currentStep)

            Spacer(modifier = Modifier.height(20.dp))

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    if (forward) {
                        (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it / 4 } + fadeOut())
                    }
                },
                label = "WizardStepTransition"
            ) { step ->
                when (step) {
                    WizardStep.WELCOME -> {
                        WelcomeStep(
                            onNext = { currentStep = WizardStep.XIAOZHI }
                        )
                    }

                    WizardStep.XIAOZHI -> {
                        XiaozhiStep(
                            prefs = prefs,
                            wsUrl = wsUrl,
                            onWsUrlChange = { wsUrl = it },
                            wsToken = wsToken,
                            onWsTokenChange = { wsToken = it },
                            isTesting = xiaozhiTesting,
                            testResult = xiaozhiTestResult,
                            onTestConnection = {
                                coroutineScope.launch {
                                    xiaozhiTesting = true
                                    xiaozhiTestResult = null
                                    xiaozhiTestResult = testXiaozhiConnection(context, wsUrl, wsToken, prefs.macAddress, prefs.clientId)
                                    xiaozhiTesting = false
                                }
                            },
                            onBack = { currentStep = WizardStep.WELCOME },
                            onNext = {
                                prefs.xiaozhiWsUrl = wsUrl
                                prefs.xiaozhiToken = wsToken
                                currentStep = WizardStep.HOME_ASSISTANT
                            }
                        )
                    }

                    WizardStep.HOME_ASSISTANT -> {
                        HomeAssistantStep(
                            haUrl = haUrl,
                            onHaUrlChange = { haUrl = it },
                            haToken = haToken,
                            onHaTokenChange = { haToken = it },
                            isTesting = haTesting,
                            testResult = haTestResult,
                            onTestConnection = {
                                coroutineScope.launch {
                                    haTesting = true
                                    haTestResult = null
                                    haTestResult = testHaConnection(context, haUrl, haToken)
                                    haTesting = false
                                }
                            },
                            onBack = { currentStep = WizardStep.XIAOZHI },
                            onSkip = { currentStep = WizardStep.VOICE },
                            onNext = {
                                prefs.haUrl = haUrl
                                prefs.haToken = haToken
                                currentStep = WizardStep.VOICE
                            }
                        )
                    }

                    WizardStep.VOICE -> {
                        VoiceProfileStep(
                            prefs = prefs,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = onRequestMicPermission,
                            onOpenAppSettings = onOpenAppSettings,
                            onBack = { currentStep = WizardStep.HOME_ASSISTANT },
                            onNext = { currentStep = WizardStep.COMPLETED }
                        )
                    }

                    WizardStep.COMPLETED -> {
                        CompletedStep(
                            prefs = prefs,
                            onStart = {
                                // The voice step cannot be skipped; guard against stale navigation anyway.
                                if (!prefs.isBiometricVoiceEnrolled) {
                                    currentStep = WizardStep.VOICE
                                } else {
                                    prefs.isOnboardingCompleted = true
                                    onFinish()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StepProgressBar(currentStep: WizardStep) {
    val steps = WizardStep.entries
    val currentIndex = steps.indexOf(currentStep)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, _ ->
            val isActive = index <= currentIndex
            val isCurrent = index == currentIndex

            Box(
                modifier = Modifier
                    .size(if (isCurrent) 36.dp else 28.dp)
                    .clip(CircleShape)
                    .background(if (isActive) NeonCyan else SurfaceDark)
                    .border(
                        width = if (isCurrent) 2.dp else 1.dp,
                        color = if (isActive) NeonCyan else CardBorder,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    color = if (isActive) Color.Black else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isCurrent) 14.sp else 12.sp
                )
            }

            if (index < steps.size - 1) {
                HorizontalDivider(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    color = if (index < currentIndex) NeonCyan else CardBorder,
                    thickness = 2.dp
                )
            }
        }
    }
}

@Composable
fun WelcomeStep(onNext: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.wizard_welcome_title),
            color = NeonCyan,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        TrekoFace(
            emotion = TrekoEmotion.HAPPY,
            size = 170.dp,
            state = TrekoState.IDLE
        )

        Text(
            text = stringResource(R.string.wizard_welcome_subtitle),
            color = TextPrimary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.wizard_why_title), color = NeonPurple, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.wizard_why_privacy), color = TextPrimary, fontSize = 13.sp)
                Text(stringResource(R.string.wizard_why_wake), color = TextPrimary, fontSize = 13.sp)
                Text(stringResource(R.string.wizard_why_ha), color = TextPrimary, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.wizard_start_setup), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
fun XiaozhiStep(
    prefs: TrekoPreferences,
    wsUrl: String,
    onWsUrlChange: (String) -> Unit,
    wsToken: String,
    onWsTokenChange: (String) -> Unit,
    isTesting: Boolean,
    testResult: String?,
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activationManager = remember(prefs) { XiaozhiActivationManager(prefs) }
    var activationState by remember {
        mutableStateOf<ActivationState>(
            if (prefs.isXiaozhiLinked) ActivationState.Success else ActivationState.Idle
        )
    }
    var activationJob by remember { mutableStateOf<Job?>(null) }
    var isWsTokenVisible by remember { mutableStateOf(false) }

    fun requestActivationCode() {
        activationJob?.cancel()
        activationJob = coroutineScope.launch {
            activationState = ActivationState.FetchingCode
            val result = activationManager.fetchActivationCode()
            activationState = result
            if (result is ActivationState.CodeReady) {
                activationManager.pollActivation(result.challenge, result.code) { newState ->
                    activationState = newState
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!prefs.isXiaozhiLinked && activationState is ActivationState.Idle) {
            requestActivationCode()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activationJob?.cancel()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(R.string.wizard_step1_title), color = NeonCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.wizard_step1_desc),
            color = TextSecondary,
            fontSize = 13.sp
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wizard_mac_title), color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(stringResource(R.string.wizard_mac_desc), color = TextPrimary, fontSize = 12.sp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DeepBackground, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.wizard_mac_label), color = TextSecondary, fontSize = 11.sp)
                        Text(prefs.macAddress, color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("MAC Address", prefs.macAddress))
                        Toast.makeText(
                            context,
                            context.getString(R.string.wizard_mac_copied, prefs.macAddress),
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.wizard_copy_mac),
                            tint = NeonCyan
                        )
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.wizard_code_title),
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                when (val state = activationState) {
                    is ActivationState.Idle -> {
                        Text(
                            stringResource(R.string.wizard_code_idle_desc),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { requestActivationCode() },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.VpnKey, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.wizard_generate_code), fontWeight = FontWeight.Bold)
                        }
                    }

                    is ActivationState.FetchingCode -> {
                        CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(32.dp))
                        Text(stringResource(R.string.wizard_fetching_code), color = TextSecondary, fontSize = 12.sp)
                    }

                    is ActivationState.CodeReady, is ActivationState.PollingApproval -> {
                        val code = if (state is ActivationState.CodeReady) {
                            state.code
                        } else {
                            (state as ActivationState.PollingApproval).code
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            code.forEach { char ->
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(DeepBackground, RoundedCornerShape(8.dp))
                                        .border(2.dp, NeonCyan, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = char.toString(),
                                        color = NeonCyan,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.wizard_code_value, code),
                                color = Color(0xFF00E676),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Xiaozhi Code", code))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.wizard_code_copied, code),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.wizard_copy_code),
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Text(
                            stringResource(R.string.wizard_code_enter_hint),
                            color = TextPrimary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = NeonPurple)
                            Text(stringResource(R.string.wizard_waiting_approval), color = TextSecondary, fontSize = 11.sp)
                        }
                    }

                    is ActivationState.Success -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(40.dp))
                        Text(
                            text = if (prefs.isXiaozhiLinked) {
                                stringResource(R.string.wizard_linked_success)
                            } else {
                                stringResource(R.string.wizard_code_already_linked)
                            },
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        OutlinedButton(onClick = { requestActivationCode() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.wizard_relink_device), fontSize = 12.sp)
                        }
                    }

                    is ActivationState.Error -> {
                        Text(
                            stringResource(R.string.wizard_error_prefix, state.message),
                            color = Color(0xFFFF5252),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        OutlinedButton(onClick = { requestActivationCode() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.wizard_retry_code))
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = wsUrl,
            onValueChange = onWsUrlChange,
            label = { Text(stringResource(R.string.wizard_ws_url_advanced)) },
            placeholder = { Text(TrekoPreferences.DEFAULT_XIAOZHI_WS_URL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = wsToken,
            onValueChange = onWsTokenChange,
            label = { Text(stringResource(R.string.wizard_ws_token_optional)) },
            visualTransformation = if (isWsTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isWsTokenVisible = !isWsTokenVisible }) {
                    Icon(
                        imageVector = if (isWsTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onTestConnection,
                enabled = !isTesting,
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(stringResource(R.string.action_test_connection), color = NeonCyan)
            }

            if (testResult != null) {
                Text(
                    text = testResult,
                    color = if (testResult.startsWith("✓")) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.action_back), color = TextPrimary)
            }
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.action_next), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun HomeAssistantStep(
    haUrl: String,
    onHaUrlChange: (String) -> Unit,
    haToken: String,
    onHaTokenChange: (String) -> Unit,
    isTesting: Boolean,
    testResult: String?,
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit
) {
    var isHaTokenVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(R.string.wizard_step2_title), color = NeonCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.wizard_step2_desc),
            color = TextSecondary,
            fontSize = 13.sp
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.wizard_ha_guide_title), color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(stringResource(R.string.wizard_ha_guide_1), color = TextPrimary, fontSize = 12.sp)
                Text(stringResource(R.string.wizard_ha_guide_2), color = TextPrimary, fontSize = 12.sp)
                Text(stringResource(R.string.wizard_ha_guide_3), color = TextPrimary, fontSize = 12.sp)
            }
        }

        OutlinedTextField(
            value = haUrl,
            onValueChange = onHaUrlChange,
            label = { Text(stringResource(R.string.wizard_ha_url_label)) },
            placeholder = { Text(stringResource(R.string.wizard_ha_url_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = haToken,
            onValueChange = onHaTokenChange,
            label = { Text(stringResource(R.string.wizard_ha_token_label)) },
            placeholder = { Text(stringResource(R.string.wizard_ha_token_placeholder)) },
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
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onTestConnection,
                enabled = !isTesting && haUrl.isNotBlank() && haToken.isNotBlank(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(stringResource(R.string.action_test_connection), color = NeonCyan)
            }

            if (testResult != null) {
                Text(
                    text = testResult,
                    color = if (testResult.startsWith("✓")) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.action_back), color = TextPrimary)
            }
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.action_skip), color = TextSecondary)
            }
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.action_save_short), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Required step: records the owner's voice print. Without it Treko cannot tell the owner
 * from other people or from its own voice coming back through the loudspeaker.
 */
@Composable
fun VoiceProfileStep(
    prefs: TrekoPreferences,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var isEnrolled by remember { mutableStateOf(prefs.isBiometricVoiceEnrolled) }
    var showEnrollment by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(R.string.wizard_voice_title), color = NeonCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.wizard_voice_desc), color = TextSecondary, fontSize = 13.sp)

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.wizard_voice_why_title), color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(stringResource(R.string.wizard_voice_why_owner), color = TextPrimary, fontSize = 12.sp)
                Text(stringResource(R.string.wizard_voice_why_echo), color = TextPrimary, fontSize = 12.sp)
                Text(stringResource(R.string.wizard_voice_why_local), color = TextPrimary, fontSize = 12.sp)
            }
        }

        Text(stringResource(R.string.wizard_voice_how), color = TextPrimary, fontSize = 13.sp)

        if (!hasMicPermission) {
            Text(stringResource(R.string.wizard_voice_mic_needed), color = Color(0xFFFF5252), fontSize = 13.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRequestMicPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.wizard_voice_grant_mic), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.wizard_voice_open_settings), color = TextPrimary)
                }
            }
        } else if (isEnrolled) {
            Text(
                stringResource(R.string.wizard_voice_done),
                color = Color(0xFF00E676),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(
                onClick = { showEnrollment = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.wizard_voice_rerecord), color = NeonCyan)
            }
        } else {
            Button(
                onClick = { showEnrollment = true },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.wizard_voice_record), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Text(stringResource(R.string.wizard_voice_required), color = TextSecondary, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.action_back), color = TextPrimary)
            }
            Button(
                onClick = onNext,
                enabled = isEnrolled,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.action_next), fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showEnrollment && hasMicPermission) {
        BiometricVoiceEnrollmentDialog(
            onDismiss = { showEnrollment = false },
            onEnrollmentApplied = { profileJson, recommendedThreshold ->
                prefs.applyBiometricVoicePrint(profileJson, recommendedThreshold)
                isEnrolled = prefs.isBiometricVoiceEnrolled
                TrekoAssistantService.reloadConfiguration(context)
            },
            onResetProfile = {
                prefs.resetBiometricVoicePrint()
                isEnrolled = false
                TrekoAssistantService.reloadConfiguration(context)
            }
        )
    }
}

@Composable
fun CompletedStep(
    prefs: TrekoPreferences,
    onStart: () -> Unit
) {
    val wakeWord = prefs.customWakeWord.ifBlank { stringResource(R.string.wake_word) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(stringResource(R.string.wizard_done_title), color = NeonCyan, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        TrekoFace(
            emotion = TrekoEmotion.HAPPY,
            size = 170.dp,
            state = TrekoState.IDLE
        )

        Text(
            text = stringResource(R.string.wizard_done_subtitle),
            color = TextPrimary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wizard_summary_title), color = NeonPurple, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.wizard_summary_wake, wakeWord), color = Color(0xFF00E676), fontSize = 13.sp)
                Text(
                    stringResource(R.string.wizard_summary_xiaozhi, "${prefs.xiaozhiWsUrl.take(28)}…"),
                    color = Color(0xFF00E676),
                    fontSize = 13.sp
                )
                val haStatus = if (prefs.haUrl.isNotBlank()) {
                    stringResource(R.string.wizard_summary_ha_ok, "${prefs.haUrl.take(25)}…")
                } else {
                    stringResource(R.string.wizard_summary_ha_skip)
                }
                Text(haStatus, color = TextPrimary, fontSize = 13.sp)
                if (prefs.isBiometricVoiceEnrolled) {
                    Text(stringResource(R.string.wizard_summary_voice), color = Color(0xFF00E676), fontSize = 13.sp)
                }
            }
        }

        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.wizard_enter_treko), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Check, contentDescription = null)
        }
    }
}

private val sharedTestHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
}

suspend fun testXiaozhiConnection(
    context: Context,
    url: String,
    token: String,
    macAddress: String = "00:00:00:00:00:00",
    clientId: String = "treko-test"
): String = withContext(Dispatchers.IO) {
    val clean = url.trim()
    if (clean.isBlank()) return@withContext context.getString(R.string.test_conn_empty_url)
    try {
        val deferred = CompletableDeferred<String>()
        val reqBuilder = Request.Builder()
            .url(clean)
            .addHeader("Device-Id", macAddress)
            .addHeader("Client-Id", clientId)
            .addHeader("Protocol-Version", "1")
        if (token.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${token.trim()}")
        }
        val ws = sharedTestHttpClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                deferred.complete(context.getString(R.string.test_conn_ok))
                webSocket.close(1000, "connection test")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val msg = if (code != null) {
                    context.getString(R.string.test_conn_http_err, code)
                } else {
                    context.getString(R.string.test_conn_fail, t.message?.take(28) ?: "error")
                }
                deferred.complete(msg)
            }
        })
        val result = withTimeoutOrNull(5500L) { deferred.await() }
        if (result == null) {
            ws.cancel()
            context.getString(R.string.test_conn_fail, "timeout")
        } else {
            result
        }
    } catch (e: Exception) {
        context.getString(R.string.test_conn_fail, e.message?.take(28) ?: "error")
    }
}

suspend fun testHaConnection(
    context: Context,
    url: String,
    token: String
): String = withContext(Dispatchers.IO) {
    if (url.isBlank() || token.isBlank()) return@withContext context.getString(R.string.test_conn_empty_ha)
    try {
        var cleanUrl = url.trim().removeSuffix("/")
        if (cleanUrl.endsWith("/api")) cleanUrl = cleanUrl.removeSuffix("/api")
        val request = Request.Builder()
            .url("$cleanUrl/api/")
            .addHeader("Authorization", "Bearer ${token.trim()}")
            .build()
        sharedTestHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                context.getString(R.string.test_conn_ha_ok)
            } else {
                context.getString(R.string.test_conn_http_err, response.code)
            }
        }
    } catch (e: Exception) {
        context.getString(R.string.test_conn_fail, e.message?.take(28) ?: "error")
    }
}
