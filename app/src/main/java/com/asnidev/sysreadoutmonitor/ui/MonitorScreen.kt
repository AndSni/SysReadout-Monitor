package com.asnidev.sysreadoutmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asnidev.sysreadoutmonitor.MonitorViewModel
import com.asnidev.sysreadoutmonitor.page.Page
import com.asnidev.sysreadoutmonitor.term.Tap
import kotlinx.coroutines.launch

private val PADDING = 4.dp

@Composable
fun MonitorScreen(vm: MonitorViewModel, onTap: (Tap) -> Unit) {
    val prefs = vm.prefs.collectAsState().value
    Column(Modifier.fillMaxSize().background(Breeze.background).safeDrawingPadding()) {
        if (prefs == null) return@Column // settings load in a few ms; show the empty terminal meanwhile

        val pages = remember(prefs.order, prefs.hidden) { Page.arrange(prefs.order, prefs.hidden) }
        val pager = rememberPagerState(pages.indexOf(Page.byTab(prefs.lastPage)).coerceAtLeast(0)) { pages.size }
        val scope = rememberCoroutineScope()
        val paused by vm.coordinator.paused.collectAsState()
        val lists = remember { Page.entries.associateWith { LazyListState() } }

        LaunchedEffect(pager, pages) {
            snapshotFlow { pager.settledPage }.collect { i -> pages.getOrNull(i)?.let(vm::show) }
        }
        // Hiding, showing or moving pages shifts indexes: stay on the page that was showing.
        LaunchedEffect(pages) {
            val index = vm.coordinator.visible.value?.let(pages::indexOf) ?: -1
            if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
        }
        val jump = vm.pendingJump
        LaunchedEffect(jump, pages) {
            val target = jump?.let(pages::indexOf)?.takeIf { it >= 0 } ?: return@LaunchedEffect
            pager.scrollToPage(target)
            vm.pendingJump = null
        }

        val style = remember(vm.textSp) {
            TextStyle(
                fontFamily = Hack,
                fontSize = vm.textSp.sp,
                color = Breeze.foreground,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )
        }
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Hack is monospaced: one character's advance and the line height make a cell.
        val sample = remember(style) { measurer.measure("0".repeat(SAMPLE), style).size }
        val charWidth = sample.width / SAMPLE.toFloat()
        val cell = with(density) { DpSize(charWidth.toDp(), sample.height.toDp()) }

        TabStrip(pages, pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().pinchToZoom(onZoom = vm::zoomBy, onEnd = vm::saveTextSize),
        ) {
            val cols = ((constraints.maxWidth - 2 * with(density) { PADDING.toPx() }) / charWidth).toInt()
            HorizontalPager(pager, key = { pages[it].tab }) { i ->
                val page = pages[i]
                val lines by vm.coordinator.content(page).collectAsState()
                TerminalPage(
                    page = page,
                    lines = lines,
                    prompt = vm.prompt,
                    style = style,
                    cols = cols,
                    cell = cell,
                    padding = PADDING,
                    list = lists.getValue(page),
                    active = pager.settledPage == i,
                    paused = page in paused,
                    onPrompt = { vm.coordinator.togglePause(page) },
                    onTap = onTap,
                )
            }
        }
    }
}

private const val SAMPLE = 20

/**
 * Two fingers change the text size; one finger is left alone for scrolling
 * and swiping. Runs before the children see the events so a pinch never scrolls.
 */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit, onEnd: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoomed = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    onZoom(zoom)
                    zoomed = true
                }
                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (zoomed) onEnd()
    }
}
