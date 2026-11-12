package app.singlebud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts automatic mode after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (MonoController.prefs(context).getBoolean(MonoController.PREF_AUTO, false)) {
            AutoMonoService.start(context)
        }
    }
}
