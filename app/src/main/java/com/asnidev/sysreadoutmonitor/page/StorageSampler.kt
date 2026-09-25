package com.asnidev.sysreadoutmonitor.page

import android.app.usage.StorageStatsManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.log.ProbeReader.Companion.bytes
import com.asnidev.sysreadoutmonitor.term.Col
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.cell
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.row
import com.asnidev.sysreadoutmonitor.term.table
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** `df -h`: internal and removable storage, the file systems, and the biggest apps. */
class StorageSampler(private val env: Env) : PageSampler {

    private data class AppSize(val pkg: String, val app: Long, val data: Long, val cache: Long) {
        val total get() = app + data + cache
    }

    // Sizing every package takes a second or so: done in the background, every few minutes.
    private val sizesCadence = Cadence { 5 * 60_000L }
    private var sizes: List<AppSize>? = null
    private var sizing: Job? = null

    override fun stop() {
        sizing?.cancel()
        sizesCadence.reset()
    }

    override suspend fun sample(): List<Line> {
        val r = env.reader
        val out = ArrayList<Line>()
        r.values(listOf("fs")).firstOrNull()?.let { (id, value) -> out += Paint.row(id, value) }
        out += usageMeter(r.storageBytes(Environment.getDataDirectory()))

        val volumes = r.removableVolumes()
        if (volumes.isEmpty()) out += row("sd", listOf(Span("none mounted", Tone.DIM)))
        volumes.forEach { (name, dir) ->
            val (avail, total) = r.storageBytes(dir)
            out += Paint.row("sd", "$name  ${bytes(avail)}/${bytes(total)} free  ${usedPct(avail, total).toInt()}% used")
            out += usageMeter(avail to total)
        }

        out += comment("file systems")
        out += fileSystems()

        if (env.missing(Access.USAGE) == null && sizesCadence.due() && sizing?.isActive != true) {
            sizing = env.scope.launch {
                sizes = withContext(Dispatchers.IO) { appSizes() }
                env.poke()
            }
        }
        out += comment("apps by size" + (sizes?.let { " · ${it.size}" } ?: ""))
        out += env.gate(Access.USAGE)?.let(::listOf) ?: sizes?.let(::sizeTable) ?: listOf(note("measuring…"))
        return out
    }

    private fun usedPct(avail: Long, total: Long) = if (total <= 0) 0.0 else (total - avail) * 100.0 / total

    private fun usageMeter(availTotal: Pair<Long, Long>): Line {
        val (avail, total) = availTotal
        val used = total - avail
        val pct = usedPct(avail, total)
        return meterLine(pct / 100, Thresholds.storage(pct), "${bytes(used)}/${bytes(total)}")
    }

    /**
     * `df`: every mount point an app may stat, once each. Read-only system
     * images are always full, so only writable ones get threshold colours.
     */
    private fun fileSystems(): List<Line> {
        val seen = HashSet<Pair<Long, Long>>()
        val readOnly = readOnlyMounts()
        val rows = MOUNTS.mapNotNull { path ->
            val fs = runCatching { StatFs(path) }.getOrNull() ?: return@mapNotNull null
            val total = fs.totalBytes
            val avail = fs.availableBytes
            if (total <= 0 || !seen.add(total to fs.freeBytes)) return@mapNotNull null
            val pct = usedPct(avail, total)
            val ro = path in readOnly
            listOf(
                cell(bytes(total)),
                cell(bytes(total - fs.freeBytes)),
                cell(bytes(avail)),
                cell("${pct.toInt()}%", if (ro) Tone.DIM else Thresholds.flag(Thresholds.storage(pct))),
                cell(if (ro) "$path (ro)" else path),
            )
        }
        return table(
            listOf(Col("SIZE", right = true), Col("USED", right = true), Col("AVAIL", right = true), Col("USE%", right = true), Col("MOUNTED ON")),
            rows,
        )
    }

    /** Mount points mounted read-only, from this process's own mount table. */
    private fun readOnlyMounts(): Set<String> = runCatching {
        File("/proc/self/mounts").readLines().mapNotNull { line ->
            val f = line.split(' ')
            if (f.size >= 4 && f[3].split(',').contains("ro")) f[1] else null
        }.toSet()
    }.getOrDefault(emptySet())

    private fun appSizes(): List<AppSize> {
        val ssm = env.context.getSystemService(StorageStatsManager::class.java)
        val me = Process.myUserHandle()
        return env.context.packageManager.getInstalledApplications(0).mapNotNull { info ->
            runCatching {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, me)
                AppSize(info.packageName, s.appBytes, s.dataBytes, s.cacheBytes)
            }.getOrNull()
        }.sortedByDescending { it.total }
    }

    private fun sizeTable(list: List<AppSize>): List<Line> = table(
        listOf(Col("TOTAL", right = true), Col("APP", right = true), Col("DATA", right = true), Col("CACHE", right = true), Col("NAME")),
        list.take(20).map { a ->
            listOf(cell(bytes(a.total)), cell(bytes(a.app), Tone.DIM), cell(bytes(a.data), Tone.DIM), cell(bytes(a.cache), Tone.DIM), cell(env.labels.pkgLabel(a.pkg)))
        },
    )

    private companion object {
        val MOUNTS = listOf("/data", "/", "/system", "/system_ext", "/product", "/vendor", "/odm", "/cache", "/metadata")
    }
}
