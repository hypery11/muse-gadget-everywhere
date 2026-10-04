package ai.muse.gadgeteverywhere

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** Restart the service at boot, but only once the device has paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val paired = File(context.filesDir, "musegadget/pairing.json").exists()
        if (paired) GadgetService.start(context)
    }
}
