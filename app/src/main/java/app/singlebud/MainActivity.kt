package app.singlebud

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var monoSwitch: Switch
    private lateinit var monoStatus: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var methodStatus: TextView
    private lateinit var setupHelp: TextView
    private lateinit var shizukuButton: Button
    private lateinit var accessibilityButton: Button

    /** True while the switches are being set from code, so listeners ignore it. */
    private var binding = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        monoSwitch = findViewById(R.id.mono_switch)
        monoStatus = findViewById(R.id.mono_status)
        autoSwitch = findViewById(R.id.auto_switch)
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
        contentResolver.registerContentObserver(MonoController.settingUri, false, observer)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
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

        binding = true
        monoSwitch.isChecked = mono
        autoSwitch.isChecked =
            MonoController.prefs(this).getBoolean(MonoController.PREF_AUTO, false)
        binding = false

        monoStatus.setText(if (mono) R.string.status_mono else R.string.status_stereo)
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
    }
}
