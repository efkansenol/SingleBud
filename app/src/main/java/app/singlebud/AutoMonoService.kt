package app.singlebud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Keeps mono audio in step with headphones: mono while a headset is connected,
 * stereo again once it is gone (so the phone speakers stay stereo).
 */
class AutoMonoService : Service() {

    private lateinit var audio: AudioManager

    /** Last headset state that was applied successfully; null means "retry". */
    private var applied: Boolean? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = evaluate()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = evaluate()
    }

    // Shizuku connects a moment after the process starts; try again when it does.
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        applied = null
        evaluate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        showForeground(getString(R.string.notif_waiting))
        audio.registerAudioDeviceCallback(deviceCallback, null)
        try {
            Shizuku.addBinderReceivedListener(shizukuBinderListener)
        } catch (t: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        evaluate()
        return START_STICKY
    }

    override fun onDestroy() {
        audio.unregisterAudioDeviceCallback(deviceCallback)
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }

    private fun headsetConnected(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }

    private fun evaluate() {
        val connected = headsetConnected()
        if (connected == applied) return
        applied = connected
        MonoController.setMonoAsync(this, connected) { ok ->
            if (!ok) applied = null
            val text = when {
                !ok -> R.string.notif_failed
                connected -> R.string.notif_mono
                else -> R.string.notif_stereo
            }
            notificationManager().notify(NOTIFICATION_ID, buildNotification(getString(text)))
        }
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_mono)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun showForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "auto"
        private const val NOTIFICATION_ID = 1

        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            22, // TYPE_USB_HEADSET (API 26)
            26  // TYPE_BLE_HEADSET (API 31)
        )

        fun start(ctx: Context) {
            val intent = Intent(ctx, AutoMonoService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                // The system refused a background start; it will start next time the app opens.
            }
        }
    }
}
