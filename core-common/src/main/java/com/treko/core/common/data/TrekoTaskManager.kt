package com.treko.core.common.data

import android.content.Context
import com.treko.core.common.model.TaskItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class TrekoTaskManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("treko_tasks_store", Context.MODE_PRIVATE)

    companion object {
        private val _tasksFlow = MutableStateFlow<List<TaskItem>>(emptyList())
        val tasksFlow: StateFlow<List<TaskItem>> = _tasksFlow.asStateFlow()

        @Volatile
        private var isInitialized = false
    }

    init {
        synchronized(TrekoTaskManager::class.java) {
            if (!isInitialized) {
                load()
                isInitialized = true
            }
        }
    }

    private fun load() {
        val jsonStr = prefs.getString("tasks_list", null)
        val items = mutableListOf<TaskItem>()
        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    items.add(
                        TaskItem(
                            id = obj.getInt("id"),
                            title = obj.getString("title"),
                            isDone = obj.optBoolean("isDone", false)
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        if (items.isEmpty()) {
            items.addAll(
                listOf(
                    TaskItem(1, "Revisar sensores de temperatura en Home Assistant", false),
                    TaskItem(2, "Completar sesión Pomodoro de 25 min", true),
                    TaskItem(3, "Configurar palabra de activación 'Treko' en la oficina", false)
                )
            )
            saveList(items)
        }

        _tasksFlow.value = items
    }

    private fun saveList(items: List<TaskItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("isDone", item.isDone)
            }
            array.put(obj)
        }
        prefs.edit().putString("tasks_list", array.toString()).apply()
        _tasksFlow.value = items
    }

    fun addTask(title: String): TaskItem {
        val current = _tasksFlow.value.toMutableList()
        val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
        val newTask = TaskItem(nextId, title.trim(), false)
        current.add(newTask)
        saveList(current)
        return newTask
    }

    fun toggleTask(id: Int) {
        val current = _tasksFlow.value.map {
            if (it.id == id) it.copy(isDone = !it.isDone) else it
        }
        saveList(current)
    }

    fun deleteTask(id: Int) {
        val current = _tasksFlow.value.filter { it.id != id }
        saveList(current)
    }

    fun listTasksSummary(): String {
        val list = _tasksFlow.value
        if (list.isEmpty()) return "No hay tareas registradas para hoy."
        val sb = StringBuilder("Tareas registradas (${list.size}):\n")
        list.forEach {
            val status = if (it.isDone) "Hecha" else "Pendiente"
            sb.append("• ${it.title} [${status}]\n")
        }
        return sb.toString().trim()
    }
}
