package com.treko.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.ui.components.*
import com.treko.assistant.ui.theme.*
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.AudioLevel
import com.treko.core.common.model.ChatMessage
import com.treko.core.common.model.TrekoEmotion
import com.treko.core.common.model.TrekoState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            TrekoAssistantService.startService(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        }
                    )
                } else {
                    MainScreen(
                        prefs = prefs,
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            TrekoAssistantService.startService(this)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            TrekoAssistantService.startService(this)
        }
    }

    override fun onDestroy() {
        val prefs = TrekoPreferences(applicationContext)
        if (!prefs.isAlwaysListeningInBackground) {
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
    onReopenWizard: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.FACE) }
    val currentEmotion by TrekoAssistantService.currentEmotion.collectAsState()
    val currentState by TrekoAssistantService.serviceState.collectAsState()
    val subtitleText by TrekoAssistantService.subtitleText.collectAsState()
    val audioLevel by NativeAudioEngine.audioLevel.collectAsState()

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
                    icon = { Icon(Icons.Default.TrendingUp, contentDescription = stringResource(R.string.tab_habits)) },
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
            when (selectedTab) {
                NavigationTab.FACE -> FaceScreen(
                    emotion = currentEmotion,
                    state = currentState,
                    subtitle = subtitleText,
                    audioLevel = audioLevel,
                    onToggleListen = {
                        if (currentState == TrekoState.IDLE) {
                            TrekoAssistantService.startConversation(prefs.customWakeWord.ifBlank { "Treko" })
                        } else if (currentState == TrekoState.SPEAKING) {
                            // Interrupción táctil durante habla (Barge-In)
                            TrekoAssistantService.startConversation(prefs.customWakeWord.ifBlank { "Treko" })
                        } else {
                            TrekoAssistantService.stopConversation()
                        }
                    }
                )
                NavigationTab.HABITS -> HabitsScreen()
                NavigationTab.TASKS -> TasksScreen()
                NavigationTab.TIMER -> TimerScreen()
                NavigationTab.SETTINGS -> SettingsScreen(
                    prefs = prefs,
                    onReopenWizard = onReopenWizard
                )
            }
        }
    }
}

