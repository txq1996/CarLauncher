package com.android.launcher37.home.widget
import com.android.launcher37.R
import com.android.launcher37.util.HoloPopup
import com.android.launcher37.navi.MapActions
import com.android.launcher37.util.IconCache

import android.app.Activity
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher37.drawer.AppDrawer
import com.android.launcher37.data.AppQuery
import com.android.launcher37.navi.MapFeature
import com.android.launcher37.data.MemoryCleaner
import com.android.launcher37.util.SharedExecutor
import com.android.launcher37.data.SplitRepository
import com.android.launcher37.data.Store

/**
 * 竖排应用容器 Widget：垂直排列条目（图标 + 可选文字），支持绑定多种目标。
 *
 * 实例属性（config，设计器属性面板编辑）：
 * - [CFG_PADDING]  条目内边距
 * - [CFG_ICON_SIZE] 图标大小
 * - [CFG_SHOW_LABEL] 是否显示文字
 * - [CFG_LABEL_SIZE] 文字大小
 * - [CFG_COUNT]     条目数量
 * - [CFG_BINDINGS]  绑定列表（逗号分隔 token，稠密对应条目下标；缺失位 = auto）
 *
 * 绑定 token：
 * - `auto`            系统排序第 i 个应用（动态）
 * - `app:<pkg/cls>`   指定应用
 * - `drawer`          打开全部应用抽屉
 * - `clean`           内存清理
 * - `split:<idx>`     已保存分屏
 * - `layout:<name>`   切换到该布局
 *
 * 换绑：运行模式长按条目弹出绑定菜单；内置只读布局不持久化。
 * 日夜切换：文字/底色引用主题色动态重取，图标归一化缓存失效后重刷。
 */
class AppListWidget(activity: Activity, spec: WidgetSpec) : WidgetView(activity, spec, R.layout.widget_applist) {

    override val displayName = "应用列表"

    override val props: List<WidgetProp> = listOf(
        WidgetProp(CFG_PADDING, "条目内边距", PropType.INT, "8", min = 0, max = 40),
        WidgetProp(CFG_ICON_SIZE, "图标大小", PropType.INT, "48", min = 24, max = 120),
        WidgetProp(CFG_SHOW_LABEL, "显示文字", PropType.BOOL, "1"),
        WidgetProp(CFG_LABEL_SIZE, "文字大小", PropType.INT, "14", min = 8, max = 30),
        WidgetProp(CFG_COUNT, "条目数量", PropType.INT, "6", min = 1, max = 12)
    )

    private val box get() = findViewById<LinearLayout>(R.id.applist_box)

    /** 异步加载 token：配置快速连续变更时丢弃过期 UI 刷新 */
    private var mRefreshToken = 0

    /** 已入队未执行的刷新任务：拖动/属性面板高频调用合并为一次 IO 任务 */
    private var mRefreshQueued = false

    /** 绑定选择器加载 token（独立于 refresh，避免互相作废） */
    private var mPickerToken = 0

    /** 条目渲染数据（index = 逻辑下标，供长按换绑；bindable=false 的条目不可换绑） */
    private class Entry(
        val index: Int,
        val icon: Drawable?,
        val label: String,
        val bindable: Boolean,
        val click: () -> Unit
    )

    override fun onBind() {
        setCardBackground(true)
        refresh()
    }

    override fun onPropChanged(key: String, value: String) = refresh()

    override fun onSpecApplied() = refresh()

    // ── 绑定读写 ─────────────────────────────────────

