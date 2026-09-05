package com.android.launcher37
import com.android.launcher37.LauncherActivity
import com.android.launcher37.R
import com.android.launcher37.drawer.DrawerAdapter
import com.android.launcher37.data.SplitRepository
import com.android.launcher37.pip.PipService
import com.android.launcher37.data.UpdateChecker
import com.android.launcher37.drawer.AppDrawer
import com.android.launcher37.data.Store
import com.android.launcher37.util.Prefs
import com.android.launcher37.util.SharedExecutor
import com.android.launcher37.navi.MapFeature
import com.android.launcher37.data.AppQuery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.android.launcher37.home.widget.HomeLayout
import com.android.launcher37.home.widget.LayoutRepository
import com.android.launcher37.home.widget.NamedLayout
import com.android.launcher37.home.widget.PageHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 桌面设置：左侧选项卡（布局/应用/通用）+ 右侧内容面板。
 *
 * 主页各部件（时间/歌词/车速/VD/应用列表）的外观属性已全部移交主页设计器
 * （每个实例独立存于布局 JSON 的 config），此处仅保留：布局入口、应用抽屉排序与
 * 外观、通用（直达抽屉/延迟/更新）。尺寸类参数为 px 滑条，全部即时写入
 * `launcher37_config`。
 */
class SettingsActivity : Activity() {

    companion object {
        // ── 车速/导航行显隐键（SpeedWidget 实例 config 键名复用） ─────
        // 导航行显隐（每个字段独立）
        const val KEY_SHOW_NAVI_TURN = "show_navi_turn"
        const val KEY_SHOW_NAVI_ROAD = "show_navi_road"
        const val KEY_SHOW_NAVI_DEST = "show_navi_dest"
        const val KEY_SHOW_NAVI_ETA = "show_navi_eta"
        const val KEY_SHOW_NAVI_ETA_TEXT = "show_navi_eta_text"
        const val KEY_SHOW_NAVI_LIGHT_COUNT = "show_navi_light_count"
        const val KEY_SHOW_NAVI_EXIT = "show_navi_exit"
        const val KEY_SHOW_NAVI_DIRECTION = "show_navi_direction"
        const val KEY_SHOW_NAVI_ALERT = "show_navi_alert"
        // 时间
        const val KEY_TS_TIME = "ts_time"

        // ── 字号/尺寸（px，int）──
        // 车速区域字号（导航/巡航共用）
        const val KEY_TS_NAVI_SPEED = "ts_navi_speed"
        const val KEY_TS_NAVI_KMH = "ts_navi_kmh"
        const val KEY_TS_NAVI_LIMIT = "ts_navi_limit"
        const val KEY_TS_NAVI_TRAFFIC_SEC = "ts_navi_traffic_sec"
        // 导航行字号
        const val KEY_TS_NAVI_TURN = "ts_navi_turn"
        const val KEY_TS_NAVI_ROAD = "ts_navi_road"
        const val KEY_TS_NAVI_DEST = "ts_navi_dest"
        const val KEY_TS_NAVI_ETA = "ts_navi_eta"
        const val KEY_TS_NAVI_ETA_TEXT = "ts_navi_eta_text"
        const val KEY_TS_NAVI_LIGHT_COUNT = "ts_navi_light_count"
        const val KEY_TS_NAVI_EXIT = "ts_navi_exit"
        const val KEY_TS_NAVI_DIRECTION = "ts_navi_direction"
        const val KEY_TS_NAVI_ALERT = "ts_navi_alert"
        // 音乐（AppDrawer 列表标题字号沿用）
        const val KEY_TS_MUSIC_TITLE = "ts_music_title"

        // ── 行序键（车速/导航行序，存于 WidgetSpec.config）──
        const val KEY_NAVI_ORDER = "navi_row_order"
        const val KEY_CRUISE_ORDER = "cruise_row_order"

        // ── 全部应用外观（抽屉/悬浮窗共用，px/int）──────────────────
        const val KEY_DRAWER_ICON_SIZE = "drawer_icon_size"
        const val KEY_DRAWER_ICON_GAP = "drawer_icon_gap"
        const val KEY_DRAWER_LABEL_SIZE = "drawer_label_size"
        /** 全部应用弹窗宽度占屏幕百分比（50~95，默认 75） */
        const val KEY_DRAWER_WIDTH_PCT = "drawer_width_pct"
        /** 全部应用弹窗高度占屏幕百分比（50~95，默认 75） */
        const val KEY_DRAWER_HEIGHT_PCT = "drawer_height_pct"

        // ── 通用 ───────────────────────
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar"

        /** 状态栏纯黑（关闭透明跟随主题）：开启后状态栏始终纯黑 */
        const val KEY_OPAQUE_STATUS_BAR = "opaque_status_bar"
        const val KEY_HOME_DIRECT_APP_DRAWER = "home_direct_app_drawer"
        /** 桌面拉起 PIP 前的延迟毫秒（0=立即；恢复 d4584a0 删除的 PIP_START_DELAY_MS=250） */
        const val KEY_PIP_START_DELAY = "pip_start_delay"
        /** PipService 拉起/拉回任务前的延迟毫秒（0=立即；恢复 d4584a0 删除的 LAUNCH_DELAY_MS=500） */
        const val KEY_VD_LAUNCH_DELAY = "vd_launch_delay"

        /** 车速卡字号 key：范围 [10,150]；其余字体 [10,50] */
        private val FONT_RANGE_SMALL = setOf(
            KEY_TS_NAVI_SPEED, KEY_TS_NAVI_KMH, KEY_TS_NAVI_LIMIT, KEY_TS_NAVI_TRAFFIC_SEC
        )
    }

