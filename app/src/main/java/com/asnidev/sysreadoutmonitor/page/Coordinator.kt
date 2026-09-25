package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What one page shows. Each sampler owns the listeners its page needs. */
interface PageSampler {
    /** Starts the listeners this page needs (sensors, GPS, telephony callbacks). */
    fun start() {}

    /** Stops them again; called when the page leaves the screen or the app stops. */
    fun stop() {}

    suspend fun sample(): List<Line>
}

/**
 * Runs the visible page's sampler and nothing else. [run] is called only
 * while the activity is started, so nothing samples in the background.
 * Every sampler runs on one serial dispatcher, so they need no locks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Coordinator(private val samplers: Map<Page, PageSampler>, private val intervalMs: () -> Long) {

    val serial = Dispatchers.Default.limitedParallelism(1)

    private val contents = Page.entries.associateWith { MutableStateFlow<List<Line>?>(null) }

    /** Null until the page's first sample. */
    fun content(page: Page): StateFlow<List<Line>?> = contents.getValue(page)

    val visible = MutableStateFlow<Page?>(null)
    val paused = MutableStateFlow<Set<Page>>(emptySet())
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    /** Samples the visible page now instead of at the next interval. */
    fun poke() {
        pokes.trySend(Unit)
    }

    fun togglePause(page: Page) {
        paused.value = if (page in paused.value) paused.value - page else paused.value + page
    }

    suspend fun run() = withContext(serial) {
        combine(visible, paused) { page, paused -> page?.takeUnless { it in paused } }
            .distinctUntilChanged()
            .collectLatest { page -> if (page != null) drive(page) }
    }

    private suspend fun drive(page: Page) {
        val sampler = samplers.getValue(page)
        sampler.start()
        try {
            while (true) {
                contents.getValue(page).value = try {
                    sampler.sample()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    listOf(Line(listOf(Span("srm: ${page.tab}: ${e.javaClass.simpleName}: ${e.message}", Tone.CRIT))))
                }
                withTimeoutOrNull(intervalMs()) { pokes.receive() }
            }
        } finally {
            sampler.stop()
        }
    }
}
