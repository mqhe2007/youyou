package com.example.youyou_album.domain.model

import java.util.GregorianCalendar
import java.util.Calendar
import java.util.TimeZone

/** Version 1: naive filename times are interpreted as UTC on every device/server. */
object MediaTime {
    const val VERSION = 1
    data class Value(val at: Long?, val source: String)
    private val pattern = Regex("(?:IMG|VID)_([0-9]{4})([0-9]{2})([0-9]{2})_([0-9]{2})([0-9]{2})([0-9]{2})(?:_([0-9]{3}))?(?:_h[0-9a-fA-F]{1,64})?(?:\\.[A-Za-z0-9]+)?")
    fun rank(source: String): Int = when(source) {
        "capture" -> 4
        "filename" -> 3
        "added" -> 2
        "modified", "legacy" -> 1
        else -> 0
    }
    fun valid(at: Long?, now: Long = System.currentTimeMillis()): Long? = at?.takeIf {
        it >= 31_536_000_000L && it <= now + 86_400_000L
    }
    fun filename(name: String): Long? {
        val m = pattern.matchEntire(name) ?: return null
        return runCatching {
            val c = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                clear()
                set(m.groupValues[1].toInt(), m.groupValues[2].toInt()-1, m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt())
                set(Calendar.MILLISECOND, m.groupValues[7].toIntOrNull() ?: 0)
            }
            valid(c.timeInMillis)
        }.getOrNull()
    }
    fun resolve(name: String, capture: Long?, added: Long?, modified: Long?, previous: Value? = null): Value {
        val candidate = valid(capture)?.let { Value(it, "capture") }
            ?: filename(name)?.let { Value(it, "filename") }
            ?: valid(added)?.let { Value(it, "added") }
            ?: valid(modified)?.let { Value(it, "modified") }
            ?: Value(null, "unknown")
        return choose(previous, candidate)
    }
    fun choose(previous: Value?, candidate: Value): Value {
        if (previous != null && valid(previous.at) != null && rank(previous.source) >= rank(candidate.source)) return previous
        return if (valid(candidate.at) != null) candidate else Value(null, "unknown")
    }
}
