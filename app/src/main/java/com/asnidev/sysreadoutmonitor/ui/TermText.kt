package com.asnidev.sysreadoutmonitor.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.term.Wrap
import kotlinx.coroutines.delay

/** One on-screen row and the logical line it came from (a long line wraps over several rows). */
class Row(val line: Line, val source: Int)

/** [lines] fitted to [cols], each row remembering its source line. */
fun wrapRows(lines: List<Line>, cols: Int): List<Row> =
    lines.flatMapIndexed { i, line -> Wrap.lines(line, cols).map { Row(it, i) } }

fun plain(text: String, cols: Int): List<Line> = Wrap.lines(Line(listOf(Span(text))), cols)

/**
 * What a long press copies: the whole logical line, however it wrapped. A meter
 * only exists once drawn, so meter lines copy their drawn rows.
 */
fun copyText(line: Line, rows: List<Row>): String =
    if (line.meter == null) line.text.trimEnd()
    else rows.joinToString(" ") { it.line.text.trim() }

/** Copies text to the clipboard with a haptic tick; Android 13+ confirms on its own, older versions get a toast. */
@Composable
fun rememberCopier(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    return remember(clipboard, haptics, context) {
        { text ->
            clipboard.setText(AnnotatedString(text))
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "copied", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Tracks which source line was just copied, so its rows can light up for a moment
 * the way a selection does in Konsole.
 */
class Flash {
    var source by mutableStateOf<Int?>(null)
}

@Composable
fun rememberFlash(): Flash {
    val flash = remember { Flash() }
    LaunchedEffect(flash.source) {
        if (flash.source != null) {
            delay(FLASH_MS)
            flash.source = null
        }
    }
    return flash
}

/** One row of terminal text: its links work on tap, and a long press copies its whole line. */
@Composable
fun TermLine(line: Line, style: TextStyle, onTap: (Tap) -> Unit, highlighted: Boolean = false, onLongPress: (() -> Unit)? = null) {
    val tap by rememberUpdatedState(onTap)
    val text = remember(line) { annotate(line) { tap(it) } }
    val press by rememberUpdatedState(onLongPress)
    BasicText(
        text,
        style = style,
        softWrap = false,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .then(if (highlighted) Modifier.background(Breeze.backgroundFaint) else Modifier.background(Color.Transparent))
            .then(if (onLongPress != null) Modifier.longPress { press?.invoke() } else Modifier),
    )
}

/**
 * A long press that leaves everything else alone: taps still reach links, scrolls and
 * swipes still scroll and swipe. Only once it fires does it swallow the rest of the
 * gesture, before the children see it, so the release doesn't also follow a link.
 */
private fun Modifier.longPress(action: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        // Released, or taken over by a scroll or swipe, before the timeout: not a long press.
        var ended = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation()
            ended = true
        }
        if (ended) return@awaitEachGesture
        action()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
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

/** Keeps a following list (the journal, the shell) scrolled to the end while the user leaves it there. */
@Composable
fun Follow(list: LazyListState, count: Int) {
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

private const val FLASH_MS = 600L
