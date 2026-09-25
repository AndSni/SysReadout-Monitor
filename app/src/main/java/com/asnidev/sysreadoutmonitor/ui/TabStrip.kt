package com.asnidev.sysreadoutmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asnidev.sysreadoutmonitor.page.Page

private val tabText = TextStyle(color = Breeze.foreground, fontSize = 14.sp)

/**
 * Konsole's tab bar: the active tab on the faint background, the others on the
 * normal one, thin separators. Tabs use the system UI font, as Konsole's do.
 */
@Composable
fun TabStrip(pages: List<Page>, current: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    // Keep the active tab in view, with its left neighbour showing when there is one.
    LaunchedEffect(current) { state.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxWidth()) {
        LazyRow(state = state, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(pages, key = { _, p -> p.tab }) { i, page ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .background(if (i == current) Breeze.backgroundFaint else Breeze.background)
                            .clickable { onSelect(i) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        BasicText(page.tab, style = tabText, maxLines = 1)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Breeze.backgroundFaint))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Breeze.backgroundFaint))
    }
}
