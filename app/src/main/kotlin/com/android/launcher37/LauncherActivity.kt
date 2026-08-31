package com.android.launcher37

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
 *
 * Acts purely as an assembler + lifecycle router: binds views once, instantiates
 * 5 delegate modules under `home/` (XxxDelegate), and forwards Activity lifecycle
 * (onStart / onStop / onDestroy / onConfigurationChanged / onWindowFocusChanged).
 * Business logic (speed, navigation, music, update) lives in the delegates.
 *
 * Day/night mode is intercepted via `configChanges=uiMode`; we never recreate
 * the Activity (would destroy the std VirtualDisplay and break navigation).
 */
class LauncherActivity : Activity() {

    private lateinit var views: HomeViews
    private lateinit var snapshot: SettingsSnapshot
    private lateinit var layout: LayoutDelegate
    private lateinit var speed: SpeedDelegate
    private lateinit var navi: NaviPanelDelegate
    private lateinit var music: MusicDelegate
    private lateinit var update: UpdateDelegate
    private lateinit var traffic: TrafficLightClient

    // Exposed to sibling modules (AppDrawer / MemoryCleaner) via cast.
    internal lateinit var dockBar: DockBar
        private set
    internal lateinit var pip: PipController
        private set
    internal val mediaHelper: MediaHelper get() = music.mediaHelper()

    private var mNeedPipSync: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IconCache.clearNormalized()
        setContentView(R.layout.activity_main)

        snapshot = SettingsSnapshot.load(this)
        views = HomeViews(
            contentRoot = findViewById(R.id.content_root),
            pageContent = findViewById(R.id.page_content),
            leftCol = findViewById(R.id.left_col),
            gapSpeedMusic = findViewById(R.id.gap_speed_music),
            gapDock = findViewById(R.id.gap_dock),
            gapCol = findViewById(R.id.gap_col),
            cardSpeed = findViewById(R.id.card_speed),
            cardMusic = findViewById(R.id.card_music),
            musicInfo = findViewById(R.id.music_info),
            musicTimeRow = findViewById(R.id.music_time_row),
            dockGrid = findViewById(R.id.dock_grid) as GridView,
            pipPlaceholder = findViewById(R.id.pip_placeholder),
            tvSpeed = findViewById(R.id.tv_speed),
            tvKm = findViewById(R.id.tv_km),
            tvLimit = findViewById(R.id.tv_limit),
            tvTraffic = findViewById(R.id.tv_traffic),
            tvTrafficSec = findViewById(R.id.tv_traffic_sec),
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
            tvNaviAlert = findViewById(R.id.tv_navi_alert)
        )

        views.tvSpeed.text = "0"
        views.tvKm.text = if (SpeedDelegate.isMiles(this)) "mph" else "km/h"
        layout = LayoutDelegate(this, views).also { it.apply(snapshot) }
        speed = SpeedDelegate(this, views)
        music = MusicDelegate(
            this, views,
            appPicker = { dockBar.pickApp(SELECT_MUSIC_TITLE, null) { picked -> music.onMusicPicked(picked) } }
        ).also { it.bindListeners() }
        navi = NaviPanelDelegate(this, views, { snapshot }, speed)
        update = UpdateDelegate(application, this)
        traffic = TrafficLightClient(this, object : TrafficLightClient.Listener {
            override fun onTrafficLight(dir: Int, status: Int, countdown: Int) {
                speed.onTrafficLight(status, countdown)
            }
            override fun onTrafficLightHidden() {
                speed.onTrafficLightHidden()
            }
        })

        // MapPipHost survives Activity recreation - reuse the application-scoped
        // instance to keep the navigation VirtualDisplay alive.
        // PipController 持 applicationContext 而非 Activity（避免 launcher 进程
        // 跨 Activity 持有死 Activity 引用导致 release/releaseTransient 回调 crash）。
        pip = (application as LauncherApp).pipController
            ?: PipController(applicationContext, views.pipPlaceholder).also {
                (application as LauncherApp).pipController = it
            }
        // Activity 重建时 placeholder 是新 ViewGroup，老 mHost 的 SurfaceView 引用
        // 还指着旧的；attach 把 SurfaceView 重新加到新 placeholder 上并触发 surfaceChanged，
        // 让 :pip 进程 service 端的 attachSurface 走 reuse VD + moveStaleTask 把导航 task 拉回画面。
        pip.setPlaceholder(views.pipPlaceholder)
        dockBar = DockBar(this, views.dockGrid)
        dockBar.setCleanAction { cleanMemory() }
        dockBar.setCellStyle(showIcon = true, showLabel = true, iconSize = 44, labelSize = 14)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.BLACK

        layout.applyTheme()
    }

    override fun onStart() {
        super.onStart()
        speed.start()
        music.start()
        navi.start()
        traffic.start()
        update.checkOnLaunch()
    }

    override fun onResume() {
        super.onResume()
        mNeedPipSync = true
    }

    override fun onStop() {
        speed.stop()
        music.stop()
        navi.stop()
        traffic.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mNeedPipSync = true
        AppDrawer.dismissIfShowing()
    }

    override fun onDestroy() {
        AppDrawer.dismissIfShowing()
        // We deliberately do NOT call pip.release() - that would destroy the
        // VirtualDisplay and drop the navigation task. On process death (self
        // update, LMK kill) the navigation state must survive so a new process
        // can attach a fresh Surface and continue showing it.
        pip.releaseTransient()
        speed.stop()
        music.stop()
        music.cancelPending()
        navi.stop()
        traffic.stop()
        update.release()
        music.clearReturnHome()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        layout.applyTheme()
        layout.reapplyNightDrawables()
        dockBar.refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (mNeedPipSync) {
            mNeedPipSync = false
            views.pipPlaceholder.postDelayed({ pip.ensureStd() }, PIP_START_DELAY_MS)
        }
    }

    fun cleanMemory() = MemoryCleaner.cleanFromUi(this)

    companion object {
        private const val PIP_START_DELAY_MS = 250L
        private const val SELECT_MUSIC_TITLE = "select music app"
    }
}
