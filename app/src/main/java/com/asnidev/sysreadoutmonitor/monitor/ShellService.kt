package com.asnidev.sysreadoutmonitor.monitor

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Shizuku "user service": Shizuku starts this class in its own process with
 * the shell uid, which can see every process and socket. Keep it free of
 * Android app state: it has no Context and no access to SR Monitor's storage.
 * The work itself is in [ShellExec] and, for the shell tab, [Running].
 */
class ShellService : IShellService.Stub() {

    private val commands = ConcurrentHashMap<Int, Running>()
    private val ids = AtomicInteger()

    override fun destroy() {
        commands.values.forEach { it.interrupt(null) }
        ShellExec.shutdown()
        exitProcess(0)
    }

    override fun protocol(): Int = ShellProtocol.VERSION

    override fun exec(command: String, timeoutMs: Long): String? = ShellExec.run(command, timeoutMs)

    override fun readFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    override fun resolve(ips: String): String =
        ShellExec.resolve(ips.lines().filter { it.isNotBlank() }, RESOLVE_BUDGET_MS)
            .entries.joinToString("\n") { (ip, host) -> "$ip\t$host" }

    override fun start(script: String): Int {
        val id = ids.incrementAndGet()
        commands[id] = Running(script)
        return id
    }

    override fun read(id: Int): String {
        val command = commands[id] ?: return Running.EXIT + "-1"
        return command.read().also { if (it.startsWith(Running.EXIT)) commands.remove(id) }
    }

    override fun interrupt(id: Int, pid: Int) {
        commands[id]?.interrupt(pid)
    }

    private companion object {
        /** All lookups of one call together; the caller gives up a little after this. */
        const val RESOLVE_BUDGET_MS = 3_000L
    }
}
