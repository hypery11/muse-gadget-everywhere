package ai.muse.gadgeteverywhere

import android.app.Activity
import com.chaquo.python.Python
import org.json.JSONObject
import java.util.concurrent.Executors

/** UI never blocks on Python or on native commands which need the main looper. */
object LocalClient {
    private val workers = Executors.newFixedThreadPool(2)
    fun execute(activity: Activity, command: String, params: JSONObject = JSONObject(), done: (JSONObject) -> Unit) {
        workers.execute {
            val result = try {
                Startup.ensurePython(activity.applicationContext)
                JSONObject(Python.getInstance().getModule("androidtv.service")
                    .callAttr("invoke", command, params.toString()).toString())
            } catch (_: Exception) {
                JSONObject().put("ok", false).put("error", "Command failed; check service status")
            }
            activity.runOnUiThread { if (!activity.isDestroyed) done(result) }
        }
    }
}
