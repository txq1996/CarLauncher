package com.android.launcher37

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.TypedValue
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
 * 底部 10 格 Dock 栏。
 *
 * - 第 0 格固定「全部应用」→ 打开 [AppDrawer]
 * - 第 1~9 格：用户配置（[Store.V2Button]） + 末尾"添加"格（未满时）
 * - 按钮类型：`map` / `app` / `split` / `clean` / `settings`
 *
 * 交互：
 * - 点击执行
 * - 长按「替换 / 移除」
 * - 末尾"添加"格 → 四选项类型选择（已存在的回家/公司与应用自动去重）
 *
 * 应用选择器 [pickApp] 复用 [AppQuery] + [HoloPopup]，与底栏"添加应用"同一套。
 */
class DockBar(
    private val mActivity: Activity,
    private val mGrid: GridView
) {
    companion object {
        /** 底栏自定义格最大数量（不含第 0 格「全部应用」） */
        const val MAX_DOCK_BUTTONS = 9

        @JvmStatic
        fun mapLabel(action: String?): String = when (action) {
            "home" -> "回家"
            "company" -> "公司"
            else -> "结束导航"
        }

        @JvmStatic
        fun mapEmoji(action: String?): String = when (action) {
            "home" -> MapFeature.HOME_EMOJI
            "company" -> MapFeature.COMPANY_EMOJI
            else -> MapFeature.STOP_EMOJI
        }

        /** 确保底栏中存在该按钮（不存在则追加，已存在则跳过） */
        @JvmStatic
        internal fun ensureInDock(dockBtns: MutableList<Store.V2Button>, btn: Store.V2Button) {
            for (e in dockBtns) {
                if (e.sameAs(btn)) return
            }
            if (dockBtns.size < MAX_DOCK_BUTTONS) {
                dockBtns.add(btn)
            }
        }
    }

    private val mAdapter = DockAdapter()
    private var mCleanAction: Runnable? = null
    private var mShowIcon = true
    private var mShowLabel = true
    private var mIconSize = 44
    private var mLabelSize = 14

    /** 格子高度（px）：跟随底栏高度，保证内容在行内垂直居中 */
    private var mCellHeightPx = 80

    /** 可见图标数量（Grid 列数）：条目超出时截断，保持单行 */
    private var mColumns = 10

    /**
     * Monotonic refresh token: each call to [refresh] bumps it by 1; the
     * SharedExecutor task captures the value at submit time and skips the UI
     * update if a newer refresh has been queued (avoids stale-data race when
     * user rapidly toggles dock configuration).
     */
    private val mRefreshToken = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        mGrid.adapter = mAdapter
        refresh()
    }

    fun setCleanAction(r: Runnable?) { mCleanAction = r }

    /** 设置可见图标数量（Grid 列数，5~10），超出部分截断 */
    fun setColumns(n: Int) {
        mColumns = n.coerceIn(1, MAX_DOCK_BUTTONS + 1)
        mGrid.numColumns = mColumns
        mAdapter.notifyDataSetChanged()
    }

    fun setCellStyle(showIcon: Boolean, showLabel: Boolean, iconSize: Int, labelSize: Int, cellHeightPx: Int = mCellHeightPx) {
        mShowIcon = showIcon
        mShowLabel = showLabel
        mIconSize = iconSize
        mLabelSize = labelSize
        mCellHeightPx = cellHeightPx
        refresh()
    }

    private fun styleCell(cell: View) {
        val icon = cell.findViewById<ImageView>(R.id.dock_icon)
        val label = cell.findViewById<TextView>(R.id.dock_label)
        icon.visibility = if (mShowIcon) View.VISIBLE else View.GONE
        label.visibility = if (mShowLabel) View.VISIBLE else View.GONE
        val lp = icon.layoutParams
        lp.width = mIconSize
        lp.height = mIconSize
        icon.layoutParams = lp
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, mLabelSize.toFloat())
        // 格子高度跟随底栏高度（单行 Grid：格高=行高=Grid 高），
        // 否则行高 80px 贴顶排布，底栏调高后内容垂直不居中
        cell.layoutParams.height = mCellHeightPx
    }

    /**
     * 重新加载按钮列表 + 异步预解析图标 + UI 刷新。
     */
    fun refresh() {
        val btns = Store.v2Buttons(mActivity)
        val token = mRefreshToken.incrementAndGet()
        val appCtx = mActivity.applicationContext
        SharedExecutor.io().execute {
            for (b in btns) {
                when (b.type) {
                    "map" -> Store.normalizedEmoji(appCtx, mapEmoji(b.action))
                    "app" -> {
                        Store.label(appCtx, b.id)
                        Store.normalizedIcon(appCtx, b.id)
                    }
                    "clean" -> Store.normalizedEmoji(appCtx, MapFeature.CLEAN_EMOJI)
                    "settings" -> Store.normalizedEmoji(appCtx, MapFeature.SETTINGS_EMOJI)
                    else -> {
                        Store.label(appCtx, b.left)
                        Store.label(appCtx, b.right)
                        Store.normalizedSplitIcon(appCtx, b.left, b.right)
                    }
                }
            }
            Store.normalizedGlyphIcon(appCtx, R.drawable.ic_drawer)
            mActivity.runOnUiThread {
                // Skip UI update if a newer refresh has been queued or activity is gone
                if (token != mRefreshToken.get()) return@runOnUiThread
                if (mActivity.isDestroyed || mActivity.isFinishing) return@runOnUiThread
                mAdapter.setItems(btns)
                for (i in 0 until mGrid.childCount) {
                    val cell = mGrid.getChildAt(i)
                    cell.setBackgroundResource(0)
                    cell.setBackgroundResource(R.drawable.bg_v2_cell)
                }
            }
        }
    }

    // ── 应用选择器 ──────────────────────

    /**
     * 弹应用列表选择器，每个 LAUNCHER 入口一条（多入口应用各自列出），
     * `exclude` 中的应用标识（pkg/cls）不出现在候选列表，选中后回调应用标识。
     */
    fun pickApp(title: String, exclude: Set<String>?, onPicked: (String) -> Unit) {
        val themed: Context = HoloPopup.themedContext(mActivity)
        val list = ListView(themed)
        val popup: PopupWindow = HoloPopup.showWithWidth(mActivity, HoloPopup.titledPanel(themed, title, list), HoloPopup.WIDTH_SMALL)
        list.onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
            popup.dismiss()
            val ri = parent.adapter.getItem(position) as? ResolveInfo ?: return@OnItemClickListener
            onPicked("${ri.activityInfo.packageName}/${ri.activityInfo.name}")
        }
        val entries: List<ResolveInfo> = AppQuery.launcherEntries(mActivity, exclude)
        SharedExecutor.io().execute {
            val adapter = PickerAdapter(mActivity, entries)
            list.post {
                if (!mActivity.isDestroyed && !mActivity.isFinishing) list.adapter = adapter
            }
        }
    }

    private fun runButton(b: Store.V2Button) {
        when (b.type) {
            "map" -> MapActions.run(mActivity, b.action)
            "app" -> Store.launchApp(mActivity, b.id)
            "clean" -> mCleanAction?.run()
            "settings" -> mActivity.startActivity(Intent(mActivity, SettingsActivity::class.java))
            else -> Store.launchSplit(mActivity, b.left, b.right)
        }
    }

    private fun showButtonMenu(index: Int) {
        val title = buttonTitle(mAdapter.itemAt(index))
        val items = arrayOf<CharSequence>("替换", "移除")
        showListPopup(
            title,
            ArrayAdapter(mActivity, android.R.layout.simple_list_item_1, items)
        ) { _, _, position, _ ->
            if (position == 0) {
                val replaceIdx = index
                AppDrawer.showForDock(mActivity, "替换为") { btn -> applyButton(btn, replaceIdx) }
            } else {
                removeButton(index)
            }
        }
    }

    /**
     * 添加/替换底栏按钮。
     *
     * - [replaceIndex] == null → 追加（重复则 Toast 提示并跳过）
     * - [replaceIndex] != null → 替换该下标（与其他下标重复则跳过）
     */
    fun applyButton(btn: Store.V2Button, replaceIndex: Int?) {
        val btns = Store.v2Buttons(mActivity)
        if (replaceIndex == null) {
            for (e in btns) {
                if (e.sameAs(btn)) {
                    Toast.makeText(mActivity, "该快捷方式已存在", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            btns.add(btn)
        } else {
            for (i in btns.indices) {
                if (i != replaceIndex && btns[i].sameAs(btn)) {
                    Toast.makeText(mActivity, "该快捷方式已存在", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            btns[replaceIndex] = btn
        }
        Store.saveV2Buttons(mActivity, btns)
        refresh()
    }

    /** 按下标移除底栏按钮。越界则静默。 */
    fun removeButton(index: Int) {
        val btns = Store.v2Buttons(mActivity)
        if (index !in btns.indices) return
        btns.removeAt(index)
        Store.saveV2Buttons(mActivity, btns)
        refresh()
    }

    private fun showListPopup(
        title: CharSequence,
        adapter: ArrayAdapter<*>,
        onPick: AdapterView.OnItemClickListener
    ) {
        val themed: Context = HoloPopup.themedContext(mActivity)
        val list = ListView(themed)
        list.adapter = adapter
        val popup = HoloPopup.show(mActivity, HoloPopup.titledPanel(themed, title, list))
        list.onItemClickListener = AdapterView.OnItemClickListener { parent, view, position, id ->
            popup.dismiss()
            onPick.onItemClick(parent, view, position, id)
        }
    }

    private fun buttonTitle(b: Store.V2Button?): String {
        if (b == null) return ""
        return when (b.type) {
            "map" -> mapLabel(b.action)
            "app" -> Store.label(mActivity, b.id)
            "settings" -> "桌面设置"
            "clean" -> "内存清理"
            else -> "${Store.label(mActivity, b.left)}|${Store.label(mActivity, b.right)}"
        }
    }

    // ── Adapter ──────────────────────

    /**
     * 底栏适配器骨架：数据未满时末尾隐含一个添加格，满员隐藏。
     */
    private abstract inner class AddTailAdapter<T>(private val mMax: Int) : BaseAdapter() {
        private val mItems = ArrayList<T>()

        fun setItems(items: List<T>) {
            mItems.clear()
            mItems.addAll(items)
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): T? = if (position < mItems.size) mItems[position] else null

        fun itemCount(): Int = mItems.size

        override fun getCount(): Int = minOf(mItems.size + 1, mMax)

        override fun getItem(position: Int): T? = itemAt(position)

        override fun getItemId(position: Int): Long = position.toLong()

        /** 自定义格数量（不含子类扩展的固定尾格） */
        protected fun customCount(): Int = minOf(mItems.size + 1, mMax)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView ?: LayoutInflater.from(mActivity).inflate(cellLayout(), parent, false)
            if (position < mItems.size) {
                bindItem(cell, mItems[position], position)
            } else {
                bindAdd(cell)
            }
            return cell
        }

        protected abstract fun cellLayout(): Int
        protected abstract fun bindItem(cell: View, item: T, index: Int)
        protected abstract fun bindAdd(cell: View)
    }

    /** 底栏适配器：第 1 格固定「全部应用」 + 自定义格 + 末尾添加按钮 */
    private inner class DockAdapter : AddTailAdapter<Store.V2Button>(MAX_DOCK_BUTTONS) {

        /** 总格数不超过列数，保持单行显示 */
        override fun getCount(): Int = minOf(customCount() + 1, mColumns)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (position == 0) {
                val cell = if (convertView != null && convertView.tag is String) {
                    convertView
                } else {
                    LayoutInflater.from(mActivity).inflate(R.layout.item_dock_cell, parent, false)
                }
                withRenderedShell(cell) { icon, label ->
                    icon.setImageDrawable(Store.normalizedGlyphIcon(mActivity, R.drawable.ic_drawer))
                    label.text = "全部应用"
                    cell.setOnClickListener { AppDrawer.show(mActivity) }
                    cell.setOnLongClickListener(null)
                    cell.tag = "drawer"
                }
                return cell
            }
            val convert = if (convertView != null && convertView.tag is String) null else convertView
            val cell = super.getView(position - 1, convert, parent)
            cell.tag = null
            return cell
        }

        override fun cellLayout(): Int = R.layout.item_dock_cell

        override fun bindItem(cell: View, b: Store.V2Button, index: Int) {
            withRenderedShell(cell) { icon, label ->
                when (b.type) {
                    "map" -> {
                        icon.setImageDrawable(Store.normalizedEmoji(mActivity, mapEmoji(b.action)))
                        label.text = mapLabel(b.action)
                    }
                    "app" -> {
                        icon.setImageDrawable(Store.normalizedIcon(mActivity, b.id))
                        label.text = Store.label(mActivity, b.id)
                    }
                    "clean" -> {
                        icon.setImageDrawable(Store.normalizedEmoji(mActivity, MapFeature.CLEAN_EMOJI))
                        label.text = "内存清理"
                    }
                    "settings" -> {
                        icon.setImageDrawable(Store.normalizedEmoji(mActivity, MapFeature.SETTINGS_EMOJI))
                        label.text = "桌面设置"
                    }
                    else -> {
                        icon.setImageDrawable(Store.normalizedSplitIcon(mActivity, b.left, b.right))
                        label.text = buttonTitle(b)
                    }
                }
                cell.setOnClickListener { runButton(b) }
                cell.setOnLongClickListener {
                    showButtonMenu(index)
                    true
                }
            }
        }

        override fun bindAdd(cell: View) {
            withRenderedShell(cell) { icon, label ->
                icon.setImageResource(R.drawable.ic_plus)
                val secondary = mActivity.resources.getColor(R.color.foreground_secondary, mActivity.theme)
                icon.setColorFilter(secondary)
                label.text = "添加"
                label.setTextColor(secondary)
                cell.setOnClickListener {
                    AppDrawer.showForDock(mActivity, "添加应用") { btn -> applyButton(btn, null) }
                }
                cell.setOnLongClickListener(null)
            }
        }
    }

    /**
     * 渲染底栏格通用底（背景 + 样式 + 文字色 + 清图标滤镜），
     * 回调中只填 icon / label / 监听器。
     */
    private inline fun withRenderedShell(
        cell: View,
        block: (ImageView, TextView) -> Unit
    ) {
        cell.setBackgroundResource(R.drawable.bg_v2_cell)
        styleCell(cell)
        val icon = cell.findViewById<ImageView>(R.id.dock_icon)
        val label = cell.findViewById<TextView>(R.id.dock_label)
        icon.clearColorFilter()
        label.setTextColor(mActivity.resources.getColor(R.color.foreground, mActivity.theme))
        block(icon, label)
    }

    /** 选择器列表项：label/icon 在构造时一次性解析，避免滚动重复 Binder 调用 */
    private class PickerAdapter(
        activity: Activity,
        apps: List<ResolveInfo>
    ) : BaseAdapter() {
        private val mActivity: Activity = activity
        private val mApps: List<ResolveInfo> = apps
        private val labels = ArrayList<String>()
        private val icons = ArrayList<Drawable?>()

        init {
            val pm = activity.packageManager
            for (ri in apps) {
                val l = ri.loadLabel(pm)
                labels.add(l?.toString() ?: ri.activityInfo.packageName)
                icons.add(ri.loadIcon(pm))
            }
        }

        override fun getCount(): Int = mApps.size
        override fun getItem(position: Int): Any = mApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(mActivity)
                .inflate(R.layout.item_app, parent, false)
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(icons[position])
            view.findViewById<TextView>(R.id.app_name).text = labels[position]
            return view
        }
    }
}
