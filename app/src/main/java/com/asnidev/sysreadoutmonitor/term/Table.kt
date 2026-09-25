package com.asnidev.sysreadoutmonitor.term

/** A table column; every column but the last is as wide as its widest cell. */
data class Col(val title: String, val right: Boolean = false)

data class Cell(val text: String, val tone: Tone = Tone.FG)

fun cell(text: String, tone: Tone = Tone.FG) = Cell(text, tone)

/**
 * Fixed-width columns with a bold header, like `top` or `ps`. The last column
 * holds the free text (a process or app name) and wraps under itself.
 */
fun table(cols: List<Col>, rows: List<List<Cell>>): List<Line> {
    val widths = cols.mapIndexed { c, col ->
        if (c == cols.lastIndex) 0 else maxOf(Wrap.width(col.title), rows.maxOfOrNull { Wrap.width(it.getOrNull(c)?.text.orEmpty()) } ?: 0)
    }
    val lastStart = widths.dropLast(1).sumOf { it + 1 }

    fun render(cells: List<Cell>): Line {
        val spans = ArrayList<Span>()
        cols.forEachIndexed { c, col ->
            val cell = cells.getOrNull(c) ?: Cell("")
            val pad = (widths[c] - Wrap.width(cell.text)).coerceAtLeast(0)
            if (c == cols.lastIndex) {
                spans += Span(cell.text, cell.tone)
            } else {
                if (col.right) spans += Span(" ".repeat(pad))
                spans += Span(cell.text, cell.tone)
                spans += Span(" ".repeat(if (col.right) 1 else pad + 1))
            }
        }
        return Line(spans.filter { it.text.isNotEmpty() }.merged(), indent = lastStart)
    }

    return listOf(render(cols.map { Cell(it.title, Tone.KEY) })) + rows.map(::render)
}

/** Joins neighbouring spans that look the same, so a line is a few spans rather than one per cell. */
private fun List<Span>.merged(): List<Span> {
    val out = ArrayList<Span>(size)
    for (s in this) {
        val last = out.lastOrNull()
        if (last != null && last.tone == s.tone && last.tap == s.tap) out[out.lastIndex] = last.copy(text = last.text + s.text)
        else out += s
    }
    return out
}