    private fun bindingTokens(): List<String> =
        cfg(CFG_BINDINGS, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 换绑第 [index] 条；写 config 走 WidgetHost（触发 refresh + 持久化）。
     * 缺位补默认与渲染侧一致（第 1 条 = drawer、其余 = auto），否则换绑第 2 条时
     * 第 1 条会从"应用抽屉"跳变为 auto（排序第一个应用）。
     * 运行模式换绑是显式操作：立即落盘，重启不丢（设计器内触发不了长按，互不影响）。
     */
    private fun setBinding(index: Int, token: String) {
        val tokens = bindingTokens().toMutableList()
        while (tokens.size <= index) {
            tokens.add(if (tokens.isEmpty()) "drawer" else TOKEN_AUTO)
        }
        tokens[index] = token
        WidgetHost.instance?.updateConfig(spec.id, CFG_BINDINGS, tokens.joinToString(","))
        PageHost.instance?.persistNow()
    }

    // ── 异步刷新 ─────────────────────────────────────

    /**
     * 异步刷新（合并去抖）：拖动每帧 MOVE 都会触发，仅首个调用入队，其余只推进
     * token；任务执行时读最新 config，完成后若 token 已变（期间又有变更）再补一次。
     * 避免对 SharedExecutor（2 线程共享池）塞入高频 PackageManager binder 查询、
     * 挤占歌词取词等其他 IO 任务。
     *  第一条缺省绑定「应用抽屉」（可换绑），其余按绑定/默认应用渲染。 */
    private fun refresh() {
        mRefreshToken++
        if (mRefreshQueued) return
        mRefreshQueued = true
        SharedExecutor.io().execute {
            val token = mRefreshToken
            val count = cfgInt(CFG_COUNT, 6)
            val bindings = bindingTokens()
            val sortedApps = AppQuery.launcherEntriesSorted(activity)
            val entries = ArrayList<Entry>(count)
            for (i in 0 until count) {
                // 第一条缺省 drawer（非 auto），全条目可换绑
                val bindToken = bindings.getOrNull(i) ?: if (i == 0) "drawer" else TOKEN_AUTO
                resolveEntry(i, bindToken, sortedApps)?.let { entries.add(it) }
            }
            activity.runOnUiThread {
                mRefreshQueued = false
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (token != mRefreshToken) {
                    refresh() // 执行期间又有变更：以最新配置补刷一次
                    return@runOnUiThread
                }
                rebuildItems(entries)
            }
        }
    }

    /** 解析 token → 条目；无法解析（如分屏/应用已卸载）返回 null 跳过 */
    private fun resolveEntry(index: Int, token: String, sortedApps: List<android.content.pm.ResolveInfo>): Entry? {
        val type = token.substringBefore(':')
        val arg = token.substringAfter(':', "")
        return when (type) {
            TOKEN_AUTO -> sortedApps.getOrNull(index)?.let { appEntry(index, AppQuery.appId(it)) }
            "app" -> when {
                arg.isEmpty() -> null
                arg.contains('/') -> appEntry(index, arg)
                // 纯包名：动态解析该包的第一个启动入口（未安装则跳过）
                else -> sortedApps.firstOrNull { it.activityInfo.packageName == arg }
                    ?.let { appEntry(index, AppQuery.appId(it)) }
            }
            "drawer" -> Entry(index,
                Store.normalizedGlyphIcon(activity, R.drawable.ic_drawer), "全部应用", bindable = true
            ) { AppDrawer.show(activity) }
            "clean" -> Entry(index,
                Store.normalizedEmoji(activity, MapFeature.CLEAN_EMOJI), "清理", bindable = true
            ) { MemoryCleaner.cleanFromUi(activity) }
            "map" -> {
                val emoji = when (arg) {
                    "home" -> MapFeature.HOME_EMOJI
                    "company" -> MapFeature.COMPANY_EMOJI
                    else -> return null
                }
                val label = when (arg) {
                    "home" -> "回家"
                    "company" -> "公司"
                    else -> "导航"
                }
                Entry(index, Store.normalizedEmoji(activity, emoji), label, bindable = true) {
                    com.android.launcher37.navi.MapActions.run(activity, arg)
                }
            }
            "split" -> {
                // token = split:<稳定id>，删除其他分屏项不会错位
                val pair = SplitRepository.get(activity, arg) ?: return null
                Entry(index,
                    Store.normalizedSplitIcon(activity, pair.left, pair.right),
                    "${Store.label(activity, pair.left)}|${Store.label(activity, pair.right)}", bindable = true
                ) { Store.launchSplit(activity, pair.left, pair.right) }
            }
            "layout" -> if (arg.isEmpty()) null else Entry(index,
                Store.normalizedGlyphIcon(activity, R.drawable.ic_layout), arg, bindable = true
            ) { switchLayout(arg) }
            else -> null
        }
    }

    private fun appEntry(index: Int, id: String): Entry {
        // 预热 label / 归一化图标缓存（后台线程 binder）
        return Entry(index,
            Store.normalizedIcon(activity, id), Store.label(activity, id), bindable = true
        ) { Store.launchApp(activity, id) }
    }

    private fun switchLayout(name: String) {
        val ok = PageHost.instance?.applyLayout(name) == true
        toast(if (ok) "已切换布局「$name」" else "布局「$name」打开失败")
    }

    // ── 条目渲染 ─────────────────────────────────────

    /** 重建全部条目（垂直居中，样式按实例 config） */
    private fun rebuildItems(entries: List<Entry>) {
        box.removeAllViews()
        val pad = cfgInt(CFG_PADDING, 8)
        val iconSize = cfgInt(CFG_ICON_SIZE, 48)
        val showLabel = cfgBool(CFG_SHOW_LABEL, true)
        val labelSize = cfgInt(CFG_LABEL_SIZE, 14)
        val fg = activity.resources.getColor(R.color.foreground, activity.theme)
        for (e in entries) {
            val cell = buildItem(e, pad, iconSize, showLabel, labelSize, fg)
            cell.setOnClickListener { e.click() }
            if (e.bindable) cell.setOnLongClickListener { pickBinding(e.index); true }
            box.addView(cell)
        }
    }

    private fun buildItem(
        e: Entry,
        pad: Int,
        iconSize: Int,
        showLabel: Boolean,
        labelSize: Int,
        fgColor: Int
    ): View {
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val icon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(e.icon)
        }
        cell.addView(icon)
        if (showLabel) {
            val label = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                setTextSize(TypedValue.COMPLEX_UNIT_PX, labelSize.toFloat())
                setTextColor(fgColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = e.label
            }
            cell.addView(label)
        }
        return cell
    }

