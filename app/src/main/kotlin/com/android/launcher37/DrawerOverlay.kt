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
 * - 适配器 / 统计栏 / 点击分发与 AppDrawer 共用（DrawerAdapter / DrawerStats / DrawerActions）
 */
object DrawerOverlay {

    private var sOverlayRoot: ViewGroup? = null
    private var sWindowManager: WindowManager? = null
    private var sDismissAction: Runnable? = null

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
        DrawerStats.stop()
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
                // 标记 fallback：isShowing()==true，后续 dismiss 转发给 AppDrawer
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
        val cardLp = FrameLayout.LayoutParams(drawerWidthPx(appCtx), drawerHeightPx(appCtx), Gravity.CENTER)
        // 不再硬限 maxW/maxH：尺寸已由百分比设置项控制（50~95%）
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

        // 注意：窗口带 FLAG_NOT_FOCUSABLE（保持底层应用焦点，否则会夺焦），
        // 无法接收按键事件 —— 返回键关闭不可行，仅靠 scrim 点击 / 关闭按钮 / 二次 toggle 关闭。
    }

    private fun bindDrawerContent(appCtx: Context, content: View) {
        val tvTitle = content.findViewById<TextView>(R.id.tv_drawer_title)
        tvTitle.text = "全部应用"
        // 同步设置：标题随时间字号，标签/图标用"全部应用外观"设置（应用 tab）
        val p = Prefs.of(appCtx)
        val titleSize = p.getInt(SettingsActivity.KEY_TS_TIME, 28)
        val labelSize = p.getInt(SettingsActivity.KEY_DRAWER_LABEL_SIZE, 17)
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize.toFloat())
        val tvStats = content.findViewById<TextView>(R.id.tv_drawer_stats)
        tvStats.visibility = View.VISIBLE
        tvStats.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (labelSize * 0.9f).toFloat())
        DrawerStats.start(appCtx, tvStats) { sOverlayRoot != null }
        content.findViewById<View>(R.id.btn_drawer_close).setOnClickListener { dismiss() }
        val grid = content.findViewById<GridView>(R.id.drawer_grid)
        grid.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String ?: return@OnItemClickListener
            DrawerActions.handleNormal(
                appCtx, tagStr,
                onDismiss = { dismiss() },
                onClean = {
                    // 悬浮窗拿不到 Activity 侧 MediaHelper，仅保留 VD 保护
                    val vdPkgs = (appCtx as? LauncherApp)?.activeHost?.vdBoundedPkgs()
                    val freed = MemoryCleaner.clean(appCtx, null, vdPkgs)
                    val msg = if (freed > 0) "已释放 $freed MB 内存" else "当前无后台进程可清理"
                    Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                },
                onSplitNew = {
                    Toast.makeText(appCtx, "请长按桌面应用列表条目添加分屏", Toast.LENGTH_SHORT).show()
                }
            )
        }
        grid.onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, view, _, _ ->
            val tagStr = view.tag as? String
            if (tagStr != null && tagStr.startsWith(DrawerAdapter.SPLIT_PREFIX)) {
                val idx = tagStr.substring(DrawerAdapter.SPLIT_PREFIX.length).toIntOrNull()
                    ?: return@OnItemLongClickListener true
                DrawerActions.removeSplitAndRefresh(appCtx, grid, idx)
            }
            true
        }
        DrawerActions.loadAdapterAsync(appCtx, grid)
    }
}
