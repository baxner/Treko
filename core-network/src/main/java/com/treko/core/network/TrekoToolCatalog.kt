package com.treko.core.network

import com.treko.core.common.model.TrekoSkills

/**
 * The MCP tools Treko offers the model (tools/list) and the usage policy sent at initialize.
 *
 * Everything here is text the model reads on every turn, so it is kept short and generic:
 * no device, room, brand or station of any particular home. Optional skills (TrekoSkills)
 * decide which tools are listed and how some descriptions read.
 */
object TrekoToolCatalog {

    /**
     * Global policy for the model, sent as `instructions` in the initialize result.
     * Kept under ~600 chars; it does not name the assistant (the user picks the name).
     */
    const val INITIALIZE_INSTRUCTIONS: String =
        "Herramientas del teléfono y de la casa (Home Assistant) del usuario. " +
            "Responde en 1 o 2 frases cortas, naturales para decirse en voz alta: sin listas, emoji, IDs ni números entre paréntesis. " +
            "Habla en español salvo que el usuario use otro idioma. " +
            "Si la intención es clara, actúa sin preguntar; pregunta solo si falta un dato necesario. " +
            "Para quitar seguros, abrir portones o cocheras o desarmar alarmas, llama la herramienta; si la respuesta pide confirmación, pregunta una vez y repite la llamada con confirm=true. " +
            "Prefiere la herramienta específica; home_assistant_call_service es el último recurso."

    internal class Param(
        val name: String,
        val type: String,
        val description: String,
        val enum: List<String>? = null,
        val itemsType: String? = null
    )

    internal class Tool(
        val name: String,
        val description: String,
        val params: List<Param> = emptyList(),
        val required: List<String> = emptyList()
    )

    private fun str(name: String, description: String, enum: List<String>? = null) = Param(name, "string", description, enum)
    private fun int(name: String, description: String) = Param(name, "integer", description)
    private fun num(name: String, description: String) = Param(name, "number", description)
    private fun bool(name: String, description: String) = Param(name, "boolean", description)

    private const val CONFIRM_DESC = "true solo al repetir una llamada cuya respuesta pidió confirmación"

