package com.android.launcher37

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
        // 巡航行显隐（每个字段独立）
        const val KEY_SHOW_CRUISE_ROAD = "show_cruise_road"
        const val KEY_SHOW_CRUISE_DIRECTION = "show_cruise_direction"
        const val KEY_SHOW_CRUISE_ALERT = "show_cruise_alert"
        // 时间
        const val KEY_TS_TIME = "ts_time"

        // ── 字号/尺寸（px，int）──
        // 车速区域字号（导航模式）
        const val KEY_TS_NAVI_SPEED = "ts_navi_speed"
        const val KEY_TS_NAVI_KMH = "ts_navi_kmh"
        const val KEY_TS_NAVI_LIMIT = "ts_navi_limit"
        const val KEY_TS_NAVI_TRAFFIC_SEC = "ts_navi_traffic_sec"
        // 车速区域字号（巡航模式）
        const val KEY_TS_CRUISE_SPEED = "ts_cruise_speed"
        const val KEY_TS_CRUISE_KMH = "ts_cruise_kmh"
        const val KEY_TS_CRUISE_LIMIT = "ts_cruise_limit"
        const val KEY_TS_CRUISE_TRAFFIC_SEC = "ts_cruise_traffic_sec"
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
        // 巡航行字号
        const val KEY_TS_CRUISE_ROAD = "ts_cruise_road"
        const val KEY_TS_CRUISE_DIRECTION = "ts_cruise_direction"
        const val KEY_TS_CRUISE_ALERT = "ts_cruise_alert"
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
        const val KEY_HOME_DIRECT_APP_DRAWER = "home_direct_app_drawer"
        /** 桌面拉起 PIP 前的延迟毫秒（0=立即；恢复 d4584a0 删除的 PIP_START_DELAY_MS=250） */
        const val KEY_PIP_START_DELAY = "pip_start_delay"
        /** PipService 拉起/拉回任务前的延迟毫秒（0=立即；恢复 d4584a0 删除的 LAUNCH_DELAY_MS=500） */
        const val KEY_VD_LAUNCH_DELAY = "vd_launch_delay"

        /** 车速卡字号 key：范围 [10,150]；其余字体 [10,50] */
        private val FONT_RANGE_SMALL = setOf(
            KEY_TS_NAVI_SPEED, KEY_TS_NAVI_KMH, KEY_TS_NAVI_LIMIT, KEY_TS_NAVI_TRAFFIC_SEC,
            KEY_TS_CRUISE_SPEED, KEY_TS_CRUISE_KMH, KEY_TS_CRUISE_LIMIT, KEY_TS_CRUISE_TRAFFIC_SEC
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
        findViewById<Button>(R.id.btn_restart_launcher).setOnClickListener { restartLauncher() }
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
        val intent = Intent(this, LauncherActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
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
        // 此处仅保留设计入口
        findViewById<Button>(R.id.btn_layout_design).setOnClickListener {
            // 主页即设计器：以设计模式拉起桌面（singleTask，运行中走 onNewIntent）
            startActivity(
                android.content.Intent(this, LauncherActivity::class.java)
                    .putExtra(LauncherActivity.EXTRA_DESIGNER, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

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

    private fun bindCheck(viewId: Int, key: String) {
        val cb = findViewById<CheckBox>(viewId)
        cb.isChecked = prefs().getBoolean(key, true)
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
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("发现新版本 v${info.versionName}")
                        .setMessage("是否立即下载并安装？")
                        .setPositiveButton("立即更新") { _, _ ->
                            mTvUpdateStatus?.text = "开始下载…"
                            mUpdater?.confirmUpdate()
                        }
                        .setNegativeButton("稍后") { _, _ ->
                            mTvUpdateStatus?.text = "已取消，可重新检查更新"
                        }
                        .show()
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
