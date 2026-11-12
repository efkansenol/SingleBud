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
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Changes Android's system-wide audio settings in Settings.System:
 *  - "master_mono":    downmix all output to mono
 *  - "master_balance": left/right balance, -1.0 (left) to 1.0 (right), Android 10+
 * AudioService watches both, so changes apply to every app with no added latency.
 *
 * Ordinary apps may not write these keys, so three routes are tried in order:
 *  1. DIRECT  - works when the app targets API 22 (the "legacy" build).
 *  2. SHIZUKU - runs `settings put` with shell rights through Shizuku.
 *  3. ROOT    - runs `settings put` through su.
 */
object MonoController {

    private const val KEY_MONO = "master_mono"
    private const val KEY_BALANCE = "master_balance"
    private const val PREFS = "singlebud"
    private const val PREF_LAST_MONO = "last_mono"
    private const val PREF_LAST_BALANCE = "last_balance"
    const val PREF_AUTO = "auto"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class Method { DIRECT, SHIZUKU, ROOT, NONE }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val monoUri: Uri get() = Settings.System.getUriFor(KEY_MONO)

    val balanceSupported: Boolean get() = Build.VERSION.SDK_INT >= 29

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMono(ctx: Context): Boolean = try {
        Settings.System.getInt(ctx.contentResolver, KEY_MONO, 0) == 1
    } catch (e: Exception) {
        prefs(ctx).getBoolean(PREF_LAST_MONO, false)
    }

    /** Current balance from -1.0 (all left) to 1.0 (all right). */
    fun getBalance(ctx: Context): Float = try {
        Settings.System.getFloat(ctx.contentResolver, KEY_BALANCE, 0f)
    } catch (e: Exception) {
        prefs(ctx).getFloat(PREF_LAST_BALANCE, 0f)
    }.coerceIn(-1f, 1f)

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
            val ok = write(app, KEY_MONO, if (on) "1" else "0")
            if (ok) prefs(app).edit().putBoolean(PREF_LAST_MONO, on).apply()
            main.post { done(ok) }
        }
    }

    /** [balance] runs from -1.0 (all left) to 1.0 (all right). */
    fun setBalanceAsync(ctx: Context, balance: Float, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val clamped = balance.coerceIn(-1f, 1f)
        val text = if (clamped == 0f) "0.0" else String.format(Locale.US, "%.2f", clamped)
        io.execute {
            val ok = write(app, KEY_BALANCE, text)
            if (ok) prefs(app).edit().putFloat(PREF_LAST_BALANCE, clamped).apply()
            main.post { done(ok) }
        }
    }

    private fun write(ctx: Context, key: String, value: String): Boolean =
        writeDirect(ctx, key, value) || writeShizuku(key, value) || writeRoot(key, value)

    private fun writeDirect(ctx: Context, key: String, value: String): Boolean {
        if (!canWriteDirect(ctx)) return false
        return try {
            Settings.System.putString(ctx.contentResolver, key, value)
        } catch (e: Exception) {
            false
        }
    }

    private fun writeShizuku(key: String, value: String): Boolean {
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
            val cmd = arrayOf("settings", "put", "system", key, value)
            val process = m.invoke(null, cmd, null, null) as Process
            waitFor(process)
        } catch (t: Throwable) {
            false
        }
    }

    private fun writeRoot(key: String, value: String): Boolean {
        if (!hasSu()) return false
        return try {
            val process = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "settings put system $key $value"))
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
