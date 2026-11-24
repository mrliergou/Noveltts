package app.noveltts.data.settings

import android.content.Context
import android.content.SharedPreferences
import app.noveltts.data.config.CONFIGURATION_LOCK

/** Keep large reading content out of the small settings file rewritten for every segment. */
internal fun contentPreferences(context: Context, name: String, keys: List<String>): SharedPreferences = synchronized(CONFIGURATION_LOCK) {
    val legacy = context.getSharedPreferences("multitts", Context.MODE_PRIVATE)
    val target = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val pending = keys.filter { legacy.contains(it) }
    if (pending.isNotEmpty()) {
        val editor = target.edit()
        pending.filterNot(target::contains).forEach { editor.putString(it, legacy.getString(it, null)) }
        check(editor.commit()) { "阅读内容迁移失败，原数据已保留" }
        val cleanup = legacy.edit()
        pending.forEach(cleanup::remove)
        check(cleanup.commit()) { "旧阅读内容清理失败，副本已保留" }
    }
    target
}