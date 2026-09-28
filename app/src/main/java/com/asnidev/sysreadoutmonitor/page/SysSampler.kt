package com.asnidev.sysreadoutmonitor.page

import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.os.ext.SdkExtensions
import android.system.Os
import android.system.OsConstants
import android.webkit.WebView
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.log.SystemProps
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.row
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/** `fastfetch`: what the phone is, and what software it runs. */
class SysSampler(private val env: Env) : PageSampler {

    private val context = env.context
    private val pm = context.packageManager
    private val appsCadence = Cadence { 60_000L }
    private var apps: String? = null
    private var versions: List<Line> = emptyList()
    private var selinux: String? = null
    private var selinuxAsked = false

    init {
        env.reader.appsSummary = { apps }
    }

    override fun stop() {
        selinuxAsked = false
    }

    override suspend fun sample(): List<Line> {
        if (appsCadence.due()) {
            apps = countApps()
            versions = appVersions()
        }
        if (!selinuxAsked && env.missing(Access.SHIZUKU) == null) {
            selinuxAsked = true
            selinux = env.shizuku.exec("getenforce")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (kernelBuild == null) kernelBuild = Software.kernelBuild(env.shizuku.readFile("/proc/version"))?.let { row("kbuild", it) }
        }
        val host = env.hostName
        val title = Line(listOf(Span("user", Tone.KEY), Span("@"), Span(host, Tone.KEY)))
        val rule = Line(listOf(Span("-".repeat(5 + host.length))))
        val banner = if (!env.prefs().banner) emptyList() else banner() + Line.BLANK
        val values = env.reader.values(ROWS).toMap()
        fun probe(id: String) = values[id]?.let { Paint.row(id, it) }

        val out = ArrayList<Line>()
        out += banner + listOf(title, rule)
        out += comment("device")
        out += listOfNotNull(probe("dev"), probe("soc"), abi(), probe("gpu"), probe("disp"))
        out += comment("android")
        out += listOfNotNull(probe("os")) + build + listOfNotNull(patch(), update, launch, probe("props"), secure(), zone())
        out += comment("kernel and firmware")
        out += listOfNotNull(probe("kern"), kernelBuild, firmware)
        out += comment("runtime and apps")
        out += versions + listOfNotNull(runtime, probe("apps"))
        out += comment("time")
        out += listOfNotNull(probe("up"), probe("boot"), probe("time"))
        return out
    }

    /** Centred like fastfetch's logo, in its colour. */
    private fun banner(): List<Line> = listOf(
        "SYSTEM READOUT MONITOR",
        "- ASNIDEV INC $FIRST_YEAR-${Year.now().value} -",
    ).map { Line(listOf(Span(it, Tone.KEY)), center = true) }

    // --- read once: none of this changes while the app runs ---

