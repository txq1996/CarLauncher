package com.android.launcher37

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast

/**
 * 全部应用悬浮窗（WindowManager TYPE_APPLICATION_OVERLAY，参考 autodock PanelService）。
 * - 直接用 Application WindowManager，Activity 1x1 透明 finish 后仍盖在原 App 上，不返桌面
 * - 二次启动关闭（toggle），无动画避免背后闪烁
 */
object DrawerOverlay {

    private var sOverlayRoot: ViewGroup? = null
    private var sWindowManager: WindowManager? = null
    private var sDismissAction: Runnable? = null
    private var sStatsRunnable: Runnable? = null

    private const val POPUP_W = 1000
    private const val POPUP_H = 620

    fun isShowing(): Boolean = sOverlayRoot != null

    fun dismiss() {
        val wm = sWindowManager
        val root = sOverlayRoot
        if (wm != null && root != null) {
            if (root.tag != "fallback") {
                try { wm.removeView(root) } catch (_: Exception) {}
            } else {
                AppDrawer.dismissIfShowing()
            }
        } else if (root?.tag == "fallback") {
            AppDrawer.dismissIfShowing()
        }
        sOverlayRoot = null
        sWindowManager = null
        stopStatsTicker()
        sDismissAction?.run()
        sDismissAction = null
    }

    fun toggle(ctx: Context) {
        if (isShowing()) dismiss() else show(ctx, null)
    }

    fun show(ctx: Context, dismissAction: Runnable?) {
        if (!canDrawOverlays(ctx)) {
            if (ctx is Activity) {
                AppDrawer.show(ctx)
                AppDrawer.addOnDismissListener(object : Runnable {
                    override fun run() {
                        AppDrawer.removeOnDismissListener(this)
                        sDismissAction?.run()
                        sDismissAction = null
                    }
                })
                sDismissAction = dismissAction
                sOverlayRoot = FrameLayout(ctx).apply { tag = "fallback" }
                sWindowManager = null
            }
            return
        }
        if (isShowing()) dismiss()
        sDismissAction = dismissAction
        showOverlayInternal(ctx.applicationContext)
    }

