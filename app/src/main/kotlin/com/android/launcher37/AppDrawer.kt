package com.android.launcher37

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
 */
object AppDrawer {

    fun interface OnDockPick {
        fun onPicked(button: Store.V2Button)
    }

    private const val TAG_SETTINGS = "settings"
    private const val TAG_HOME = "feat_home"
    private const val TAG_COMPANY = "feat_company"
    private const val TAG_CLEAN = "feat_clean"
    private const val TAG_SPLIT_NEW = "split_new"
    private const val SPLIT_PREFIX = "split:"

    private var sPopup: PopupWindow? = null
    private var sStatsRunnable: Runnable? = null
    private var sPrevIdle: Long = 0
    private var sPrevTotal: Long = 0
    private var sDismissListeners: MutableList<Runnable> = mutableListOf()

    // 弹窗尺寸常量（与 HoloPopup.WIDTH=400 区分；本弹窗是 1000×620 大窗口）
    private const val POPUP_W = 1000
    private const val POPUP_H = 620

    fun showForDock(activity: Activity, title: String, callback: OnDockPick) {
        showInternal(activity, title, callback)
    }

    fun show(activity: Activity) {
        android.util.Log.i("VDFocusDbg", "AppDrawer.show()")  // debug-point lifecy-v1
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
        val popup = PopupWindow(content, POPUP_W, POPUP_H, true).apply {
            setBackgroundDrawable(
                ColorDrawable(activity.resources.getColor(R.color.surface, activity.theme))
            )
            isOutsideTouchable = true
            showAtLocation(activity.window.decorView, Gravity.CENTER, 0, 0)
        }
        sPopup = popup
        val p = Prefs.of(activity)
        val titleSize = p.getInt(SettingsActivity.KEY_TS_TIME, 28)
        val labelSize = p.getInt(SettingsActivity.KEY_TS_MUSIC_TITLE, 24)
        val iconSize = (labelSize * 2.7f).toInt().coerceIn(48, 96)
        val tvTitle = content.findViewById<TextView>(R.id.tv_drawer_title)
        tvTitle.text = dockTitle ?: "全部应用"
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize.toFloat())
        val tvStats = content.findViewById<TextView>(R.id.tv_drawer_stats)
        // 标题栏系统状态：仅全部应用模式显示，dock选择模式隐藏
        tvStats.visibility = if (dockCallback == null) View.VISIBLE else View.GONE
        tvStats.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (labelSize * 0.9f).toFloat())
        if (dockCallback == null) startStatsTicker(activity, tvStats, popup)
        popup.setOnDismissListener {
            if (sPopup === popup) sPopup = null
            stopStatsTicker()
            notifyDismiss()
        }
        val grid = content.findViewById<GridView>(R.id.drawer_grid)
        content.findViewById<View>(R.id.btn_drawer_close).setOnClickListener { popup.dismiss() }
        val dockBtns = Store.v2Buttons(activity)
        val pickCallback: OnDockPick? = dockCallback

        grid.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String ?: return@OnItemClickListener
            when {
                tagStr == TAG_SETTINGS -> {
                    if (pickCallback != null) {
                        pickAndClose(pickCallback, Store.V2Button.settings())
                    } else {
                        activity.startActivity(Intent(activity, SettingsActivity::class.java))
                        popup.dismiss()
                    }
                }
                tagStr == TAG_HOME -> {
                    if (pickCallback != null) {
                        pickAndClose(pickCallback, Store.V2Button.map("home"))
                    } else {
                        ensureInDock(dockBtns, Store.V2Button.map("home"))
                        Store.saveV2Buttons(activity, dockBtns)
                        popup.dismiss()
                        MapActions.run(activity, "home")
                    }
                }
                tagStr == TAG_COMPANY -> {
                    if (pickCallback != null) {
                        pickAndClose(pickCallback, Store.V2Button.map("company"))
                    } else {
                        ensureInDock(dockBtns, Store.V2Button.map("company"))
                        Store.saveV2Buttons(activity, dockBtns)
                        popup.dismiss()
                        MapActions.run(activity, "company")
                    }
                }
                tagStr == TAG_CLEAN -> {
                    if (pickCallback != null) {
                        pickAndClose(pickCallback, Store.V2Button.clean())
                    } else {
                        ensureInDock(dockBtns, Store.V2Button.clean())
                        Store.saveV2Buttons(activity, dockBtns)
                        popup.dismiss()
                        cleanMemory(activity)
                    }
                }
                tagStr == TAG_SPLIT_NEW -> {
                    pickNewSplitItem(activity, popup, dockBtns)
                }
                tagStr.startsWith(SPLIT_PREFIX) -> {
                    val idx = tagStr.substring(SPLIT_PREFIX.length).toInt()
                    val pair = getSplitItem(activity, idx)
                    if (pair != null) {
                        if (pickCallback != null) {
                            pickAndClose(pickCallback, Store.V2Button.split(pair[0], pair[1]))
                        } else {
                            popup.dismiss()
                            Store.launchSplit(activity, pair[0], pair[1])
                        }
                    }
                }
                else -> {
                    if (pickCallback != null) {
                        pickAndClose(pickCallback, Store.V2Button.app(tagStr))
                    } else {
                        Store.launchApp(activity, tagStr)
                        popup.dismiss()
                    }
                }
            }
        }

        grid.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String
            if (tagStr != null && tagStr.startsWith(SPLIT_PREFIX)) {
                val idx = tagStr.substring(SPLIT_PREFIX.length).toInt()
                if (pickCallback == null) {
                    removeSplitItem(activity, idx)
                    grid.adapter = DrawerAdapter(activity, loadApps(activity), Store.v2Buttons(activity), pickCallback != null, labelSize, iconSize)
                }
            }
            true
        }

        SharedExecutor.io().execute {
            val adapter = DrawerAdapter(activity.applicationContext, loadApps(activity.applicationContext), dockBtns, pickCallback != null, labelSize, iconSize)
            grid.post {
                if (!activity.isDestroyed && !activity.isFinishing) grid.adapter = adapter
            }
        }
    }

    /** 触发内存清理（统一在 [MemoryCleaner.cleanFromUi]） */
    private fun cleanMemory(activity: Activity) = MemoryCleaner.cleanFromUi(activity)

    // ── Feature grid items ──────────────────────────

    /** 确保底栏中存在该按钮（不存在则追加，已存在则跳过） */
    private fun ensureInDock(dockBtns: MutableList<Store.V2Button>, btn: Store.V2Button) {
        for (e in dockBtns) {
            if (e.sameAs(btn)) return
        }
        if (dockBtns.size < DockBar.MAX_DOCK_BUTTONS) {
            dockBtns.add(btn)
        }
    }

    private fun isFeatureTag(tag: String): Boolean =
        tag == TAG_SETTINGS || tag == TAG_HOME || tag == TAG_COMPANY
            || tag == TAG_CLEAN || tag == TAG_SPLIT_NEW

    private fun hasDockItem(btns: List<Store.V2Button>, type: String, action: String?): Boolean {
        for (b in btns) {
            if (type == b.type && (action == null || action == b.action)) return true
        }
        return false
    }

    private fun getSplitItem(c: Context, i: Int): Array<String>? = SplitRepository.get(c, i)
    private fun addSplitItem(c: Context, l: String, r: String) = SplitRepository.add(c, l, r)
    private fun removeSplitItem(c: Context, i: Int) {
        SplitRepository.remove(c, i)
        Toast.makeText(c, "已删除分屏项", Toast.LENGTH_SHORT).show()
    }

    private fun pickNewSplitItem(activity: Activity, drawer: PopupWindow, dockBtns: MutableList<Store.V2Button>) {
        val ids = resolveAppIds(activity)
        // 选左 → 选右：左侧 popup 关闭后用右侧 popup 接管列表
        pickSplitSide(activity, drawer, ids, title = "分屏 · 选择左侧应用") { leftId ->
            pickSplitSide(activity, drawer, ids, title = "分屏 · 选择右侧应用", leftId) { rightId ->
                addSplitItem(activity, leftId, rightId)
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
                        if (leftId == null) {
                            onPicked(ids[pos])
                        } else {
                            onPicked(ids[pos])
                            drawer.dismiss()
                        }
                    }
                }
            }
        }
    }

    /** 解析当前可启动应用为 `pkg/cls` 列表（按用户优先 + 字典序） */
    private fun resolveAppIds(activity: Activity): List<String> =
        loadApps(activity).map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }

    private fun loadApps(context: Context): List<ResolveInfo> = AppQuery.launcherEntriesSorted(context)

    // ── Grid Adapter：固定项 + 已保存分屏 + 普通应用 ──

    private class DrawerAdapter(
        context: Context,
        apps: List<ResolveInfo>,
        dockBtns: List<Store.V2Button>,
        dockMode: Boolean,
        private val labelSizePx: Int = 17,
        private val iconSizePx: Int = 64
    ) : BaseAdapter() {
        private val mContext: Context = context
        private val labels = ArrayList<String>()
        private val icons = ArrayList<Drawable?>()
        private val tags = ArrayList<String>()

        init {
            if (!dockMode || !hasDockItem(dockBtns, "settings", null)) {
                labels.add("桌面设置")
                icons.add(Store.normalizedEmoji(context, MapFeature.SETTINGS_EMOJI))
                tags.add(TAG_SETTINGS)
            }
            if (!dockMode || !hasDockItem(dockBtns, "map", "home")) {
                labels.add("回家")
                icons.add(Store.normalizedEmoji(context, MapFeature.HOME_EMOJI))
                tags.add(TAG_HOME)
            }
            if (!dockMode || !hasDockItem(dockBtns, "map", "company")) {
                labels.add("公司")
                icons.add(Store.normalizedEmoji(context, MapFeature.COMPANY_EMOJI))
                tags.add(TAG_COMPANY)
            }
            if (!dockMode || !hasDockItem(dockBtns, "clean", null)) {
                labels.add("清理")
                icons.add(Store.normalizedEmoji(context, MapFeature.CLEAN_EMOJI))
                tags.add(TAG_CLEAN)
            }
            if (!dockMode) {
                labels.add("分屏")
                icons.add(Store.normalizedEmoji(context, MapFeature.SPLIT_EMOJI))
                tags.add(TAG_SPLIT_NEW)
            }
            val splits = SplitRepository.load(context)
            for (i in splits.indices) {
                val pair = splits[i]
                if (dockMode && dockBtns.any { it == Store.V2Button.split(pair[0], pair[1]) }) continue
                labels.add("${Store.label(context, pair[0])}|${Store.label(context, pair[1])}")
                icons.add(Store.normalizedSplitIcon(context, pair[0], pair[1]))
                tags.add("$SPLIT_PREFIX$i")
            }
            for (ri in apps) {
                val id = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
                if (dockMode && hasDockItem(dockBtns, "app", id)) continue
                labels.add(Store.label(context, id))
                icons.add(Store.normalizedIcon(context, id))
                tags.add(id)
            }
        }

        override fun getCount(): Int = labels.size
        override fun getItem(position: Int): Any = tags[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView
                ?: LayoutInflater.from(mContext).inflate(R.layout.item_drawer_cell, parent, false)
            cell.findViewById<ImageView>(R.id.drawer_icon).setImageDrawable(icons[position])
            (cell.findViewById<View>(R.id.drawer_label) as TextView).text = labels[position]
            cell.tag = tags[position]
            return cell
        }
    }

    private fun startStatsTicker(activity: Activity, tv: TextView, popup: PopupWindow) {
        stopStatsTicker()
        val handler = MainThread.handler
        val runnable = object : Runnable {
            override fun run() {
                if (!popup.isShowing || activity.isDestroyed || activity.isFinishing) { stopStatsTicker(); return }
                tv.text = buildStatsText(activity)
                handler.postDelayed(this, 1000)
            }
        }
        sStatsRunnable = runnable
        tv.text = buildStatsText(activity)
        handler.postDelayed(runnable, 1000)
    }

    private fun stopStatsTicker() {
        sStatsRunnable?.let { MainThread.handler.removeCallbacks(it) }
        sStatsRunnable = null
    }

    private fun buildStatsText(c: Context): String {
        val cpu = readCpuPercent()
        val temp = readCpuTemp()
        val mem = readMemPercent(c)
        val cpuStr = if (cpu >= 0) String.format("%4s", "$cpu%") else String.format("%4s", "--%")
        val tempStr = if (temp >= 0) String.format("%4s", "${temp}°C") else String.format("%4s", "--°C")
        val memStr = if (mem >= 0) String.format("%4s", "$mem%") else String.format("%4s", "--%")
        return "CPU:$cpuStr  $tempStr  MEM:$memStr"
    }

    private fun readCpuPercent(): Int {
        return try {
            val stat = java.io.File("/proc/stat").bufferedReader().use { it.readLine() } ?: return -1
            val t = stat.trim().split(Regex("\\s+"))
            if (t.size < 8 || t[0] != "cpu") return -1
            val user = t[1].toLongOrNull() ?: 0L; val nice = t[2].toLongOrNull() ?: 0L
            val sys = t[3].toLongOrNull() ?: 0L; val idle = t[4].toLongOrNull() ?: 0L
            val iow = t[5].toLongOrNull() ?: 0L; val irq = t[6].toLongOrNull() ?: 0L; val sirq = t[7].toLongOrNull() ?: 0L
            val total = user + nice + sys + idle + iow + irq + sirq
            val idleAll = idle + iow
            val diffIdle = idleAll - sPrevIdle
            val diffTotal = total - sPrevTotal
            sPrevIdle = idleAll; sPrevTotal = total
            if (diffTotal <= 0) return -1
            ((diffTotal - diffIdle) * 100 / diffTotal).toInt().coerceIn(0, 100)
        } catch (_: Exception) { -1 }
    }

    private fun readCpuTemp(): Int {
        return try {
            val f = java.io.File("/sys/class/thermal/thermal_zone0/temp")
            if (!f.exists()) return -1
            val v = f.bufferedReader().use { it.readLine() }?.trim()?.toLongOrNull() ?: return -1
            val c = if (v > 1000) (v / 1000).toInt() else v.toInt()
            if (c in 0..150) c else -1
        } catch (_: Exception) { -1 }
    }

    private fun readMemPercent(c: Context): Int {
        return try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt().coerceIn(0, 100)
        } catch (_: Exception) { -1 }
    }
}
