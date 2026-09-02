package com.android.launcher37

import android.app.Activity
import android.content.Context
import android.content.pm.ResolveInfo
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

/**
 * 全部应用弹窗 + 底栏选择模式。
 *
 * 弹窗结构（1000×620 居中）：
 * - 标题栏（关闭按钮）
 * - GridView 5 列大格子（92px 图标 + 17px 名称，纯色卡片）
 *
 * 模式：
 * - 全部应用模式（[show]）：点击显式组件启动；首部固定「桌面设置」入口
 * - 底栏模式（[showForDock]）：点击直接回调 [OnDockPick]，让调用方写入底栏
 *
 * 列表首部按当前模式去重（已加入底栏的不再显示在选择器中）。
 * 适配器 / 统计栏 / 普通模式点击分发 / 分屏增删与 [DrawerOverlay] 共用
 * （[DrawerAdapter] / [DrawerStats] / [DrawerActions]）。
 */
object AppDrawer {

    fun interface OnDockPick {
        fun onPicked(button: Store.V2Button)
    }

    private var sPopup: PopupWindow? = null
    private var sDismissListeners: MutableList<Runnable> = mutableListOf()

    // 弹窗尺寸：按屏幕宽高百分比（设置项 KEY_DRAWER_WIDTH_PCT / KEY_DRAWER_HEIGHT_PCT，默认 75%）
    private fun drawerWidthPx(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        val pct = Prefs.of(ctx).getInt(SettingsActivity.KEY_DRAWER_WIDTH_PCT, 75)
        return (dm.widthPixels * pct / 100f).toInt()
    }

    private fun drawerHeightPx(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        val pct = Prefs.of(ctx).getInt(SettingsActivity.KEY_DRAWER_HEIGHT_PCT, 75)
        return (dm.heightPixels * pct / 100f).toInt()
    }

    fun showForDock(activity: Activity, title: String, callback: OnDockPick) {
        showInternal(activity, title, callback)
    }

    fun show(activity: Activity) {
        Dbg.i("VDFocusDbg") { "AppDrawer.show()" }  // debug-point lifecy-v1
        showInternal(activity, null, null)
    }

    fun isShowing(): Boolean = sPopup?.isShowing == true

    fun addOnDismissListener(listener: Runnable) {
        sDismissListeners.add(listener)
    }

    fun removeOnDismissListener(listener: Runnable) {
        sDismissListeners.remove(listener)
    }

    private fun notifyDismiss() {
        val listeners = sDismissListeners.toList()
        sDismissListeners.clear()
        for (l in listeners) l.run()
    }

    fun dismissIfShowing() {
        if (sPopup?.isShowing == true) {
            sPopup?.dismiss()
        }
    }

    /** 切换全部应用抽屉：已显示时关闭，否则打开 */
    fun toggle(activity: Activity) {
        if (sPopup?.isShowing == true) {
            dismissIfShowing()
        } else {
            show(activity)
        }
    }

    /** dock 模式：选中后回调 + 关弹窗（消除 6 处 pickCallback != null 重复） */
    private fun pickAndClose(cb: OnDockPick, btn: Store.V2Button) {
        cb.onPicked(btn)
        sPopup?.takeIf { it.isShowing }?.dismiss()
    }