    /** The tools for a home with [skills], already filtered by TrekoSkills requirements. */
    internal fun tools(skills: Set<String>): List<Tool> {
        val vision = TrekoSkills.LLM_VISION in skills
        val printer = TrekoSkills.OCTOPRINT in skills
        val alexa = TrekoSkills.ALEXA_MEDIA in skills
        val watches = TrekoSkills.isToolAvailable("watch_cameras", skills)

        val all = listOf(
            Tool(
                "get_device_status",
                "Batería del teléfono y fecha y hora actuales."
            ),
            Tool(
                "manage_vault_memory",
                "Memoria permanente del usuario (gustos, fechas, notas).",
                listOf(
                    str("action", "set guarda, get consulta, list muestra todo, delete olvida", listOf("set", "get", "list", "delete")),
                    str("key", "Clave corta, ej. 'comida_favorita'"),
                    str("value", "Solo set")
                ),
                listOf("action")
            ),
            Tool(
                "home_assistant_call_service",
                "Último recurso: llama un servicio de Home Assistant si ninguna otra herramienta sirve " +
                    "(ej. climate.set_temperature, vacuum.start). Encender/apagar: home_assistant_control; música: " +
                    "play_music_or_radio; anuncios: home_assistant_broadcast. entity_id desconocido: home_assistant_list_entities. " +
                    "Cerraduras, portones y alarmas: si la respuesta pide confirmación, pregunta y repite con confirm=true.",
                listOf(
                    str("domain", "ej. 'climate'"),
                    str("service", "ej. 'set_temperature'"),
                    Param("service_data", "object", "Datos del servicio, ej. {\"entity_id\":\"climate.x\",\"temperature\":22}"),
                    bool("confirm", CONFIRM_DESC)
                ),
                listOf("domain", "service")
            ),
            Tool(
                "home_assistant_broadcast",
                "Anuncio en voz alta por las bocinas de la casa (avisar o mandar un recado).",
                listOf(
                    str("message", "Texto a anunciar"),
                    str("target", "Bocina o habitación como la nombró el usuario; vacío o 'todas' = todas")
                ),
                listOf("message")
            ),
            Tool(
                "home_assistant_list_entities",
                "Busca entidades de Home Assistant por dominio o nombre (nombre, entity_id y estado).",
                listOf(
                    str("domain", "ej. 'light', 'sensor' o 'all'"),
                    str("search", "Parte del nombre o del ID")
                )
            ),
            Tool(
                "home_assistant_get_entity_state",
                "Estado y atributos de una entidad (temperatura, si está encendida, batería…).",
                listOf(str("entity_id", "entity_id exacto, ej. 'sensor.temperatura'")),
                listOf("entity_id")
            ),
            Tool(
                "home_assistant_list_services",
                "Servicios disponibles de un dominio de Home Assistant (para home_assistant_call_service).",
                listOf(str("domain", "ej. 'climate', 'vacuum'"))
            ),
            Tool(
                "home_assistant_control",
                "Enciende, apaga o alterna un aparato por su nombre coloquial (luces, enchufes, teles, persianas…); también " +
                    "brillo, color, blanco y entrada de la tele. Portones y cocheras: si pide confirmación, pregunta y repite con confirm=true.",
                listOf(
                    str("entity_id", "Nombre del aparato como lo dijo el usuario, o su entity_id"),
                    str("action", "turn_on también abre y turn_off también cierra (persianas, portones)", listOf("turn_on", "turn_off", "toggle", "select_source")),
                    str("source", "Entrada de la tele, ej. 'HDMI 1'"),
                    str("color", "ej. 'azul', 'cálido'"),
                    int("brightness", "1-100 %"),
                    int("kelvin", "2700-6500"),
                    bool("confirm", CONFIRM_DESC)
                ),
                listOf("entity_id", "action")
            ),
            Tool(
                "manage_habits",
                "Hábitos diarios del usuario (pantalla Hábitos) y vasos de agua de hoy.",
                listOf(
                    str(
                        "action",
                        "add_water suma un vaso; set_water fija los vasos",
                        listOf("add", "list", "toggle", "delete", "add_water", "set_water")
                    ),
                    str("title", "Nombre del hábito"),
                    int("id", ""),
                    int("water_count", "Vasos tomados hoy (set_water)")
                ),
                listOf("action")
            ),
            Tool(
                "manage_tasks",
                "Tareas del usuario (pantalla Tareas): agregar, marcar hecha, borrar o listar.",
                listOf(
                    str("action", "toggle marca o desmarca hecha", listOf("add", "list", "toggle", "delete")),
                    str("title", "Texto de la tarea"),
                    int("id", "")
                ),
                listOf("action")
            ),
            Tool(
                "manage_timer",
                "Temporizadores con nombre (suenan al terminar) y el Pomodoro. start crea uno (sin duración, la respuesta " +
                    "la pide); cancel por etiqueta, id o todos (label='all').",
                listOf(
                    str("action", "", listOf("start", "status", "cancel", "pause", "reset")),
                    int("minutes", ""),
                    int("seconds", ""),
                    str("label", "Motivo, ej. 'pasta'"),
                    int("timer_id", "ID a cancelar"),
                    str("mode", "Solo Pomodoro: 25, 5 o 15 min", listOf("pomodoro", "short_break", "long_break"))
                ),
                listOf("action")
            ),
            Tool(
                "control_flashlight",
                "Linterna del teléfono.",
                listOf(str("action", "", listOf("turn_on", "turn_off", "toggle"))),
                listOf("action")
            ),
            Tool(
                "open_app",
                "Abre una app instalada en el teléfono.",
                listOf(str("app_name", "")),
                listOf("app_name")
            ),
            Tool(
                "set_device_volume",
                "Volumen del teléfono (no el de las bocinas de la casa).",
                listOf(
                    str("action", "set fija level; get consulta", listOf("up", "down", "mute", "set", "get")),
                    int("level", "0-100")
                ),
                listOf("action")
            ),
            Tool(
                "set_alarm",
                "Pone una alarma en el reloj del teléfono.",
                listOf(
                    int("hour", "0-23"),
                    int("minute", "0-59"),
                    str("message", "Etiqueta")
                ),
                listOf("hour", "minute")
            ),
            Tool(
                "get_home_weather",
                "Clima actual y pronóstico de la casa según Home Assistant."
            ),
            Tool(
                "media_player_playback",
                "Controla lo que suena en una bocina, tele o el teléfono (pausa, siguiente, volumen…).",
                listOf(
                    str(
                        "action",
                        "",
                        listOf("play", "pause", "play_pause", "next", "previous", "stop", "volume_up", "volume_down", "volume_set", "mute", "unmute")
                    ),
                    str("device", "Como lo nombró el usuario, o 'telefono'; vacío = lo que suena"),
                    num("volume_level", "0.0-1.0")
                ),
                listOf("action")
            ),
            Tool(
                "trigger_scene",
                "Activa una escena de Home Assistant por su nombre.",
                listOf(str("scene", "")),
                listOf("scene")
            ),
            Tool(
                "view_camera",
                "Muestra en pantalla una cámara, o todas en el muro de cámaras (camera='todas'). Solo la muestra" +
                    (if (vision) "; para saber qué se ve usa analyze_camera, que también la muestra (no llames las dos)." else "."),
                listOf(
                    str("camera", "Cámara como la nombró el usuario, o 'todas'"),
                    str("action", "view foto, live en vivo, dismiss cerrar", listOf("view", "live", "dismiss"))
                ),
                listOf("camera")
            ),
            // view_all_cameras is no longer listed (view_camera with 'todas' does the same);
            // the app still answers it for servers that remember the old name.
            Tool(
                "analyze_camera",
                "Mira una cámara con IA y dice qué hay (quién está en la puerta, si hay paquete…). Tarda 10-40 s; también la muestra." +
                    (if (printer) " Para la impresora 3D usa primero get_3d_printer_status." else ""),
                listOf(
                    str("camera", "Cámara como la nombró el usuario"),
                    str("question", "Qué quiere saber")
                ),
                listOf("camera")
            ),
            Tool(
                "play_music_or_radio",
                "Pone música (canción, artista, género, playlist) o radio en las bocinas de la casa o en el teléfono. Úsala en vez de search_music.",
                listOf(
                    str("query", "ej. 'rock clásico', 'música para concentrarme' o una estación"),
                    str("device", "Bocina como la nombró el usuario, o 'telefono'; vacío = predeterminada"),
                    str(
                        "media_type",
                        if (alexa) "routine = rutina de Alexa" else "",
                        listOf("auto", "music", "artist", "playlist", "radio") + (if (alexa) listOf("routine") else emptyList())
                    ),
                    str("provider", "", listOf("auto", "spotify", "tunein", "amazon_music"))
                ),
                listOf("query")
            ),
            Tool(
                "home_status_summary",
                "Resumen rápido de la casa: luces, lo que suena, clima, puertas o cerraduras abiertas y cámaras ('¿cómo está la casa?')."
            ),
            Tool(
                "watch_cameras",
                "Vigila lugares de la casa y avisa con voz y notificación con foto cuando aparece alguien o algo; los avisos " +
                    "quedan en la pantalla Avisos. Para 'avísame cuando llegue alguien', 'ya no vigiles', '¿qué vigilas?'. " +
                    "Si un lugar no existe, la respuesta dice cuáles hay." +
                    (if (vision) "" else " Sin LLM Vision en Home Assistant el aviso lleva la foto pero no la describe."),
                listOf(
                    str("action", "stop sin lugares = todo", listOf("start", "stop", "list")),
                    Param("places", "array", "Como los dijo el usuario; vacío = toda la casa", itemsType = "string"),
                    str("object", "Por defecto person", listOf("person", "car", "animal", "package", "any")),
                    bool("once", "true = un solo aviso"),
                    int("duration_minutes", "Máximo 720")
                ),
                listOf("action")
            ),
            Tool(
                "get_3d_printer_status",
                "Impresora 3D (OctoPrint): progreso, archivo, hora de fin, temperaturas y errores. " +
                    "Úsala primero para preguntas sobre la impresión" +
                    (if (vision) "; analyze_camera solo si además quieren ver la pieza." else ".")
            ),
            Tool(
                "manage_workflows",
                "Rutinas de Treko. Integradas: buenos_dias, buenas_noches, modo_cine, concentracion y vigilancia_total " +
                    "(solo abre el muro de cámaras y un resumen; no vigila ni avisa" +
                    (if (watches) ": para eso usa watch_cameras)." else ")."),
                listOf(
                    str("action", "", listOf("list", "run", "create", "delete")),
                    str("name", "Nombre o ID"),
                    str("description", "Para create"),
                    str(
                        "steps",
                        "Para create: arreglo JSON; tipos: ha_control, play_media, broadcast, scene, camera_wall, " +
                            "cinema_mode, timer, weather, home_summary, tasks_summary, habits_summary"
                    )
                ),
                listOf("action")
            ),
            Tool(
                "manage_proactive_assistant",
                "Avisos de Treko (quedan en la pantalla Avisos). read_pending_alert: 'lee tu aviso pendiente', '¿qué me ibas " +
                    "a decir?'. snooze: 'posponlo'. check_now: revisión a fondo" + (if (vision) " con cámaras" else "") +
                    " (tarda); para '¿cómo está la casa?' usa home_status_summary.",
                listOf(
                    str("action", "", listOf("read_pending_alert", "check_now", "snooze", "status", "set_speak_out_loud")),
                    bool("speak_out_loud", "set_speak_out_loud: true habla, false solo notifica"),
                    str("topic", "Para snooze", listOf("water", "habits", "tasks", "home", "cameras", "all")),
                    int("duration_minutes", "Para snooze; 120 por defecto")
                ),
                listOf("action")
            )
        )
        return all.filter { TrekoSkills.isToolAvailable(it.name, skills) }
    }

