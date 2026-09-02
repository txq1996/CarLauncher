package com.android.launcher37

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.GridView
import com.android.launcher37.home.HomeViews
import com.android.launcher37.home.LayoutDelegate
import com.android.launcher37.home.MusicDelegate
import com.android.launcher37.home.NaviPanelDelegate
import com.android.launcher37.home.SettingsSnapshot
import com.android.launcher37.home.SpeedDelegate
import com.android.launcher37.home.UpdateDelegate

/**
 * Home launcher activity (singleTask).
 * 直接启动时若开启 home_direct_app_drawer，则像 autodock 一样走 Service 悬浮，不建 1x1 窗口，不返桌面。
 */
class LauncherActivity : Activity() {

    private lateinit var views: HomeViews
    private lateinit var snapshot: SettingsSnapshot
    private lateinit var layout: LayoutDelegate
    private lateinit var speed: SpeedDelegate
    private lateinit var navi: NaviPanelDelegate
    private lateinit var music: MusicDelegate
    private lateinit var time: com.android.launcher37.home.TimeDelegate
    private lateinit var update: UpdateDelegate

    internal lateinit var dockBar: DockBar
        private set
    internal lateinit var pip: PipController
        private set
    internal val mediaHelper: MediaHelper get() = music.mediaHelper()