    private fun showInternal(activity: Activity, dockTitle: String?, dockCallback: OnDockPick?) {
        dismissIfShowing()
        val themed: Context = HoloPopup.themedContext(activity)
        val content: View = LayoutInflater.from(themed).inflate(R.layout.dialog_app_drawer, null)
        val popup = PopupWindow(content, drawerWidthPx(activity), drawerHeightPx(activity), true).apply {
            setBackgroundDrawable(themed.getDrawable(R.drawable.bg_drawer_dialog))
            isOutsideTouchable = true
            showAtLocation(activity.window.decorView, Gravity.CENTER, 0, 0)
        }
        sPopup = popup
        val p = Prefs.of(activity)
        val titleSize = p.getInt(SettingsActivity.KEY_TS_TIME, 28)
        val labelSize = p.getInt(SettingsActivity.KEY_DRAWER_LABEL_SIZE, 17)
        val tvTitle = content.findViewById<TextView>(R.id.tv_drawer_title)
        tvTitle.text = dockTitle ?: "全部应用"
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize.toFloat())
        val tvStats = content.findViewById<TextView>(R.id.tv_drawer_stats)
        // 标题栏系统状态：仅全部应用模式显示，dock选择模式隐藏
        tvStats.visibility = if (dockCallback == null) View.VISIBLE else View.GONE
        tvStats.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (labelSize * 0.9f).toFloat())
        if (dockCallback == null) {
            DrawerStats.start(activity, tvStats) {
                popup.isShowing && !activity.isDestroyed && !activity.isFinishing
            }
        }
        popup.setOnDismissListener {
            if (sPopup === popup) sPopup = null
            DrawerStats.stop()
            notifyDismiss()
        }
        val grid = content.findViewById<GridView>(R.id.drawer_grid)
        content.findViewById<View>(R.id.btn_drawer_close).setOnClickListener { popup.dismiss() }
        val dockBtns = Store.v2Buttons(activity)
        val pickCallback: OnDockPick? = dockCallback

        grid.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String ?: return@OnItemClickListener
            if (pickCallback != null) {
                handleDockPick(activity, tagStr, pickCallback)
            } else {
                DrawerActions.handleNormal(
                    activity, tagStr, dockBtns,
                    onDismiss = { popup.dismiss() },
                    onClean = { MemoryCleaner.cleanFromUi(activity) },
                    onSplitNew = { pickNewSplitItem(activity, popup) }
                )
            }
        }

        grid.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String
            if (pickCallback == null && tagStr != null && tagStr.startsWith(DrawerAdapter.SPLIT_PREFIX)) {
                val idx = tagStr.substring(DrawerAdapter.SPLIT_PREFIX.length).toIntOrNull() ?: return@OnItemLongClickListener true
                DrawerActions.removeSplitAndRefresh(activity, grid, idx, dockMode = false)
            }
            true
        }

        DrawerActions.loadAdapterAsync(
            activity.applicationContext, grid, dockCallback != null
        ) { !activity.isDestroyed && !activity.isFinishing }
    }

    /** dock 模式点击分发：所有格子都只回调，不执行动作（split_new 仅普通模式存在） */
    private fun handleDockPick(activity: Activity, tagStr: String, cb: OnDockPick) {
        when {
            tagStr == DrawerAdapter.TAG_SETTINGS -> pickAndClose(cb, Store.V2Button.settings())
            tagStr == DrawerAdapter.TAG_HOME -> pickAndClose(cb, Store.V2Button.map("home"))
            tagStr == DrawerAdapter.TAG_COMPANY -> pickAndClose(cb, Store.V2Button.map("company"))
            tagStr == DrawerAdapter.TAG_CLEAN -> pickAndClose(cb, Store.V2Button.clean())
            tagStr.startsWith(DrawerAdapter.SPLIT_PREFIX) -> {
                val idx = tagStr.substring(DrawerAdapter.SPLIT_PREFIX.length).toIntOrNull() ?: return
                val pair = SplitRepository.get(activity, idx)
                if (pair != null) pickAndClose(cb, Store.V2Button.split(pair[0], pair[1]))
            }
            else -> pickAndClose(cb, Store.V2Button.app(tagStr))
        }
    }

    // ── 分屏选择器 ──────────────────────────

    private fun pickNewSplitItem(activity: Activity, drawer: PopupWindow) {
        val ids = resolveAppIds(activity)
        // 选左 → 选右：左侧 popup 关闭后用右侧 popup 接管列表
        pickSplitSide(activity, drawer, ids, title = "分屏 · 选择左侧应用") { leftId ->
            pickSplitSide(activity, drawer, ids, title = "分屏 · 选择右侧应用", leftId) { rightId ->
                SplitRepository.add(activity, leftId, rightId)
                Toast.makeText(activity, "已添加分屏到全部应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 分屏侧选择弹窗（左侧 / 右侧通用）。
     *
     * - [leftId] == null → 选完关弹窗并 [onPicked] 回调（让调用方开"选右"弹窗）
     * - [leftId] != null → 选完关弹窗 + 调用方抽屉 + 回调 (rightId)
     */
    private fun pickSplitSide(
        activity: Activity,
        drawer: PopupWindow,
        ids: List<String>,
        title: String,
        leftId: String? = null,
        onPicked: (String) -> Unit
    ) {
        val themed: Context = HoloPopup.themedContext(activity)
        val list = ListView(themed)
        val popup = HoloPopup.showWithWidth(activity, HoloPopup.titledPanel(themed, title, list), HoloPopup.WIDTH_SMALL)
        val appCtx = activity.applicationContext
        SharedExecutor.io().execute {
            val entries = ids.map { it to Store.label(appCtx, it) }
            val icons = ids.map { Store.normalizedIcon(appCtx, it) }
            list.post {
                if (!activity.isDestroyed && !activity.isFinishing) {
                    val labelSize = Prefs.of(activity).getInt(SettingsActivity.KEY_TS_MUSIC_TITLE, 24)
                    list.adapter = object : BaseAdapter() {
                        override fun getCount(): Int = entries.size
                        override fun getItem(p: Int): Any = entries[p].first
                        override fun getItemId(p: Int): Long = p.toLong()
                        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                            val v = cv ?: LayoutInflater.from(activity).inflate(R.layout.item_app, parent, false)
                            v.findViewById<ImageView>(R.id.app_icon).setImageDrawable(icons[pos])
                            val tv = v.findViewById<TextView>(R.id.app_name)
                            tv.text = entries[pos].second
                            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, labelSize.toFloat())
                            return v
                        }
                    }
                    list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                        popup.dismiss()
                        onPicked(ids[pos])
                        if (leftId != null) drawer.dismiss()
                    }
                }
            }
        }
    }

    /** 解析当前可启动应用为 `pkg/cls` 列表（按用户优先 + 字典序） */
    private fun resolveAppIds(activity: Activity): List<String> =
        AppQuery.launcherEntriesSorted(activity).map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
}
