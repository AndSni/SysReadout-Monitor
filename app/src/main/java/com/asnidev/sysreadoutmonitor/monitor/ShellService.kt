package com.asnidev.sysreadoutmonitor.monitor

import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku "user service": Shizuku starts this class in its own process with
 * the shell uid, which can see every process and socket. Keep it free of
 * Android app state: it has no Context and no access to SR Monitor's storage.
 */
class ShellService : IShellService.Stub() {

    private val lookups = Executors.newFixedThreadPool(4)

    override fun destroy() {
        lookups.shutdownNow()
        exitProcess(0)
    }

    override fun exec(command: String, timeoutMs: Long): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        // Read on a separate thread so a stuck command can't block past the timeout.
        val output = lookups.submit<String> { process.inputStream.bufferedReader().readText() }
        return try {
            output.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            ""
        } finally {
            process.destroy()
        }
    }

    override fun readFile(path: String): String = runCatching { File(path).readText() }.getOrDefault("")

    override fun resolve(ips: String): String {
        val jobs = ips.lines().filter { it.isNotBlank() }.map { ip ->
            ip to lookups.submit<String?> {
                val host = InetAddress.getByName(ip).canonicalHostName
                host.takeIf { it != ip }
            }
        }
        return jobs.mapNotNull { (ip, job) ->
            runCatching { job.get(3, TimeUnit.SECONDS) }.getOrNull()?.let { "$ip\t$it" }
        }.joinToString("\n")
    }
}
