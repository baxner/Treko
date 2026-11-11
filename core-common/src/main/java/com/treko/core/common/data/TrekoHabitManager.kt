package com.treko.core.common.data

import android.content.Context
import com.treko.core.common.model.HabitItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class TrekoHabitManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("treko_habits_store", Context.MODE_PRIVATE)

    companion object {
        private val _habitsFlow = MutableStateFlow<List<HabitItem>>(emptyList())
        val habitsFlow: StateFlow<List<HabitItem>> = _habitsFlow.asStateFlow()

        private val _waterGlassesFlow = MutableStateFlow(5)
        val waterGlassesFlow: StateFlow<Int> = _waterGlassesFlow.asStateFlow()

        @Volatile
        private var isInitialized = false
    }

    init {
        synchronized(TrekoHabitManager::class.java) {
            if (!isInitialized) {
                load()
                isInitialized = true
            }
        }
    }

    private fun load() {
        val jsonStr = prefs.getString("habits_list", null)
        val items = mutableListOf<HabitItem>()
        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    items.add(
                        HabitItem(
                            id = obj.getInt("id"),
                            title = obj.getString("title"),
                            icon = obj.optString("icon", "✨"),
                            isDone = obj.optBoolean("isDone", false)
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        if (items.isEmpty()) {
            items.addAll(
                listOf(
                    HabitItem(1, "10 min de meditación y respiración", "🧘", true),
                    HabitItem(2, "Lectura técnica o enfoque de estudio (25 min)", "📖", true),
                    HabitItem(3, "Sincronizar iluminación inteligente de descanso", "💡", false),
                    HabitItem(4, "Caminar 30 min o actividad física", "🚶", false),
                    HabitItem(5, "Hacer la cama todas las mañanas", "🛏️", false)
                )
            )
            saveList(items)
        }

        _habitsFlow.value = items
        _waterGlassesFlow.value = prefs.getInt("water_glasses", 5)
    }

    private fun saveList(items: List<HabitItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("icon", item.icon)
                put("isDone", item.isDone)
            }
            array.put(obj)
        }
        prefs.edit().putString("habits_list", array.toString()).apply()
        _habitsFlow.value = items
    }

    fun addHabit(title: String, icon: String = "✨"): HabitItem {
        val current = _habitsFlow.value.toMutableList()
        val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
        val chosenIcon = if (icon.isNotBlank() && icon != "✨") icon else detectIcon(title)
        val newHabit = HabitItem(nextId, title.trim(), chosenIcon, false)
        current.add(newHabit)
        saveList(current)
        return newHabit
    }

    fun toggleHabit(id: Int) {
        val current = _habitsFlow.value.map {
            if (it.id == id) it.copy(isDone = !it.isDone) else it
        }
        saveList(current)
    }

    fun deleteHabit(id: Int) {
        val current = _habitsFlow.value.filter { it.id != id }
        saveList(current)
    }

    fun setWaterGlasses(count: Int) {
        val c = count.coerceIn(0, 20)
        prefs.edit().putInt("water_glasses", c).apply()
        _waterGlassesFlow.value = c
    }

    fun listHabitsSummary(): String {
        val list = _habitsFlow.value
        if (list.isEmpty()) return "No hay hábitos registrados actualmente."
        val water = _waterGlassesFlow.value
        val sb = StringBuilder("Hábitos registrados (${list.size}):\n")
        list.forEach {
            val status = if (it.isDone) "Completado" else "Pendiente"
            sb.append("• ${it.icon} ${it.title} [${status}]\n")
        }
        sb.append("• 💧 Hidratación: $water de 8 vasos")
        return sb.toString().trim()
    }

    private fun detectIcon(title: String): String {
        val t = title.lowercase()
        return when {
            t.contains("cama") -> "🛏️"
            t.contains("agua") || t.contains("hidrata") -> "💧"
            t.contains("leer") || t.contains("libro") || t.contains("estudi") -> "📖"
            t.contains("medit") || t.contains("respir") || t.contains("yoga") -> "🧘"
            t.contains("caminar") || t.contains("corr") || t.contains("gym") || t.contains("ejercic") -> "🏃"
            t.contains("luz") || t.contains("foco") -> "💡"
            t.contains("dient") || t.contains("bañ") || t.contains("aseo") -> "🪥"
            t.contains("comid") || t.contains("dieta") || t.contains("comer") -> "🥗"
            else -> "✨"
        }
    }
}
