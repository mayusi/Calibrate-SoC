package io.github.mayusi.calibratesoc.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rolling in-memory log of HUD events: profile applies via the chip
 * row, ± MHz steps, layout swaps, and any errors the service hit. Used
 * by the Dashboard's "Show HUD logs" sheet so the user can see what
 * the HUD actually did during a play session — useful when something
 * looked right in the moment but the result didn't feel like it took.
 *
 * In-memory only: a process restart starts the log over. The tune
 * history store keeps the persistent record of actual writes; this log
 * is a noisier, more granular "what did the HUD do" diary.
 */
@Singleton
class HudEventLog @Inject constructor() {

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun add(level: Level, message: String) {
        val entry = Entry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            message = message,
        )
        val next = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = next
    }

    fun clear() {
        _entries.value = emptyList()
    }

    data class Entry(
        val timestampMs: Long,
        val level: Level,
        val message: String,
    )

    enum class Level { INFO, ACTION, WARN, ERROR }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
