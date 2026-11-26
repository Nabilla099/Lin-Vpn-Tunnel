package com.linvpn.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log aplikasi. Pesan dikumpulkan dulu lalu digabung per ~120 ms supaya UI
 * tidak di-recompose ratusan kali per detik saat log ramai.
 */
object LogBus {
    data class Line(val time: String, val text: String)

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    @Volatile
    var maxLines = 500

    private val queue = Channel<Line>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    init {
        scope.launch {
            while (true) {
                val first = queue.receive()
                delay(120)
                val batch = ArrayList<Line>()
                batch.add(first)
                while (true) {
                    val n = queue.tryReceive().getOrNull() ?: break
                    batch.add(n)
                }
                _lines.update { old ->
                    val merged = old + batch
                    val cap = maxLines.coerceAtLeast(50)
                    if (merged.size > cap) merged.subList(merged.size - cap, merged.size).toList() else merged
                }
            }
        }
    }

    fun add(msg: String) {
        val t = fmt.get()!!.format(Date())
        for (raw in msg.split('\n')) {
            val s = raw.trimEnd('\r')
            if (s.isNotBlank()) queue.trySend(Line(t, s))
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun asText(): String = _lines.value.joinToString("\n") { "[${it.time}] ${it.text}" }
}
