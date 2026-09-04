package com.android.launcher37
import com.android.launcher37.LauncherApp
import com.android.launcher37.SettingsActivity
import com.android.launcher37.R
import com.android.launcher37.drawer.DrawerOverlay
import com.android.launcher37.util.Dbg
import com.android.launcher37.drawer.AppDrawer
import com.android.launcher37.music.MediaHelper
import com.android.launcher37.util.IconCache
import com.android.launcher37.drawer.DrawerService
import com.android.launcher37.util.Prefs
import com.android.launcher37.data.MemoryCleaner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import com.android.launcher37.home.UpdateDelegate
import com.android.launcher37.home.widget.LyricsWidget
import com.android.launcher37.home.widget.PageHost

/**
 * Home launcher activity (singleTask).
 * 直接启动时若开启 home_direct_app_drawer，则像 autodock 一样走 Service 悬浮，不建 1x1 窗口，不返桌面。
 *
 * 主页 = 单页 Widget 画布（[PageHost]）：Time/Music/Lyrics/Speed/Dock/VD 六类
 * Widget 绝对定位摆放，命名布局持久化（保存/打开），设计器模式（EXTRA_DESIGNER）
 * 直接在主页拖动/缩放/增删 Widget。
 */
class LauncherActivity : Activity() {

    private var host: PageHost? = null
    private var update: UpdateDelegate? = null
    private var mDesignMode: Boolean = false
    private var mHasFocus: Boolean = false
    private var mAppDrawerPending: Boolean = false
    private var mNeedVdSync: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 设计模式入口（设置页）：跳过 direct/悬浮分支，直接以桌面形态进入
        var designerRequested = intent?.getBooleanExtra(EXTRA_DESIGNER, false) == true
        // 默认布局只读：不可进设计器（正常回落桌面形态）
        if (designerRequested &&
            com.android.launcher37.home.widget.LayoutRepository.activeName(this) ==
            com.android.launcher37.home.widget.LayoutRepository.BUILTIN_NAME
        ) {
            designerRequested = false
            intent?.removeExtra(EXTRA_DESIGNER)
            Toast.makeText(this, "默认布局只读，请先在布局管理中添加自定义布局", Toast.LENGTH_LONG).show()
        }
        // 用内存焦点状态 sLauncherForeground 判定"启动前是否在本 launcher"：
        // 冷启动（新进程）默认 false → 走悬浮；进程存活的重建保留原焦点状态。
        val direct = !designerRequested &&
            Prefs.of(this).getBoolean(SettingsActivity.KEY_HOME_DIRECT_APP_DRAWER, true)
        val fromHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        if (direct && !fromHome) {
            if (DrawerOverlay.isShowing() || AppDrawer.isShowing()) {
                DrawerOverlay.dismiss()
                AppDrawer.dismissIfShowing()
                super.onCreate(savedInstanceState)
                finish()
                return
            }
            // 用内存前台标志区分：启动前不在本 launcher（其他 App）弹悬浮，在桌面弹 AppDrawer
            if (!sLauncherForeground) {
                super.onCreate(savedInstanceState)
                hideWindowForOverlay()
                startService(Intent(this, DrawerService::class.java))
                finish()
                return
            } else {
                mAppDrawerPending = true
            }
        }
        if (direct && fromHome) {
            if (DrawerOverlay.isShowing()) DrawerOverlay.dismiss()
            AppDrawer.dismissIfShowing()
        }
        super.onCreate(savedInstanceState)
        IconCache.clearNormalized()
        setContentView(R.layout.activity_main)
        val container = findViewById<android.widget.FrameLayout>(R.id.widget_container)
        host = PageHost(this, container)
        host?.onDesignerExit = { exitDesign() }
        host?.onToggleStatusBar = { applyStatusBarVisibility() }
        (application as LauncherApp).activeHost = host
        update = UpdateDelegate(application, this)
        // 注意：mDesignMode 不在此提前置 true，否则 enterDesign() 的 `if (mDesignMode) return`
        // 会让首次进入直接返回（工具栏不显示、设计模式不激活）。由 enterDesign() 统一置位。
        // 容器首次布局（测量完成，宽高=屏幕像素）后装配 Widget
        container.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (isFinishing || isDestroyed) return
                host?.install(designerRequested)
                if (designerRequested) {
                    host?.startAll()
                    enterDesign()
                } else {
                    applyStatusBarVisibility()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // 注意：不能在此复位 sLauncherStopped —— API 31+ 上单任务回拉的回调顺序是
        // onRestart→onStart→onNewIntent→onResume（API 28 为 onNewIntent→onStart），
        // 在此清零会把 onStop 留下的"被真 App 盖顶"证据在 onNewIntent 判定前擦掉。
        // 统一放到 onResume（两版本均晚于 onNewIntent）复位。
        Dbg.i("VDFocusDbg") { "onStart stopped=$sLauncherStopped top=${dbgTop()}" }
        update?.checkOnLaunch()
    }

