package app.singlebud

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : Activity() {

    private lateinit var monoSwitch: Switch
    private lateinit var monoStatus: TextView
    private lateinit var testButton: Button
    private lateinit var testStatus: TextView
    private lateinit var balanceSection: View
    private lateinit var balanceSeek: SeekBar
    private lateinit var balanceCenter: Button
    private lateinit var autoSwitch: Switch
    private lateinit var devicesSection: View
    private lateinit var devicesList: LinearLayout
    private lateinit var devicesEmpty: TextView
    private lateinit var methodStatus: TextView
    private lateinit var setupHelp: TextView
    private lateinit var shizukuButton: Button
    private lateinit var accessibilityButton: Button

    private lateinit var audio: AudioManager

    /** True while controls are being set from code, so listeners ignore it. */
    private var binding = false
    private var seeking = false

    private val testHandler = Handler(Looper.getMainLooper())
    private var testTrack: AudioTrack? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        monoSwitch = findViewById(R.id.mono_switch)
        monoStatus = findViewById(R.id.mono_status)
        testButton = findViewById(R.id.test_button)
        testStatus = findViewById(R.id.test_status)
        balanceSection = findViewById(R.id.balance_section)
        balanceSeek = findViewById(R.id.balance_seek)
        balanceCenter = findViewById(R.id.balance_center)
        autoSwitch = findViewById(R.id.auto_switch)
        devicesSection = findViewById(R.id.devices_section)
        devicesList = findViewById(R.id.devices_list)
        devicesEmpty = findViewById(R.id.devices_empty)
        methodStatus = findViewById(R.id.method_status)
        setupHelp = findViewById(R.id.setup_help)
        shizukuButton = findViewById(R.id.shizuku_button)
        accessibilityButton = findViewById(R.id.accessibility_button)

        monoSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            monoSwitch.isEnabled = false
            MonoController.setMonoAsync(this, checked) { ok ->
                monoSwitch.isEnabled = true
                if (!ok) toast(R.string.toast_failed)
                refresh()
            }
        }

        testButton.setOnClickListener { playTest() }

        balanceSection.visibility = if (MonoController.balanceSupported) View.VISIBLE else View.GONE
        balanceSeek.max = BALANCE_STEPS * 2
        balanceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seeking = false
                applyBalance((seekBar.progress - BALANCE_STEPS) / BALANCE_STEPS.toFloat())
            }
        })
        balanceCenter.setOnClickListener { applyBalance(0f) }

        autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            MonoController.prefs(this).edit()
                .putBoolean(MonoController.PREF_AUTO, checked).apply()
            if (checked) {
                requestNotificationPermission()
                AutoMonoService.start(this)
            } else {
                stopService(Intent(this, AutoMonoService::class.java))
            }
            refresh()
        }

        shizukuButton.setOnClickListener { onShizukuClicked() }
        accessibilityButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        } catch (t: Throwable) {
            // Shizuku not available; the other methods still work.
        }

        if (MonoController.prefs(this).getBoolean(MonoController.PREF_AUTO, false)) {
            AutoMonoService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(MonoController.monoUri, false, observer)
        audio.registerAudioDeviceCallback(deviceCallback, null)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        stopTest()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }

    private fun refresh() {
        val mono = MonoController.isMono(this)
        val method = MonoController.method(this)
        val auto = MonoController.prefs(this).getBoolean(MonoController.PREF_AUTO, false)

        binding = true
        monoSwitch.isChecked = mono
        autoSwitch.isChecked = auto
        if (!seeking) {
            balanceSeek.progress =
                (MonoController.getBalance(this) * BALANCE_STEPS).roundToInt() + BALANCE_STEPS
        }
        binding = false

        monoStatus.setText(if (mono) R.string.status_mono else R.string.status_stereo)
        refreshDevices(auto)

        methodStatus.setText(
            when (method) {
                MonoController.Method.DIRECT -> R.string.method_direct
                MonoController.Method.SHIZUKU -> R.string.method_shizuku
                MonoController.Method.ROOT -> R.string.method_root
                MonoController.Method.NONE -> R.string.method_none
            }
        )

        val needsSetup = method == MonoController.Method.NONE
        setupHelp.visibility = if (needsSetup) View.VISIBLE else View.GONE
        accessibilityButton.visibility = if (needsSetup) View.VISIBLE else View.GONE
        shizukuButton.visibility =
            if (method == MonoController.Method.NONE || method == MonoController.Method.ROOT) {
                View.VISIBLE
            } else {
                View.GONE
            }
        shizukuButton.setText(
            if (MonoController.shizukuRunning()) R.string.btn_shizuku_grant
            else R.string.btn_shizuku_open
        )
    }

    // ---- Per-device rules -------------------------------------------------

    private fun refreshDevices(auto: Boolean) {
        devicesSection.visibility = if (auto) View.VISIBLE else View.GONE
        if (!auto) return

        val connected = Devices.connectedKeys(this)
        Devices.remember(this, connected)
        val known = Devices.knownKeys(this).sortedBy { Devices.label(this, it).lowercase() }

        devicesEmpty.visibility = if (known.isEmpty()) View.VISIBLE else View.GONE
        devicesList.removeAllViews()
        val minHeight = (48 * resources.displayMetrics.density).toInt()
        for (key in known) {
            val label = Devices.label(this, key)
            val box = CheckBox(this)
            box.text = if (key in connected) getString(R.string.device_connected, label) else label
            box.textSize = 16f
            box.minimumHeight = minHeight
            box.isChecked = !Devices.isStereo(this, key)
            box.setOnCheckedChangeListener { _, checked ->
                Devices.setStereo(this, key, !checked)
                AutoMonoService.start(this) // re-check the rules right away
            }
            devicesList.addView(box)
        }
    }

    // ---- Balance ----------------------------------------------------------

    private fun applyBalance(balance: Float) {
        MonoController.setBalanceAsync(this, balance) { ok ->
            if (!ok) toast(R.string.toast_balance_failed)
            refresh()
        }
    }

    // ---- Left / right test ------------------------------------------------

    /** Plays a low tone on the left channel, then a higher tone on the right. */
    private fun playTest() {
        if (testTrack != null) return
        try {
            val rate = 44100
            val tone = rate * 7 / 10
            val gap = rate / 4
            val frames = tone * 2 + gap
            val data = ShortArray(frames * 2)
            val fade = rate / 50

            fun fill(startFrame: Int, channel: Int, freq: Double) {
                for (i in 0 until tone) {
                    val envelope = minOf(1.0, i / fade.toDouble(), (tone - 1 - i) / fade.toDouble())
                    val sample = sin(2 * PI * freq * i / rate) * envelope * 0.5 * Short.MAX_VALUE
                    data[(startFrame + i) * 2 + channel] = sample.toInt().toShort()
                }
            }
            fill(0, 0, 330.0)
            fill(tone + gap, 1, 660.0)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.play()
            testTrack = track

            testButton.isEnabled = false
            testStatus.setText(R.string.test_left)
            testHandler.postDelayed(
                { testStatus.setText(R.string.test_right) }, (tone + gap) * 1000L / rate
            )
            testHandler.postDelayed({ stopTest() }, frames * 1000L / rate + 200)
        } catch (e: Exception) {
            stopTest()
            toast(R.string.toast_test_failed)
        }
    }

    private fun stopTest() {
        testHandler.removeCallbacksAndMessages(null)
        try {
            testTrack?.release()
        } catch (e: Exception) {
        }
        testTrack = null
        testButton.isEnabled = true
        testStatus.setText(R.string.test_hint)
    }

    // ---- Setup ------------------------------------------------------------

    private fun onShizukuClicked() {
        if (MonoController.shizukuRunning()) {
            try {
                if (Shizuku.isPreV11()) {
                    toast(R.string.toast_shizuku_old)
                } else {
                    Shizuku.requestPermission(REQUEST_SHIZUKU)
                }
            } catch (t: Throwable) {
                toast(R.string.toast_failed)
            }
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(MonoController.SHIZUKU_PACKAGE)
        try {
            if (launch != null) {
                toast(R.string.toast_shizuku_start)
                startActivity(launch)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            }
        } catch (e: Exception) {
            toast(R.string.toast_failed)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQUEST_SHIZUKU = 1
        const val REQUEST_NOTIFICATIONS = 2

        /** Slider steps on each side of the centre (0.1 per step). */
        const val BALANCE_STEPS = 10
    }
}