    /** tools/list `tools` array for a home with [skills], as compact JSON. */
    fun toolsJson(skills: Set<String>): String {
        val sb = StringBuilder()
        sb.append('[')
        tools(skills).forEachIndexed { i, tool ->
            if (i > 0) sb.append(',')
            appendTool(sb, tool)
        }
        sb.append(']')
        return sb.toString()
    }

    /** Names of the tools listed for [skills]. */
    fun toolNames(skills: Set<String>): List<String> = tools(skills).map { it.name }

    private fun appendTool(sb: StringBuilder, tool: Tool) {
        sb.append("{\"name\":").appendJsonString(tool.name)
        sb.append(",\"description\":").appendJsonString(tool.description)
        sb.append(",\"inputSchema\":{\"type\":\"object\",\"properties\":{")
        tool.params.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.appendJsonString(p.name).append(":{\"type\":").appendJsonString(p.type)
            p.itemsType?.let { sb.append(",\"items\":{\"type\":").appendJsonString(it).append('}') }
            p.enum?.let { values ->
                sb.append(",\"enum\":[")
                values.forEachIndexed { j, v ->
                    if (j > 0) sb.append(',')
                    sb.appendJsonString(v)
                }
                sb.append(']')
            }
            if (p.description.isNotEmpty()) sb.append(",\"description\":").appendJsonString(p.description)
            sb.append('}')
        }
        sb.append('}')
        if (tool.required.isNotEmpty()) {
            sb.append(",\"required\":[")
            tool.required.forEachIndexed { i, r ->
                if (i > 0) sb.append(',')
                sb.appendJsonString(r)
            }
            sb.append(']')
        }
        sb.append("}}")
    }

    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
        return this
    }
}
