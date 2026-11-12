package app.noveltts.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log

/** 设备上已安装的一个系统 TTS 引擎。 */
data class TtsEngineInfo(
    val packageName: String,
    val serviceName: String,
    val label: String,
)

/**
 * 枚举设备上可用的系统 TTS 引擎，并挑选出一个可以委托的后端。
 *
 * 复刻版当前阶段不内置音源，而是把系统调用转发给别的已安装引擎，因此这里必须
 * 排除自己，避免自委托形成死循环。
 */
object EngineCatalog {

    private const val TAG = "EngineCatalog"

    fun installedEngines(context: Context): List<TtsEngineInfo> {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val resolved = runCatching {
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }.getOrElse {
            Log.w(TAG, "queryIntentServices failed", it)
            return emptyList()
        }
        return resolved.mapNotNull { info ->
            val pkg = info.serviceInfo?.packageName ?: return@mapNotNull null
            val name = info.serviceInfo.name ?: return@mapNotNull null
            TtsEngineInfo(pkg, name, applicationLabel(context, pkg))
        }.distinctBy { it.packageName }
    }

    /**
     * 当前的系统默认引擎包名，取不到时返回 null。
     *
     * 用 Settings.Secure 读取而不是 `TextToSpeech.getDefaultEngine()`：后者是实例方法，
     * 为了读一个名字先起一个 TTS 连接代价太大。
     */
    fun defaultEnginePackage(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 选出委托后端：优先系统默认引擎，其次任意一个非本应用的引擎。
     *
     * 如果用户把默认引擎设置成了复刻版自己，这里会自动退到下一个可用引擎。
     */
    fun resolveDelegate(context: Context): TtsEngineInfo? {
        val candidates = installedEngines(context).filter { it.packageName != context.packageName }
        if (candidates.isEmpty()) {
            Log.w(TAG, "no delegate TTS engine installed")
            return null
        }
        val preferred = defaultEnginePackage(context)
        return candidates.firstOrNull { it.packageName == preferred } ?: candidates.first()
    }

    private fun applicationLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
