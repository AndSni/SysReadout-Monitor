package com.asnidev.sysreadoutmonitor.monitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.asnidev.sysreadoutmonitor.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.util.concurrent.atomic.AtomicInteger

enum class ShizukuState(val label: String) {
    OFF("off"),
    NOT_INSTALLED("not installed"),
    NOT_RUNNING("installed, not running"),
    UNSUPPORTED("too old, update Shizuku"),
    NO_PERMISSION("needs permission"),
    CONNECTING("connecting…"),
    FAILING("helper keeps failing, retrying"),
    READY("connected");

    companion object {
        /** The state from what was just observed. [running] means Shizuku's (or Sui's) binder answers. */
        fun of(
            enabled: Boolean,
            running: Boolean,
            installed: Boolean,
            preV11: Boolean,
            permitted: Boolean,
            connected: Boolean,
            failing: Boolean,
        ): ShizukuState = when {
            !enabled -> OFF
            !running -> if (installed) NOT_RUNNING else NOT_INSTALLED
            preV11 -> UNSUPPORTED
            !permitted -> NO_PERMISSION
            connected -> READY
            failing -> FAILING
            else -> CONNECTING
        }
    }
}

/**
 * Optional shell-level access through Shizuku, or Sui on rooted phones.
 * Ported from SysReadout Launcher, always enabled here, with the shell tab's calls.
 *
 * Nothing here may take the app down: every Shizuku call is caught and
 * runs off the main thread, every helper call is bounded in time, and a helper
 * that keeps failing is left alone for a while (30 s, doubling up to 5 min)
 * instead of being retried in a tight loop. While switched off with
 * [setEnabled] no Shizuku code runs at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuBridge(private val context: Context) {

    private val _state = MutableStateFlow(ShizukuState.OFF)
    val state: StateFlow<ShizukuState> = _state

    /** True when the binder comes from Sui (root) rather than the Shizuku app. */
    @Volatile var isSui = false
        private set

    @Volatile private var enabled = false
    @Volatile private var service: IShellService? = null

    // State changes happen one at a time on this worker; the helper's blocking calls run on IO.
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, e -> Log.w(TAG, "shizuku task failed", e) },
    )

    // Only touched on the worker.
    private var bindingSince = 0L
    private var failedBinds = 0
    private var pauses = 0

    @Volatile private var pausedUntil = 0L
    private val failures = AtomicInteger()
    private val permits = Semaphore(MAX_CALLS)

    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .daemon(false)
        .processNameSuffix("monitor")
        .debuggable(BuildConfig.DEBUG)
        // A new protocol replaces a helper still running from an older build of the same version.
        .version(BuildConfig.VERSION_CODE * 100 + ShellProtocol.VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            scope.launch(worker) { attach(binder) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refresh()
        }
    }

    private val onBinder = Shizuku.OnBinderReceivedListener { refresh() }
    private val onDead = Shizuku.OnBinderDeadListener {
        service = null
        refresh()
    }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        setEnabled(true)
    }

    /** Switches the whole integration on or off. Off: no listeners, no helper process. */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            safely { Shizuku.addBinderReceivedListenerSticky(onBinder) }
            safely { Shizuku.addBinderDeadListener(onDead) }
            safely { Shizuku.addRequestPermissionResultListener(onPermission) }
        } else {
            safely { Shizuku.removeBinderReceivedListener(onBinder) }
            safely { Shizuku.removeBinderDeadListener(onDead) }
            safely { Shizuku.removeRequestPermissionResultListener(onPermission) }
            scope.launch(worker) { release() }
        }
        refresh()
    }

    /** Re-checks Shizuku in the background; cheap enough to call often. */
    fun refresh() {
        scope.launch(worker) { recompute() }
    }

    suspend fun refreshNow() = withContext(worker) { recompute() }

    /** Starts a fresh helper now, ending any pause after repeated failures. */
    fun restartHelper() {
        scope.launch(worker) {
            pausedUntil = 0L
            pauses = 0
            failedBinds = 0
            release()
            recompute()
        }
    }

    private fun recompute() {
        if (!enabled) {
            _state.value = ShizukuState.OFF
            return
        }
        // Sui (root) hands over the same binder without the Shizuku app being installed.
        val running = safely { Shizuku.pingBinder() } == true
        val installed = running || isInstalled()
        val preV11 = running && safely { Shizuku.isPreV11() } == true
        val permitted = running && !preV11 &&
            safely { Shizuku.checkSelfPermission() } == PackageManager.PERMISSION_GRANTED
        isSui = running && safely { Sui.isSui() } == true
        if (!running) service = null
        // isBinderAlive is a local check: a ping would hang here if the helper does.
        val connected = service?.let { s -> safely { s.asBinder().isBinderAlive } } == true
        if (!connected) service = null
        val failing = SystemClock.elapsedRealtime() < pausedUntil
        if (permitted && !connected && !failing) bind()
        _state.value = ShizukuState.of(enabled, running, installed, preV11, permitted, connected, failing)
    }

    private fun bind() {
        val now = SystemClock.elapsedRealtime()
        if (bindingSince != 0L) {
            if (now - bindingSince < BIND_TIMEOUT_MS) return // one attempt at a time
            bindFailed() // the last attempt never connected
            if (now < pausedUntil) return
        }
        bindingSince = now
        if (safely { Shizuku.bindUserService(args, connection) } == null) {
            bindingSince = 0L
            bindFailed()
        }
    }

    private fun attach(binder: IBinder?) {
        bindingSince = 0L
        val s = binder?.takeIf { safely { it.isBinderAlive } == true }?.let(IShellService.Stub::asInterface)
        when {
            s == null -> bindFailed()
            safely { s.protocol() } != ShellProtocol.VERSION -> {
                Log.i(TAG, "replacing a helper from an older build")
                release()
                bindFailed()
            }
            else -> {
                service = s
                failedBinds = 0
                pauses = 0
                failures.set(0)
            }
        }
        recompute()
    }

    private fun bindFailed() {
        if (++failedBinds >= MAX_FAILURES) pause()
    }

    /** Leaves the helper alone for a while and kills it, so the next attempt starts clean. */
    private fun pause() {
        val backoff = (FIRST_PAUSE_MS shl pauses.coerceAtMost(4)).coerceAtMost(MAX_PAUSE_MS)
        pauses++
        pausedUntil = SystemClock.elapsedRealtime() + backoff
        failedBinds = 0
        failures.set(0)
        Log.w(TAG, "shizuku helper keeps failing; pausing for ${backoff / 1000}s")
        release()
    }

    private fun release() {
        service = null
        bindingSince = 0L
        safely { Shizuku.unbindUserService(args, connection, true) }
    }

    fun requestPermission() {
        scope.launch(worker) {
            safely { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE) }
        }
    }

    private fun isInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Opens the Shizuku app so the user can start it; false when it isn't installed. */
    fun openApp(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** Connected and usable right now. */
    val ready: Boolean get() = service != null && _state.value == ShizukuState.READY

    /** Output of `sh -c command` as the shell user; null when it failed or Shizuku isn't ready. */
    suspend fun exec(command: String, timeoutMs: Long = 5_000): String? =
        call(timeoutMs + CALL_SLACK_MS) { it.exec(command, timeoutMs) }

    suspend fun readFile(path: String): String? = call(3_000) { it.readFile(path) }

    // The shell tab's commands, run as the shell user (see Running).
    suspend fun start(script: String): Int? = call(3_000) { it.start(script) }
    suspend fun read(id: Int): String? = call(3_000) { it.read(id) }
    suspend fun interrupt(id: Int, pid: Int) {
        call(3_000) { it.interrupt(id, pid) }
    }

    /** Reverse-DNS names for [ips]; IPs without a PTR record are left out. */
    suspend fun resolve(ips: List<String>): Map<String, String> =
        call(3_000 + CALL_SLACK_MS) { it.resolve(ips.joinToString("\n")) }?.lineSequence()?.mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }?.toMap().orEmpty()

    /**
     * One call into the helper. It runs on its own IO job so a helper that hangs
     * can't hold up the caller past [timeoutMs]; at most [MAX_CALLS] can be in
     * flight, so hung calls can't pile up threads either.
     */
    private suspend fun <T> call(timeoutMs: Long, block: (IShellService) -> T?): T? {
        val s = service ?: return null
        if (SystemClock.elapsedRealtime() < pausedUntil) return null
        if (!permits.tryAcquire()) {
            callFailed(IllegalStateException("helper calls are stuck"))
            return null
        }
        val job = scope.async(Dispatchers.IO) { block(s) }
        job.invokeOnCompletion { permits.release() }
        return try {
            val result = withTimeoutOrNull(timeoutMs) { job.await() }
            // A null that did come back is the helper saying "that command failed", not a broken link.
            if (result != null || job.isCompleted) failures.set(0) else callFailed(IllegalStateException("timed out"))
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // DeadObjectException, RemoteException, anything the binder throws
            if (e is RemoteException) service = null
            callFailed(e)
            null
        }
    }

    private fun callFailed(e: Exception) {
        Log.w(TAG, "shizuku helper call failed: $e")
        if (failures.incrementAndGet() >= MAX_FAILURES) {
            scope.launch(worker) {
                pause()
                recompute()
            }
        } else {
            refresh()
        }
    }

    fun close() = setEnabled(false)

    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) { // Shizuku throws when its binder is missing or has just died
        Log.w(TAG, "shizuku call failed: $e")
        null
    }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"
        /** Shizuku's own download page (Play Store and GitHub releases). */
        const val DOWNLOAD = "https://shizuku.rikka.app/download/"
        private const val TAG = "Shizuku"
        private const val REQUEST_CODE = 7301
        private const val MAX_CALLS = 3
        private const val MAX_FAILURES = 3
        private const val CALL_SLACK_MS = 2_000L
        private const val BIND_TIMEOUT_MS = 15_000L
        private const val FIRST_PAUSE_MS = 30_000L
        private const val MAX_PAUSE_MS = 5 * 60_000L
    }
}
