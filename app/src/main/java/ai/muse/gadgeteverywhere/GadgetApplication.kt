package ai.muse.gadgeteverywhere

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

class GadgetApplication : Application(), Application.ActivityLifecycleCallbacks {
    companion object {
        @Volatile var visibleActivity = WeakReference<Activity>(null)
    }
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }
    override fun onActivityResumed(activity: Activity) { visibleActivity = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) {
        if (visibleActivity.get() === activity) visibleActivity.clear()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
