package com.asnidev.sysreadoutmonitor.term

/**
 * What a piece of terminal text means; the UI maps each tone to a Breeze colour
 * (see the colour table in CLAUDE.md). Colours carry meaning, never decoration.
 */
enum class Tone {
    /** Normal text and values. */
    FG,
    /** Row keys, labels and table headers: Konsole draws bold text in the intense foreground. */
    KEY,
    GOOD,
    WARN,
    CRIT,
    /** Secondary: units, timestamps, PIDs, comments, meter brackets. */
    DIM,
    /** Something to tap: cyan, underlined. */
    LINK,
    // Journal keys, one colour per event source.
    SYSTEM,
    USAGE,
    SHELL,
    DNS,
    NOTIF,
}

/** A run of text in one tone; [tap] makes it a link. */
data class Span(val text: String, val tone: Tone = Tone.FG, val tap: Tap? = null)

/** An htop-style `[|||||    37.0%]` meter that fills the rest of its line. */
data class Meter(val fraction: Float, val tone: Tone, val label: String)

/**
 * One terminal line. It never soft-wraps on screen; [Wrap] splits it to the
 * screen's width first, continuing at column [indent] (a hanging indent that
 * keeps keys and table columns readable). A [meter] is drawn after the spans.
 * A [center]ed line is padded to the middle of the screen; an [anchor] names
 * the line so a tap elsewhere can scroll to it.
 */
data class Line(
    val spans: List<Span>,
    val indent: Int = 0,
    val meter: Meter? = null,
    val center: Boolean = false,
    val anchor: String? = null,
) {
    val text: String get() = spans.joinToString("") { it.text }

    companion object {
        val BLANK = Line(emptyList())
    }
}

/** Builds a line from spans: `line { key("mem"); text("3.1G") }`. */
class LineBuilder {
    val spans = mutableListOf<Span>()

    fun text(s: String, tone: Tone = Tone.FG, tap: Tap? = null) {
        if (s.isNotEmpty()) spans += Span(s, tone, tap)
    }

    fun dim(s: String) = text(s, Tone.DIM)
    fun key(s: String) = text(s, Tone.KEY)
    fun link(s: String, tap: Tap) = text(s, Tone.LINK, tap)
    fun add(more: List<Span>) {
        spans += more
    }
}

fun line(indent: Int = 0, meter: Meter? = null, build: LineBuilder.() -> Unit): Line =
    Line(LineBuilder().apply(build).spans.toList(), indent, meter)
