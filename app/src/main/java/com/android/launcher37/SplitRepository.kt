package com.android.launcher37

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

    /** 追加分屏项 */
    @JvmStatic
    fun add(c: Context, left: String, right: String) {
        val sp = prefs(c)
        val cur = sp.getString(KEY, "")
        val entry = "$left|$right"
        val json = if (cur.isNullOrEmpty()) entry else "$cur\n$entry"
        sp.edit().putString(KEY, json).apply()
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
