package ai.muse.gadgeteverywhere

import android.content.Context
import org.json.JSONObject

/** Private local settings. Only the settings screen can enable trusted actions. */
object AppSettings {
    fun read(context: Context): JSONObject = JSONObject(
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("json", "{}") ?: "{}",
    )
    fun write(context: Context, value: JSONObject) {
        check(context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("json", value.toString()).commit()) { "Could not save settings" }
    }
}
