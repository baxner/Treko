package com.treko.assistant.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treko.assistant.ui.theme.*
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.TrekoEmotion
import com.treko.core.network.ActivationState
import com.treko.core.network.XiaozhiActivationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class WizardStep {
    WELCOME, XIAOZHI, HOME_ASSISTANT, COMPLETED
}

@Composable
fun SetupWizardScreen(
    prefs: TrekoPreferences,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentStep by remember { mutableStateOf(WizardStep.WELCOME) }

    // Campos temporales
    var wsUrl by remember { mutableStateOf(prefs.xiaozhiWsUrl) }
    var wsToken by remember { mutableStateOf(prefs.xiaozhiToken) }
    var haUrl by remember { mutableStateOf(prefs.haUrl) }
    var haToken by remember { mutableStateOf(prefs.haToken) }

    // Estados de prueba
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
            // Barra de progreso de pasos
            StepProgressBar(currentStep = currentStep)

            Spacer(modifier = Modifier.height(20.dp))

            when (currentStep) {
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
                                xiaozhiTestResult = testXiaozhiConnection(wsUrl, wsToken)
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
                                haTestResult = testHaConnection(haUrl, haToken)
                                haTesting = false
                            }
                        },
                        onBack = { currentStep = WizardStep.XIAOZHI },
                        onSkip = {
                            currentStep = WizardStep.COMPLETED
                        },
                        onNext = {
                            prefs.haUrl = haUrl
                            prefs.haToken = haToken
                            currentStep = WizardStep.COMPLETED
                        }
                    )
                }

                WizardStep.COMPLETED -> {
                    CompletedStep(
                        prefs = prefs,
                        onStart = {
                            prefs.isOnboardingCompleted = true
                            onFinish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StepProgressBar(currentStep: WizardStep) {
    val steps = listOf(WizardStep.WELCOME, WizardStep.XIAOZHI, WizardStep.HOME_ASSISTANT, WizardStep.COMPLETED)
    val currentIndex = steps.indexOf(currentStep)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
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
                Divider(
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
            text = "¡Hola! Soy Treko",
            color = NeonCyan,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        TrekoFace(
            emotion = TrekoEmotion.HAPPY,
            size = 180.dp
        )

        Text(
            text = "Tu asistente personal de voz nativo, ultra-rápido y 100% privado.",
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
                Text("¿Por qué Treko es diferente?", color = NeonPurple, fontWeight = FontWeight.Bold)
                Text("• Privacidad Zero-Leak: La detección de voz se ejecuta 100% en tu procesador local.", color = TextPrimary, fontSize = 13.sp)
                Text("• Activación instantánea: Di en voz alta \"Treko\" en cualquier momento.", color = TextPrimary, fontSize = 13.sp)
                Text("• Control de Luces y Hogar: Soporte nativo para Home Assistant.", color = TextPrimary, fontSize = 13.sp)
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
            Text("Comenzar Configuración", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
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
    val activationManager = remember { XiaozhiActivationManager(prefs) }
    var activationState by remember { mutableStateOf<ActivationState>(ActivationState.Idle) }

    fun requestActivationCode() {
        coroutineScope.launch {
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

    // Auto-solicitar el código al abrir este paso
    LaunchedEffect(Unit) {
        if (!prefs.isXiaozhiLinked) {
            requestActivationCode()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Paso 1: Vincular con Xiaozhi", color = NeonCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Xiaozhi es el motor de IA que sintetiza la voz y gestiona las conversaciones de Treko.",
            color = TextSecondary,
            fontSize = 13.sp
        )

        // Tarjeta con la dirección MAC oficial para xiaozhi.me
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Dirección MAC de este Treko:", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("En xiaozhi.me selecciona \"Añadir dispositivo\" e introduce esta dirección MAC:", color = TextPrimary, fontSize = 12.sp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DeepBackground, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("MAC Address:", color = TextSecondary, fontSize = 11.sp)
                        Text(prefs.macAddress, color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("MAC Address", prefs.macAddress))
                        Toast.makeText(context, "MAC copiada: ${prefs.macAddress}", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar MAC", tint = NeonCyan)
                    }
                }
            }
        }

        // Tarjeta de Código de Verificación de 6 Dígitos
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
                    "2. Código de Verificación de 6 Dígitos",
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                when (val state = activationState) {
                    is ActivationState.Idle -> {
                        Text(
                            "Presiona el botón para solicitar el código que te pide xiaozhi.me.",
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
                            Text("Generar Código de 6 Dígitos", fontWeight = FontWeight.Bold)
                        }
                    }

                    is ActivationState.FetchingCode -> {
                        CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(32.dp))
                        Text("Obteniendo código del servidor Xiaozhi…", color = TextSecondary, fontSize = 12.sp)
                    }

                    is ActivationState.CodeReady, is ActivationState.PollingApproval -> {
                        val code = if (state is ActivationState.CodeReady) state.code else (state as ActivationState.PollingApproval).code

                        // Mostrar los 6 dígitos en cajas llamativas
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
                                text = "Código: $code",
                                color = Color(0xFF00E676),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Código Xiaozhi", code))
                                Toast.makeText(context, "Código copiado: $code", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar Código", tint = NeonCyan, modifier = Modifier.size(18.dp))
                            }
                        }

                        Text(
                            "Escribe este código en la ventana de xiaozhi.me y presiona 'Link'",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = NeonPurple)
                            Text("Esperando a que presiones 'Link' en xiaozhi.me…", color = TextSecondary, fontSize = 11.sp)
                        }
                    }

                    is ActivationState.Success -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(40.dp))
                        Text(
                            "¡Dispositivo Vinculado Exitosamente con Xiaozhi! 🎉",
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    is ActivationState.Error -> {
                        Text("Error: ${state.message}", color = Color(0xFFFF5252), fontSize = 12.sp, textAlign = TextAlign.Center)
                        OutlinedButton(onClick = { requestActivationCode() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reintentar Código")
                        }
                    }
                }
            }
        }

        // Campos opcionales / avanzados de WebSocket
        OutlinedTextField(
            value = wsUrl,
            onValueChange = onWsUrlChange,
            label = { Text("URL WebSocket de Xiaozhi (Avanzado)") },
            placeholder = { Text("wss://api.tenclass.net/xiaozhi/v1/") },
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
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Probar Conexión", color = NeonCyan)
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
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Atrás", color = TextPrimary)
            }
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Siguiente", fontWeight = FontWeight.Bold)
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Paso 2: Home Assistant (Opcional)", color = NeonCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Conecta tu domótica para que Treko encienda luces, regule temperatura de color (Kelvin) y controle dispositivos por voz.",
            color = TextSecondary,
            fontSize = 13.sp
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🔑 Cómo generar tu Token de Home Assistant:", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("1. Abre Home Assistant y haz clic en tu Nombre de Usuario (perfil).", color = TextPrimary, fontSize = 12.sp)
                Text("2. Baja a la sección \"Tokens de acceso de larga duración\".", color = TextPrimary, fontSize = 12.sp)
                Text("3. Haz clic en \"Crear token\", nómbralo \"Treko\" y cópialo aquí.", color = TextPrimary, fontSize = 12.sp)
            }
        }

        OutlinedTextField(
            value = haUrl,
            onValueChange = onHaUrlChange,
            label = { Text("URL del Servidor Home Assistant") },
            placeholder = { Text("ej. http://homeassistant.local:8123") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = haToken,
            onValueChange = onHaTokenChange,
            label = { Text("Token de Acceso de Larga Duración") },
            placeholder = { Text("Pega tu Token de HA aquí") },
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
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Probar Conexión", color = NeonCyan)
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
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Atrás", color = TextPrimary)
            }
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Omitir", color = TextSecondary)
            }
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Guardar", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CompletedStep(
    prefs: TrekoPreferences,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("¡Todo Listo!", color = NeonCyan, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        TrekoFace(
            emotion = TrekoEmotion.HAPPY,
            size = 180.dp
        )

        Text(
            text = "Treko está configurado y listo para responderte.",
            color = TextPrimary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Estado de la Configuración:", color = NeonPurple, fontWeight = FontWeight.Bold)
                Text("• Activación por voz: Activa (Di \"Treko\")", color = Color(0xFF00E676), fontSize = 13.sp)
                Text("• Servidor Xiaozhi: ${prefs.xiaozhiWsUrl.take(28)}…", color = Color(0xFF00E676), fontSize = 13.sp)
                val haStatus = if (prefs.haUrl.isNotBlank()) "Configurado (${prefs.haUrl.take(25)}…)" else "Omitido (puedes añadirlo en Ajustes)"
                Text("• Home Assistant: $haStatus", color = TextPrimary, fontSize = 13.sp)
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
            Text("Entrar a Treko", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Check, contentDescription = null)
        }
    }
}

suspend fun testXiaozhiConnection(url: String, token: String): String = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext "✗ URL vacía"
    try {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url.replace("wss://", "https://").replace("ws://", "http://"))
            .addHeader("Authorization", "Bearer $token")
            .build()
        val response = client.newCall(request).execute()
        if (response.code in 200..499) {
            "✓ Conexión exitosa"
        } else {
            "✗ Error HTTP ${response.code}"
        }
    } catch (e: Exception) {
        "✗ Fallo: ${e.message?.take(20)}"
    }
}

suspend fun testHaConnection(url: String, token: String): String = withContext(Dispatchers.IO) {
    if (url.isBlank() || token.isBlank()) return@withContext "✗ URL o Token vacíos"
    try {
        var cleanUrl = url.trim().removeSuffix("/")
        if (cleanUrl.endsWith("/api")) cleanUrl = cleanUrl.removeSuffix("/api")
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("$cleanUrl/api/")
            .addHeader("Authorization", "Bearer ${token.trim()}")
            .build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            "✓ Conectado a HA"
        } else {
            "✗ HTTP ${response.code}"
        }
    } catch (e: Exception) {
        "✗ Fallo: ${e.message?.take(20)}"
    }
}
