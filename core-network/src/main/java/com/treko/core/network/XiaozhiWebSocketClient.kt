package com.treko.core.network

import android.util.Log
import com.treko.core.common.model.TrekoSkills
import com.treko.core.common.model.TrekoState
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Cliente WebSocket oficial para el protocolo Xiaozhi AI v1/v2.
 * Maneja:
 * - Autenticación por MAC y Client-ID.
 * - Negociación Opus a 16kHz captura y 48kHz reproducción.
 * - Escucha activa natural sin eco ni auto-disparo de la palabra clave.
 * - Soporte para herramientas MCP (Model Context Protocol).
 */
class XiaozhiWebSocketClient(
    private val serverUrl: String,
    private val macAddress: String,
    private val clientId: String,
    private val token: String = ""
) {
    private val TAG = "TrekoNetwork"
    @Volatile
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // Keepalive cada 20s para evitar timeout de inactividad de 60s en el gateway
        .retryOnConnectionFailure(true)
        .build()

    private val _connectionState = MutableStateFlow(TrekoState.IDLE)
    val connectionState: StateFlow<TrekoState> = _connectionState.asStateFlow()

    @Volatile
    var sessionId: String? = null
        private set

    /** Home Assistant integrations detected for this user; gates skill tools (TrekoSkills). */
    @Volatile
    var availableSkills: Set<String> = emptySet()

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var isConnecting: Boolean = false

    @Volatile
    private var isExplicitDisconnect: Boolean = false

    @Volatile
    private var pendingListenStart: Boolean = false

    @Volatile
    private var pendingListenMode: String = "auto"

    @Volatile
    var advertiseAecFeature: Boolean = true

    @Volatile
    private var reconnectAttempt: Int = 0

    @Volatile
    private var reconnectScheduledForSocket: WebSocket? = null

    private var reconnectJob: Job? = null

    var onSessionStarted: ((String) -> Unit)? = null
    var onSttReceived: ((String) -> Unit)? = null
    var onLlmReceived: ((text: String, emotion: String?) -> Unit)? = null
    var onTtsStateChanged: ((state: String, text: String?) -> Unit)? = null
    var onAudioPacketReceived: ((ByteArray) -> Unit)? = null
    var onConnectionStateChanged: ((TrekoState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onServerAlert: ((status: String, message: String) -> Unit)? = null
    var onMcpToolCall: (suspend (name: String, args: JSONObject) -> String)? = null

    internal fun computeReconnectDelayMs(attempt: Int): Long {
        val exp = 1L shl attempt.coerceIn(0, 4) // 1, 2, 4, 8, 16 -> 2s..32s
        val baseMs = min(2000L * exp, 30_000L)
        val jitterMs = Random.nextLong(0L, 500L)
        return baseMs + jitterMs
    }

    private fun scheduleReconnect(closedSocket: WebSocket? = null) {
        if (isExplicitDisconnect) return
        if (closedSocket != null) {
            if (reconnectScheduledForSocket === closedSocket) return
            reconnectScheduledForSocket = closedSocket
        }
        reconnectJob?.cancel()
        val delayMs = computeReconnectDelayMs(reconnectAttempt)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(10)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isConnected && !isConnecting && !isExplicitDisconnect) {
                TrekoLogBuffer.i(TAG, "Reconectando a Xiaozhi (intento #$reconnectAttempt, delay=${delayMs}ms)...")
                connect()
            }
        }
    }

    fun connect() {
        isExplicitDisconnect = false
        if (isConnected || isConnecting) {
            Log.d(TAG, "Conexión WebSocket ya se encuentra activa o en progreso.")
            return
        }

        val cleanUrl = serverUrl.trim()
        if (!cleanUrl.startsWith("ws://", ignoreCase = true) && !cleanUrl.startsWith("wss://", ignoreCase = true)) {
            val errMsg = "URL WebSocket inválida: '$serverUrl' (debe iniciar con ws:// o wss://)"
            TrekoLogBuffer.e(TAG, errMsg)
            _connectionState.value = TrekoState.ERROR
            onConnectionStateChanged?.invoke(TrekoState.ERROR)
            onError?.invoke(errMsg)
            return
        }

        isConnecting = true

        try {
            val requestBuilder = Request.Builder()
                .url(cleanUrl)
                .addHeader("Device-Id", macAddress)
                .addHeader("Client-Id", clientId)
                .addHeader("Protocol-Version", "1")

            if (token.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            val request = requestBuilder.build()
            TrekoLogBuffer.i(TAG, "Conectando a Xiaozhi WebSocket: $cleanUrl (MAC: $macAddress)...")

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (ws !== webSocket) return
                    TrekoLogBuffer.i(TAG, "Conexión WebSocket establecida con Xiaozhi.")
                    isConnecting = false
                    isConnected = true
                    reconnectAttempt = 0
                    reconnectScheduledForSocket = null
                    sendHello()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (ws !== webSocket) return
                    handleJsonMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (ws !== webSocket) return
                    onAudioPacketReceived?.invoke(bytes.toByteArray())
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    if (ws !== webSocket) return
                    TrekoLogBuffer.i(TAG, "WebSocket cerrándose: $code / $reason")
                    isConnecting = false
                    isConnected = false
                    sessionId = null
                    _connectionState.value = TrekoState.IDLE
                    onConnectionStateChanged?.invoke(TrekoState.IDLE)
                    scheduleReconnect(ws)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (ws !== webSocket) return
                    isConnecting = false
                    isConnected = false
                    sessionId = null
                    _connectionState.value = TrekoState.IDLE
                    onConnectionStateChanged?.invoke(TrekoState.IDLE)
                    scheduleReconnect(ws)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (ws !== webSocket) return
                    val httpInfo = response?.let { " (HTTP ${it.code})" } ?: ""
                    val errMsg = "${t.message ?: "Error de conexión"}$httpInfo"
                    TrekoLogBuffer.e(TAG, "Fallo en conexión WebSocket Xiaozhi: $errMsg")
                    isConnecting = false
                    isConnected = false
                    sessionId = null
                    _connectionState.value = TrekoState.ERROR
                    onConnectionStateChanged?.invoke(TrekoState.ERROR)
                    onError?.invoke(errMsg)
                    scheduleReconnect(ws)
                }
            })
        } catch (e: Exception) {
            isConnecting = false
            isConnected = false
            val errMsg = e.message ?: "Error iniciando WebSocket"
            TrekoLogBuffer.e(TAG, "Error construyendo conexión WebSocket: $errMsg", e)
            _connectionState.value = TrekoState.ERROR
            onConnectionStateChanged?.invoke(TrekoState.ERROR)
            onError?.invoke(errMsg)
        }
    }

    private fun sendHello() {
        try {
            val hello = JSONObject().apply {
                put("type", "hello")
                put("version", 1)
                put("features", JSONObject().apply {
                    put("mcp", true)
                    if (advertiseAecFeature) {
                        put("aec", true)
                    }
                })
                put("transport", "websocket")
                put("audio_params", JSONObject().apply {
                    put("format", "opus")
                    put("sample_rate", 16000)
                    put("channels", 1)
                    put("frame_duration", 60)
                })
            }
            sendJson(hello.toString())
            Log.d(TAG, "Mensaje hello enviado.")
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando hello: ${e.message}")
        }
    }

    private fun handleJsonMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "hello" -> {
                    sessionId = if (json.has("session_id")) json.optString("session_id") else null
                    Log.i(TAG, "Negociación completada con éxito. Session ID: $sessionId")
                    _connectionState.value = TrekoState.IDLE
                    onConnectionStateChanged?.invoke(TrekoState.IDLE)
                    sessionId?.let { onSessionStarted?.invoke(it) }

                    if (pendingListenStart) {
                        pendingListenStart = false
                        Log.i(TAG, "Iniciando escucha diferida tras negociación exitosa...")
                        startListening(pendingListenMode)
                    }
                }
                "mcp" -> {
                    handleMcpMessage(json)
                }
                "stt" -> {
                    val transcript = json.optString("text")
                    if (transcript.isNotBlank()) {
                        Log.i(TAG, "STT reconocido: $transcript")
                        onSttReceived?.invoke(transcript)
                    }
                }
                "llm" -> {
                    val emotion = if (json.has("emotion")) json.optString("emotion") else null
                    val answer = json.optString("text", "")
                    Log.i(TAG, "LLM respuesta: texto='$answer', emoción='$emotion'")
                    onLlmReceived?.invoke(answer, emotion)
                }
                "tts" -> {
                    val state = json.optString("state")
                    val subtitle = if (json.has("text")) json.optString("text") else null
                    Log.d(TAG, "TTS estado: $state (texto: $subtitle)")
                    onTtsStateChanged?.invoke(state, subtitle)
                }
                "alert" -> {
                    val status = json.optString("status", "ERROR")
                    val message = json.optString("message", "")
                    TrekoLogBuffer.w(TAG, "Alerta del servidor Xiaozhi ($status): $message")
                    onServerAlert?.invoke(status, message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analizando JSON entrante: ${e.message}")
        }
    }

    private fun handleMcpMessage(json: JSONObject) {
        try {
            val payload = json.optJSONObject("payload") ?: return
            val method = payload.optString("method")
            val idValue = payload.opt("id")?.takeIf { it !== JSONObject.NULL }

            if (method == "initialize" && idValue != null) {
                val response = JSONObject().apply {
                    put("session_id", sessionId ?: "")
                    put("type", "mcp")
                    put("payload", JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", idValue)
                        put("result", JSONObject().apply {
                            put("protocolVersion", "2024-11-05")
                            put("capabilities", JSONObject().apply {
                                put("tools", JSONObject())
                            })
                            put("serverInfo", JSONObject().apply {
                                put("name", "Treko Android")
                                put("version", "1.0.0")
                            })
                        })
                    })
                }
                sendJson(response.toString())
            } else if (method == "tools/list" && idValue != null) {
                val toolsArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "get_device_status")
                        put("description", "Obtiene el nivel de batería, hora actual y estado del asistente Treko en el teléfono.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "manage_vault_memory")
                        put("description", "Gestiona la memoria permanente (Vault) del usuario: guarda recuerdos, gustos personales o consulta lo almacenado.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'set' para guardar, 'get' para consultar o 'list' para listar todos")
                                })
                                put("key", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Clave del recuerdo (ej. 'comida_favorita', 'cumpleanos')")
                                })
                                put("value", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Contenido o dato a guardar")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "home_assistant_call_service")
                        put("description", "Llama a cualquier servicio o acción avanzada en Home Assistant. Permite controlar absolutamente cualquier dispositivo o integración disponible (enviar anuncios o hablar por altavoces Alexa/Google/media_players con 'notify' o 'media_player', reproducir música, pausar, cambiar volumen, activar escenas, scripts, automatizaciones, abrir/cerrar persianas, controlar clima o termostatos, aspiradoras, cerraduras, etc.). NOTA: Para prender o apagar televisores o luces por nombre ('la tele', 'luz'), usa prioritariamente 'home_assistant_control'.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("domain", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Dominio del servicio (ej. 'notify', 'media_player', 'light', 'switch', 'climate', 'scene', 'script', 'automation', 'cover')")
                                })
                                put("service", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre del servicio o acción a ejecutar (ej. 'alexa_media_en_todas_partes', 'media_play', 'media_pause', 'volume_set', 'turn_on', 'turn_off', 'set_temperature', 'trigger')")
                                })
                                put("service_data", JSONObject().apply {
                                    put("type", "object")
                                    put("description", "Parámetros del servicio en formato JSON (ej. {\"message\": \"Aviso a la casa\"}, {\"entity_id\": \"media_player.sala\", \"volume_level\": 0.5}) (opcional)")
                                })
                            })
                            put("required", JSONArray().apply { put("domain"); put("service") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "home_assistant_broadcast")
                        put("description", "Envía anuncios de voz o mensajes a los altavoces de la casa (Alexa, Echo, Google Cast, etc.) a través de Home Assistant. Úsalo cuando el usuario pida avisar, mandar un recado, regañar a alguien o hablar por las bocinas/Alexas.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("message", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Mensaje o texto que dirán los altavoces en voz alta")
                                })
                                put("target", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Destino opcional: 'todas', 'casa', o nombre de una habitación o altavoz (ej. 'cocina', 'recamara')")
                                })
                            })
                            put("required", JSONArray().apply { put("message") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "home_assistant_list_entities")
                        put("description", "Lista o busca entidades y dispositivos disponibles en Home Assistant (altavoces/Alexas, luces, interruptores, sensores, climas, persianas, etc.) con sus nombres amigables, entity_id reales y estados. Úsalo para saber qué dispositivos existen o encontrar el entity_id correcto.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("domain", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Filtro opcional por dominio: 'light', 'media_player', 'notify', 'switch', 'climate', 'sensor', 'scene', 'automation', o 'all' (opcional)")
                                })
                                put("search", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Término de búsqueda opcional por nombre o ID (ej. 'alexa', 'sala', 'cuarto', 'luz', 'temperatura')")
                                })
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "home_assistant_get_entity_state")
                        put("description", "Consulta el estado actual y todos los atributos detallados de un dispositivo o entidad específica en Home Assistant (ej. temperatura actual, si un dispositivo está encendido o apagado, volumen de un altavoz, nivel de batería, etc.).")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("entity_id", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "El entity_id exacto en Home Assistant (ej. 'sensor.temperatura', 'media_player.sala', 'light.sala')")
                                })
                            })
                            put("required", JSONArray().apply { put("entity_id") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "home_assistant_list_services")
                        put("description", "Lista los servicios o acciones disponibles en Home Assistant, filtrados opcionalmente por dominio. Úsalo si necesitas saber qué acciones soporta un dominio como 'notify', 'media_player', 'climate', etc.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("domain", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Dominio a consultar (ej. 'notify', 'media_player', 'climate', 'scene')")
                                })
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "home_assistant_control")
                        put("description", "Control rápido, universal y directo para encender, apagar, alternar, cambiar entrada de video (HDMI, TV), color o brillo en CUALQUIER dispositivo de la casa (televisores/Samsung/Roku/Apple TV/pantallas/tele, luces, interruptores, enchufes, etc.). Resuelve nombres coloquiales en español ('la tele', 'tele samsung', 'televisión', 'el hdmi', 'luz de la sala', 'foco') sin requerir IDs exactos. ¡USA SIEMPRE ESTA HERRAMIENTA PREFERENTEMENTE para órdenes directas de encender, apagar o cambiar entrada de cualquier aparato!")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("entity_id", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre coloquial o ID del dispositivo (ej. 'tele samsung', 'la tele', 'televisión', 'luz de la sala', 'foco')")
                                })
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'turn_on' (encender), 'turn_off' (apagar), 'toggle' (alternar), o 'select_source' (cambiar entrada)")
                                })
                                put("source", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Entrada o fuente de video para televisores (ej. 'HDMI', 'TV', 'HDMI 1') (opcional)")
                                })
                                put("color", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Color deseado en español (ej. 'azul', 'rojo', 'verde', 'amarillo', 'morado', 'cálido', 'frío') (opcional)")
                                })
                                put("brightness", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Brillo de 1 a 100 por ciento (opcional)")
                                })
                                put("kelvin", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Temperatura blanco 2700 a 6500 K (opcional)")
                                })
                            })
                            put("required", JSONArray().apply { put("entity_id"); put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "manage_habits")
                        put("description", "Gestiona los hábitos y rutinas diarias del usuario en la pantalla de Hábitos de Treko. Permite agregar un nuevo hábito ('add', ej. 'hacer la cama todas las mañanas'), marcar como hecho/pendiente ('toggle'), eliminar ('delete'), listar hábitos ('list'), sumar un vaso de agua tomado ('add_water') o fijar vasos ('set_water'). ¡ÚSALO SIEMPRE cuando el usuario mencione hábitos, rutinas diarias o que tomó agua!")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'add' para registrar nuevo hábito, 'list' para consultar hábitos actuales, 'toggle' para marcar/desmarcar hecho, 'delete' para eliminar, 'add_water' para sumar un vaso de agua tomado ahora, o 'set_water' para fijar vasos de agua")
                                })
                                put("title", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre o descripción del hábito (ej. 'Hacer la cama todas las mañanas', 'Leer 20 min') (requerido para 'add')")
                                })
                                put("id", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "ID numérico del hábito a modificar o eliminar (opcional)")
                                })
                                put("water_count", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Cantidad de vasos de agua tomados hoy (requerido para 'set_water')")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "manage_tasks")
                        put("description", "Gestiona la lista de tareas del día del usuario en la pantalla de Tareas de Treko. Permite crear tareas ('add'), marcar como completadas ('toggle'), eliminar ('delete') o listar ('list'). ¡ÚSALO SIEMPRE que el usuario pida agendar, apuntar o gestionar tareas o pendientes!")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'add' para agregar tarea, 'list' para consultar tareas, 'toggle' para marcar/desmarcar hecha, 'delete' para eliminar")
                                })
                                put("title", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Descripción de la tarea (ej. 'Comprar fruta', 'Revisar servidor') (requerido para 'add')")
                                })
                                put("id", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "ID numérico de la tarea a modificar o eliminar (opcional)")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "manage_timer")
                        put("description", "Gestiona múltiples temporizadores y recordatorios rápidos tipo Alexa en Treko (con aviso sonoro y notificación al terminar), además del temporizador Pomodoro principal. Permite crear varios timers simultáneos con etiqueta ('start'/'add'), listar activos ('status'/'list'), cancelar uno por nombre/id o todos ('cancel'), pausar ('pause') o reiniciar ('reset').")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'start' o 'add' para iniciar un nuevo temporizador/recordatorio, 'list' o 'status' para consultar todos los activos, 'cancel' o 'delete' para cancelar por etiqueta/id (o todos si label='all'), 'pause', 'reset'")
                                })
                                put("minutes", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Minutos a temporizar (ej. 1, 8, 15, 25)")
                                })
                                put("seconds", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Segundos adicionales o totales a temporizar (opcional)")
                                })
                                put("label", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Etiqueta o motivo del temporizador/recordatorio (ej. 'Bañarme', 'Pasta', 'Horno')")
                                })
                                put("timer_id", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "ID numérico del temporizador a cancelar (opcional)")
                                })
                                put("mode", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Solo para Pomodoro principal: 'pomodoro' (25m), 'short_break' (5m), o 'long_break' (15m)")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "control_flashlight")
                        put("description", "Enciende o apaga la linterna / flash del teléfono móvil.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'turn_on' (encender), 'turn_off' (apagar) o 'toggle' (alternar)")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "open_app")
                        put("description", "Abre cualquier aplicación instalada en el celular del usuario (ej. Spotify, YouTube, WhatsApp, Netflix, Home Assistant, Maps, Cámara, Ajustes, etc.).")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("app_name", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre de la aplicación a abrir (ej. 'spotify', 'youtube', 'whatsapp', 'netflix', 'maps', 'camara', 'ajustes')")
                                })
                            })
                            put("required", JSONArray().apply { put("app_name") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "set_device_volume")
                        put("description", "Controla el volumen multimedia y del asistente en el teléfono celular (subir, bajar, silenciar o fijar un porcentaje de volumen).")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'up' (subir), 'down' (bajar), 'mute' (silenciar), 'set' (fijar porcentaje), 'get' (consultar)")
                                })
                                put("level", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Porcentaje de volumen de 0 a 100 (para action='set')")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "set_alarm")
                        put("description", "Programa una alarma en el reloj del sistema de Android.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("hour", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Hora de la alarma en formato 24h (0 a 23)")
                                })
                                put("minute", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Minuto de la alarma (0 a 59)")
                                })
                                put("message", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Etiqueta o descripción de la alarma (ej. 'Despertador', 'Reunión') (opcional)")
                                })
                            })
                            put("required", JSONArray().apply { put("hour"); put("minute") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "get_weather")
                        put("description", "Consulta el estado del clima actual y pronóstico (temperatura, lluvia, viento, humedad) desde Home Assistant.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "media_player_playback")
                        put("description", "Control de reproducción multimedia para televisores, Alexas y reproductores (pausar, reproducir, play/pause, canción siguiente, anterior, detener, silenciar o cambiar volumen).")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'play', 'pause', 'play_pause', 'next', 'previous', 'stop', 'volume_up', 'volume_down', 'mute', 'unmute', 'volume_set'")
                                })
                                put("device", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre del dispositivo o tele (ej. 'tele', 'samsung', 'alexa', 'cocina', 'sala') (opcional)")
                                })
                                put("volume_level", JSONObject().apply {
                                    put("type", "number")
                                    put("description", "Nivel de volumen de 0.0 a 1.0 (opcional para volume_set)")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "trigger_scene")
                        put("description", "Activa una escena de ambiente en Home Assistant (ej. 'cine', 'modo noche', 'relajación').")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("scene", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre de la escena a activar (ej. 'cine', 'noche', 'descanso')")
                                })
                            })
                            put("required", JSONArray().apply { put("scene") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "view_camera")
                        put("description", "Muestra una cámara de seguridad o todas ('todas'/'all') en el muro multicámara dentro de la aplicación Treko en tiempo real (instantánea o live stream). Soporta nombres coloquiales como 'ring', 'timbre', 'puerta', 'jardín', 'cochera', 'patio', 'entrada', 'todas', 'all', etc. ¡ÚSALO SIEMPRE que el usuario pida ver, mostrar o poner una cámara!")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("camera", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre o alias de la cámara (ej. 'ring', 'timbre', 'puerta', 'jardín', 'cochera', 'entrada', o 'todas'/'all' para abrir el muro multicámara)")
                                })
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'view' (mostrar en pantalla), 'live' (transmisión en vivo), 'list' o 'grid' (abrir muro multicámara), 'dismiss' (cerrar)")
                                })
                            })
                            put("required", JSONArray().apply { put("camera") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "view_all_cameras")
                        put("description", "Abre el muro visual multicámara (Multi-Camera Security Wall) en Treko con todas las cámaras de seguridad de Home Assistant deduplicadas y muestra un resumen de sus estados.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("live", JSONObject().apply {
                                    put("type", "boolean")
                                    put("description", "Si es true, activa actualización continua en vivo para todas las cámaras del muro (opcional)")
                                })
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "analyze_camera")
                        put("description", "Analiza e interpreta visualmente con Inteligencia Artificial lo que está sucediendo en una cámara de seguridad en tiempo real (ej. '¿quién está en la puerta?', '¿hay alguien en el jardín?', '¿puedes ver la cámara del timbre?', '¿qué ves en el ring?', '¿hay paquetes o autos?'), y la despliega en pantalla. Para el progreso o estado de la impresora 3D usa get_3d_printer_status, no la cámara.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("camera", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre o alias de la cámara (ej. 'ring', 'timbre', 'puerta', 'jardín', 'cochera')")
                                })
                                put("question", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Pregunta o instrucción de lo que se desea verificar o identificar en la imagen")
                                })
                            })
                            put("required", JSONArray().apply { put("camera") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "play_music_or_radio")
                        put("description", "¡PRIORIDAD MÁXIMA! Usa SIEMPRE esta herramienta en lugar de search_music para poner música, canciones, artistas, géneros, playlists o estaciones de radio en Alexas/Echo, bocinas de Home Assistant o en el teléfono.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("query", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Canción, artista, género, playlist o estación de radio (ej. 'rock clásico', 'Los Ángeles Azules', 'Alfa 91.3', 'una estación de radio')")
                                })
                                put("device", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Dispositivo o habitación destino: 'cuarto', 'sala', 'cocina', 'todas' o 'telefono' (opcional)")
                                })
                                put("media_type", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Tipo de contenido: 'radio', 'music', 'artist', 'playlist', 'routine' o 'auto' (opcional)")
                                })
                                put("provider", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Proveedor preferido: 'tunein', 'spotify', 'amazon_music' o 'auto' (opcional)")
                                })
                            })
                            put("required", JSONArray().apply { put("query") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "home_status_summary")
                        put("description", "Genera un resumen ejecutivo de luces encendidas, Alexas/TVs reproduciendo, clima y temperatura, puertas/sensores activos y cámaras de seguridad del hogar.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "watch_cameras")
                        put("description", "Vigila en tiempo real lugares de la casa y avisa al usuario (voz y notificación con foto) cuando aparece alguien o algo. Úsalo cuando pidan 'avísame cuando veas/llegue alguien en el frente', 'vigila el jardín', 'ya no vigiles' o '¿qué estás vigilando?'. Los lugares son los nombres que use el usuario; si alguno no existe, la respuesta lista los disponibles. No inventes lugares.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("enum", JSONArray().apply { put("start"); put("stop"); put("list") })
                                    put("description", "start = empezar a vigilar, stop = dejar de vigilar (todo si no hay lugares), list = qué se está vigilando")
                                })
                                put("places", JSONObject().apply {
                                    put("type", "array")
                                    put("items", JSONObject().put("type", "string"))
                                    put("description", "Lugares tal como los dijo el usuario (ej. 'frente', 'jardín'); vacío o 'toda la casa' = todas las cámaras")
                                })
                                put("object", JSONObject().apply {
                                    put("type", "string")
                                    put("enum", JSONArray().apply { put("person"); put("car"); put("animal"); put("package"); put("any") })
                                    put("description", "Qué detectar. Por defecto 'person' (alguien).")
                                })
                                put("once", JSONObject().apply {
                                    put("type", "boolean")
                                    put("description", "true si pidió un solo aviso ('avísame cuando llegue alguien'); false para seguir vigilando")
                                })
                                put("duration_minutes", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Opcional. Por defecto y como máximo 720 (12 h).")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "get_3d_printer_status")
                        put("description", "Estado real de la impresora 3D desde OctoPrint en Home Assistant: si está imprimiendo, porcentaje de progreso, archivo, hora estimada de fin, temperaturas de boquilla y cama, y errores. Úsalo SIEMPRE primero cuando pregunten por la impresora 3D o cómo va la impresión; usa analyze_camera solo si además quieren ver cómo luce la pieza.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "manage_workflows")
                        put("description", "Lista, ejecuta, crea o elimina rutinas/workflows automatizados en Treko (integrados: 'buenos_dias', 'buenas_noches', 'modo_cine', 'vigilancia_total', 'concentracion', y rutinas personalizadas).")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'list' para listar rutinas, 'run' para ejecutar una rutina, 'create' para crear una personalizada, o 'delete' para eliminar una personalizada")
                                })
                                put("name", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre o ID de la rutina (ej. 'buenos días', 'modo cine', 'vigilancia total', 'concentración')")
                                })
                                put("description", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Descripción opcional de la rutina personalizada (para action='create')")
                                })
                                put("steps", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Arreglo JSON de pasos o descripción de pasos para action='create' (tipos: 'ha_control', 'play_media', 'broadcast', 'scene', 'camera_wall', 'cinema_mode', 'timer', 'weather', 'home_summary', 'tasks_summary', 'habits_summary')")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "manage_proactive_assistant")
                        put("description", "Lee el aviso proactivo actual de Treko (estado de casa, alertas reales de cámaras, hábitos, hidratación y tareas), ejecuta chequeos bajo demanda o pospone recordatorios (snooze). Úsala SIEMPRE cuando el usuario diga 'lee tu aviso pendiente', '¿qué me ibas a decir?', 'posponlo', 'recuérdame más tarde' o 'revisa la casa'.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Acción a realizar: 'read_pending_alert' / 'deliver_pending', 'check_now', 'snooze', 'status' o 'set_speak_out_loud'")
                                })
                                put("speak_out_loud", JSONObject().apply {
                                    put("type", "boolean")
                                    put("description", "Opcional para action='set_speak_out_loud': true para iniciar conversación por voz automáticamente, false para enviar solo notificación")
                                })
                                put("topic", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Opcional para action='snooze': 'water', 'habits', 'tasks', 'home', 'cameras' o 'all'")
                                })
                                put("duration_minutes", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Opcional para action='snooze': minutos a posponer el recordatorio (por defecto 120)")
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                }

                val toolsResp = JSONObject().apply {
                    put("session_id", sessionId ?: "")
                    put("type", "mcp")
                    put("payload", JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", idValue)
                        put("result", JSONObject().apply {
                            put("tools", filterToolsForSkills(toolsArray))
                        })
                    })
                }
                sendJson(toolsResp.toString())
            } else if (method == "tools/call" && idValue != null) {
                val params = payload.optJSONObject("params")
                val toolName = params?.optString("name") ?: ""
                val arguments = params?.optJSONObject("arguments") ?: JSONObject()
                Log.i(TAG, "Ejecutando herramienta MCP: $toolName con argumentos: $arguments")

                scope.launch {
                    val callResp = try {
                        val resultText = onMcpToolCall?.invoke(toolName, arguments)
                            ?: "Herramienta $toolName ejecutada correctamente."
                        JSONObject().apply {
                            put("session_id", sessionId ?: "")
                            put("type", "mcp")
                            put("payload", JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("id", idValue)
                                put("result", JSONObject().apply {
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put("text", resultText)
                                        })
                                    })
                                })
                            })
                        }
                    } catch (e: Exception) {
                        TrekoLogBuffer.e(TAG, "Error ejecutando herramienta MCP '$toolName'", e)
                        JSONObject().apply {
                            put("session_id", sessionId ?: "")
                            put("type", "mcp")
                            put("payload", JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("id", idValue)
                                put("error", JSONObject().apply {
                                    put("code", -32000)
                                    put("message", e.message ?: "Error ejecutando herramienta $toolName")
                                })
                            })
                        }
                    }
                    sendJson(callResp.toString())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error manejando mensaje MCP: ${e.message}")
        }
    }

    /**
     * Inicia la captura de audio en vivo sin auto-enviar la palabra de activación como texto prompt.
     */
    fun startListening(mode: String = "auto") {
        val sId = sessionId
        if (sId == null || !isConnected) {
            Log.i(TAG, "Sesión no lista al iniciar escucha. Marcando inicio diferido y conectando...")
            pendingListenMode = mode
            pendingListenStart = true
            connect()
            return
        }
        pendingListenStart = false
        val start = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "start")
            put("mode", mode)
        }
        sendJson(start.toString())
        Log.i(TAG, "Iniciada sesión de escucha para voz del usuario en Xiaozhi (Session: $sId, mode=$mode).")
    }

    fun stopListening() {
        val sId = sessionId
        if (sId == null || !isConnected) {
            Log.w(TAG, "Ignorando stopListening: sesión aún no lista.")
            pendingListenStart = false
            return
        }
        val stop = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "stop")
        }
        sendJson(stop.toString())
        Log.i(TAG, "Detenida sesión de escucha en Xiaozhi (esperando respuesta).")
    }

    fun abortSpeaking(reason: String = "wake_word_detected") {
        val sId = sessionId
        if (!isConnected || sId.isNullOrBlank()) return
        val abort = JSONObject().apply {
            put("session_id", sId)
            put("type", "abort")
            put("reason", reason)
        }
        sendJson(abort.toString())
        Log.i(TAG, "Interrupción de habla enviada al servidor Xiaozhi (reason=$reason).")
    }

    /**
     * Inicia una conversación proactiva enviando un disparador breve en listen(state="detect")
     * para que Xiaozhi consulte manage_proactive_assistant sin exceder el límite de longitud del servidor.
     */
    fun sendProactiveTrigger(triggerText: String = "Treko lee tu aviso pendiente"): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected) return false
        val sId = sessionId
        if (sId.isNullOrBlank()) return false
        val raw = triggerText.trim()
        val compact = if (raw.length <= 44 && raw.isNotBlank()) raw else "Treko lee tu aviso pendiente"

        val msg = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "detect")
            put("text", compact)
        }
        val sent = ws.send(msg.toString())
        TrekoLogBuffer.i(TAG, "Enviado disparador proactivo a Xiaozhi (sent=$sent, text='$compact').")
        Log.i(TAG, "Disparador proactivo enviado a Xiaozhi (Session: $sId, sent=$sent, text='$compact').")
        return sent
    }

    fun sendProactivePrompt(prompt: String): Boolean = sendProactiveTrigger(prompt)

    fun sendAudio(opusData: ByteArray) {
        if (!isConnected || sessionId == null) return
        try {
            webSocket?.send(ByteString.of(*opusData))
        } catch (e: Exception) {
            Log.w(TAG, "Error enviando paquete Opus: ${e.message}")
        }
    }

    private fun filterToolsForSkills(tools: JSONArray): JSONArray {
        val skills = availableSkills
        val filtered = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            if (TrekoSkills.isToolAvailable(tool.optString("name"), skills)) filtered.put(tool)
        }
        return filtered
    }

    /** Asks the server to fetch tools/list again after the detected skills changed (MCP). */
    fun notifyToolsChanged() {
        if (!isConnected) return
        val notification = JSONObject().apply {
            put("session_id", sessionId ?: "")
            put("type", "mcp")
            put("payload", JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/tools/list_changed")
            })
        }
        sendJson(notification.toString())
    }

    fun sendJson(json: String) {
        webSocket?.send(json)
    }

    fun disconnect() {
        isExplicitDisconnect = true
        isConnecting = false
        reconnectJob?.cancel()
        pendingListenStart = false
        val wsToClose = webSocket
        webSocket = null
        try {
            wsToClose?.close(1000, "Desconexión normal")
        } catch (e: Exception) {
            // ignore
        }
        isConnected = false
        sessionId = null
        _connectionState.value = TrekoState.IDLE
        onConnectionStateChanged?.invoke(TrekoState.IDLE)
    }

    fun release() {
        disconnect()
        scope.cancel()
        client.connectionPool.evictAll()
    }
}
