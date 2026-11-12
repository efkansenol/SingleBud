package app.singlebud

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Headphone-like output devices and the per-device rule for automatic mode.
 * Every device gets mono unless the user marked it as a stereo device.
 * Devices are identified by the name Android reports for them.
 */
object Devices {

    private const val PREF_KNOWN = "known_devices"
    private const val PREF_STEREO = "stereo_devices"
    private const val WIRED = "__wired__"

    private val WIRED_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    )
    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        26 // TYPE_BLE_HEADSET (API 31)
    )
    private const val TYPE_USB_HEADSET = 22 // API 26

    /** Keys of the headphone-like devices connected right now. */
    fun connectedKeys(ctx: Context): Set<String> {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keys = LinkedHashSet<String>()
        for (device in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val name = device.productName?.toString()?.trim().orEmpty()
            when (device.type) {
                in WIRED_TYPES -> keys.add(WIRED)
                in BLUETOOTH_TYPES -> keys.add(name.ifEmpty { "Bluetooth" })
                TYPE_USB_HEADSET -> keys.add(name.ifEmpty { "USB" })
            }
        }
        return keys
    }

    fun knownKeys(ctx: Context): Set<String> =
        MonoController.prefs(ctx).getStringSet(PREF_KNOWN, emptySet())!!.toSet()

    /** Adds devices to the list shown in the app. */
    fun remember(ctx: Context, keys: Set<String>) {
        val known = knownKeys(ctx)
        if (!known.containsAll(keys)) {
            MonoController.prefs(ctx).edit().putStringSet(PREF_KNOWN, known + keys).apply()
        }
    }

    private fun stereoKeys(ctx: Context): Set<String> =
        MonoController.prefs(ctx).getStringSet(PREF_STEREO, emptySet())!!.toSet()

    fun isStereo(ctx: Context, key: String): Boolean = key in stereoKeys(ctx)

    fun setStereo(ctx: Context, key: String, stereo: Boolean) {
        val current = stereoKeys(ctx)
        val updated = if (stereo) current + key else current - key
        MonoController.prefs(ctx).edit().putStringSet(PREF_STEREO, updated).apply()
    }

    fun label(ctx: Context, key: String): String =
        if (key == WIRED) ctx.getString(R.string.device_wired) else key

    /** Mono is wanted when at least one connected device is not marked stereo. */
    fun wantsMono(ctx: Context, connected: Set<String>): Boolean =
        connected.any { !isStereo(ctx, it) }
}
