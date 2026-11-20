package com.treko.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.ui.components.SetupWizardScreen
import com.treko.assistant.ui.screens.CameraViewerDialog
import com.treko.assistant.ui.screens.DiagnosticsDialog
import com.treko.assistant.ui.screens.FaceScreen
import com.treko.assistant.ui.screens.HabitsScreen
import com.treko.assistant.ui.screens.MultiCameraDialog
import com.treko.assistant.ui.screens.SettingsScreen
import com.treko.assistant.ui.screens.TasksScreen
import com.treko.assistant.ui.screens.TimerScreen
import com.treko.assistant.ui.theme.DeepBackground
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TrekoTheme
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.TrekoState

class MainActivity : ComponentActivity() {

    private var hasMicPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        hasMicPermission = recordAudioGranted
        if (recordAudioGranted) {
            TrekoAssistantService.startService(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        refreshPermissionState()
        checkAndRequestPermissions()

        val prefs = TrekoPreferences(applicationContext)

        setContent {
            TrekoTheme {
                var isOnboardingCompleted by remember { mutableStateOf(prefs.isOnboardingCompleted) }

                if (!isOnboardingCompleted) {
                    SetupWizardScreen(
                        prefs = prefs,
                        onFinish = {
                            prefs.isOnboardingCompleted = true
                            isOnboardingCompleted = true
                            if (hasMicPermission) {
                                TrekoAssistantService.reloadConfiguration(this)
                            }
                        }
                    )
                } else {
                    MainScreen(
                        prefs = prefs,
                        hasMicPermission = hasMicPermission,
                        onRequestMicPermission = { checkAndRequestPermissions() },
                        onOpenAppSettings = { openAppSettings() },
                        onReopenWizard = {
                            prefs.isOnboardingCompleted = false
                            isOnboardingCompleted = false
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        if (hasMicPermission) {
            TrekoAssistantService.startService(this)
        }
    }

    private fun refreshPermissionState() {
        hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            hasMicPermission = true
            TrekoAssistantService.startService(this)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    override fun onDestroy() {
        val prefs = TrekoPreferences(applicationContext)
        // Keep the service while the user has camera watches running.
        if (!isChangingConfigurations && !prefs.isAlwaysListeningInBackground &&
            TrekoAssistantService.activeWatchAreas.value.isEmpty()
        ) {
            TrekoAssistantService.stopService(this)
        }
        super.onDestroy()
    }
}

enum class NavigationTab {
    FACE, HABITS, TASKS, TIMER, SETTINGS
}

@Composable
fun MainScreen(
    prefs: TrekoPreferences,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onReopenWizard: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(NavigationTab.FACE) }
    var showDiagnostics by remember { mutableStateOf(false) }

    val currentEmotion by TrekoAssistantService.currentEmotion.collectAsState()
    val currentState by TrekoAssistantService.serviceState.collectAsState()
    val serviceAssistantName by TrekoAssistantService.assistantName.collectAsState()
    val effectiveWakeWord = serviceAssistantName.ifBlank { prefs.customWakeWord.ifBlank { "Treko" } }
    val isMuted by TrekoAssistantService.isMuted.collectAsState()
    val isCinemaMode by TrekoAssistantService.isCinemaMode.collectAsState()
    val connectionStatus by TrekoAssistantService.connectionStatus.collectAsState()
    val subtitleText by TrekoAssistantService.subtitleText.collectAsState()
    val micAudioLevelState = NativeAudioEngine.audioLevel.collectAsState()
    val playbackAudioLevelState = TrekoAssistantService.playbackAudioLevel.collectAsState()
    val activeCameraFeed by TrekoAssistantService.activeCameraFeed.collectAsState()
    val activeMultiCameraGrid by TrekoAssistantService.activeMultiCameraGrid.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                contentColor = TextPrimary
            ) {
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.FACE,
                    onClick = { selectedTab = NavigationTab.FACE },
                    icon = { Icon(Icons.Default.Face, contentDescription = stringResource(R.string.tab_face)) },
                    label = { Text(stringResource(R.string.tab_face)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, indicatorColor = SurfaceDark)
                )
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.HABITS,
                    onClick = { selectedTab = NavigationTab.HABITS },
                    icon = { Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = stringResource(R.string.tab_habits)) },
                    label = { Text(stringResource(R.string.tab_habits)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, indicatorColor = SurfaceDark)
                )
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.TASKS,
                    onClick = { selectedTab = NavigationTab.TASKS },
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.tab_tasks)) },
                    label = { Text(stringResource(R.string.tab_tasks)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, indicatorColor = SurfaceDark)
                )
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.TIMER,
                    onClick = { selectedTab = NavigationTab.TIMER },
                    icon = { Icon(Icons.Default.Timer, contentDescription = stringResource(R.string.tab_timer)) },
                    label = { Text(stringResource(R.string.tab_timer)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, indicatorColor = SurfaceDark)
                )
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.SETTINGS,
                    onClick = { selectedTab = NavigationTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.tab_settings)) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, indicatorColor = SurfaceDark)
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DeepBackground)
        ) {
            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(180),
                label = "TabCrossfade"
            ) { tab ->
                when (tab) {
                    NavigationTab.FACE -> FaceScreen(
                        emotion = currentEmotion,
                        state = currentState,
                        connectionStatus = connectionStatus,
                        subtitle = subtitleText,
                        wakeWord = effectiveWakeWord,
                        hasMicPermission = hasMicPermission,
                        isMuted = isMuted,
                        isCinemaMode = isCinemaMode,
                        micAudioLevelState = micAudioLevelState,
                        playbackAudioLevelState = playbackAudioLevelState,
                        onToggleListen = {
                            if (!hasMicPermission) {
                                onRequestMicPermission()
                            } else if (currentState == TrekoState.IDLE || currentState == TrekoState.ERROR || currentState == TrekoState.SPEAKING) {
                                TrekoAssistantService.startConversation(
                                    context,
                                    effectiveWakeWord
                                )
                            } else {
                                TrekoAssistantService.stopConversation()
                            }
                        },
                        onToggleMute = {
                            if (!hasMicPermission) {
                                onRequestMicPermission()
                            } else {
                                TrekoAssistantService.toggleMute(context)
                            }
                        },
                        onToggleCinemaMode = {
                            TrekoAssistantService.toggleCinemaMode(context)
                        },
                        onRequestMicPermission = onRequestMicPermission,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenDiagnostics = { showDiagnostics = true },
                        onOpenMultiCamera = { TrekoAssistantService.openMultiCameraPanel(context) }
                    )
                    NavigationTab.HABITS -> HabitsScreen()
                    NavigationTab.TASKS -> TasksScreen()
                    NavigationTab.TIMER -> TimerScreen()
                    NavigationTab.SETTINGS -> SettingsScreen(
                        prefs = prefs,
                        onReopenWizard = onReopenWizard,
                        onOpenDiagnostics = { showDiagnostics = true },
                        onRequestMicPermission = onRequestMicPermission
                    )
                }
            }

            activeMultiCameraGrid?.let { gridState ->
                MultiCameraDialog(
                    gridState = gridState,
                    onDismiss = { TrekoAssistantService.dismissMultiCameraGrid() },
                    onSelectCamera = { feed -> TrekoAssistantService.showCameraFeed(feed) },
                    onAnalyzeCamera = { feed -> TrekoAssistantService.analyzeCameraFromUi(feed) },
                    onToggleLiveAll = { enabled -> TrekoAssistantService.setMultiCameraLiveAll(enabled) }
                )
            }

            activeCameraFeed?.let { feed ->
                CameraViewerDialog(
                    feed = feed,
                    onDismiss = { TrekoAssistantService.dismissCameraFeed() }
                )
            }

            if (showDiagnostics) {
                DiagnosticsDialog(
                    prefs = prefs,
                    onDismiss = { showDiagnostics = false }
                )
            }
        }
    }
}
