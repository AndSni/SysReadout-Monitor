package com.asnidev.sysreadoutmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.asnidev.sysreadoutmonitor.page.Page
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Tap

/**
 * One page as a terminal session: the prompt and the page's command, then its
 * output. No prompt waits at the end: nothing here takes input (the shell tab
 * does). A tap on the prompt pauses the page the way ^Z stops a job; a long
 * press on any row copies it.
 */
@Composable
fun TerminalPage(
    page: Page,
    lines: List<Line>?,
    prompt: String,
    style: TextStyle,
    cols: Int,
    padding: Dp,
    list: LazyListState,
    paused: Boolean,
    anchor: String?,
    onAnchored: () -> Unit,
    onPrompt: () -> Unit,
    onTap: (Tap) -> Unit,
) {
    val source = lines.orEmpty()
    val rows = remember(source, cols) { wrapRows(source, cols) }
    // The prompt wraps like everything else, so a long host name or command is never cut off.
    val head = remember(prompt, page, cols) { plain(prompt + page.command, cols) }
    val stopped = remember(page, cols) { plain("^Z", cols) + plain("[1]+  Stopped                 ${page.command}", cols) }
    val copy = rememberCopier()
    val flash = rememberFlash()

    if (page == Page.JOURNAL) Follow(list, rows.size)
    // A tap elsewhere asked for a line on this page: scroll to it once it's there (+1 for the prompt item).
    LaunchedEffect(anchor, rows) {
        val at = anchor?.let { a -> rows.indexOfFirst { it.line.anchor == a } } ?: return@LaunchedEffect
        if (at >= 0) {
            list.scrollToItem(at + 1)
            onAnchored()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = padding, vertical = padding)) {
        item(key = "prompt") {
            Column(Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onPrompt)) {
                head.forEach { TermLine(it, style, onTap, onLongPress = { copy(prompt + page.command) }) }
            }
        }
        items(rows.size) { i ->
            val row = rows[i]
            TermLine(row.line, style, onTap, highlighted = flash.source == row.source) {
                copy(copyText(source[row.source], rows.filter { it.source == row.source }))
                flash.source = row.source
            }
        }
        if (paused) {
            item(key = "stop") {
                Column { stopped.forEach { TermLine(it, style, onTap) } }
            }
        }
    }
}
