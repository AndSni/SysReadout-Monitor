package com.asnidev.sysreadoutmonitor.monitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.asnidev.sysreadoutmonitor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

enum class ShizukuState(val label: String) {
    NOT_INSTALLED("not installed"),
    NOT_RUNNING("installed, not running"),
    UNSUPPORTED("too old, update Shizuku"),
    NO_PERMISSION("needs permission"),
    CONNECTING("connecting…"),
    READY("connected"),
}

/**
 * Optional shell-level access through Shizuku. Everything degrades to "not
 * available" when Shizuku is missing, stopped or not granted. Shizuku can die
 * at any moment (a reboot, the user stopping it, its process killed), and its
 * API then throws from calls that looked safe a moment earlier, so every call
 * into it is guarded: a dead Shizuku means NOT_RUNNING, never a crash.
 */
class ShizukuBridge(private val context: Context) {

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state

    @Volatile private var service: IShellService? = null
    @Volatile private var binding = false

    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .daemon(false)
        .processNameSuffix("monitor")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = binder?.takeIf { it.pingBinder() }?.let(IShellService.Stub::asInterface)
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refresh()
        }
    }

    private val onBinder = Shizuku.OnBinderReceivedListener { refresh() }
    private val onDead = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        refresh()
    }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(onBinder)
            Shizuku.addBinderDeadListener(onDead)
            Shizuku.addRequestPermissionResultListener(onPermission)
        }
        refresh()
    }

    fun refresh() {
        _state.value = try {
            when {
                !installed() -> ShizukuState.NOT_INSTALLED
                !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
                Shizuku.isPreV11() -> ShizukuState.UNSUPPORTED
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
                service != null -> ShizukuState.READY
                else -> {
                    bind()
                    ShizukuState.CONNECTING
                }
            }
        } catch (e: RuntimeException) {
            // The binder died between the ping and the call (IllegalStateException, or a RemoteException rethrown).
            service = null
            binding = false
            ShizukuState.NOT_RUNNING
        }
    }

    private fun bind() {
        if (binding) return
        binding = true
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { binding = false }
    }

    fun requestPermission() {
        runCatching { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { refresh() }
    }

    private fun installed(): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Opens the Shizuku app so the user can start it. */
    fun openApp(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    val ready: Boolean get() = service != null

    suspend fun exec(command: String, timeoutMs: Long = 5_000): String? = call { it.exec(command, timeoutMs) }

    suspend fun readFile(path: String): String? = call { it.readFile(path) }

    /** Reverse-DNS names for [ips]; IPs without a PTR record are left out. */
    suspend fun resolve(ips: List<String>): Map<String, String> =
        call { it.resolve(ips.joinToString("\n")) }?.lineSequence()?.mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }?.toMap().orEmpty()

    // The shell tab's commands, run as the shell user (see Running).
    suspend fun start(script: String): Int? = call { it.start(script) }
    suspend fun read(id: Int): String? = call { it.read(id) }
    suspend fun interrupt(id: Int, pid: Int) {
        call { it.interrupt(id, pid) }
    }

    private suspend fun <T> call(block: (IShellService) -> T): T? = withContext(Dispatchers.IO) {
        val s = service ?: return@withContext null
        try {
            block(s)
        } catch (e: Exception) { // DeadObjectException, RemoteException
            service = null
            refresh()
            null
        }
    }

    fun close() {
        runCatching {
            Shizuku.removeBinderReceivedListener(onBinder)
            Shizuku.removeBinderDeadListener(onDead)
            Shizuku.removeRequestPermissionResultListener(onPermission)
        }
        if (service != null) runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"
        /** Shizuku's own download page (Play Store and GitHub releases). */
        const val DOWNLOAD = "https://shizuku.rikka.app/download/"
        private const val REQUEST_CODE = 7301
    }
}
