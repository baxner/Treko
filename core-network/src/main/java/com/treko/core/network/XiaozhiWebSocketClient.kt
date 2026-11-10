package com.treko.core.network

import android.util.Log
import com.treko.core.common.model.TrekoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS) // 0 desactiva la desconexión agresiva por pongs perdidos del gateway
        .retryOnConnectionFailure(true)
        .build()

    private val _connectionState = MutableStateFlow(TrekoState.IDLE)
    val connectionState: StateFlow<TrekoState> = _connectionState.asStateFlow()

    @Volatile
    var sessionId: String? = null
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var isConnecting: Boolean = false

    @Volatile
    private var isExplicitDisconnect: Boolean = false

    @Volatile
    private var pendingListenStart: Boolean = false

    private var reconnectJob: Job? = null

    var onSessionStarted: ((String) -> Unit)? = null
    var onSttReceived: ((String) -> Unit)? = null
    var onLlmReceived: ((text: String, emotion: String?) -> Unit)? = null
    var onTtsStateChanged: ((state: String, text: String?) -> Unit)? = null
    var onAudioPacketReceived: ((ByteArray) -> Unit)? = null
    var onConnectionStateChanged: ((TrekoState) -> Unit)? = null
    var onMcpToolCall: (suspend (name: String, args: JSONObject) -> String)? = null

    private fun scheduleReconnect() {
        if (isExplicitDisconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2000)
            if (!isConnected && !isConnecting && !isExplicitDisconnect) {
                Log.i(TAG, "Reconectando automáticamente a Xiaozhi...")
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
        isConnecting = true

        val requestBuilder = Request.Builder()
            .url(serverUrl)
            .addHeader("Device-Id", macAddress)
            .addHeader("Client-Id", clientId)
            .addHeader("Protocol-Version", "1")

        if (token.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        val request = requestBuilder.build()
        Log.i(TAG, "Conectando a Xiaozhi WebSocket: $serverUrl (MAC: $macAddress)...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Conexión WebSocket establecida con Xiaozhi.")
                isConnecting = false
                isConnected = true
                sendHello()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleJsonMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                onAudioPacketReceived?.invoke(bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket cerrándose: $code / $reason")
                isConnecting = false
                isConnected = false
                sessionId = null
                _connectionState.value = TrekoState.IDLE
                onConnectionStateChanged?.invoke(TrekoState.IDLE)
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Fallo en conexión WebSocket Xiaozhi: ${t.message}")
                isConnecting = false
                isConnected = false
                sessionId = null
                _connectionState.value = TrekoState.ERROR
                onConnectionStateChanged?.invoke(TrekoState.ERROR)
                scheduleReconnect()
            }
        })
    }

    private fun sendHello() {
        try {
            val hello = JSONObject().apply {
                put("type", "hello")
                put("version", 1)
                put("features", JSONObject().apply { put("mcp", true) })
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
                        startListening()
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analizando JSON entrante: ${e.message}")
        }
    }

    private fun handleMcpMessage(json: JSONObject) {
        try {
            val payload = json.optJSONObject("payload") ?: return
            val method = payload.optString("method")
            val id = payload.optLong("id", -1)

            if (method == "initialize" && id != -1L) {
                val response = JSONObject().apply {
                    put("session_id", sessionId ?: "")
                    put("type", "mcp")
                    put("payload", JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", id)
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
            } else if (method == "tools/list" && id != -1L) {
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
                        put("description", "Control rápido, universal y directo para encender, apagar, alternar, cambiar color o brillo en CUALQUIER dispositivo de la casa (televisores/Samsung/Roku/Apple TV/pantallas/tele, luces, interruptores, enchufes, etc.). Resuelve nombres coloquiales en español ('la tele', 'tele samsung', 'televisión', 'luz de la sala', 'foco') sin requerir IDs exactos. ¡USA SIEMPRE ESTA HERRAMIENTA PREFERENTEMENTE para órdenes directas de encender o apagar cualquier aparato!")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("entity_id", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Nombre coloquial o ID del dispositivo (ej. 'tele samsung', 'la tele', 'televisión', 'luz de la sala', 'foco')")
                                })
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "'turn_on' (encender), 'turn_off' (apagar), o 'toggle' (alternar)")
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
                }

                val toolsResp = JSONObject().apply {
                    put("session_id", sessionId ?: "")
                    put("type", "mcp")
                    put("payload", JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", JSONObject().apply {
                            put("tools", toolsArray)
                        })
                    })
                }
                sendJson(toolsResp.toString())
            } else if (method == "tools/call" && id != -1L) {
                val params = payload.optJSONObject("params")
                val toolName = params?.optString("name") ?: ""
                val arguments = params?.optJSONObject("arguments") ?: JSONObject()
                Log.i(TAG, "Ejecutando herramienta MCP: $toolName con argumentos: $arguments")

                scope.launch {
                    val resultText = onMcpToolCall?.invoke(toolName, arguments)
                        ?: "Herramienta $toolName ejecutada correctamente."

                    val callResp = JSONObject().apply {
                        put("session_id", sessionId ?: "")
                        put("type", "mcp")
                        put("payload", JSONObject().apply {
                            put("jsonrpc", "2.0")
                            put("id", id)
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
    fun startListening() {
        val sId = sessionId
        if (sId == null || !isConnected) {
            Log.i(TAG, "Sesión no lista al iniciar escucha. Marcando inicio diferido y conectando...")
            pendingListenStart = true
            connect()
            return
        }
        pendingListenStart = false
        val start = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "start")
            put("mode", "auto")
        }
        sendJson(start.toString())
        Log.i(TAG, "Iniciada sesión de escucha para voz del usuario en Xiaozhi (Session: $sId).")
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

    fun abortSpeaking() {
        val sId = sessionId ?: ""
        val abort = JSONObject().apply {
            put("session_id", sId)
            put("type", "abort")
            put("reason", "wake_word_detected")
        }
        sendJson(abort.toString())
        Log.i(TAG, "Interrupción de habla enviada al servidor Xiaozhi.")
    }

    fun sendAudio(opusData: ByteArray) {
        if (!isConnected || sessionId == null) return
        try {
            webSocket?.send(ByteString.of(*opusData))
        } catch (e: Exception) {
            Log.w(TAG, "Error enviando paquete Opus: ${e.message}")
        }
    }

    fun sendJson(json: String) {
        webSocket?.send(json)
    }

    fun disconnect() {
        isExplicitDisconnect = true
        reconnectJob?.cancel()
        pendingListenStart = false
        try {
            webSocket?.close(1000, "Desconexión normal")
        } catch (e: Exception) {
            // ignore
        }
        webSocket = null
        isConnected = false
        sessionId = null
        _connectionState.value = TrekoState.IDLE
        onConnectionStateChanged?.invoke(TrekoState.IDLE)
    }
}