    private val build: List<Line> by lazy {
        val out = ArrayList<Line>()
        // Build.DISPLAY often already includes type and keys ("65.2.A.2.270 release-keys"): use the plain build number.
        val id = Build.ID.takeIf { it.isNotBlank() } ?: Build.DISPLAY
        out += row("build", "$id · ${Build.TYPE} ${Build.TAGS}")
        Build.VERSION.INCREMENTAL.takeIf { it.isNotBlank() && it != id }?.let { out += row("", listOf(Span("incremental $it", Tone.DIM))) }
        if (Build.TIME > 0) {
            val built = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(Build.TIME))
            out += row("built", built)
        }
        out += row("finger", Build.FINGERPRINT)
        out
    }

    private val update: Line? by lazy {
        val version = listOf("com.google.android.modulemetadata", "com.android.modulemetadata")
            .firstNotNullOfOrNull { runCatching { pm.getPackageInfo(it, 0).versionName }.getOrNull() }
        Software.playUpdate(version)?.let { row("update", "google play system update $it") }
    }

    private val launch: Line? by lazy {
        val parts = ArrayList<String>()
        SystemProps["ro.product.first_api_level"]?.toIntOrNull()?.let { api ->
            parts += "shipped with android ${Software.androidName(api) ?: "api $api"}"
        }
        SystemProps["ro.vndk.version"]?.let { parts += "vndk $it" }
        SystemProps["ro.build.version.min_supported_target_sdk"]?.let { parts += "installs apps for api $it+" }
        if (parts.isEmpty()) null else row("launch", parts.joinToString(" · "))
    }

    // Android 14 keeps /proc/version from normal apps; Shizuku's shell can read it.
    private var kernelBuild: Line? = runCatching { File("/proc/version").readText() }.getOrNull()
        ?.let(Software::kernelBuild)?.let { row("kbuild", it) }

    private val firmware: Line? by lazy {
        val parts = listOfNotNull(
            Build.BOOTLOADER.takeIf { it.isNotBlank() && it != Build.UNKNOWN }?.let { "bootloader $it" },
            Software.baseband(runCatching { Build.getRadioVersion() }.getOrNull())?.let { "baseband $it" },
        )
        if (parts.isEmpty()) null else row("loader", parts.joinToString(" · "))
    }

    private val runtime: Line? by lazy {
        val parts = ArrayList<String>()
        System.getProperty("java.vm.version")?.let { parts += "art $it" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) }.getOrNull()?.takeIf { it > 0 }?.let { parts += "sdk extensions $it" }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.MEDIA_PERFORMANCE_CLASS > 0) {
            parts += "media performance class ${Build.VERSION.MEDIA_PERFORMANCE_CLASS}"
        }
        if (parts.isEmpty()) null else row("runtime", parts.joinToString(" · "))
    }

    // --- rows with meaning (colours) or that can change ---

    /** Every ABI the phone runs, and the memory page size (16 KB on some newer phones). */
    private fun abi(): Line {
        val page = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull()?.let { "  ${it / 1024} KB pages" }.orEmpty()
        return row("abi", Build.SUPPORTED_ABIS.joinToString(", ") + page)
    }

    /** Security and vendor patch levels, with the security patch's age coloured by threshold. */
    private fun patch(): Line? {
        val security = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() } ?: return null
        val spans = mutableListOf(Span("security $security"))
        Software.patchAgeDays(security, LocalDate.now())?.let { days ->
            spans += Span("  " + Software.age(days), Thresholds.patchAge(days))
        }
        SystemProps["ro.vendor.build.security_patch"]?.takeIf { it != security }?.let { spans += Span("  vendor $it") }
        return row("patch", spans)
    }

    /** Encryption, verity, verified boot and SELinux, each coloured by what it means. */
    private fun secure(): Line? {
        val spans = ArrayList<Span>()
        fun add(text: String, tone: Tone = Tone.FG) {
            if (spans.isNotEmpty()) spans += Span(" · ")
            spans += Span(text, tone)
        }
        SystemProps["ro.crypto.state"]?.let { state ->
            val type = SystemProps["ro.crypto.type"]?.let { " ($it)" }.orEmpty()
            add("$state$type", if (state == "encrypted") Tone.GOOD else Tone.CRIT)
        }
        SystemProps["ro.boot.verifiedbootstate"]?.let { add("boot $it", Thresholds.verifiedBoot(it)) }
        SystemProps["ro.boot.veritymode"]?.let { add("verity $it", if (it == "enforcing") Tone.GOOD else Tone.WARN) }
        selinux?.let { add("selinux $it", if (it == "enforcing") Tone.GOOD else Tone.CRIT) }
        return if (spans.isEmpty()) null else row("secure", spans)
    }

    private fun zone(): Line {
        val tz = runCatching { android.icu.util.TimeZone.getTZDataVersion() }.getOrNull()
        return row("zone", listOfNotNull(TimeZone.getDefault().id, tz?.let { "tzdata $it" }, Locale.getDefault().toLanguageTag()).joinToString(" · "))
    }

    /** WebView, Play services and Play Store versions: they update on their own, so re-read with the app counts. */
    private fun appVersions(): List<Line> {
        fun version(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()?.substringBefore(' ')
        val out = ArrayList<Line>()
        val webview = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        webview?.let { out += row("webview", "${env.labels.pkgLabel(it.packageName)} ${it.versionName.orEmpty()}".trim()) }
        val google = listOfNotNull(
            version("com.google.android.gms")?.let { "play services $it" },
            version("com.android.vending")?.let { "play store $it" },
        )
        if (google.isNotEmpty()) out += row("google", google.joinToString(" · "))
        return out
    }

    /** Installed packages (user-installed among them) and launchable apps per profile. */
    private fun countApps(): String {
        val installed = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val user = installed.count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        val launcher = context.getSystemService(LauncherApps::class.java)
        val profiles = context.getSystemService(UserManager::class.java).userProfiles
        val me = Process.myUserHandle()
        val mine = runCatching { launcher.getActivityList(null, me).size }.getOrDefault(0)
        val work = profiles.filter { it != me }.sumOf { runCatching { launcher.getActivityList(null, it).size }.getOrDefault(0) }
        return "${installed.size} installed ($user by you)  $mine launchable" + if (work > 0) "  +$work work" else ""
    }

    private companion object {
        const val FIRST_YEAR = 2026
        val ROWS = listOf("dev", "os", "kern", "props", "soc", "gpu", "disp", "up", "boot", "time", "apps")
    }
}
