package com.android.launcher37.data
import com.android.launcher37.drawer.DrawerAdapter
import com.android.launcher37.data.Store
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo

/**
 * 桌面应用入口（`ACTION_MAIN + CATEGORY_LAUNCHER`）查询工具。
 *
 * 所有方法仅负责枚举/排序/标识转换，不做 UI 绑定。
 * 多入口应用各占一条记录，应用标识统一使用 `pkg/cls` 形式
 * （由 [appId] 构造）。
 *
 * 无状态，所有方法都是 `static`。
 */
object AppQuery {

    /**
     * 列举所有可启动的桌面应用入口。
     *
     * 自动排除：
     * - [Store.SELF_PKG]（避免桌面把自身列为可启动项）
     * - [excludeIds] 中已包含的 `pkg/cls` 标识（用于排除已选中的项）
     *
     * @param c          任意 Context
     * @param excludeIds 要排除的应用标识集合；`null` 表示不排除
     * @return 解析结果列表，顺序为 PackageManager 返回的原始顺序
     */
    @JvmStatic
    fun launcherEntries(c: Context, excludeIds: Set<String>?): List<ResolveInfo> {
        val out = ArrayList<ResolveInfo>()
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in c.packageManager.queryIntentActivities(main, 0)) {
            if (ri == null || ri.activityInfo == null
                || ri.activityInfo.applicationInfo == null
                || Store.SELF_PKG == ri.activityInfo.packageName
            ) {
                continue
            }
            val id = ri.activityInfo.packageName + "/" + ri.activityInfo.name
            if (excludeIds != null && id in excludeIds) continue
            out.add(ri)
        }
        return out
    }

    /**
     * 列举所有可启动的桌面应用入口，并按"用户应用优先 + label 字典序"排序。
     *
     * 排序规则：
     * 1. 用户应用（`FLAG_SYSTEM == 0`）排在系统应用之前
     * 2. 同类别内按 label 字典序（大小写不敏感）
     *
     * 排序前会预先解析 label（写入 [Store.label] 的 LRU 缓存），
     * 避免 Adapter 滚动时重复 binder 调用。
     *
     * @param c 任意 Context
     * @return 排序后的入口列表
     */
    @JvmStatic
    fun launcherEntriesSorted(c: Context): List<ResolveInfo> {
        val list: MutableList<ResolveInfo> = launcherEntries(c, null).toMutableList()
        for (ri in list) {
            Store.label(c, ri.activityInfo.packageName + "/" + ri.activityInfo.name)
        }
        list.sortWith(Comparator<ResolveInfo> { a, b ->
            val aInfo = a.activityInfo.applicationInfo
            val bInfo = b.activityInfo.applicationInfo
            val aUser = (aInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            val bUser = (bInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            if (aUser != bUser) return@Comparator if (aUser) -1 else 1
            val aId = a.activityInfo.packageName + "/" + a.activityInfo.name
            val bId = b.activityInfo.packageName + "/" + b.activityInfo.name
            val aLabel: String = Store.label(c, aId)
            val bLabel: String = Store.label(c, bId)
            String.CASE_INSENSITIVE_ORDER.compare(aLabel, bLabel)
        })
        return list
    }

    /**
     * 从 [ResolveInfo] 构造应用标识 `pkg/cls`。
     *
     * 该形式与 [Store.pkgOf] 互逆：`pkgOf(appId(ri)) == ri.activityInfo.packageName`。
     *
     * @param ri 解析结果
     * @return 形如 `com.example/.MainActivity` 的标识
     */
    @JvmStatic
    fun appId(ri: ResolveInfo): String = ri.activityInfo.packageName + "/" + ri.activityInfo.name

    /**
     * 按用户设置过滤抽屉应用列表：移除隐藏应用（[Store.drawerHidden]）。
     * 排序统一由 [DrawerAdapter.applyUserOrder] 处理（含功能项/分屏/应用全集）。
     */
    @JvmStatic
    fun applyDrawerPrefs(c: Context, list: List<ResolveInfo>): List<ResolveInfo> {
        val hidden = Store.drawerHidden(c)
        if (hidden.isEmpty()) return list
        return list.filter { appId(it) !in hidden }
    }
}
