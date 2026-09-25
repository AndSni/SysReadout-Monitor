package com.asnidev.sysreadoutmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.asnidev.sysreadoutmonitor.page.Page
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.term.Wrap
import kotlinx.coroutines.delay

/**
 * One page as a terminal session: the prompt and the page's command, its
 * output, then a fresh prompt with a block cursor. Only the cursor blinks, and
 * only on the visible, running page. A tap on the first prompt pauses the page
 * the way ^Z stops a job.
 */
@Composable
fun TerminalPage(
    page: Page,
    lines: List<Line>?,
    prompt: String,
    style: TextStyle,
    cols: Int,
    cell: DpSize,
    padding: Dp,
    list: LazyListState,
    active: Boolean,
    paused: Boolean,
    anchor: String?,
    onAnchored: () -> Unit,
    onPrompt: () -> Unit,
    onTap: (Tap) -> Unit,
) {
    val wrapped = remember(lines, cols) { lines.orEmpty().flatMap { Wrap.lines(it, cols) } }
    // The prompts wrap like everything else, so a long host name or command is never cut off.
    val head = remember(prompt, page, cols) { plain(prompt + page.command, cols) }
    val stopped = remember(page, cols) { plain("^Z", cols) + plain("[1]+  Stopped                 ${page.command}", cols) }
    val tail = remember(prompt, cols) {
        val rows = plain(prompt, cols)
        // The cursor needs a cell after the prompt; without one it starts the next row.
        if (Wrap.width(rows.last().text) < cols) rows else rows + Line.BLANK
    }
    val follow = page == Page.JOURNAL
    if (follow) Follow(list, wrapped.size)
    // A tap elsewhere asked for a line on this page: scroll to it once it's there (+1 for the prompt item).
    LaunchedEffect(anchor, wrapped) {
        val at = anchor?.let { a -> wrapped.indexOfFirst { it.anchor == a } } ?: return@LaunchedEffect
        if (at >= 0) {
            list.scrollToItem(at + 1)
            onAnchored()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = padding, vertical = padding)) {
        item(key = "prompt") {
            Column(Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onPrompt)) {
                head.forEach { TermLine(it, style, onTap) }
            }
        }
        items(wrapped.size) { i -> TermLine(wrapped[i], style, onTap) }
        if (paused) {
            item(key = "stop") {
                Column { stopped.forEach { TermLine(it, style, onTap) } }
            }
        }
        item(key = "cursor") {
            // Before the first sample the command is still "running": cursor on an empty line.
            val rows = if (lines != null || paused) tail else listOf(Line.BLANK)
            Column {
                rows.dropLast(1).forEach { TermLine(it, style, onTap) }
                Row {
                    BasicText(rows.last().text, style = style, softWrap = false, maxLines = 1)
                    Cursor(cell, blinking = active && !paused)
                }
            }
        }
    }
}

private fun plain(text: String, cols: Int): List<Line> = Wrap.lines(Line(listOf(Span(text))), cols)

@Composable
private fun TermLine(line: Line, style: TextStyle, onTap: (Tap) -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val text = remember(line) { annotate(line) { tap(it) } }
    BasicText(text, style = style, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
}

private fun annotate(line: Line, onTap: (Tap) -> Unit): AnnotatedString = buildAnnotatedString {
    for (span in line.spans) {
        val style = span.tone.spanStyle()
        val tap = span.tap
        if (tap != null) {
            withLink(LinkAnnotation.Clickable("tap", TextLinkStyles(style)) { onTap(tap) }) { append(span.text) }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}

/** Konsole's block cursor. The blink redraws this box alone; nothing recomposes. */
@Composable
private fun Cursor(size: DpSize, blinking: Boolean) {
    var on by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(blinking, lifecycle) {
        on = true
        if (!blinking) return@LaunchedEffect
        // delay() keeps ticking in the background; blink only while the app is on screen.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    delay(BLINK_MS)
                    on = !on
                }
            } finally {
                on = true
            }
        }
    }
    Box(Modifier.size(size).drawBehind { if (on) drawRect(Breeze.foreground) })
}

/** Keeps a following page (the journal) scrolled to the end while the user leaves it there. */
@Composable
private fun Follow(list: LazyListState, count: Int) {
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, more) ->
            if (scrolling) stick = !more
        }
    }
    LaunchedEffect(count) {
        if (stick && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
}

private const val BLINK_MS = 500L
