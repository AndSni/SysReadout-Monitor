package com.asnidev.sysreadoutmonitor.log

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.SystemClock

/** Build properties from `getprop`, re-read at most once a minute (a few change, e.g. gsm.*). */
object SystemProps {
    private var props: Map<String, String> = emptyMap()
    private var readAt = 0L
    private val LINE = Regex("^\\[(.+?)]: \\[(.*)]$")

    @Synchronized
    fun get(): Map<String, String> {
        if (props.isEmpty() || SystemClock.elapsedRealtime() - readAt > 60_000) {
            props = runCatching {
                Runtime.getRuntime().exec(arrayOf("getprop")).inputStream.bufferedReader().useLines { lines ->
                    lines.mapNotNull { LINE.find(it)?.destructured?.let { (k, v) -> k to v } }.toMap()
                }
            }.getOrDefault(props)
            readAt = SystemClock.elapsedRealtime()
        }
        return props
    }

    operator fun get(key: String): String? = get()[key]?.takeIf { it.isNotBlank() }
}

/** GPU name and API versions. Needs a throwaway EGL context, so it's read once and cached. */
object GpuInfo {
    @Volatile private var cached: String? = null

    fun describe(context: Context): String? {
        cached?.let { return it }
        val gl = runCatching { glStrings() }.getOrNull()
        val vulkan = context.packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
            ?.let { v -> "vk ${v shr 22}.${(v shr 12) and 0x3FF}" }
        val version = gl?.second?.let { Regex("OpenGL ES (\\d+\\.\\d+)").find(it)?.groupValues?.get(1) }?.let { "GLES $it" }
        return listOfNotNull(gl?.first, version, vulkan).joinToString(" · ").ifEmpty { null }?.also { cached = it }
    }

    /** (GL_RENDERER, GL_VERSION) from an offscreen 1×1 pbuffer context. */
    private fun glStrings(): Pair<String, String>? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (!EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) return null
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, IntArray(1), 0)) return null
            val config = configs[0] ?: return null
            val ctx = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) return null
                return (GLES20.glGetString(GLES20.GL_RENDERER) ?: return null) to (GLES20.glGetString(GLES20.GL_VERSION) ?: "")
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }
}
