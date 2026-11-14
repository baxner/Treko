package com.treko.core.common.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight thread-safe ring-buffer diagnostic logger for Treko modules.
 * Records recent events in memory so Diagnostics UI can display live audio/KWS/network health.
 */
object TrekoLogBuffer {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class LogEntry(
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private const val MAX_ENTRIES = 200
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) {
        record(Level.DEBUG, tag, message)
        safeAndroidLog { Log.d(tag, message) }
    }

    fun i(tag: String, message: String) {
        record(Level.INFO, tag, message)
        safeAndroidLog { Log.i(tag, message) }
    }

    fun w(tag: String, message: String) {
        record(Level.WARN, tag, message)
        safeAndroidLog { Log.w(tag, message) }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message: ${throwable.message}" else message
        record(Level.ERROR, tag, fullMsg)
        safeAndroidLog {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    private fun record(level: Level, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
    }

    private inline fun safeAndroidLog(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // Ignored in pure JVM unit tests where android.util.Log is stubbed
        } catch (_: UnsatisfiedLinkError) {
            // Ignored
        }
    }
}
