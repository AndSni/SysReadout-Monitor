package com.asnidev.sysreadoutmonitor.page

import android.os.Process
import com.asnidev.sysreadoutmonitor.monitor.Running
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shell tab: a line-based shell. Each command runs with `sh -c` in its own
 * process group, as the shell user through Shizuku when it's connected (the
 * access adb has), otherwise as SR Monitor's own app user. The working
 * directory carries over between commands; variables don't. There's no
 * terminal (tty), so full-screen programs don't work. Used from the main thread.
 */
class Shell(private val env: Env, private val scope: CoroutineScope) {

    data class State(val lines: List<Line>, val running: Boolean, val prompt: String, val viaShizuku: Boolean)

    private val _state = MutableStateFlow(State(emptyList(), false, "", false))
    val state: StateFlow<State> = _state

    private val lines = ArrayList<Line>()
    private val partial = StringBuilder()
    private var cwd = "/"
    private var running = false
    private var pid: Int? = null
    private var remoteId: Int? = null
    private var local: Running? = null
    private var lastViaShizuku: Boolean? = null

    private val history = ArrayList<String>()
    private var historyAt = 0

    private val appUser: String = Process.myUid().let { "u${it / 100_000}_a${it % 100_000 - 10_000}" }
    private val viaShizuku: Boolean get() = env.shizuku.ready

    init {
        publish()
    }

    fun prompt(): String {
        val dir = if (cwd == "/") "/" else cwd.trimEnd('/').substringAfterLast('/')
        return "[${if (viaShizuku) "shell" else appUser}@${env.hostName} $dir]$ "
    }

    /** Refreshes the prompt (Shizuku came or went). */
    fun refresh() = publish()

    fun submit(input: String) {
        if (running) return
        val command = input.trim()
        lines += Line(listOf(Span(prompt() + input)))
        if (command.isNotEmpty() && history.lastOrNull() != command) history += command
        historyAt = history.size
        when (command) {
            "" -> Unit
            "clear" -> lines.clear()
            else -> scope.launch { run(command) }
        }
        publish()
    }

    /** ^C: stops the running command, or abandons the line, as in bash. */
    fun interrupt() {
        if (!running) {
            lines += Line(listOf(Span(prompt() + "^C")))
            return publish()
        }
        partial.append("^C")
        val group = pid ?: 0
        remoteId?.let { id -> scope.launch { env.shizuku.interrupt(id, group) } }
        local?.interrupt(group)
        publish()
    }

    /** The app left the screen: nothing keeps running unseen. */
    fun stopForBackground() {
        if (!running) return
        interrupt()
        note("stopped: SR Monitor left the screen")
    }

    fun historyBack(): String? {
        if (history.isEmpty()) return null
        historyAt = (historyAt - 1).coerceAtLeast(0)
        return history[historyAt]
    }

    fun historyForward(): String? {
        if (historyAt >= history.size - 1) {
            historyAt = history.size
            return ""
        }
        return history[++historyAt]
    }

    private suspend fun run(command: String) {
        val shizuku = viaShizuku
        if (shizuku != lastViaShizuku) {
            lastViaShizuku = shizuku
            note(if (shizuku) "running as shell through shizuku" else "running as $appUser, SR Monitor's own user")
        }
        running = true
        publish()
        val script = script(command)
        try {
            if (shizuku) {
                val id = env.shizuku.start(script) ?: return note("shizuku went away; try again")
                remoteId = id
                while (true) {
                    val chunk = env.shizuku.read(id) ?: return note("shizuku went away while the command ran")
                    if (consume(chunk)) break
                    delay(if (chunk.isEmpty()) IDLE_POLL_MS else BUSY_POLL_MS)
                }
            } else {
                val command = withContext(Dispatchers.IO) { runCatching { Running(script) }.getOrNull() }
                    ?: return note("couldn't start sh")
                local = command
                while (true) {
                    val chunk = command.read()
                    if (consume(chunk)) break
                    delay(if (chunk.isEmpty()) IDLE_POLL_MS else BUSY_POLL_MS)
                }
            }
        } finally {
            if (partial.isNotEmpty()) emit(partial.toString())
            partial.setLength(0)
            running = false
            remoteId = null
            local = null
            pid = null
            publish()
        }
    }

    /** Takes one chunk of output; true once the command has ended. */
    private fun consume(chunk: String): Boolean {
        if (chunk.startsWith(Running.EXIT)) return true
        if (chunk.isEmpty()) return false
        partial.append(chunk.replace("\r\n", "\n"))
        while (true) {
            val nl = partial.indexOf('\n')
            val text = if (nl < 0) partial.toString() else partial.substring(0, nl)
            val end = text.indexOf(END)
            if (end >= 0) {
                // "<status> <cwd>" after the marker; anything before it on the line is output.
                if (end > 0) emit(text.substring(0, end))
                text.substring(end + END.length).trim().split(' ', limit = 2).getOrNull(1)?.let { if (it.startsWith("/")) cwd = it }
                partial.delete(0, if (nl < 0) partial.length else nl + 1)
                continue
            }
            if (nl < 0) break
            if (text.startsWith(PID)) pid = text.removePrefix(PID).trim().toIntOrNull() else emit(text)
            partial.delete(0, nl + 1)
        }
        publish()
        // The end marker is printed just before sh exits; the command is over when EXIT arrives.
        return false
    }

    private fun emit(raw: String) {
        // Lone carriage returns (progress bars) start a fresh line; escape sequences are dropped.
        raw.split('\r').forEach { part ->
            lines += Line(listOf(Span(expandTabs(ANSI.replace(part, "")))), indent = 0)
        }
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private fun note(text: String) {
        lines += Line(listOf(Span("# $text", Tone.DIM)), indent = 2)
        publish()
    }

    private fun publish() {
        val shown = if (partial.isEmpty() || partial.startsWith(PID)) lines.toList()
        else lines + Line(listOf(Span(expandTabs(ANSI.replace(partial.toString().substringBefore(END), "")))))
        _state.value = State(shown, running, prompt(), viaShizuku)
    }

    /**
     * The command wrapped so the shell tells us its process group first and, when
     * done, its exit status and working directory (so `cd` carries over).
     */
    private fun script(command: String): String = buildString {
        append("printf '${PID_ESC}%s\\n' \"\$\$\"\n")
        append("cd ").append(quote(cwd)).append(" 2>/dev/null\n")
        append(command).append('\n')
        append("printf '${END_ESC}%s %s\\n' \"\$?\" \"\$(pwd)\"\n")
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun expandTabs(s: String): String {
        if ('\t' !in s) return s
        val out = StringBuilder()
        for (c in s) if (c == '\t') do out.append(' ') while (out.length % 8 != 0) else out.append(c)
        return out.toString()
    }

    private companion object {
        // Markers in the output, starting with the ASCII record separator (never in normal text).
        const val PID = "\u001ESRM-PID "
        const val END = "\u001ESRM-END "
        const val PID_ESC = "\\036SRM-PID "
        const val END_ESC = "\\036SRM-END "
        const val MAX_LINES = 3000
        const val BUSY_POLL_MS = 40L
        const val IDLE_POLL_MS = 150L
        val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Z0-9]|\u001B[=>]")
    }
}
