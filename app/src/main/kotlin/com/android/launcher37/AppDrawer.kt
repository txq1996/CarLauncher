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

    // 弹窗尺寸常量（与 HoloPopup.WIDTH=400 区分；本弹窗是 1000×620 大窗口）
    private const val POPUP_W = 1000
    private const val POPUP_H = 620

    fun showForDock(activity: Activity, title: String, callback: OnDockPick) {
        showInternal(activity, title, callback)
    }

    fun show(activity: Activity) {
        showInternal(activity, null, null)
    }

    fun dismissIfShowing() {
        sPopup?.takeIf { it.isShowing }?.dismiss()
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
            setOnDismissListener { if (sPopup === this) sPopup = null }
        }
        sPopup = popup
        val tvTitle = content.findViewById<TextView>(R.id.tv_drawer_title)
        tvTitle.text = dockTitle ?: "全部应用"
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
            if (tagStr != null) {
                if (tagStr.startsWith(SPLIT_PREFIX)) {
                    val idx = tagStr.substring(SPLIT_PREFIX.length).toInt()
                    removeSplitItem(activity, idx)
                    grid.adapter = DrawerAdapter(activity, loadApps(activity), Store.v2Buttons(activity), pickCallback != null)
                } else if (!isFeatureTag(tagStr)) {
                    showDockActionMenu(activity, tagStr, popup)
                }
            }
            true
        }

        val adapter = DrawerAdapter(activity, loadApps(activity), dockBtns, pickCallback != null)
        SharedExecutor.io().execute {
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
        val popup = HoloPopup.show(activity, HoloPopup.titledPanel(themed, title, list))
        SharedExecutor.io().execute {
            val labels = ids.map { Store.label(activity, it) }
            list.post {
                if (!activity.isDestroyed && !activity.isFinishing) {
                    list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)
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

    /** 解析当前可启动应用为 `pkg/cls` 列表（按用户优先 + 字典序） */
    private fun resolveAppIds(activity: Activity): List<String> =
        loadApps(activity).map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }

    // ── 底栏操作（长按/添加模式） ──────────────────────────

    private fun showDockActionMenu(activity: Activity, appId: String, drawer: PopupWindow) {
        val dockBtns = Store.v2Buttons(activity)
        var alreadyInDock = false
        var foundIndex = -1
        for (i in dockBtns.indices) {
            val b = dockBtns[i]
            if (b.type == "app" && appId == b.id) { alreadyInDock = true; foundIndex = i; break }
        }
        val dockIndex = foundIndex
        if (!alreadyInDock && dockBtns.size >= DockBar.MAX_DOCK_BUTTONS) {
            Toast.makeText(activity, "底栏已满（最多${DockBar.MAX_DOCK_BUTTONS}个）", Toast.LENGTH_SHORT).show()
            return
        }
        val appName = Store.label(activity, appId)
        val options = ArrayList<String>()
        val kinds = ArrayList<Int>()
        if (!alreadyInDock) { options.add("添加到底栏"); kinds.add(0) }
        options.add("替换底栏按钮"); kinds.add(1)
        if (alreadyInDock) { options.add("从底栏移除"); kinds.add(2) }
        val themed: Context = HoloPopup.themedContext(activity)
        val list = ListView(themed)
        val popup = HoloPopup.show(activity, HoloPopup.titledPanel(themed, appName, list))
        list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, options)
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            when (kinds[position]) {
                0 -> addToDock(activity, Store.V2Button.app(appId))
                1 -> showReplaceDockPicker(activity, appId)
                2 -> removeDockItem(activity, dockIndex)
            }
        }
    }

    private fun addToDock(activity: Activity, btn: Store.V2Button) {
        val before = Store.v2Buttons(activity).size
        (activity as? LauncherActivity)?.dockBar?.applyButton(btn, replaceIndex = null)
        // applyButton 内部已 Toast"该快捷方式已存在"或成功；成功后需要"已添加"提示
        if (Store.v2Buttons(activity).size == before + 1) {
            Toast.makeText(activity, "已添加到底栏", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showReplaceDockPicker(activity: Activity, newAppId: String) {
        val dockBtns = Store.v2Buttons(activity)
        val titles = Array(dockBtns.size) { i ->
            val b = dockBtns[i]
            when (b.type) {
                "map" -> "地图·" + if (b.action == "home") "回家" else "公司"
                "app" -> Store.label(activity, b.id)
                "clean" -> "内存清理"
                "settings" -> "桌面设置"
                else -> "${Store.label(activity, b.left)}|${Store.label(activity, b.right)}"
            }
        }
        val themed: Context = HoloPopup.themedContext(activity)
        val list = ListView(themed)
        val popup = HoloPopup.show(activity, HoloPopup.titledPanel(themed, "替换为", list))
        list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, titles)
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            (activity as? LauncherActivity)?.dockBar?.applyButton(
                Store.V2Button.app(newAppId), replaceIndex = position
            )
            Toast.makeText(activity, "已替换", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeDockItem(activity: Activity, index: Int) {
        (activity as? LauncherActivity)?.dockBar?.removeButton(index)
        Toast.makeText(activity, "已从底栏移除", Toast.LENGTH_SHORT).show()
    }

    private fun loadApps(context: Context): List<ResolveInfo> = AppQuery.launcherEntriesSorted(context)

    // ── Grid Adapter：固定项 + 已保存分屏 + 普通应用 ──

    private class DrawerAdapter(
        context: Context,
        apps: List<ResolveInfo>,
        dockBtns: List<Store.V2Button>,
        dockMode: Boolean
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
            if (!dockMode) {
                val splits = SplitRepository.load(context)
                for (i in splits.indices) {
                    val pair = splits[i]
                    labels.add("${Store.label(context, pair[0])}|${Store.label(context, pair[1])}")
                    icons.add(Store.normalizedSplitIcon(context, pair[0], pair[1]))
                    tags.add("$SPLIT_PREFIX$i")
                }
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
}