    private lateinit var mTabs: Array<TextView>
    private lateinit var mPanels: Array<View>
    private lateinit var mTabActiveBg: GradientDrawable
    private var mUpdater: UpdateChecker? = null
    private var mTvUpdateStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        mTabActiveBg = GradientDrawable().apply {
            cornerRadius = 18f
            setColor(resources.getColor(R.color.surface, theme))
        }
        mTabs = arrayOf(
            findViewById(R.id.tab_layout),
            findViewById(R.id.tab_apps),
            findViewById(R.id.tab_general)
        )
        mPanels = arrayOf(
            findViewById(R.id.panel_layout),
            findViewById(R.id.panel_apps),
            findViewById(R.id.panel_general)
        )
        for (i in mTabs.indices) {
            val index = i
            mTabs[i].setOnClickListener { switchTab(index) }
        }
        switchTab(0)

        bindLayoutTab()
        bindAppsTab()
        bindDrawerLooks()
        bindGeneralTab()
    }

    override fun onDestroy() {
        // mUpdater（= UpdateChecker）持 application 强引用 + 可选 Activity 引用
        // （mActivity）；显式 release 清空 mActivity 避免 UpdateChecker 异步
        // 回调进死 Activity 抛 BadTokenException。
        mUpdater?.release()
        mUpdater = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 关闭设置界面时自动重启桌面（重建 LauncherActivity 让设置生效），不再需要手动重启按钮
        restartLauncher()
    }

    /**
     * 重建 LauncherActivity 让刚才改的设置生效（`onCreate` 重新读 SP）。
     * `CLEAR_TASK + NEW_TASK` 保证 task 内所有 Activity（含自己）被销毁后，用新 Intent 重新
     * 启动 LauncherActivity —— 进程保留，PipService 不受影响。
     */
    private fun restartLauncher() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            // 带 HOME category：重启后进桌面，而不是走"其他 App 上弹悬浮窗"路径
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    /** 左侧选项卡切换：选中项卡片底 + 主文字色，其余透明 + 次文字色 */
    private fun switchTab(index: Int) {
        val primary = resources.getColor(R.color.foreground, theme)
        val secondary = resources.getColor(R.color.foreground_secondary, theme)
        for (i in mTabs.indices) {
            val active = i == index
            mTabs[i].background = if (active) mTabActiveBg else null
            mTabs[i].setTextColor(if (active) primary else secondary)
            mTabs[i].paint.isFakeBoldText = active
            mPanels[i].visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    private fun bindLayoutTab() {
        // 卡片显隐/尺寸/状态栏已全部移交主页设计器（布局 JSON 持久化 / 工具栏开关），
        // 此处仅保留布局管理；设计器入口在布局行的「设计器」按钮
        rebuildLayoutList()
    }

    // ── 布局管理（内嵌列表：默认布局 / 添加 / 自定义布局行右侧 删除·重命名·属性） ──────

    /** 内嵌布局列表：默认布局行 + 添加按钮 + 自定义布局行（右侧 删除/重命名/属性） */
    private fun rebuildLayoutList() {
        val ctx = this
        val container = findViewById<LinearLayout>(R.id.layout_list_container) ?: return
        container.removeAllViews()
        val active = LayoutRepository.activeName(ctx)
        val names = LayoutRepository.listNames(ctx)
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels

        // 按钮样式与通用卡 ThemeButton 一致：18px 字号、前景色、56px 高、左右 20px 内边距
        // （注意直接用 px 值，不经 px() 缩放，保持与 XML ThemeButton 同高）
        fun actionButton(text: String, onClick: () -> Unit): Button =
            Button(ctx).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f)
                setTextColor(resources.getColor(R.color.foreground, theme))
                setBackgroundResource(R.drawable.bg_btn)
                stateListAnimator = null
                minHeight = 56
                minimumHeight = 0
                minimumWidth = 0
                minWidth = 0
                setPadding(20, 0, 20, 0)
                setOnClickListener { onClick() }
            }

        // 复制布局为「布局N」并激活应用
        fun copyLayout(srcName: String) {
            val src = LayoutRepository.load(ctx, srcName, sw, sh) ?: return
            var n = 1
            while (names.any { it == "布局$n" }) n++
            LayoutRepository.save(ctx, NamedLayout("布局$n", src.pages))
            LayoutRepository.setActive(ctx, "布局$n")
            PageHost.instance?.applyLayout("布局$n")
            Toast.makeText(ctx, "已复制为「布局$n」，进入设计器可编辑", Toast.LENGTH_LONG).show()
            rebuildLayoutList()
        }

        fun nameRow(name: String, isBuiltin: Boolean) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                // 与应用/通用界面的 CheckRow 同款配色：bg_app_order_row 背景、64px 高、行间 6px
                setBackgroundResource(R.drawable.bg_app_order_row)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 64).apply { topMargin = 6 }
                setPadding(16, 0, 16, 0)
            }
            row.addView(TextView(ctx).apply {
                text = name
                // 与应用/通用行主文字一致：20px + foreground（未显式设色会走主题默认色，色值有偏差）
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
                setTextColor(resources.getColor(R.color.foreground, theme))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
            })
            // 设为默认布局（启动时加载）：当前默认置灰不可点
            val isDefault = name == active
            row.addView(actionButton(if (isDefault) "✅ 默认" else "⭐ 设默认") {
                LayoutRepository.setActive(ctx, name)
                PageHost.instance?.applyLayout(name)
                Toast.makeText(ctx, "已设「$name」为默认布局", Toast.LENGTH_SHORT).show()
                rebuildLayoutList()
            }.apply { isEnabled = !isDefault })
            if (isBuiltin) {
                row.addView(actionButton("📋 复制") { copyLayout(name) })
            } else {
                row.addView(actionButton("🗑️ 删除") {
                compactDialog(
                    "删除布局",
                    message("确定删除「$name」？"),
                    listOf(
                        "取消" to { },
                        "删除" to {
                            LayoutRepository.delete(ctx, name)
                            PageHost.instance?.applyLayout(LayoutRepository.activeName(ctx))
                            rebuildLayoutList()
                        }
                    )
                )
            })
                row.addView(actionButton("✏️ 重命名") {
                    val input = android.widget.EditText(ctx)
                    input.setText(name)
                    // 输入框统一配色（surface_variant 底 + foreground 字），去系统下划线风格
                    input.setTextColor(ctx.getColor(com.android.launcher37.R.color.foreground))
                    input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f)
                    input.setBackgroundResource(com.android.launcher37.R.drawable.bg_input)
                    input.setPadding(12, 10, 12, 10)
                    compactDialog(
                        "重命名布局",
                        input,
                        listOf(
                            "取消" to { },
                            "确定" to {
                                val newName = input.text.toString().trim()
                                val valid = newName.isNotEmpty() && newName != name &&
                                    !LayoutRepository.listNames(ctx).any { it == newName }
                                if (valid) {
                                    val src = LayoutRepository.load(ctx, name, sw, sh)
                                    if (src != null) {
                                        LayoutRepository.save(ctx, NamedLayout(newName, src.pages))
                                        LayoutRepository.delete(ctx, name)
                                        if (active == name) {
                                            LayoutRepository.setActive(ctx, newName)
                                            PageHost.instance?.applyLayout(newName)
                                        }
                                        rebuildLayoutList()
                                    }
                                }
                            }
                        )
                    )
                })
                row.addView(actionButton("⚙️ 属性") { showLayoutProps(name) })
                // 设计器入口（原顶部「布局设计」按钮移此）：先切到该布局，再以设计模式拉起桌面
                row.addView(actionButton("🎨 设计器") {
                    if (name != LayoutRepository.activeName(ctx)) {
                        PageHost.instance?.applyLayout(name)
                    }
                    ctx.startActivity(
                        android.content.Intent(ctx, LauncherActivity::class.java)
                            .putExtra(LauncherActivity.EXTRA_DESIGNER, true)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                })
                row.addView(actionButton("📋 复制") { copyLayout(name) })
            }
            container.addView(row)
        }

        for (name in names) nameRow(name, LayoutRepository.isBuiltIn(name))
    }

    /** 单布局属性：状态栏显隐 + 控件间距 + 屏幕边距（保存并应用） */
    private fun showLayoutProps(name: String) {
        val ctx = this
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val page = LayoutRepository.load(ctx, name, sw, sh)?.pages?.firstOrNull()

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), 0)
        }
        var hideBar = page?.hideStatusBar ?: false
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = "显示状态栏"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f)
                setTextColor(getColor(R.color.foreground))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(android.widget.CheckBox(ctx).apply {
                isChecked = !(page?.hideStatusBar ?: false)
                setOnCheckedChangeListener { _, checked -> hideBar = !checked }
            })
        })

        fun sliderRow(label: String, value: Int, onApply: (Int) -> Unit): android.view.View {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, px(10), 0, 0)
            }
            row.addView(TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f)
                setTextColor(getColor(R.color.foreground))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // 实时数值显示（px）：辅助信息走 secondary（与 item_seek_row 的 seek_value 同层级）
            val valueText = TextView(ctx).apply {
                text = "${value}px"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f)
                setTextColor(getColor(R.color.foreground_secondary))
                setPadding(0, 0, px(8), 0)
            }
            row.addView(valueText)
            row.addView(SeekBar(ctx).apply {
                max = 50
                progress = value
                layoutParams = LinearLayout.LayoutParams(px(280),
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        valueText.text = "${p}px"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) = onApply(sb.progress)
                })
            })
            return row
        }

        fun savePage(transform: (HomeLayout) -> HomeLayout) {
            val src = LayoutRepository.load(ctx, name, sw, sh)?.pages?.firstOrNull() ?: return
            LayoutRepository.save(ctx, NamedLayout(name, listOf(transform(src))))
            // 修改的是激活布局 → 实时应用桌面
            if (LayoutRepository.activeName(ctx) == name) {
                PageHost.instance?.applyLayout(name)
            }
        }

        // 经典布局只读：间距/边距不允许设置，仅可复制后编辑
        if (!LayoutRepository.isBuiltIn(name)) {
            box.addView(sliderRow("控件间距", page?.gap ?: HomeLayout.DEFAULT_GAP) { v ->
                savePage { it.copy(gap = v) }
            })
            box.addView(sliderRow("屏幕边距", page?.margin ?: 0) { v ->
                savePage { it.copy(margin = v) }
            })
        }

        compactDialog(
            "布局属性 - $name",
            box,
            listOf(
                "取消" to { },
                "确定" to { savePage { it.copy(hideStatusBar = hideBar) } }
            )
        )
    }

    // ── 紧凑对话框共享骨架 ───────────────────────────

    /** 紧凑标题行（14px 字、18/9/18/3 内边距），替代系统 AlertDialog 大标题 */
    private fun dialogTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.foreground))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f)
        includeFontPadding = false
        setPadding(18, 9, 18, 3)
    }

    /** 紧凑正文（15px 字），替代系统 AlertDialog 大正文 */
    private fun message(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.foreground))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f)
        includeFontPadding = false
        setPadding(18, 12, 18, 0)
    }

    /** 紧凑按钮（与布局行 actionButton 同款：18px 字、56px 高、20px 水平内边距、统一 bg_btn 背景） */
    private fun dialogButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f)
        setTextColor(getColor(R.color.foreground))
        setBackgroundResource(R.drawable.bg_btn)
        stateListAnimator = null
        minHeight = 56
        minimumHeight = 0
        minimumWidth = 0
        setPadding(20, 0, 20, 0)
        setOnClickListener { onClick() }
    }

    /**
     * 紧凑对话框骨架：14px 标题 + 内容 + 底部按钮行（按钮从右往左：取消在前），
     * 替代系统 AlertDialog 的 sp 大标题/大正文；点空白关闭（不触发按钮）。
     */
    private fun compactDialog(title: String, content: View, buttons: List<Pair<String, () -> Unit>>) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(dialogTitle(title))
        root.addView(content)
        var dlg: AlertDialog? = null
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(18, 12, 12, 6)
        }
        buttons.forEach { (label, action) ->
            btnRow.addView(dialogButton(label) { dlg?.dismiss(); action() })
        }
        root.addView(btnRow)
        dlg = AlertDialog.Builder(this).setView(root).create()
        dlg.window?.setBackgroundDrawableResource(R.color.surface_highlight)
        dlg.show()
        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.5f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** dp→px（density 1.5） */
    private fun px(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ── 应用选项卡（抽屉排序与隐藏 + 全部应用外观） ──────────────────────

    /** 应用选项卡行模型（tag / 显示名 / 图标） */
    private data class OrderRow(val tag: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    /** 全部可启动应用入口（IO 线程预载，默认"用户优先+字典序"） */
    private var mAppEntries: List<android.content.pm.ResolveInfo> = emptyList()

    /** 抽屉项顺序（tag 全集：功能项/分屏/应用；含隐藏项，隐藏仅决定显示与否） */
    private val mAppOrder: ArrayList<String> = ArrayList()

    /** 抽屉隐藏应用集合 */
    private val mAppHidden: LinkedHashSet<String> = LinkedHashSet()

    /** IO 线程预载应用列表后合并已保存顺序，再渲染行 */
    private fun bindAppsTab() {
        mAppHidden.clear()
        mAppHidden.addAll(Store.drawerHidden(this))
        mAppOrder.clear()
        mAppOrder.addAll(Store.drawerOrder(this))
        SharedExecutor.io().execute {
            val entries = AppQuery.launcherEntriesSorted(this)
            // 预热 icon 缓存，主线程渲染行时不做 binder 调用
            for (ri in entries) Store.normalizedIcon(this, AppQuery.appId(ri))
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                mAppEntries = entries
                rebuildAppsPanel()
            }
        }
    }

    /** 与 DrawerAdapter 构造顺序一致的默认行序（功能项 → 分屏 → 应用） */
    private fun buildDefaultRows(): List<OrderRow> {
        val rows = ArrayList<OrderRow>()
        val names = listOf("桌面设置", "回家", "公司", "清理", "分屏", "返回主页", "重启桌面")
        val tags = listOf(
            DrawerAdapter.TAG_SETTINGS, DrawerAdapter.TAG_HOME, DrawerAdapter.TAG_COMPANY,
            DrawerAdapter.TAG_CLEAN, DrawerAdapter.TAG_SPLIT_NEW, DrawerAdapter.TAG_GOHOME,
            DrawerAdapter.TAG_RESTART
        )
        val emojis = listOf(
            MapFeature.SETTINGS_EMOJI, MapFeature.HOME_EMOJI, MapFeature.COMPANY_EMOJI,
            MapFeature.CLEAN_EMOJI, MapFeature.SPLIT_EMOJI, MapFeature.GOHOME_EMOJI,
            MapFeature.RESTART_EMOJI
        )
        for (i in tags.indices) rows.add(OrderRow(tags[i], names[i], Store.normalizedEmoji(this, emojis[i])))
        // 已保存布局（与 DrawerAdapter 同序：内置 + 用户），参与应用排序
        for (name in LayoutRepository.listNames(this)) {
            rows.add(OrderRow(
                "${DrawerAdapter.LAYOUT_PREFIX}$name",
                name,
                Store.normalizedGlyphIcon(this, R.drawable.ic_layout)
            ))
        }
        val splits = SplitRepository.load(this)
        for (i in splits.indices) {
            val pair = splits[i]
            rows.add(OrderRow(
                "${DrawerAdapter.SPLIT_PREFIX}$i",
                "${Store.label(this, pair[0])}|${Store.label(this, pair[1])}",
                Store.normalizedSplitIcon(this, pair[0], pair[1])
            ))
        }
        for (ri in mAppEntries) {
            val id = AppQuery.appId(ri)
            rows.add(OrderRow(id, Store.label(this, id), Store.normalizedIcon(this, id)))
        }
        return rows
    }

    private fun rebuildAppsPanel() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.box_apps) ?: return
        val defaults = buildDefaultRows()
        val byTag = HashMap<String, OrderRow>(defaults.size * 2)
        for (r in defaults) byTag[r.tag] = r
        // 合并：已保存顺序在前（仅保留仍存在的项），新项（新装应用/分屏/返回主页）按默认序追加
        val merged = ArrayList<OrderRow>(defaults.size)
        val seen = HashSet<String>()
        for (tag in mAppOrder) {
            byTag[tag]?.let { if (seen.add(tag)) merged.add(it) }
        }
        for (r in defaults) if (seen.add(r.tag)) merged.add(r)
        mAppOrder.clear()
        mAppOrder.addAll(merged.map { it.tag })
        // 每次进入设置页重建 adapter，避免跨 Activity 实例复用
        mOrderAdapter = OrderAdapter()
        list.adapter = mOrderAdapter
        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        mItemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(mOrderAdapter!!.DragCallback())
        mItemTouchHelper!!.attachToRecyclerView(list)
        mOrderAdapter!!.setRows(merged)
    }

    /**
     * 排序/隐藏列表适配器：长按行（或按住 ≡ 手柄）上下拖动排序，
     * checkbox 勾选隐藏。数据顺序 = mAppOrder 顺序。
     */
    private inner class OrderAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<OrderAdapter.VH>() {

        private val rows = ArrayList<OrderRow>()
        /** 拖动中顺序已改：松手时持久化 */
        private var mDragDirty = false

        fun setRows(list: List<OrderRow>) {
            rows.clear()
            rows.addAll(list)
            mDragDirty = false
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            java.util.Collections.swap(rows, from, to)
            notifyItemMoved(from, to)
            mAppOrder.clear()
            mAppOrder.addAll(rows.map { it.tag })
            mDragDirty = true
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_app_order_row, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.icon.setImageDrawable(row.icon)
            holder.label.text = row.label
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = row.tag in mAppHidden
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked) mAppHidden.add(row.tag) else mAppHidden.remove(row.tag)
                Store.saveDrawerHidden(this@SettingsActivity, mAppHidden)
            }
            // 手柄拖拽（长按行也可拖：ItemTouchHelper.isLongPressDragEnabled 默认 true）
            holder.itemView.findViewById<TextView>(R.id.btn_drag).setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    mItemTouchHelper?.startDrag(holder)
                    return@setOnTouchListener true
                }
                false
            }
        }

        inner class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val icon: android.widget.ImageView = v.findViewById(R.id.app_icon)
            val label: TextView = v.findViewById(R.id.app_label)
            val check: CheckBox = v.findViewById(R.id.item_check)
        }

        inner class DragCallback : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Int = androidx.recyclerview.widget.ItemTouchHelper.Callback.makeMovementFlags(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0)

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                from: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                to: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {}

            override fun onSelectedChanged(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder?, action: Int) {
                super.onSelectedChanged(vh, action)
                // 拖起浮起：放大 + 抬升，落位还原
                if (action == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh?.itemView?.animate()?.scaleX(1.04f)?.scaleY(1.04f)
                        ?.translationZ(12f)?.setDuration(80)?.start()
                }
            }

            override fun clearView(rv: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120).start()
                if (mDragDirty) {
                    mDragDirty = false
                    Store.saveDrawerOrder(this@SettingsActivity, mAppOrder)
                }
            }
        }

    }

    private var mOrderAdapter: OrderAdapter? = null
    private var mItemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null

    private fun bindDrawerLooks() {
        val box = findViewById<LinearLayout>(R.id.box_drawer_seeks)
        box.removeAllViews()
        bindSeek(box, "窗口宽度", KEY_DRAWER_WIDTH_PCT, 75, 50, 95, unit = "%")
        bindSeek(box, "窗口高度", KEY_DRAWER_HEIGHT_PCT, 75, 50, 95, unit = "%")
        bindSeek(box, "图标大小", KEY_DRAWER_ICON_SIZE, 64, 50, 250)
        bindSeek(box, "间距", KEY_DRAWER_ICON_GAP, 8, 0, 40)
        bindFontSeek(box, "字体大小", KEY_DRAWER_LABEL_SIZE, 17)
    }

    private fun prefs(): SharedPreferences = Prefs.of(this)

    private fun bindCheck(viewId: Int, key: String, def: Boolean = true) {
        val cb = findViewById<CheckBox>(viewId)
        cb.isChecked = prefs().getBoolean(key, def)
        cb.setOnCheckedChangeListener { _, isChecked ->
            prefs().edit().putBoolean(key, isChecked).apply()
        }
    }

    /** 滑条行（SeekBar）：范围 [min,max] 步进 step，拖动即写 SP，右侧实时显示当前值 */
    private fun bindSeek(box: LinearLayout, name: String, key: String, def: Int, min: Int, max: Int, unit: String = "px", step: Int = 1) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_seek_row, box, false)
        row.findViewById<TextView>(R.id.seek_name).text = name
        val valTv = row.findViewById<TextView>(R.id.seek_value)
        val bar = row.findViewById<SeekBar>(R.id.seek_bar)
        val cur = prefs().getInt(key, def).coerceIn(min, max)
        bar.max = (max - min) / step
        bar.progress = (cur - min) / step
        valTv.text = "$cur$unit"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + progress * step
                valTv.text = "$v$unit"
                prefs().edit().putInt(key, v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        box.addView(row)
    }

    /**
     * 字体滑条行（SeekBar）：字体范围 [10,50]（车速卡 [10,150]），
     * 拖动即写 SP，右侧实时显示当前值。
     */
    private fun bindFontSeek(box: LinearLayout, name: String, key: String, def: Int) {
        val max = if (key in FONT_RANGE_SMALL) 150 else 50
        val row = LayoutInflater.from(this).inflate(R.layout.item_font_seek_row, box, false)
        row.findViewById<TextView>(R.id.seek_name).text = name
        val valTv = row.findViewById<TextView>(R.id.seek_value)
        val bar = row.findViewById<SeekBar>(R.id.font_seek)
        val cur = prefs().getInt(key, def).coerceIn(10, max)
        bar.max = max - 10
        bar.progress = cur - 10
        valTv.text = "$cur px"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = progress + 10
                valTv.text = "$v px"
                prefs().edit().putInt(key, v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        box.addView(row)
    }

    // 颜色设置页已移除：主题色改由 values/colors.xml + values-night/colors.xml 自动切换

    private fun bindGeneralTab() {
        bindCheck(R.id.cb_home_direct_drawer, KEY_HOME_DIRECT_APP_DRAWER)
        bindCheck(R.id.cb_opaque_status_bar, KEY_OPAQUE_STATUS_BAR, def = false)
        val box = findViewById<LinearLayout>(R.id.box_general_seeks)
        bindSeek(box, "桌面拉起延迟", KEY_PIP_START_DELAY, 200, 0, 1000, unit = "毫秒", step = 10)
        bindSeek(box, "VD拉起延迟", KEY_VD_LAUNCH_DELAY, 200, 0, 1000, unit = "毫秒", step = 10)
        mTvUpdateStatus = findViewById(R.id.tv_update_status)
        findViewById<TextView>(R.id.tv_version_info).text = buildVersionInfo()
        val btn = findViewById<Button>(R.id.btn_check_update)
        val updater = UpdateChecker(this, object : UpdateChecker.Listener {
            override fun onUpdateStart() {
                mTvUpdateStatus?.text = "正在检查更新…"
            }
            override fun onUpdateFound(info: UpdateChecker.UpdateInfo) {
                mTvUpdateStatus?.text = "发现新版本 v${info.versionName} (code=${info.versionCode})，等待确认"
                try {
                    compactDialog(
                        "发现新版本 v${info.versionName}",
                        message("是否立即下载并安装？"),
                        listOf(
                            "稍后" to { mTvUpdateStatus?.text = "已取消，可重新检查更新" },
                            "立即更新" to {
                                mTvUpdateStatus?.text = "开始下载…"
                                mUpdater?.confirmUpdate()
                            }
                        )
                    )
                } catch (e: Exception) {
                    mTvUpdateStatus?.text = "弹窗失败：${e.message}"
                }
            }
            override fun onUpToDate() {
                mTvUpdateStatus?.text = "已是最新版本"
            }
            override fun onProgress(percent: Int) {
                mTvUpdateStatus?.text = "下载中 $percent%"
            }
            override fun onError(message: String) {
                mTvUpdateStatus?.text = message
            }
        })
        mUpdater = updater
        btn.setOnClickListener { updater.checkManually() }
    }

    /**
     * 构造"当前版本"信息字符串（用于通用 tab 版本行 TextView，与"检查更新"按钮同行）。
     * 内容：versionName（来自 packageInfo）+ 构建时间（北京时间，从 BuildConfig.BUILD_TIME_EPOCH 读）。
     */
    private fun buildVersionInfo(): String {
        val vn = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        val buildEpoch = BuildConfig.BUILD_TIME_EPOCH
        val cstFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        val buildTimeCst = cstFmt.format(Date(buildEpoch * 1000L))
        return "当前版本：v$vn  构建：$buildTimeCst"
    }
}
