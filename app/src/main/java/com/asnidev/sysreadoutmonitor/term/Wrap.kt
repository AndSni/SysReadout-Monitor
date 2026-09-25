package com.asnidev.sysreadoutmonitor.term

import kotlin.math.roundToInt

/**
 * Fits lines to the screen's width in columns, the way a terminal would, so
 * nothing is ever cut off: long lines continue on the next row at their
 * hanging indent, preferably at a field break (two spaces), else at a space.
 * Meters stretch to fill whatever room their line leaves.
 */
object Wrap {
    /** Narrower than this and a meter moves to its own row. */
    const val MIN_METER = 10

    fun lines(line: Line, cols: Int): List<Line> {
        if (cols <= 0) return listOf(line)
        line.meter?.let { return meterLines(line, it, cols) }
        if (width(line.spans) <= cols) return listOf(line)

        val cells = cells(line.spans)
        val indent = line.indent.coerceIn(0, cols / 2)
        val rows = mutableListOf<List<Cell>>()
        var i = 0
        while (i < cells.size) {
            val first = rows.isEmpty()
            val room = if (first) cols else cols - indent
            var used = 0
            var end = i
            while (end < cells.size && used + cells[end].w <= room) used += cells[end++].w
            if (end == i) end = i + 1 // one cell wider than the room: take it anyway
            // The first row never breaks inside its key column (the uncapped indent).
            if (end < cells.size) end = breakAt(cells, i, end, if (first) i + maxOf(line.indent, 1) else i + 1)
            rows += cells.subList(i, end).dropLastWhile { it.ch == " " }
            i = end
            while (i < cells.size && cells[i].ch == " ") i++
        }
        return rows.mapIndexed { n, row ->
            val spans = spansOf(row, line.spans)
            Line(if (n == 0 || indent == 0) spans else listOf(Span(" ".repeat(indent))) + spans)
        }
    }

    /**
     * Where to end a row that would run from [start] to [end]: after the last
     * double space if that keeps at least half the row, else after the last
     * space, else hard at [end]. Never before [minBreak] (the key column).
     */
    private fun breakAt(cells: List<Cell>, start: Int, end: Int, minBreak: Int): Int {
        var single = -1
        var double = -1
        for (k in end downTo minBreak + 1) {
            if (k >= cells.size || cells[k].ch != " ") continue
            if (single < 0) single = k
            if (cells[k - 1].ch == " ") {
                double = k - 1
                break
            }
        }
        return when {
            double > start && double - start >= (end - start) / 2 -> double
            single > start -> single
            else -> end
        }
    }

    private fun meterLines(line: Line, meter: Meter, cols: Int): List<Line> {
        val prefix = width(line.spans)
        if (cols - prefix >= MIN_METER) return listOf(Line(line.spans + render(meter, cols - prefix)))
        val indent = line.indent.coerceIn(0, cols / 2)
        val head = if (line.spans.isEmpty()) emptyList() else lines(line.copy(meter = null), cols)
        return head + Line(listOf(Span(" ".repeat(indent))) + render(meter, cols - indent))
    }

    /** `[|||||      37.0%]` in exactly [width] columns; the label sits right-aligned inside, over the bars. */
    fun render(meter: Meter, width: Int): List<Span> {
        val inner = (width - 2).coerceAtLeast(1)
        val label = meter.label.take(inner)
        val bars = (meter.fraction.coerceIn(0f, 1f) * inner).roundToInt()
        val gap = inner - label.length
        val shown = minOf(bars, gap)
        return listOf(
            Span("[", Tone.DIM),
            Span("|".repeat(shown), meter.tone),
            Span(" ".repeat(gap - shown)),
            Span(label),
            Span("]", Tone.DIM),
        ).filter { it.text.isNotEmpty() }
    }

    private class Cell(val ch: String, val w: Int, val span: Int)

    private fun cells(spans: List<Span>): List<Cell> {
        val out = ArrayList<Cell>()
        spans.forEachIndexed { s, span ->
            var i = 0
            while (i < span.text.length) {
                val cp = span.text.codePointAt(i)
                val n = Character.charCount(cp)
                out += Cell(span.text.substring(i, i + n), cellWidth(cp), s)
                i += n
            }
        }
        return out
    }

    private fun spansOf(row: List<Cell>, spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        while (i < row.size) {
            val s = row[i].span
            val text = StringBuilder()
            while (i < row.size && row[i].span == s) text.append(row[i++].ch)
            out += spans[s].copy(text = text.toString())
        }
        return out
    }

    fun width(spans: List<Span>): Int = spans.sumOf { width(it.text) }

    fun width(s: String): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            w += cellWidth(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /** East Asian wide characters and emoji take two columns in a terminal. */
    fun cellWidth(cp: Int): Int = when {
        cp < 0x1100 -> 1
        cp <= 0x115F -> 2
        cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF -> 2
        cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 -> 2
        cp in 0x1F300..0x1FAFF || cp in 0x20000..0x3FFFD -> 2
        else -> 1
    }
}
