package com.treko.core.common.data

import android.content.Context
import com.treko.core.common.model.HabitItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrekoHabitManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("treko_habits_store", Context.MODE_PRIVATE)

    companion object {
        private val lock = Any()
        private val _habitsFlow = MutableStateFlow<List<HabitItem>>(emptyList())
        val habitsFlow: StateFlow<List<HabitItem>> = _habitsFlow.asStateFlow()

        private val _waterGlassesFlow = MutableStateFlow(5)
        val waterGlassesFlow: StateFlow<Int> = _waterGlassesFlow.asStateFlow()

        @Volatile
        private var isInitialized = false
    }

    init {
        synchronized(lock) {
            if (!isInitialized) {
                load()
                isInitialized = true
            }
        }
    }

    private fun todayDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun load() {
        val jsonStr = prefs.getString("habits_list", null)
        val items = mutableListOf<HabitItem>()
        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val datesArray = obj.optJSONArray("completedDates")
                    val dates = mutableListOf<String>()
                    if (datesArray != null) {
                        for (j in 0 until datesArray.length()) {
                            datesArray.optString(j).takeIf { it.isNotBlank() }?.let { dates.add(it) }
                        }
                    }
                    items.add(
                        HabitItem(
                            id = obj.getInt("id"),
                            title = obj.getString("title"),
                            icon = obj.optString("icon", "✨"),
                            isDone = obj.optBoolean("isDone", false),
                            lastCompletedDate = obj.optString("lastCompletedDate", "").ifBlank { null },
                            completedDates = dates
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        if (items.isEmpty()) {
            val isSpanish = Locale.getDefault().language == "es"
            val today = todayDateString()
            items.addAll(
                if (isSpanish) {
                    listOf(
                        HabitItem(1, "10 min de meditación y respiración", "🧘", true, today, listOf(today)),
                        HabitItem(2, "Lectura técnica o enfoque de estudio (25 min)", "📖", true, today, listOf(today)),
                        HabitItem(3, "Sincronizar iluminación inteligente de descanso", "💡", false),
                        HabitItem(4, "Caminar 30 min o actividad física", "🚶", false),
                        HabitItem(5, "Hacer la cama todas las mañanas", "🛏️", false)
                    )
                } else {
                    listOf(
                        HabitItem(1, "10 min meditation & breathing", "🧘", true, today, listOf(today)),
                        HabitItem(2, "Focused reading or study (25 min)", "📖", true, today, listOf(today)),
                        HabitItem(3, "Sync evening smart lighting", "💡", false),
                        HabitItem(4, "Walk 30 min or exercise", "🚶", false),
                        HabitItem(5, "Make the bed every morning", "🛏️", false)
                    )
                }
            )
            prefs.edit().putString("last_reset_date", today).apply()
            saveListLocked(items)
        }

        _habitsFlow.value = items
        _waterGlassesFlow.value = prefs.getInt("water_glasses", 5)
        ensureCurrentDayLocked()
    }

    /**
     * Resets daily completion state when the calendar date changes while preserving
     * per-habit completion history ([HabitItem.completedDates] and [HabitItem.lastCompletedDate]).
     */
    fun ensureCurrentDay() {
        synchronized(lock) {
            ensureCurrentDayLocked()
        }
    }

    private fun ensureCurrentDayLocked() {
        val today = todayDateString()
        val lastReset = prefs.getString("last_reset_date", null)
        if (lastReset == null) {
            prefs.edit().putString("last_reset_date", today).apply()
            return
        }
        if (lastReset != today) {
            val resetItems = _habitsFlow.value.map { it.copy(isDone = false) }
            prefs.edit()
                .putString("last_reset_date", today)
                .putInt("water_glasses", 0)
                .putLong("last_water_intake_ms", 0L)
                .apply()
            _waterGlassesFlow.value = 0
            saveListLocked(resetItems)
        }
    }

    private fun saveListLocked(items: List<HabitItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("icon", item.icon)
                put("isDone", item.isDone)
                if (item.lastCompletedDate != null) {
                    put("lastCompletedDate", item.lastCompletedDate)
                }
                put("completedDates", JSONArray(item.completedDates.takeLast(60)))
            }
            array.put(obj)
        }
        prefs.edit().putString("habits_list", array.toString()).apply()
        _habitsFlow.value = items
    }

    fun addHabit(title: String, icon: String = "✨"): HabitItem {
        synchronized(lock) {
            ensureCurrentDayLocked()
            val current = _habitsFlow.value.toMutableList()
            val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
            val chosenIcon = if (icon.isNotBlank() && icon != "✨") icon else detectIcon(title)
            val newHabit = HabitItem(nextId, title.trim(), chosenIcon, false)
            current.add(newHabit)
            saveListLocked(current)
            return newHabit
        }
    }

    fun toggleHabit(id: Int) {
        synchronized(lock) {
            ensureCurrentDayLocked()
            val today = todayDateString()
            val current = _habitsFlow.value.map { item ->
                if (item.id == id) {
                    val nowDone = !item.isDone
                    val updatedDates = if (nowDone) {
                        (item.completedDates + today).distinct().takeLast(60)
                    } else {
                        item.completedDates.filterNot { it == today }
                    }
                    item.copy(
                        isDone = nowDone,
                        lastCompletedDate = updatedDates.lastOrNull(),
                        completedDates = updatedDates
                    )
                } else {
                    item
                }
            }
            saveListLocked(current)
        }
    }

    fun deleteHabit(id: Int) {
        synchronized(lock) {
            ensureCurrentDayLocked()
            val current = _habitsFlow.value.filter { it.id != id }
            saveListLocked(current)
        }
    }

    fun setWaterGlasses(count: Int, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            ensureCurrentDayLocked()
            val prev = _waterGlassesFlow.value
            val c = count.coerceIn(0, 20)
            val editor = prefs.edit().putInt("water_glasses", c)
            if (c > prev) {
                editor.putLong("last_water_intake_ms", nowMs)
            } else if (c == 0) {
                editor.putLong("last_water_intake_ms", 0L)
            }
            editor.apply()
            _waterGlassesFlow.value = c
        }
    }

    fun getLastWaterIntakeMs(): Long {
        synchronized(lock) {
            ensureCurrentDayLocked()
            return prefs.getLong("last_water_intake_ms", 0L)
        }
    }

    fun listHabitsSummary(nowMs: Long = System.currentTimeMillis()): String {
        synchronized(lock) {
            ensureCurrentDayLocked()
        }
        val list = _habitsFlow.value
        if (list.isEmpty()) return "No hay hábitos registrados actualmente."
        val water = _waterGlassesFlow.value
        val lastWaterMs = prefs.getLong("last_water_intake_ms", 0L)
        val sinceStr = if (lastWaterMs in 1..nowMs) {
            val mins = ((nowMs - lastWaterMs) / 60_000L).coerceAtLeast(0L)
            " (último vaso hace ${mins} min)"
        } else {
            ""
        }
        val sb = StringBuilder("Hábitos registrados (${list.size}):\n")
        list.forEach {
            val status = if (it.isDone) "Completado" else "Pendiente"
            val last = it.lastCompletedDate?.let { d -> ", último: $d (${it.completedDates.size} días)" } ?: ""
            sb.append("• ${it.icon} ${it.title} [$status$last]\n")
        }
        sb.append("• 💧 Hidratación: $water de 8 vasos$sinceStr")
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
