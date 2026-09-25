package com.asnidev.sysreadoutmonitor.monitor

import android.system.Os
import android.system.OsConstants
import kotlin.concurrent.thread

/**
 * One command typed into the shell tab, running in its own process group (via
 * `setsid`) so ^C reaches everything it started, not just `sh`. Used both in
 * the app (as its own user) and in Shizuku's ShellService (as the shell user),
 * so it must not touch Android app state.
 */
class Running(script: String) {

    private val process = ProcessBuilder("setsid", "sh", "-c", script).redirectErrorStream(true).start()
    private val out = StringBuilder()
    @Volatile private var exit: Int? = null

    init {
        // No input: commands that read stdin get end-of-file instead of waiting forever.
        runCatching { process.outputStream.close() }
        thread(isDaemon = true, name = "shell-output") {
            val reader = process.inputStream.reader()
            val buf = CharArray(4096)
            while (true) {
                val n = runCatching { reader.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                synchronized(out) {
                    out.append(buf, 0, n)
                    if (out.length > MAX_BUFFER) out.delete(0, out.length - MAX_BUFFER)
                }
            }
            exit = runCatching { process.waitFor() }.getOrDefault(-1)
        }
    }

    /**
     * Output since the last call. Once the command has ended and everything was read,
     * returns [EXIT] followed by the exit status.
     */
    fun read(): String {
        val text = synchronized(out) { out.toString().also { out.setLength(0) } }
        if (text.isNotEmpty()) return text
        return exit?.let { "$EXIT$it" } ?: ""
    }

    /** ^C: SIGINT to the whole group, then harder if it doesn't listen. */
    fun interrupt(pid: Int?) {
        if (pid != null && pid > 0) {
            runCatching { Os.kill(-pid, OsConstants.SIGINT) }
            thread(isDaemon = true) {
                Thread.sleep(700)
                if (exit == null) runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
            }
        }
        thread(isDaemon = true) {
            Thread.sleep(1_000)
            if (exit == null) process.destroy()
        }
    }

    companion object {
        /** Marks the end of a command in [read]'s output; the exit status follows. */
        const val EXIT = "\u0000EXIT "
        private const val MAX_BUFFER = 256 * 1024
    }
}
