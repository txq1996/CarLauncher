package com.android.launcher37

import android.content.Context
import android.content.pm.ResolveInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * 抽屉网格适配器（AppDrawer 弹窗与 DrawerOverlay 悬浮窗共用）：
 * 功能固定项 + 已保存分屏 + 普通应用。
 * dockMode（底栏选择模式）下去重已在底栏的项。
 */
internal class DrawerAdapter(
    context: Context,
    apps: List<ResolveInfo>,
    dockBtns: List<Store.V2Button>,
    dockMode: Boolean,
    private val iconSizePx: Int = 64,
    private val labelSizePx: Int = 17
) : BaseAdapter() {
    private val mContext: Context = context
    private val labels = ArrayList<String>()
    private val icons = ArrayList<android.graphics.drawable.Drawable?>()
    private val tags = ArrayList<String>()

    init {
        if (!dockMode || !hasDockItem(dockBtns, "settings", null)) {
            labels.add("桌面设置"); icons.add(Store.normalizedEmoji(context, MapFeature.SETTINGS_EMOJI)); tags.add(TAG_SETTINGS)
        }
        if (!dockMode || !hasDockItem(dockBtns, "map", "home")) {
            labels.add("回家"); icons.add(Store.normalizedEmoji(context, MapFeature.HOME_EMOJI)); tags.add(TAG_HOME)
        }
        if (!dockMode || !hasDockItem(dockBtns, "map", "company")) {
            labels.add("公司"); icons.add(Store.normalizedEmoji(context, MapFeature.COMPANY_EMOJI)); tags.add(TAG_COMPANY)
        }
        if (!dockMode || !hasDockItem(dockBtns, "clean", null)) {
            labels.add("清理"); icons.add(Store.normalizedEmoji(context, MapFeature.CLEAN_EMOJI)); tags.add(TAG_CLEAN)
        }
        if (!dockMode) {
            labels.add("分屏"); icons.add(Store.normalizedEmoji(context, MapFeature.SPLIT_EMOJI)); tags.add(TAG_SPLIT_NEW)
            labels.add("返回主页"); icons.add(Store.normalizedEmoji(context, MapFeature.GOHOME_EMOJI)); tags.add(TAG_GOHOME)
        }
        val splits = SplitRepository.load(context)
        for (i in splits.indices) {
            val pair = splits[i]
            if (dockMode && dockBtns.any { it == Store.V2Button.split(pair[0], pair[1]) }) continue
            labels.add("${Store.label(context, pair[0])}|${Store.label(context, pair[1])}")
            icons.add(Store.normalizedSplitIcon(context, pair[0], pair[1])); tags.add("$SPLIT_PREFIX$i")
        }
        for (ri in apps) {
            val id = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
            if (dockMode && hasDockItem(dockBtns, "app", id)) continue
            labels.add(Store.label(context, id)); icons.add(Store.normalizedIcon(context, id)); tags.add(id)
        }
        applyUserOrder()
    }

    /**
     * 用户自定义排序（设置页"应用"选项卡，功能项/分屏/应用统一排序）。
     * 顺序表外的项（如新装应用）保持默认相对次序排尾（stable sort）。
     */
    private fun applyUserOrder() {
        val order = Store.drawerOrder(mContext)
        if (order.isEmpty()) return
        val idx = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { i, t -> if (!idx.containsKey(t)) idx[t] = i }
        val indices = (0 until tags.size).sortedBy { idx[tags[it]] ?: Int.MAX_VALUE }
        val newLabels = ArrayList<String>(tags.size)
        val newIcons = ArrayList<android.graphics.drawable.Drawable?>(tags.size)
        val newTags = ArrayList<String>(tags.size)
        for (i in indices) {
            newLabels.add(labels[i]); newIcons.add(icons[i]); newTags.add(tags[i])
        }
        labels.clear(); labels.addAll(newLabels)
        icons.clear(); icons.addAll(newIcons)
        tags.clear(); tags.addAll(newTags)
    }

    override fun getCount(): Int = labels.size
    override fun getItem(position: Int): Any = tags[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val cell = convertView
            ?: LayoutInflater.from(mContext).inflate(R.layout.item_drawer_cell, parent, false)
        val iv = cell.findViewById<ImageView>(R.id.drawer_icon)
        iv.setImageDrawable(icons[position])
        val lp = iv.layoutParams
        lp.width = iconSizePx
        lp.height = iconSizePx
        iv.layoutParams = lp
        // 格子正方形：高度跟随 GridView 列宽；极端字号下兜底保证内容不裁切
        val cellLp = cell.layoutParams
        cellLp.height = maxOf(
            (parent as android.widget.GridView).columnWidth,
            iconSizePx + labelSizePx + 22
        )
        cell.layoutParams = cellLp
        val tv = cell.findViewById<TextView>(R.id.drawer_label)
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, labelSizePx.toFloat())
        tv.text = labels[position]
        cell.tag = tags[position]
        return cell
    }

    companion object {
        internal const val TAG_SETTINGS = "settings"
        internal const val TAG_HOME = "feat_home"
        internal const val TAG_COMPANY = "feat_company"
        internal const val TAG_CLEAN = "feat_clean"
        internal const val TAG_SPLIT_NEW = "split_new"
        internal const val TAG_GOHOME = "feat_gohome"
        internal const val SPLIT_PREFIX = "split:"

        /** 底栏中是否已存在该类按钮（type 匹配，action 为 null 时只看 type） */
        internal fun hasDockItem(btns: List<Store.V2Button>, type: String, action: String?): Boolean {
            for (b in btns) {
                if (type == b.type && (action == null || action == b.action)) return true
            }
            return false
        }
    }
}
