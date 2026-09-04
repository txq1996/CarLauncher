package com.android.launcher37.drawer
import com.android.launcher37.SettingsActivity
import com.android.launcher37.R
import com.android.launcher37.util.HoloPopup
import com.android.launcher37.drawer.DrawerOverlay
import com.android.launcher37.drawer.DrawerStats
import com.android.launcher37.drawer.DrawerAdapter
import com.android.launcher37.data.SplitRepository
import com.android.launcher37.util.Dbg
import com.android.launcher37.data.Store
import com.android.launcher37.util.Prefs
import com.android.launcher37.data.MemoryCleaner
import com.android.launcher37.util.SharedExecutor
import com.android.launcher37.data.AppQuery
import com.android.launcher37.drawer.DrawerActions
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
 * 全部应用弹窗。
 *
 * 弹窗结构（宽高按屏幕百分比）：
 * - 标题栏（关闭按钮）
 * - GridView 大格子（图标 + 名称，纯色卡片）
 *
 * 内容（[DrawerAdapter]）：功能项 + 已保存布局 + 分屏 + 应用；
 * 点击分发 / 分屏增删与 [DrawerOverlay] 共用（[DrawerActions]）。
 */
object AppDrawer {

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

    fun show(activity: Activity) {
        Dbg.i("VDFocusDbg") { "AppDrawer.show()" }  // debug-point lifecy-v1
        showInternal(activity)
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

    private fun showInternal(activity: Activity) {
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
        tvTitle.text = "全部应用"
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize.toFloat())
        val tvStats = content.findViewById<TextView>(R.id.tv_drawer_stats)
        tvStats.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (labelSize * 0.9f).toFloat())
        DrawerStats.start(activity, tvStats) {
            popup.isShowing && !activity.isDestroyed && !activity.isFinishing
        }
        popup.setOnDismissListener {
            if (sPopup === popup) sPopup = null
            DrawerStats.stop()
            notifyDismiss()
        }
        val grid = content.findViewById<GridView>(R.id.drawer_grid)
        content.findViewById<View>(R.id.btn_drawer_close).setOnClickListener { popup.dismiss() }

        grid.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String ?: return@OnItemClickListener
            DrawerActions.handleNormal(
                activity, tagStr,
                onDismiss = { popup.dismiss() },
                onClean = { MemoryCleaner.cleanFromUi(activity) },
                onSplitNew = { pickNewSplitItem(activity, popup) }
            )
        }

        grid.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String
            if (tagStr != null && tagStr.startsWith(DrawerAdapter.SPLIT_PREFIX)) {
                val idx = tagStr.substring(DrawerAdapter.SPLIT_PREFIX.length).toIntOrNull() ?: return@OnItemLongClickListener true
                DrawerActions.removeSplitAndRefresh(activity, grid, idx)
            }
            true
        }

        DrawerActions.loadAdapterAsync(
            activity.applicationContext, grid
        ) { !activity.isDestroyed && !activity.isFinishing }
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
