package com.example

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val LOG_FILE_NAME = "app_log.txt"
    private const val MAX_LOG_LINES = 200
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    private val writes = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var writtenLines = 0
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var logFile: File? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                logFile = File(context.filesDir, LOG_FILE_NAME)
                loadFromDisk()
            }
        }
    }

    private fun loadFromDisk() {
        val file = logFile ?: return
        if (file.exists()) {
            try {
                val lines = file.readLines()
                val trimmed = if (lines.size > MAX_LOG_LINES) {
                    lines.takeLast(MAX_LOG_LINES)
                } else {
                    lines
                }
                // Newest first for UI display
                _logs.value = trimmed.reversed()
            } catch (e: Exception) {
                // Ignore initial read error
            }
        }
    }

    fun log(tag: String, message: String) {
        val timestamp = timeFormat.get()!!.format(Date())
        val entry = "[$timestamp] [$tag] $message"
        android.util.Log.i("WatchMenu", entry)
        appendEntry(entry)
    }

    fun logError(tag: String, action: String, error: Throwable) {
        val timestamp = timeFormat.get()!!.format(Date())
        val errorDetail = "${error.javaClass.simpleName}: ${error.message ?: "No error message"}"
        val entry = "[$timestamp] [$tag] FAIL - $action: $errorDetail"
        android.util.Log.e("WatchMenu", entry, error)
        appendEntry(entry)
    }

    private fun appendEntry(entry: String) {
        synchronized(lock) {
            val current = _logs.value.toMutableList()
            current.add(0, entry) // Newest at top
            if (current.size > MAX_LOG_LINES) {
                current.removeAt(current.size - 1)
            }
            _logs.value = current

            writes.trySend(entry)
        }
    }

    init {
        // One writer keeps the lines in order, batches them, and keeps the file small.
        scope.launch {
            for (first in writes) {
                val batch = StringBuilder(first).append('\n')
                var n = 1
                while (n < 50) { val next = writes.tryReceive().getOrNull() ?: break; batch.append(next).append('\n'); n++ }
                try {
                    val f = logFile ?: continue
                    f.appendText(batch.toString())
                    writtenLines += n
                    if (writtenLines > MAX_LOG_LINES * 3) {
                        f.writeText(f.readLines().takeLast(MAX_LOG_LINES).joinToString("\n", postfix = "\n"))
                        writtenLines = MAX_LOG_LINES
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WatchMenu", "Failed to write log to file", e)
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            _logs.value = emptyList()
            scope.launch {
                try {
                    logFile?.writeText("")
                } catch (e: Exception) {
                    android.util.Log.e("WatchMenu", "Failed to clear log file", e)
                }
            }
        }
    }

    fun getAllText(): String {
        return synchronized(lock) {
            // Return newest first or chronological? Newest first matching display
            _logs.value.joinToString("\n")
        }
    }
}
