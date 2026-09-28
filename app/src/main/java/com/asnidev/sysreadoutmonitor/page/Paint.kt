package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.row

/**
 * Colours the meaningful parts of a ProbeReader row: signal words, thermal
 * status, temperatures, the low-memory flag, and percentages past a
 * threshold. Everything else stays plain, as `ls --color` would leave it.
 */
object Paint {

    fun row(id: String, value: String): Line = row(id, spans(id, value))

    fun spans(id: String, value: String): List<Span> {
        val rules = RULES[id] ?: return listOf(Span(value))
        val hits = rules.flatMap { rule ->
            rule.regex.findAll(value).mapNotNull { m ->
                val g = m.groups[rule.group] ?: return@mapNotNull null
                rule.tone(g.value)?.let { Triple(g.range.first, g.range.last + 1, it) }
            }
        }.sortedBy { it.first }
        val out = ArrayList<Span>()
        var at = 0
        for ((start, end, tone) in hits) {
            if (start < at) continue // overlaps an earlier hit
            if (start > at) out += Span(value.substring(at, start))
            out += Span(value.substring(start, end), tone)
            at = end
        }
        if (at < value.length) out += Span(value.substring(at))
        return merge(out)
    }

    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>(spans.size)
        for (s in spans) {
            val last = out.lastOrNull()
            if (last != null && last.tone == s.tone && last.tap == null && s.tap == null) out[out.lastIndex] = last.copy(text = last.text + s.text)
            else out += s
        }
        return out
    }

    private class Rule(val regex: Regex, val group: Int = 0, val tone: (String) -> Tone?)

    private fun number(s: String) = Regex("-?\\d+(\\.\\d+)?").find(s)?.value?.toDoubleOrNull()

    private val temperature = Rule(Regex("-?\\d+(\\.\\d+)?°C?")) { number(it)?.let(Thresholds::temperature) }
    private val signal = Rule(Regex("\\b(very strong|strong|medium|very weak|weak|no signal)\\b")) { Thresholds.signal(it) }
    private fun percent(before: String = "", after: String = "", tone: (Double) -> Tone) =
        Rule(Regex("$before(\\d+(\\.\\d+)?%)$after"), 1) { s -> number(s)?.let { Thresholds.flag(tone(it)) } }

    private val HEALTH = mapOf(
        "good" to Tone.GOOD, "cold" to Tone.WARN, "OVERHEAT" to Tone.CRIT, "DEAD" to Tone.CRIT, "OVERVOLT" to Tone.CRIT,
    )

    private val RULES: Map<String, List<Rule>> = mapOf(
        "bat" to listOf(
            percent(before = "^", tone = { Thresholds.battery(it.toInt()) }),
            temperature,
            Rule(Regex("health (\\S+)"), 1) { HEALTH[it] },
        ),
        "therm" to listOf(
            Rule(Regex("^(\\S+)"), 1) { Thresholds.thermal(it) },
            Rule(Regex("bat (-?[\\d.]+°C)"), 1) { number(it)?.let(Thresholds::temperature) },
        ),
        "mem" to listOf(Rule(Regex("\\bLOW\\b")) { Thresholds.lowMemory }, percent(after = " used", tone = Thresholds::load)),
        "swap" to listOf(percent(after = "$", tone = Thresholds::load)),
        "fs" to listOf(percent(after = " used", tone = Thresholds::storage)),
        "sd" to listOf(percent(after = " used", tone = Thresholds::storage)),
        "props" to listOf(
            Rule(Regex("vbs (\\w+)"), 1) { Thresholds.verifiedBoot(it) },
            Rule(Regex("\\bUNLOCKED\\b")) { Tone.WARN },
        ),
        "wifi" to listOf(signal),
        "cell" to listOf(signal),
    )
}
