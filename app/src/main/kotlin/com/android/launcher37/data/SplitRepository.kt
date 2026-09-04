package com.android.launcher37.data
import com.android.launcher37.data.Store
import com.android.launcher37.util.Prefs
import android.content.Context
import android.content.SharedPreferences

/**
 * 分屏项仓储（持久化 `split_items`）。
 *
 * 存储格式：`pkg/cls1|pkg/cls2` 列表，每条一行（`\n` 分隔）。
 * 加载时自动剔除任一侧应用未安装的条目并回写。
 */
object SplitRepository {

    private const val KEY = "split_items"

    /**
     * 加载所有分屏项。已安装校验失败的会自动剔除并回写。
     */
    @JvmStatic
    fun load(c: Context): MutableList<Array<String>> {
        val json = prefs(c).getString(KEY, "")
        val out = ArrayList<Array<String>>()
        if (json.isNullOrEmpty()) return out
        var changed = false
        for (s in json.split("\n".toRegex())) {
            val sep = s.indexOf('|')
            if (sep > 0) {
                val left = s.substring(0, sep)
                val right = s.substring(sep + 1)
                if (Store.installed(c, Store.pkgOf(left)) && Store.installed(c, Store.pkgOf(right))) {
                    out.add(arrayOf(left, right))
                } else {
                    changed = true
                }
            }
        }
        if (changed) save(c, out)
        return out
    }

    /** 取指定下标的分屏项；越界返回 null */
    @JvmStatic
    fun get(c: Context, index: Int): Array<String>? {
        val items = load(c)
        return if (index in 0 until items.size) items[index] else null
    }

    /** 追加分屏项（自动去重 + 排序后稳定） */
    @JvmStatic
    fun add(c: Context, left: String, right: String) {
        val items = load(c).toMutableList()
        val entry = arrayOf(left, right)
        // 去重：完全相同的 left|right 跳过
        if (items.none { it[0] == left && it[1] == right }) {
            items.add(entry)
            save(c, items)
        }
    }

    /** 按下标删除分屏项 */
    @JvmStatic
    fun remove(c: Context, index: Int) {
        val items = load(c)
        if (index !in 0 until items.size) return
        items.removeAt(index)
        save(c, items)
    }

    private fun save(c: Context, items: List<Array<String>>) {
        val sb = StringBuilder()
        for (i in items.indices) {
            if (i > 0) sb.append("\n")
            sb.append(items[i][0]).append("|").append(items[i][1])
        }
        prefs(c).edit().putString(KEY, sb.toString()).apply()
    }

    private fun prefs(c: Context): SharedPreferences = Prefs.of(c)
}
