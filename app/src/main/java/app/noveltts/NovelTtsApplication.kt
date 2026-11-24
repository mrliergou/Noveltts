package app.noveltts

import android.app.Application
import android.app.ActivityManager
import android.os.Process
import app.noveltts.domain.RuleRuntime
import app.noveltts.rules.RulesProcessClient

class NovelTtsApplication : Application(), androidx.lifecycle.ViewModelStoreOwner {
    // App playback and its state survive Activity destruction and share/send task reuse.
    override val viewModelStore by lazy { androidx.lifecycle.ViewModelStore() }
    override fun onCreate() {
        super.onCreate()
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        if (name?.endsWith(":rules") == true) return
        app.noveltts.data.backup.ConfigurationRecovery.recover(this)
        val client = RulesProcessClient(this)
        RuleRuntime.remote = client::execute
    }
}