    // #region debug-point lifecy-v1（仅日志，不改逻辑）
    private fun dbgTop(): String = try {
        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getRunningTasks(1).firstOrNull()?.topActivity?.flattenToString() ?: "null"
    } catch (_: Exception) { "err" }
    // #endregion

    /**
     * 快照当前系统顶部任务包名，供 onNewIntent 判定"触发前用户是否在本桌面"。
     *
     * 关键事实（emulator-5554 API 28 实测）：
     * - 高德跑在本 launcher 的 VirtualDisplay 上时，系统"顶部任务"是 VD 上的高德任务，
     *   它会把主屏 launcher 挤成 paused+失焦 —— 但用户视觉上仍在桌面（不触发 onStop）；
     * - 此刻 getRunningTasks 恰好返回该 VD 任务（top=amapauto），
     *   而浏览器等真正盖顶的其他 App 返回自身包名 —— 以此辅助区分。
     */
    private fun snapshotTop() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            sLastPauseTopPkg = am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        } catch (_: Exception) { }
    }

    override fun onResume() {
        sLauncherForeground = true
        // 快照重置为自身：resume 即回到顶部，旧的"抢焦者"快照已过期
        // （否则 HOME 回桌面后，am start 会读到浏览器等陈旧快照误走悬浮）。
        sLastPauseTopPkg = packageName
        // stopped 标志在此复位而非 onStart：onResume 必在 onNewIntent 之后，
        // 不会擦掉 onStop 留给 am-start 判定的"被盖顶"证据。
        sLauncherStopped = false
        Dbg.i("VDFocusDbg") { "onResume fg=${sLauncherForeground} focus=$mHasFocus lastPauseHadFocus=$sLastPauseHadFocus top=${dbgTop()}" }
        super.onResume()
        if (mAppDrawerPending) {
            mAppDrawerPending = false
            // 需 post 到 window token 就绪后再弹，避免 BadToken
            window.decorView.post {
                if (!isFinishing && !isDestroyed && !AppDrawer.isShowing() && !DrawerOverlay.isShowing()) {
                    AppDrawer.show(this)
                }
            }
        }
        mNeedVdSync = true
        host?.startAll()
    }

    override fun onPause() {
        sLauncherForeground = false
        // 记录本次被暂停时窗口是否仍持焦 —— 这是"触发前是否在本桌面"的快照之一。
        // pause 时先清 stopped 标志：VD 抢焦只 pause 不 stop（launcher 仍可见），
        // 而其他 App 真盖顶随后必然 onStop。onNewIntent 据此区分两种"失焦"。
        sLauncherStopped = false
        sLastPauseHadFocus = mHasFocus
        snapshotTop()
        Dbg.i("VDFocusDbg") { "onPause focus=$mHasFocus -> lastPauseHadFocus=$sLastPauseHadFocus lastTop=$sLastPauseTopPkg top=${dbgTop()}" }
        super.onPause()
    }

    override fun onStop() {
        Dbg.i("VDFocusDbg") { "onStop focus=$mHasFocus top=${dbgTop()}" }
        // 置 stopped：其他 App 盖顶（浏览器等）必经此回调；VD 抢焦不触发。
        // 也刷新快照：launcher 已被 VD 抢焦成 paused 后，其他 App 盖顶不会再触发新的 onPause。
        sLauncherStopped = true
        // 焦点快照同步失效：桌面持焦时被盖顶的 pause 会留下 hadFocus=true，
        // 不清掉的话后续 am start 会误判"仍在桌面"而弹抽屉（应为悬浮窗）。
        sLastPauseHadFocus = false
        snapshotTop()
        host?.stopAll()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Dbg.i("VDFocusDbg") { "onNewIntent act=${intent.action} fromHome=${intent.hasCategory(Intent.CATEGORY_HOME)} mHasFocus=$mHasFocus lastPauseHadFocus=$sLastPauseHadFocus fg=$sLauncherForeground top=${dbgTop()}" }
        // 设置页返回：重建本 Activity 以应用最新设置（无 CLEAR_TASK，避免桌面未到前台被 VD 任务抢先）
        if (intent.getBooleanExtra(EXTRA_APPLY_SETTINGS, false)) {
            recreate()
            return
        }
        mNeedVdSync = true
        val isDesigner = intent.getBooleanExtra(EXTRA_DESIGNER, false)
        if (isDesigner && !mDesignMode) {
            // 默认布局只读：不可进设计器
            if (com.android.launcher37.home.widget.LayoutRepository.activeName(this) ==
                com.android.launcher37.home.widget.LayoutRepository.BUILTIN_NAME) {
                Toast.makeText(this, "默认布局只读，请先在布局管理中添加自定义布局", Toast.LENGTH_LONG).show()
            } else {
                enterDesign()
            }
        }
        // 进入设计器意图：不弹全部应用/悬浮，直接回到桌面
        if (isDesigner) return
        val direct = Prefs.of(this).getBoolean(SettingsActivity.KEY_HOME_DIRECT_APP_DRAWER, true)
        val fromHome = intent.hasCategory(Intent.CATEGORY_HOME)
        if (fromHome) {
            // HOME：桌面上切换全部应用，其余回桌面
            if (mHasFocus) {
                if (AppDrawer.isShowing() || DrawerOverlay.isShowing()) {
                    AppDrawer.dismissIfShowing()
                    DrawerOverlay.dismiss()
                } else {
                    AppDrawer.show(this)
                }
            } else {
                if (DrawerOverlay.isShowing()) DrawerOverlay.dismiss()
                AppDrawer.dismissIfShowing()
            }
            return
        }
        if (direct) {
            // am start com.android.launcher37：判定"触发前用户在不在本 launcher 桌面"。
            // 焦点快照（sLastPauseHadFocus）在"桌面挂着高德 VD"时不可靠：VD 上的高德任务
            // 会成为系统顶部任务，把 launcher 挤成 paused+失焦（用户视觉上仍在桌面），
            // 导致快照恒 false → 误走悬浮窗。因此叠加多重判定，任一成立即视为桌面：
            // - sLastPauseHadFocus：最后一次 onPause 时窗口仍持焦（纯桌面场景）；
            // - lastTop==自己：am start 自身触发的瞬时 onPause（快照被刷新为 launcher）；
            // - !sLauncherStopped：VD 抢焦只 pause 不 stop（launcher 仍可见，用户仍在桌面）；
            //   而浏览器等真盖顶必经 onStop（API 28 无任务 displayId，用此区分 VD/盖顶）。
            // 快照在 onPause/onStop 都会刷新（已 paused 后被盖顶只触发 onStop），
            // onResume 时重置为自身，避免 HOME 回桌面后读到陈旧快照。
            val onDesktop = sLastPauseHadFocus
                || sLastPauseTopPkg == packageName
                || !sLauncherStopped
            Dbg.i("VDFocusDbg") { "am-start path: onDesktop=$onDesktop (hadFocus=$sLastPauseHadFocus topPkg=$sLastPauseTopPkg stopped=$sLauncherStopped)" }
            if (onDesktop) {
                if (AppDrawer.isShowing() || DrawerOverlay.isShowing()) {
                    AppDrawer.dismissIfShowing()
                    DrawerOverlay.dismiss()
                } else {
                    AppDrawer.show(this)
                }
                return
            }
            // 其他 App 上：悬浮切换，不返桌面
            hideWindowForOverlay()
            if (DrawerOverlay.isShowing()) {
                DrawerOverlay.dismiss()
            } else {
                startService(Intent(this, DrawerService::class.java))
            }
            try { moveTaskToBack(true) } catch (_: Exception) {}
            finish()
            return
        }
        if (mHasFocus) {
            AppDrawer.toggle(this)
        } else {
            AppDrawer.dismissIfShowing()
        }
    }

    override fun onDestroy() {
        AppDrawer.dismissIfShowing()
        host?.destroyAll()
        host = null
        (application as? LauncherApp)?.activeHost = null
        update?.release()
        update = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode 切换不重建 Activity：重读状态栏主题 + 通知全部 Widget 重读日/夜色
        applyStatusBarVisibility()
        applySystemBarTheme()
        // 桌面根布局背景重读日/夜色（已 inflate 的背景不会随配置自动更新）
        (findViewById<ViewGroup>(android.R.id.content))?.getChildAt(0)
            ?.setBackgroundColor(resources.getColor(R.color.background, theme))
        host?.onThemeChange()
    }

    private fun applyStatusBarVisibility() {
        val hide = Prefs.of(this).getBoolean(SettingsActivity.KEY_HIDE_STATUS_BAR, false)
        if (hide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
        applySystemBarTheme()
    }

    /** 状态栏配色跟日夜主题（uiMode 切换不重建 Activity，必须每次同步）；
     *  设置开启"状态栏纯黑"时始终纯黑（关闭透明），图标用夜色（白色）保证对比 */
    private fun applySystemBarTheme() {
        val opaque = Prefs.of(this).getBoolean(SettingsActivity.KEY_OPAQUE_STATUS_BAR, false)
        val bg = if (opaque) android.graphics.Color.BLACK else resources.getColor(R.color.background, theme)
        window.statusBarColor = bg
        val isLightBar = !opaque && (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val ctrl = window.insetsController
            if (isLightBar) {
                ctrl?.setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                ctrl?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (isLightBar) android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Dbg.i("VDFocusDbg") { "onWindowFocusChanged hasFocus=$hasFocus" }
        mHasFocus = hasFocus
        if (!hasFocus || host == null) return
        if (mNeedVdSync) {
            mNeedVdSync = false
            // VD 拉起延迟（pip_start_delay，0=立即）：窗口焦点就绪后再拉起全部 VDWidget
            host?.ensureVdLaunched()
        }
    }

    // ── 设计器模式 ───────────────────────────────────

    /**
     * 进入设计器：主页本身即画布（Widget 实时预览真实数据），显示悬浮工具栏。
     * 不再强制全屏：画布尺寸与普通模式一致（状态栏显隐由设置页布局属性控制）。
     */
    private fun enterDesign() {
        if (mDesignMode || host == null) return
        mDesignMode = true
        applyStatusBarVisibility()
        findViewById<View>(R.id.design_toolbar).visibility = View.VISIBLE
        host?.enterDesignMode()
    }

    /** 完成/保存路径：退出设计模式 + 恢复正常运行（由 PageHost 调用） */
    private fun exitDesign() {
        if (!mDesignMode) return
        mDesignMode = false
        findViewById<View>(R.id.design_toolbar).visibility = View.GONE
        applyStatusBarVisibility()
        // 退出设计器返回设置界面
        startActivity(android.content.Intent(this, SettingsActivity::class.java))
    }

    /** 返回键：设计模式下退出设计器（未点保存的更改丢弃），由 exitDesign 返回设置页 */
    override fun onBackPressed() {
        if (mDesignMode) {
            host?.exitDesignMode()
            return
        }
        super.onBackPressed()
    }

    fun cleanMemory() = MemoryCleaner.cleanFromUi(this)

    /** 内存清理保护：正在播放音乐的包（全部 LyricsWidget 汇总） */
    internal fun playingPkgs(): Set<String> {
        val out = HashSet<String>()
        host?.allWidgets()?.filterIsInstance<LyricsWidget>()?.forEach {
            out.addAll(it.mediaHelper().playingPackages)
        }
        return out
    }

    /** 内存清理保护 / 全屏搬移：全部 VDWidget 绑定的包名 */
    internal fun vdPkgs(): Set<String> = host?.vdBoundedPkgs() ?: emptySet()

    /**
     * 浮窗切换路径：让 launcher 窗口保持不可见（透明背景 + 隐藏内容），
     * 避免 am start 拉前台时在原 App 上闪出桌面/背景。仅本浮窗路径调用，
     * 随后即 finish/moveTaskToBack，无需恢复。
     */
    private fun hideWindowForOverlay() {
        try {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            findViewById<android.view.View>(android.R.id.content)?.visibility = android.view.View.INVISIBLE
        } catch (_: Exception) {}
    }

    companion object {
        /** 设置页入口：以设计器模式启动主页 */
        const val EXTRA_DESIGNER = "designer"

        /** 设置页返回：走 onNewIntent 后 recreate 重建（应用设置），替代 CLEAR_TASK 重启 */
        const val EXTRA_APPLY_SETTINGS = "apply_settings"

        /** 本 launcher 是否曾进入前台（onResume=true / onPause=false）。仅用于 onCreate 冷启动兜底判定。 */
        @Volatile
        var sLauncherForeground: Boolean = false

        /** 最近一次 onPause 时窗口是否仍持焦。判定 am start 热启动前用户是否在本桌面。 */
        @Volatile
        var sLastPauseHadFocus: Boolean = false

        /** 最后一次 onPause/onStop 时系统顶部任务的包名（抢焦者快照）。 */
        @Volatile
        var sLastPauseTopPkg: String? = null

        /** launcher 是否处于 stopped（被其他 App 真盖顶）。VD 抢焦只 pause 不 stop，据此区分二者。 */
        @Volatile
        var sLauncherStopped: Boolean = false
    }
}