    private fun canDrawOverlays(c: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(c) else true
        } catch (_: Exception) { true }
    }

    private fun showOverlayInternal(appCtx: Context) {
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sWindowManager = wm
        val themed = if (appCtx is Activity) HoloPopup.themedContext(appCtx) else appCtx

        val root = FrameLayout(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // 半透遮罩，点击关闭（无动画）
        val scrim = View(appCtx).apply {
            setBackgroundColor(0x4D000000) // 30% 黑
            isClickable = true
            setOnClickListener { dismiss() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 卡片直接复用 dialog 布局，保持原有直角风格，无圆角无阴影
        val content: View = LayoutInflater.from(themed).inflate(R.layout.dialog_app_drawer, root, false)
        val cardLp = FrameLayout.LayoutParams(POPUP_W, POPUP_H, Gravity.CENTER)
        // 按屏幕自适应，避免超出
        try {
            val dm = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            val maxW = (dm.widthPixels * 0.88f).toInt()
            val maxH = (dm.heightPixels * 0.78f).toInt()
            if (cardLp.width > maxW) cardLp.width = maxW
            if (cardLp.height > maxH) cardLp.height = maxH
        } catch (_: Exception) {}
        root.addView(content, cardLp)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        sOverlayRoot = root
        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            sOverlayRoot = null; sWindowManager = null; return
        }

        bindDrawerContent(appCtx, content)

        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                dismiss(); return@setOnKeyListener true
            }
            false
        }
    }

    private fun bindDrawerContent(appCtx: Context, content: View) {
        val tvTitle = content.findViewById<TextView>(R.id.tv_drawer_title)
        tvTitle.text = "全部应用"
        // 同步设置：标题随时间字号，标签随音乐标题字号（不新增设置项）
        val p = Prefs.of(appCtx)
        val titleSize = p.getInt(SettingsActivity.KEY_TS_TIME, 28)
        val labelSize = p.getInt(SettingsActivity.KEY_TS_MUSIC_TITLE, 24)
        val iconSize = (labelSize * 2.7f).toInt().coerceIn(48, 96)
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize.toFloat())
        val tvStats = content.findViewById<TextView>(R.id.tv_drawer_stats)
        tvStats.visibility = View.VISIBLE
        tvStats.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (labelSize * 0.9f).toFloat())
        startStatsTicker(appCtx, tvStats)
        content.findViewById<View>(R.id.btn_drawer_close).setOnClickListener { dismiss() }
        val grid = content.findViewById<GridView>(R.id.drawer_grid)
        grid.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String ?: return@OnItemClickListener
            val dockBtns = Store.v2Buttons(appCtx)
            when {
                tagStr == "settings" -> {
                    try {
                        val it = android.content.Intent(appCtx, SettingsActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appCtx.startActivity(it)
                    } catch (_: Exception) {}
                    dismiss()
                }
                tagStr == "feat_home" -> {
                    ensureInDock(dockBtns, Store.V2Button.map("home"))
                    Store.saveV2Buttons(appCtx, dockBtns)
                    dismiss(); MapActions.run(appCtx, "home")
                }
                tagStr == "feat_company" -> {
                    ensureInDock(dockBtns, Store.V2Button.map("company"))
                    Store.saveV2Buttons(appCtx, dockBtns)
                    dismiss(); MapActions.run(appCtx, "company")
                }
                tagStr == "feat_clean" -> {
                    ensureInDock(dockBtns, Store.V2Button.clean())
                    Store.saveV2Buttons(appCtx, dockBtns)
                    dismiss()
                    val pipPkg = (appCtx as? LauncherApp)?.pipController?.resolvePkg()
                    val freed = MemoryCleaner.clean(appCtx, null, pipPkg)
                    val msg = if (freed > 0) "已释放 $freed MB 内存" else "当前无后台进程可清理"
                    Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                }
                tagStr == "split_new" -> {
                    Toast.makeText(appCtx, "请到桌面底栏添加分屏", Toast.LENGTH_SHORT).show()
                }
                tagStr.startsWith("split:") -> {
                    val idx = tagStr.substring(6).toIntOrNull() ?: return@OnItemClickListener
                    val pair = SplitRepository.get(appCtx, idx)
                    if (pair != null) {
                        dismiss(); Store.launchSplit(appCtx, pair[0], pair[1])
                    }
                }
                else -> {
                    dismiss(); Store.launchApp(appCtx, tagStr)
                }
            }
        }
        grid.onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String
            if (tagStr != null && tagStr.startsWith("split:")) {
                val idx = tagStr.substring(6).toIntOrNull() ?: return@OnItemLongClickListener true
                SplitRepository.remove(appCtx, idx)
                Toast.makeText(appCtx, "已删除分屏项", Toast.LENGTH_SHORT).show()
                SharedExecutor.io().execute {
                    val adapter = DrawerAdapter(appCtx, AppQuery.launcherEntriesSorted(appCtx), Store.v2Buttons(appCtx), false, iconSizePx = iconSize)
                    grid.post { grid.adapter = adapter }
                }
            }
            true
        }
        SharedExecutor.io().execute {
            val adapter = DrawerAdapter(appCtx, AppQuery.launcherEntriesSorted(appCtx), Store.v2Buttons(appCtx), false, iconSizePx = iconSize)
            grid.post { grid.adapter = adapter }
        }
    }

    private fun ensureInDock(dockBtns: MutableList<Store.V2Button>, btn: Store.V2Button) {
        for (e in dockBtns) if (e.sameAs(btn)) return
        if (dockBtns.size < DockBar.MAX_DOCK_BUTTONS) dockBtns.add(btn)
    }

    private fun startStatsTicker(c: Context, tv: TextView) {
        stopStatsTicker()
        val handler = MainThread.handler
        val runnable = object : Runnable {
            override fun run() {
                if (sOverlayRoot == null) { stopStatsTicker(); return }
                tv.text = buildStatsText(c)
                handler.postDelayed(this, 1000)
            }
        }
        sStatsRunnable = runnable
        tv.text = buildStatsText(c)
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

    private var sPrevIdle: Long = 0
    private var sPrevTotal: Long = 0
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

    private class DrawerAdapter(
        context: Context,
        apps: List<android.content.pm.ResolveInfo>,
        dockBtns: List<Store.V2Button>,
        dockMode: Boolean,
        private val iconSizePx: Int = 64
    ) : android.widget.BaseAdapter() {
        private val mContext: Context = context
        private val labels = ArrayList<String>()
        private val icons = ArrayList<android.graphics.drawable.Drawable?>()
        private val tags = ArrayList<String>()
        init {
            fun hasDockItem(type: String, action: String?): Boolean {
                for (b in dockBtns) if (type == b.type && (action == null || action == b.action)) return true
                return false
            }
            if (!dockMode || !hasDockItem("settings", null)) {
                labels.add("桌面设置"); icons.add(Store.normalizedEmoji(context, MapFeature.SETTINGS_EMOJI)); tags.add("settings")
            }
            if (!dockMode || !hasDockItem("map", "home")) {
                labels.add("回家"); icons.add(Store.normalizedEmoji(context, MapFeature.HOME_EMOJI)); tags.add("feat_home")
            }
            if (!dockMode || !hasDockItem("map", "company")) {
                labels.add("公司"); icons.add(Store.normalizedEmoji(context, MapFeature.COMPANY_EMOJI)); tags.add("feat_company")
            }
            if (!dockMode || !hasDockItem("clean", null)) {
                labels.add("清理"); icons.add(Store.normalizedEmoji(context, MapFeature.CLEAN_EMOJI)); tags.add("feat_clean")
            }
            if (!dockMode) {
                labels.add("分屏"); icons.add(Store.normalizedEmoji(context, MapFeature.SPLIT_EMOJI)); tags.add("split_new")
            }
            val splits = SplitRepository.load(context)
            for (i in splits.indices) {
                val pair = splits[i]
                if (dockMode && dockBtns.any { it == Store.V2Button.split(pair[0], pair[1]) }) continue
                labels.add("${Store.label(context, pair[0])}|${Store.label(context, pair[1])}")
                icons.add(Store.normalizedSplitIcon(context, pair[0], pair[1])); tags.add("split:$i")
            }
            for (ri in apps) {
                val id = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
                if (dockMode && hasDockItem("app", id)) continue
                labels.add(Store.label(context, id)); icons.add(Store.normalizedIcon(context, id)); tags.add(id)
            }
        }
        override fun getCount(): Int = labels.size
        override fun getItem(position: Int): Any = tags[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView ?: LayoutInflater.from(mContext).inflate(R.layout.item_drawer_cell, parent, false)
            val iv = cell.findViewById<android.widget.ImageView>(R.id.drawer_icon)
            iv.setImageDrawable(icons[position])
            val lp = iv.layoutParams
            lp.width = iconSizePx
            lp.height = iconSizePx
            iv.layoutParams = lp
            (cell.findViewById<View>(R.id.drawer_label) as TextView).text = labels[position]
            cell.tag = tags[position]
            return cell
        }
    }
}