    // ── 换绑 ─────────────────────────────────────────

    /**
     * 长按换绑：直接弹出全量绑定选择器浮窗（应用抽屉 / 回家 / 公司 / 清理 /
     * 全部布局 / 全部分屏 / 全部应用），图标+名称列表（后台预取），点击即绑定。
     * 列表内容就绪后再显示弹窗：空列表时以小尺寸居中显示、适配器到达后
     * 窗口不重定位，下半部分会超出屏幕不可点（Gravity.CENTER 定位竞态）。
     */
    private fun pickBinding(index: Int) {
        val themed: android.content.Context = com.android.launcher37.util.HoloPopup.themedContext(activity)
        val list = android.widget.ListView(themed)
        var popup: android.widget.PopupWindow? = null
        val token = ++mPickerToken
        SharedExecutor.io().execute {
            data class Item(val icon: Drawable?, val label: String, val bind: String)
            val items = ArrayList<Item>()
            items.add(Item(Store.normalizedGlyphIcon(activity, R.drawable.ic_drawer), "全部应用", "drawer"))
            items.add(Item(Store.normalizedEmoji(activity, MapFeature.HOME_EMOJI), "回家", "map:home"))
            items.add(Item(Store.normalizedEmoji(activity, MapFeature.COMPANY_EMOJI), "公司", "map:company"))
            items.add(Item(Store.normalizedEmoji(activity, MapFeature.CLEAN_EMOJI), "清理", "clean"))
            for (name in LayoutRepository.listNames(activity)) {
                items.add(Item(Store.normalizedGlyphIcon(activity, R.drawable.ic_layout), name, "layout:$name"))
            }
            for (pair in SplitRepository.load(activity)) {
                items.add(Item(
                    Store.normalizedSplitIcon(activity, pair.left, pair.right),
                    "${Store.label(activity, pair.left)}|${Store.label(activity, pair.right)}", "split:${pair.id}"
                ))
            }
            for (ri in AppQuery.launcherEntriesSorted(activity)) {
                val id = AppQuery.appId(ri)
                items.add(Item(Store.normalizedIcon(activity, id), Store.label(activity, id), "app:$id"))
            }
            activity.runOnUiThread {
                if (token != mPickerToken || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                list.adapter = object : android.widget.BaseAdapter() {
                    override fun getCount() = items.size
                    override fun getItem(position: Int) = items[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                        val v = convertView ?: activity.layoutInflater.inflate(R.layout.item_app, parent, false)
                        v.findViewById<android.widget.ImageView>(R.id.app_icon).setImageDrawable(items[position].icon)
                        v.findViewById<TextView>(R.id.app_name).text = items[position].label
                        return v
                    }
                }
                list.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ ->
                    popup?.dismiss()
                    setBinding(index, items[pos].bind)
                }
                // 适配器就绪后显示：窗口按完整内容测量，超高会被钳到屏内（列表可滚动）
                popup = com.android.launcher37.util.HoloPopup.showWithWidth(
                    activity,
                    com.android.launcher37.util.HoloPopup.titledPanel(themed, "绑定第 ${index + 1} 项", list),
                    com.android.launcher37.util.HoloPopup.WIDTH_SMALL
                )
            }
        }
    }

    override fun onThemeChange() {
        // 卡底色 + 文字色随日夜主题重取；图标归一化缓存失效后重刷
        setCardBackground(true)
        com.android.launcher37.util.IconCache.clearNormalized()
        refresh()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        // 实例外观属性 config 键
        const val CFG_PADDING = "app_padding"
        const val CFG_ICON_SIZE = "app_icon_size"
        const val CFG_SHOW_LABEL = "app_show_label"
        const val CFG_LABEL_SIZE = "app_label_size"
        const val CFG_COUNT = "app_count"
        const val CFG_BINDINGS = "app_bindings"

        /** 默认绑定：系统排序第 i 个应用 */
        const val TOKEN_AUTO = "auto"
    }
}
