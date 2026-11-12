package app.singlebud

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Turns Android's system-wide mono audio setting (Settings.System "master_mono")
 * on and off. AudioService watches that setting and downmixes all output, so the
 * change applies to every app with no added latency.
 *
 * Ordinary apps may not write this key, so three routes are tried in order:
 *  1. DIRECT  - works when the app targets API 22 (the "legacy" build).
 *  2. SHIZUKU - runs `settings put` with shell rights through Shizuku.
 *  3. ROOT    - runs `settings put` through su.
 */
object MonoController {

    private const val KEY = "master_mono"
    private const val PREFS = "singlebud"
    private const val PREF_LAST = "last_mono"
    const val PREF_AUTO = "auto"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class Method { DIRECT, SHIZUKU, ROOT, NONE }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val settingUri: Uri get() = Settings.System.getUriFor(KEY)

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMono(ctx: Context): Boolean = try {
        Settings.System.getInt(ctx.contentResolver, KEY, 0) == 1
    } catch (e: Exception) {
        prefs(ctx).getBoolean(PREF_LAST, false)
    }

    fun canWriteDirect(ctx: Context): Boolean =
        ctx.applicationInfo.targetSdkVersion <= 22 && Settings.System.canWrite(ctx)

    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun hasSu(): Boolean = try {
        (System.getenv("PATH") ?: "").split(":").any { it.isNotEmpty() && File(it, "su").exists() }
    } catch (t: Throwable) {
        false
    }

    fun method(ctx: Context): Method = when {
        canWriteDirect(ctx) -> Method.DIRECT
        shizukuGranted() -> Method.SHIZUKU
        hasSu() -> Method.ROOT
        else -> Method.NONE
    }

    /** Runs off the main thread; [done] is called on the main thread with the result. */
    fun setMonoAsync(ctx: Context, on: Boolean, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val ok = setMono(app, on)
            main.post { done(ok) }
        }
    }

    private fun setMono(ctx: Context, on: Boolean): Boolean {
        val value = if (on) "1" else "0"
        val ok = writeDirect(ctx, on) || writeShizuku(value) || writeRoot(value)
        if (ok) prefs(ctx).edit().putBoolean(PREF_LAST, on).apply()
        return ok
    }

    private fun writeDirect(ctx: Context, on: Boolean): Boolean {
        if (!canWriteDirect(ctx)) return false
        return try {
            Settings.System.putInt(ctx.contentResolver, KEY, if (on) 1 else 0)
        } catch (e: Exception) {
            false
        }
    }

    private fun writeShizuku(value: String): Boolean {
        if (!shizukuGranted()) return false
        return try {
            // newProcess is private in recent Shizuku API versions; reflection is
            // the usual way to run a one-off shell command.
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val cmd = arrayOf("settings", "put", "system", KEY, value)
            val process = m.invoke(null, cmd, null, null) as Process
            waitFor(process)
        } catch (t: Throwable) {
            false
        }
    }

    private fun writeRoot(value: String): Boolean {
        if (!hasSu()) return false
        return try {
            val process = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "settings put system $KEY $value"))
            waitFor(process)
        } catch (t: Throwable) {
            false
        }
    }

    private fun waitFor(process: Process): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            if (process.waitFor(20, TimeUnit.SECONDS)) {
                process.exitValue() == 0
            } else {
                process.destroy()
                false
            }
        } else {
            process.waitFor() == 0
        }
    }
}