    private var mNeedPipSync: Boolean = false
    private var mHasFocus: Boolean = false
    private var mAppDrawerPending: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 用内存焦点状态 sLauncherForeground 判定“启动前是否在本 launcher”：
        // 冷启动（新进程）默认 false → 走悬浮；进程存活的重建保留原焦点状态。
        val direct = Prefs.of(this).getBoolean(SettingsActivity.KEY_HOME_DIRECT_APP_DRAWER, true)
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
        buildUi()
    }

    /** 构建桌面 UI：设置快照 + view 树引用 + 全部 delegate + 底栏 + PIP placeholder（onCreate/rebuildUi 共用） */
    private fun buildUi() {
        snapshot = SettingsSnapshot.load(this)
        views = HomeViews(
            contentRoot = findViewById(R.id.page_content),
            pageContent = findViewById(R.id.page_content),
            leftCol = findViewById(R.id.left_col),
            gapTimeSpeed = findViewById(R.id.gap_time_speed),
            gapSpeedMusic = findViewById(R.id.gap_speed_music),
            gapDock = findViewById(R.id.gap_dock),
            gapCol = findViewById(R.id.gap_col),
            cardTime = findViewById(R.id.card_time),
            cardSpeed = findViewById(R.id.card_speed),
            cardMusic = findViewById(R.id.card_music),
            musicInfo = findViewById(R.id.music_info),
            musicTimeRow = findViewById(R.id.music_time_row),
            dockGrid = findViewById(R.id.dock_grid) as GridView,
            pipPlaceholder = findViewById(R.id.pip_placeholder),
            tvMusicName = findViewById(R.id.tv_music_name),
            tvArtist = findViewById(R.id.tv_artist),
            curTime = findViewById(R.id.music_cur_time),
            totalTime = findViewById(R.id.music_total_time),
            musicProgress = findViewById(R.id.music_progress),
            btnPlayPause = findViewById(R.id.btn_playpause),
            btnPrev = findViewById(R.id.btn_prev),
            btnNext = findViewById(R.id.btn_next),
            naviPanel = findViewById(R.id.navi_panel),
            naviRowTurn = findViewById(R.id.navi_row_turn),
            naviRowEta = findViewById(R.id.navi_row_eta),
            ivTurnIcon = findViewById(R.id.iv_turn_icon),
            tvNaviDist = findViewById(R.id.tv_navi_dist),
            tvNaviRoad = findViewById(R.id.tv_navi_road),
            tvNaviDest = findViewById(R.id.tv_navi_dest),
            tvNaviTime = findViewById(R.id.tv_navi_time),
            tvNaviRemain = findViewById(R.id.tv_navi_remain),
            tvNaviAlert = findViewById(R.id.tv_navi_alert),
            tvNaviEtaText = findViewById(R.id.tv_navi_eta_text),
            tvNaviLightCount = findViewById(R.id.tv_navi_light_count),
            tvNaviExit = findViewById(R.id.tv_navi_exit),
            tvNaviDirection = findViewById(R.id.tv_navi_direction),
            tvTime = findViewById(R.id.tv_time)
        )

        layout = LayoutDelegate(this, views).also { it.apply(snapshot) }
        time = com.android.launcher37.home.TimeDelegate(views).also { it.applyLayout() }
        speed = SpeedDelegate(this, views)
        music = MusicDelegate(
            this, views,
            appPicker = { dockBar.pickApp(SELECT_MUSIC_TITLE, null) { picked -> music.onMusicPicked(picked) } }
        ).also { it.bindListeners() }
        navi = NaviPanelDelegate(this, views, { snapshot }, speed)
        update = UpdateDelegate(application, this)
        speed.bind()

        pip = (application as LauncherApp).pipController
            ?: PipController(applicationContext, views.pipPlaceholder).also {
                (application as LauncherApp).pipController = it
            }
        pip.setPlaceholder(views.pipPlaceholder)
        dockBar = DockBar(this, views.dockGrid)
        dockBar.setCleanAction { cleanMemory() }
        val showLabel = snapshot.show(SettingsActivity.KEY_SHOW_DOCK_LABEL, true)
        dockBar.setCellStyle(
            showIcon = true, showLabel = showLabel,
            iconSize = snapshot.size(SettingsActivity.KEY_DOCK_ICON_SIZE, 44),
            labelSize = 14,
            cellHeightPx = snapshot.size(SettingsActivity.KEY_DOCK_HEIGHT, 80)
        )
        dockBar.setColumns(snapshot.size(SettingsActivity.KEY_DOCK_COLUMNS, 10))

        applyStatusBarVisibility()
        layout.applyTheme()
    }

    override fun onStart() {
        super.onStart()
        // 注意：不能在此复位 sLauncherStopped —— API 31+ 上单任务回拉的回调顺序是
        // onRestart→onStart→onNewIntent→onResume（API 28 为 onNewIntent→onStart），
        // 在此清零会把 onStop 留下的"被真 App 盖顶"证据在 onNewIntent 判定前擦掉。
        // 统一放到 onResume（两版本均晚于 onNewIntent）复位。
        Dbg.i("VDFocusDbg") { "onStart stopped=$sLauncherStopped top=${dbgTop()}" }
        if (!::views.isInitialized) return
        time.start()
        music.start()
        navi.start()
        update.checkOnLaunch()
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
        if (mAppDrawerPending && ::views.isInitialized) {
            mAppDrawerPending = false
            // 需 post 到 window token 就绪后再弹，避免 BadToken
            window.decorView.post {
                if (!isFinishing && !isDestroyed && !AppDrawer.isShowing() && !DrawerOverlay.isShowing()) {
                    AppDrawer.show(this)
                }
            }
        }
        mNeedPipSync = true
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
        if (::views.isInitialized) {
            time.stop()
            music.stop()
            navi.stop()
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Dbg.i("VDFocusDbg") { "onNewIntent act=${intent.action} fromHome=${intent.hasCategory(Intent.CATEGORY_HOME)} mHasFocus=$mHasFocus lastPauseHadFocus=$sLastPauseHadFocus fg=$sLauncherForeground top=${dbgTop()}" }
        mNeedPipSync = true
        val direct = Prefs.of(this).getBoolean(SettingsActivity.KEY_HOME_DIRECT_APP_DRAWER, true)
        val fromHome = intent.hasCategory(Intent.CATEGORY_HOME)
        if (direct) {
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
        if (::views.isInitialized) {
            pip.releaseTransient()
            speed.unbind()
            music.stop()
            music.cancelPending()
            navi.stop()
            update.release()
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::views.isInitialized) return
        applyStatusBarVisibility()
        layout.applyTheme()
        layout.reapplyNightDrawables()
        // uiMode 切换不重建 Activity，动态渲染的文字/转向图标需强制重建重新读色
        if (::navi.isInitialized) navi.rebuildForThemeChange()
        dockBar.refresh()
    }

    private fun applyStatusBarVisibility() {
        val hide = Prefs.of(this).getBoolean(SettingsActivity.KEY_HIDE_STATUS_BAR, false)
        if (hide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Dbg.i("VDFocusDbg") { "onWindowFocusChanged hasFocus=$hasFocus" }
        mHasFocus = hasFocus
        if (!hasFocus || !::views.isInitialized) return
        if (mNeedPipSync) {
            mNeedPipSync = false
            // 桌面拉起延迟（pip_start_delay，0=立即）：恢复 d4584a0 删除的 PIP_START_DELAY_MS(250ms) 固定延迟，改为可设置
            val startDelayMs = snapshot.size(SettingsActivity.KEY_PIP_START_DELAY, 250)
            if (startDelayMs > 0) {
                views.pipPlaceholder.postDelayed({ pip.ensureStd() }, startDelayMs.toLong())
            } else {
                pip.ensureStd()
            }
        }
    }

    fun cleanMemory() = MemoryCleaner.cleanFromUi(this)

    companion object {
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

        private const val SELECT_MUSIC_TITLE = "选择音乐应用"
    }
}