@Composable
fun FaceScreen(
    emotion: TrekoEmotion,
    state: TrekoState,
    subtitle: String,
    audioLevel: AudioLevel,
    onToggleListen: () -> Unit
) {
    var showHistory by remember { mutableStateOf(false) }
    val history by TrekoAssistantService.conversationHistory.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll al final del historial cuando llega un mensaje nuevo
    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) {
            listState.animateScrollToItem(history.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Encabezado con título, chip de estado y botón para ver/ocultar historial
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    color = NeonCyan,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                AssistChip(
                    onClick = {},
                    label = { Text(state.name, color = NeonPurple, fontSize = 11.sp) }
                )
            }

            // Botón interactivo para ver u ocultar el historial con badge contador
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
                        contentDescription = if (showHistory) "Ocultar historial" else "Ver historial"
                    )
                }
            }
        }

        // Lógica facial reactiva: NUNCA cortar la animación de habla con el micrófono
        val dynamicEmotion = when {
            state == TrekoState.SPEAKING -> emotion // NUNCA cortar animación de respuesta por micro
            state == TrekoState.THINKING -> TrekoEmotion.PIENSA
            state == TrekoState.LISTENING -> TrekoEmotion.ESCUCHA
            else -> emotion
        }

        if (showHistory) {
            // Modo Historial: Cara compacta superior + Lista de conversación interactiva
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clickable { onToggleListen() }
                        .padding(top = 4.dp)
                ) {
                    TrekoFace(
                        emotion = dynamicEmotion,
                        size = 110.dp
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.88f)),
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
                                text = "Conversación (${history.size})",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (history.isNotEmpty()) {
                                TextButton(
                                    onClick = { TrekoAssistantService.clearHistory() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Limpiar", color = Color(0xFFFF5252), fontSize = 11.sp)
                                }
                            }
                        }

                        if (history.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No hay mensajes en esta sesión.\nDi 'Treko' para comenzar a hablar.",
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
                                    ChatBubble(msg = msg)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Modo Cara Principal: Cara grande de 260dp
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clickable { onToggleListen() }
                    .padding(vertical = 16.dp)
            ) {
                TrekoFace(
                    emotion = dynamicEmotion,
                    size = 260.dp
                )
            }
        }

        // Ecualizador reactivo, subtítulo y control táctil
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReactiveEqualizer(audioLevel = audioLevel)

            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = NeonCyan,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else {
                Text(
                    text = when (state) {
                        TrekoState.IDLE -> if (dynamicEmotion == TrekoEmotion.SLEEPY) "Treko durmiendo (Zzz... di 'Treko' para despertar)" else stringResource(R.string.status_idle)
                        TrekoState.LISTENING -> stringResource(R.string.status_listening)
                        TrekoState.THINKING -> stringResource(R.string.status_thinking)
                        TrekoState.SPEAKING -> stringResource(R.string.status_speaking)
                        TrekoState.ERROR -> "Error de conexión"
                    },
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            FloatingActionButton(
                onClick = onToggleListen,
                containerColor = if (state == TrekoState.LISTENING) NeonPurple else NeonCyan,
                contentColor = Color.Black,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(60.dp)
            ) {
                Icon(
                    imageVector = if (state == TrekoState.LISTENING) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Micrófono"
                )
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.sender.equals("Tú", ignoreCase = true)
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
                    text = msg.sender,
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

// -------------------------------------------------------------
// PANTALLA DE HÁBITOS (INTERACTIVA)
// -------------------------------------------------------------
data class HabitItem(
    val id: Int,
    val title: String,
    val icon: String,
    var isDone: Boolean
)

@Composable
fun HabitsScreen() {
    var waterGlasses by remember { mutableIntStateOf(5) }
    val habits = remember {
        mutableStateListOf(
            HabitItem(1, "10 min de meditación y respiración", "🧘", true),
            HabitItem(2, "Lectura técnica o enfoque de estudio (25 min)", "📖", true),
            HabitItem(3, "Sincronizar iluminación inteligente de descanso", "💡", false),
            HabitItem(4, "Caminar 30 min o actividad física", "🚶", false)
        )
    }

    val totalHabits = habits.size + 1 // +1 por la meta de agua (8 vasos)
    val waterDone = waterGlasses >= 8
    val completedHabits = habits.count { it.isDone } + (if (waterDone) 1 else 0)
    val progress = (completedHabits.toFloat() / totalHabits.toFloat()).coerceIn(0f, 1f)

    var showAddDialog by remember { mutableStateOf(false) }
    var newHabitTitle by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Hábitos Diarios", color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Seguimiento y bienestar personal", color = TextSecondary, fontSize = 13.sp)
            }
            IconButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.background(SurfaceDark, CircleShape)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Añadir Hábito", tint = NeonCyan)
            }
        }

        // Tarjeta de progreso general
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Progreso de hoy", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "$completedHabits de $totalHabits (${(progress * 100).toInt()}%)",
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    color = NeonCyan,
                    trackColor = CardBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                )
            }
        }

        // Contador de Hidratación interactivo
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("💧", fontSize = 28.sp)
                    Column {
                        Text("Hidratación Diaria", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            text = if (waterDone) "Meta de 8 vasos cumplida 🎉" else "$waterGlasses de 8 vasos (250 ml)",
                            color = if (waterDone) Color(0xFF00E676) else TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = { if (waterGlasses > 0) waterGlasses-- },
                        modifier = Modifier
                            .size(36.dp)
                            .background(CardBorder, CircleShape)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Menos", tint = TextPrimary, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = "$waterGlasses",
                        color = NeonCyan,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { waterGlasses++ },
                        modifier = Modifier
                            .size(36.dp)
                            .background(CardBorder, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Más", tint = TextPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Lista de hábitos interactivos
        Text("Rutinas y Hábitos", color = NeonPurple, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        habits.forEachIndexed { index, habit ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (habit.isDone) SurfaceDark.copy(alpha = 0.7f) else SurfaceDark
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { habits[index] = habit.copy(isDone = !habit.isDone) },
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(habit.icon, fontSize = 22.sp)
                        Text(
                            text = habit.title,
                            color = if (habit.isDone) TextSecondary else TextPrimary,
                            textDecoration = if (habit.isDone) TextDecoration.LineThrough else TextDecoration.None,
                            fontSize = 14.sp
                        )
                    }

                    Checkbox(
                        checked = habit.isDone,
                        onCheckedChange = { checked -> habits[index] = habit.copy(isDone = checked) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = NeonCyan,
                            checkmarkColor = Color.Black
                        )
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Nuevo Hábito", color = NeonCyan) },
            text = {
                OutlinedTextField(
                    value = newHabitTitle,
                    onValueChange = { newHabitTitle = it },
                    label = { Text("Nombre del hábito") },
                    placeholder = { Text("Ej. Estiramiento 10 min") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newHabitTitle.isNotBlank()) {
                            habits.add(HabitItem(habits.size + 1, newHabitTitle.trim(), "✨", false))
                            newHabitTitle = ""
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                ) {
                    Text("Añadir")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

// -------------------------------------------------------------
// PANTALLA DE TAREAS (INTERACTIVA)
// -------------------------------------------------------------
data class TaskItem(
    val id: Int,
    val title: String,
    var isDone: Boolean
)

@Composable
fun TasksScreen() {
    val tasks = remember {
        mutableStateListOf(
            TaskItem(1, "Revisar sensores de temperatura en Home Assistant", false),
            TaskItem(2, "Completar sesión Pomodoro de 25 min", true),
            TaskItem(3, "Configurar palabra de activación 'Treko' en la oficina", false)
        )
    }

    var newTaskText by remember { mutableStateOf("") }
    var filterMode by remember { mutableIntStateOf(0) } // 0: Todas, 1: Pendientes, 2: Hechas

    val filteredTasks = tasks.filter {
        when (filterMode) {
            1 -> !it.isDone
            2 -> it.isDone
            else -> true
        }
    }

    val doneCount = tasks.count { it.isDone }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Tareas del Día", color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("$doneCount de ${tasks.size} completadas", color = TextSecondary, fontSize = 13.sp)
            }
        }

        // Campo para añadir tarea rápidamente
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = newTaskText,
                onValueChange = { newTaskText = it },
                placeholder = { Text("Escribe una nueva tarea…", fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                onClick = {
                    if (newTaskText.isNotBlank()) {
                        tasks.add(0, TaskItem(System.currentTimeMillis().toInt(), newTaskText.trim(), false))
                        newTaskText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Añadir")
            }
        }

        // Filtros rápidos
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filterMode == 0,
                onClick = { filterMode = 0 },
                label = { Text("Todas (${tasks.size})") }
            )
            FilterChip(
                selected = filterMode == 1,
                onClick = { filterMode = 1 },
                label = { Text("Pendientes (${tasks.count { !it.isDone }})") }
            )
            FilterChip(
                selected = filterMode == 2,
                onClick = { filterMode = 2 },
                label = { Text("Completadas ($doneCount)") }
            )
        }

        // Lista de tareas
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredTasks, key = { it.id }) { task ->
                val taskIndex = tasks.indexOfFirst { it.id == task.id }
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Checkbox(
                                checked = task.isDone,
                                onCheckedChange = { checked ->
                                    if (taskIndex >= 0) {
                                        tasks[taskIndex] = task.copy(isDone = checked)
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = NeonCyan,
                                    checkmarkColor = Color.Black
                                )
                            )
                            Text(
                                text = task.title,
                                color = if (task.isDone) TextSecondary else TextPrimary,
                                textDecoration = if (task.isDone) TextDecoration.LineThrough else TextDecoration.None,
                                fontSize = 14.sp
                            )
                        }

                        IconButton(
                            onClick = { tasks.removeAll { it.id == task.id } }
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar", tint = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// PANTALLA DE TEMPORIZADOR (POMODORO FUNCIONAL)
// -------------------------------------------------------------
enum class TimerMode(val title: String, val seconds: Int) {
    POMODORO("Pomodoro (25m)", 25 * 60),
    SHORT_BREAK("Descanso Corto (5m)", 5 * 60),
    LONG_BREAK("Descanso Largo (15m)", 15 * 60)
}

@Composable
fun TimerScreen() {
    var selectedMode by remember { mutableStateOf(TimerMode.POMODORO) }
    var remainingSeconds by remember { mutableIntStateOf(selectedMode.seconds) }
    var isRunning by remember { mutableStateOf(false) }
    var completedSessions by remember { mutableIntStateOf(2) }

    LaunchedEffect(isRunning) {
        while (isRunning && remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        if (remainingSeconds <= 0 && isRunning) {
            isRunning = false
            if (selectedMode == TimerMode.POMODORO) {
                completedSessions++
            }
        }
    }

    val progress = (remainingSeconds.toFloat() / selectedMode.seconds.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Temporizador de Enfoque", color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Técnica Pomodoro asistida por Treko", color = TextSecondary, fontSize = 13.sp)

            Spacer(modifier = Modifier.height(16.dp))

            // Selector de modo
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimerMode.values().forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = {
                            selectedMode = mode
                            remainingSeconds = mode.seconds
                            isRunning = false
                        },
                        label = { Text(mode.title, fontSize = 11.sp) }
                    )
                }
            }
        }

        // Reloj Cyber Digital
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            val timeString = String.format("%02d:%02d", minutes, seconds)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
                    .border(3.dp, if (isRunning) NeonCyan else CardBorder, CircleShape)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = timeString,
                        color = if (isRunning) NeonCyan else TextPrimary,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isRunning) "En progreso…" else "Pausado",
                        color = if (isRunning) NeonPurple else TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                color = NeonCyan,
                trackColor = CardBorder,
                modifier = Modifier
                    .width(220.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }

        // Controles y estadísticas
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { isRunning = !isRunning },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) Color(0xFFFF5252) else NeonCyan,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.height(50.dp).width(140.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRunning) "Pausar" else "Iniciar", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        isRunning = false
                        remainingSeconds = selectedMode.seconds
                    },
                    modifier = Modifier.height(50.dp).width(120.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = TextPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reiniciar", color = TextPrimary)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sesiones hoy", color = TextSecondary, fontSize = 12.sp)
                        Text("$completedSessions", color = NeonCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Enfoque total", color = TextSecondary, fontSize = 12.sp)
                        Text("${completedSessions * 25} min", color = NeonPurple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// PANTALLA DE AJUSTES (DINÁMICA Y PERSISTIDA)
// -------------------------------------------------------------
@Composable
fun SettingsScreen(
    prefs: TrekoPreferences,
    onReopenWizard: () -> Unit
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

    var isTokenVisible by remember { mutableStateOf(false) }

    // Estados de pruebas
    var isTestingXiaozhi by remember { mutableStateOf(false) }
    var xiaozhiResult by remember { mutableStateOf<String?>(null) }
    var isTestingHa by remember { mutableStateOf(false) }
    var haResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Ajustes del Asistente", color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        // Tarjeta Servidor Xiaozhi
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
                    Text("Servidor Xiaozhi AI", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MAC Address", prefs.macAddress))
                            Toast.makeText(context, "MAC copiada: ${prefs.macAddress}", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar MAC", tint = NeonCyan, modifier = Modifier.size(20.dp))
                    }
                }

                AssistChip(
                    onClick = {},
                    label = { Text("MAC (xiaozhi.me): ${prefs.macAddress}", fontSize = 12.sp, color = NeonCyan) }
                )

                OutlinedTextField(
                    value = xiaozhiUrl,
                    onValueChange = { xiaozhiUrl = it },
                    label = { Text("URL WebSocket Xiaozhi", fontSize = 12.sp) },
                    placeholder = { Text("wss://api.tenclass.net/xiaozhi/v1/") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = xiaozhiToken,
                    onValueChange = { xiaozhiToken = it },
                    label = { Text("Token de Xiaozhi (opcional)", fontSize = 12.sp) },
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
                                xiaozhiResult = testXiaozhiConnection(xiaozhiUrl, xiaozhiToken)
                                isTestingXiaozhi = false
                            }
                        },
                        enabled = !isTestingXiaozhi
                    ) {
                        if (isTestingXiaozhi) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Probar Servidor", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Tarjeta Home Assistant
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Home Assistant (Domótica)", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                OutlinedTextField(
                    value = haUrl,
                    onValueChange = { haUrl = it },
                    label = { Text("URL del Servidor", fontSize = 12.sp) },
                    placeholder = { Text("http://homeassistant.local:8123") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = haToken,
                    onValueChange = { haToken = it },
                    label = { Text("Token de Larga Duración", fontSize = 12.sp) },
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                            Icon(
                                imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    },
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
                                haResult = testHaConnection(haUrl, haToken)
                                isTestingHa = false
                            }
                        },
                        enabled = !isTestingHa
                    ) {
                        if (isTestingHa) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Probar HA", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Tarjeta Sensibilidad Wake Word y Personalización KWS
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Detección de Voz Local (KWS)", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Motor neuronal Sherpa-ONNX Zipformer (100% privado en tu teléfono)", color = TextSecondary, fontSize = 12.sp)

                OutlinedTextField(
                    value = customWakeWord,
                    onValueChange = { customWakeWord = it },
                    label = { Text("Palabra de Activación Personalizada", fontSize = 12.sp) },
                    placeholder = { Text("Treko, Jarvis, Alexa, etc.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(2.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sensibilidad al Susurro (Ganancia)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(String.format(java.util.Locale.US, "%.1fx", wakeWordBoost), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Text("Multiplicador acústico para despertar a Treko hablando suave o de lejos (def: 2.5x)", color = TextSecondary, fontSize = 11.sp)
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
                        Text("Umbral de Ruido (Filtro Acústico)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(String.format(java.util.Locale.US, "%.2f", sensitivity), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Text("Menor valor = más sensible. Mayor valor = evita falsos positivos con ruidos (def: 0.05)", color = TextSecondary, fontSize = 11.sp)
                }

                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    valueRange = 0.01f..0.20f,
                    colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                )
            }
        }

        // Tarjeta Escucha en Segundo Plano
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Escuchar con app cerrada",
                            color = NeonPurple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (alwaysListenInBackground)
                                "Treko continúa escuchando 'Treko' en segundo plano aunque cierres la app o apagues la pantalla."
                            else
                                "Treko solo escuchará mientras la app esté abierta. Al cerrar la app se detiene el micrófono.",
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
                                TrekoAssistantService.startService(context)
                                Toast.makeText(context, "Escucha en segundo plano activada", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Treko se detendrá al cerrar la app", Toast.LENGTH_SHORT).show()
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

        // Acciones y Botones Finales
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

                TrekoAssistantService.updateWakeWordConfig(
                    context = context,
                    keyword = prefs.customWakeWord,
                    boost = prefs.wakeWordBoost,
                    threshold = prefs.wakeWordSensitivity
                )

                if (alwaysListenInBackground) {
                    TrekoAssistantService.startService(context)
                }

                Toast.makeText(context, "¡Ajustes guardados!", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Guardar Ajustes", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
            Text("Reabrir Asistente de Configuración (Wizard)", color = NeonPurple, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
