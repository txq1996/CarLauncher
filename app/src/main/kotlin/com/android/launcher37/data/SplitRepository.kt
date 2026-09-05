package com.android.launcher37.data
import com.android.launcher37.data.Store
import com.android.launcher37.util.Prefs
import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 分屏项仓储（持久化 `split_items`）。
 *
 * 存储格式：`id|left|right`，每条一行（`\n` 分隔）。id 为稳定标识，删除任意一条
 * 不会使抽屉排序/隐藏记录（tag = `split:<id>`）错位到其他分屏上。
 * 加载时自动剔除任一侧应用未安装的条目并回写。
 */
object SplitRepository {

    private const val KEY = "split_items"

    /** 一条分屏项：id 稳定（持久化后不变），left/right 为 `pkg/cls` 标识 */
    class Entry(val id: String, val left: String, val right: String)

    /**
     * 加载所有分屏项。格式非法或已卸载的条目剔除，有变化时回写。
     */
    @JvmStatic
    fun load(c: Context): MutableList<Entry> {
        val json = prefs(c).getString(KEY, "")
        val out = ArrayList<Entry>()
        if (json.isNullOrEmpty()) return out
        var changed = false
        for (s in json.split("\n".toRegex())) {
            // pkg/cls 不含 '|'，按 '|' 切成 3 段即合法条目
            val parts = s.split('|')
            if (parts.size != 3 || parts.any { it.isEmpty() }) {
                changed = true
                continue
            }
            val e = Entry(parts[0], parts[1], parts[2])
            if (Store.installed(c, Store.pkgOf(e.left)) && Store.installed(c, Store.pkgOf(e.right))) {
                out.add(e)
            } else {
                changed = true
            }
        }
        if (changed) save(c, out)
        return out
    }

    /** 按稳定 id 取分屏项；不存在返回 null */
    @JvmStatic
    fun get(c: Context, id: String): Entry? {
        if (id.isEmpty()) return null
        return load(c).firstOrNull { it.id == id }
    }

    /** 追加分屏项（自动去重 + 排序后稳定） */
    @JvmStatic
    fun add(c: Context, left: String, right: String) {
        val items = load(c).toMutableList()
        // 去重：完全相同的 left|right 跳过
        if (items.none { it.left == left && it.right == right }) {
            items.add(Entry(newId(), left, right))
            save(c, items)
        }
    }

    /** 按 id 删除分屏项 */
    @JvmStatic
    fun remove(c: Context, id: String) {
        val items = load(c)
        val filtered = items.filter { it.id != id }
        if (filtered.size != items.size) save(c, filtered)
    }

    /** 稳定 id：随机 10 位 hex，不随删除/新增的数组下标漂移 */
    private fun newId(): String =
        "s" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun save(c: Context, items: List<Entry>) {
        val sb = StringBuilder()
        for ((i, e) in items.withIndex()) {
            if (i > 0) sb.append("\n")
            sb.append(e.id).append("|").append(e.left).append("|").append(e.right)
        }
        prefs(c).edit().putString(KEY, sb.toString()).apply()
    }

    private fun prefs(c: Context): SharedPreferences = Prefs.of(c)
}
