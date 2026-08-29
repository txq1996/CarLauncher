package com.android.launcher37

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面 HOME Activity (singleTask)。
 *
 * 委托：
 * - PIP：`PipController`（std VirtualDisplay 方案）
 * - 音乐：`MediaHelper` + `MusicLauncher`（冷启动轮询 + 回桌面）
 * - 内存清理：`cleanMemory()` → 收集 Dock 包名 → `MemoryCleaner.clean()`
 *
 * 日/夜切换通过 `configChanges=uiMode` 拦截 `onConfigurationChanged`，
 * 避免 std PIP 中断。
 */
class LauncherActivity : Activity(),
    SpeedClient.Listener,
    MediaHelper.UiCallback,
    TrafficLightClient.Listener,
    NaviTextClient.Listener {

    companion object {
        private const val KEY_MILES = "persist.sys.isMiles"
        private const val MILE_RATIO = 0.62f
        private const val PIP_START_DELAY_MS = 250L
        private const val MUSIC_APP_KEY = "music_app_pkg"
    }

    // ── Views ──────────────────────
    private lateinit var mTvSpeed: TextView
    private lateinit var mTvKm: TextView
    private lateinit var mTvTrafficSec: TextView
    private lateinit var mTvLimit: TextView
    private lateinit var mTvMusicName: TextView
    private lateinit var mTvArtist: TextView
    private lateinit var mMusicProgress: ProgressBar
    private lateinit var mCurTime: TextView
    private lateinit var mTotalTime: TextView
    private lateinit var mBtnPlayPause: ImageButton
    private lateinit var mBtnPrev: ImageButton
    private lateinit var mBtnNext: ImageButton
    private lateinit var mPipPlaceholder: View
    private lateinit var mNaviPanel: LinearLayout
    private lateinit var mNaviRowTurn: LinearLayout
    private lateinit var mNaviRowEta: LinearLayout
    private lateinit var mIvTurnIcon: ImageView
    private lateinit var mTvNaviDist: TextView
    private lateinit var mTvNaviRoad: TextView
    private lateinit var mTvNaviDest: TextView
    private lateinit var mTvNaviTime: TextView
    private lateinit var mTvNaviRemain: TextView
    private lateinit var mTvNaviAlert: TextView
    // 布局/间距调节目标
    private lateinit var mPageContent: View
    private lateinit var mGapSpeedMusic: View
    private lateinit var mGapDock: View
    private lateinit var mGapCol: View
    private lateinit var mLeftCol: View
    private lateinit var mCardMusic: View
    private lateinit var mCardSpeed: View
    private lateinit var mContentRoot: View
    private lateinit var mDockGrid: View
    private lateinit var mTvTraffic: View
    private lateinit var mMusicInfo: View
    private lateinit var mMusicTimeRow: View

    // ── Controllers ──────────────────────
    private lateinit var mSpeedClient: SpeedClient
    internal lateinit var mMediaHelper: MediaHelper
    private lateinit var mMusicLauncher: MusicLauncher
    private lateinit var mTrafficLightClient: TrafficLightClient
    private lateinit var mNaviTextClient: NaviTextClient
    internal lateinit var mPip: PipController
    internal lateinit var mDockBar: DockBar
    private var mUpdateChecker: UpdateChecker? = null

    // ── State ──────────────────────
    private val mReturnHomeRunnable = Runnable { returnToHome() }
    private var mShownSpeed: Int = 0
    private var mMiles: Boolean = false
    private var mLimitKmh: Int = 0
    private val mShowPrefs = HashMap<String, Boolean>()
    private val mIntPrefs = HashMap<String, Int>()
    private var mNeedPipSync: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IconCache.clearNormalized()
        setContentView(R.layout.activity_main)
        mMiles = "1" == SysProps.get(KEY_MILES, "0")
        loadSettings()

        mTvSpeed = findViewById(R.id.tv_speed)
        mTvKm = findViewById(R.id.tv_km)
        mTvTrafficSec = findViewById(R.id.tv_traffic_sec)
        mTvLimit = findViewById(R.id.tv_limit)
        mTvMusicName = findViewById(R.id.tv_music_name)
        mTvArtist = findViewById(R.id.tv_artist)
        mMusicProgress = findViewById(R.id.music_progress)
        mCurTime = findViewById(R.id.music_cur_time)
        mTotalTime = findViewById(R.id.music_total_time)
        mBtnPlayPause = findViewById(R.id.btn_playpause)
        mBtnPrev = findViewById(R.id.btn_prev)
        mBtnNext = findViewById(R.id.btn_next)
        mPipPlaceholder = findViewById(R.id.pip_placeholder)
        mPageContent = findViewById(R.id.page_content)
        mGapSpeedMusic = findViewById(R.id.gap_speed_music)
        mGapDock = findViewById(R.id.gap_dock)
        mGapCol = findViewById(R.id.gap_col)
        mLeftCol = findViewById(R.id.left_col)
        mCardMusic = findViewById(R.id.card_music)
        mCardSpeed = findViewById(R.id.card_speed)
        mContentRoot = findViewById(R.id.content_root)
        mDockGrid = findViewById(R.id.dock_grid)
        mTvTraffic = findViewById(R.id.tv_traffic)
        mMusicInfo = findViewById(R.id.music_info)
        mMusicTimeRow = findViewById(R.id.music_time_row)
        mNaviPanel = findViewById(R.id.navi_panel)
        mNaviRowTurn = findViewById(R.id.navi_row_turn)
        mNaviRowEta = findViewById(R.id.navi_row_eta)
        mIvTurnIcon = findViewById(R.id.iv_turn_icon)
        mTvNaviDist = findViewById(R.id.tv_navi_dist)
        mTvNaviRoad = findViewById(R.id.tv_navi_road)
        mTvNaviDest = findViewById(R.id.tv_navi_dest)
        mTvNaviTime = findViewById(R.id.tv_navi_time)
        mTvNaviRemain = findViewById(R.id.tv_navi_remain)
        mTvNaviAlert = findViewById(R.id.tv_navi_alert)

        mTextSizers = arrayOf(
            TextSizer(mTvSpeed, SettingsActivity.KEY_TS_SPEED, 100),
            TextSizer(mTvKm, SettingsActivity.KEY_TS_KMH, 20),
            TextSizer(mTvLimit, SettingsActivity.KEY_TS_LIMIT, 17),
            TextSizer(mTvTrafficSec, SettingsActivity.KEY_TS_TRAFFIC_SEC, 20),
            TextSizer(mTvNaviDist, SettingsActivity.KEY_TS_NAVI_DIST, 36),
            TextSizer(mTvNaviRoad, SettingsActivity.KEY_TS_NAVI_ROAD, 26),
            TextSizer(mTvNaviDest, SettingsActivity.KEY_TS_NAVI_DEST, 15),
            TextSizer(mTvNaviRemain, SettingsActivity.KEY_TS_NAVI_ETA, 17),
            TextSizer(mTvNaviTime, SettingsActivity.KEY_TS_NAVI_ETA, 17),
            TextSizer(mTvNaviAlert, SettingsActivity.KEY_TS_NAVI_ALERT, 17),
            TextSizer(mTvMusicName, SettingsActivity.KEY_TS_MUSIC_TITLE, 24),
            TextSizer(mTvArtist, SettingsActivity.KEY_TS_MUSIC_ARTIST, 15),
            TextSizer(mCurTime, SettingsActivity.KEY_TS_MUSIC_TIME, 15),
            TextSizer(mTotalTime, SettingsActivity.KEY_TS_MUSIC_TIME, 15)
        )

        mTvKm.text = if (mMiles) "mph" else "km/h"
        mTvSpeed.text = "0"

        // 设置快照应用到视图（全部重启生效，onCreate 一次性应用）
        applyLayout()
        applyTextSizes()
        applySpeedVisibility()
        applyMusicVisibility()

        mBtnPrev.setOnClickListener { onMusicButton(mMediaHelper::prev) }
        mBtnPlayPause.setOnClickListener { onMusicButton(mMediaHelper::togglePlay) }
        mBtnNext.setOnClickListener { onMusicButton(mMediaHelper::next) }
        // 音乐卡信息区（进度条上方）：未绑定点击绑定 / 已绑定点击进入；长按换绑
        mMusicInfo.setOnClickListener {
            if (getBoundMusicPkg() == null) {
                mDockBar.pickApp("选择音乐应用", null) { pkgCls -> onMusicPicked(pkgCls) }
            } else {
                launchBoundApp()
            }
        }
        mMusicInfo.setOnLongClickListener {
            mDockBar.pickApp("选择音乐应用", null) { pkgCls -> onMusicPicked(pkgCls) }
            true
        }

        mSpeedClient = SpeedClient(this, this)
        mMediaHelper = MediaHelper(this, this)
        mMusicLauncher = MusicLauncher(this, mMediaHelper, mReturnHomeRunnable)
        mTrafficLightClient = TrafficLightClient(this, this)
        mNaviTextClient = NaviTextClient(this, this)
        mPip = PipController(this, mPipPlaceholder)
        mDockBar = DockBar(this, mDockGrid as GridView)
        mDockBar.setCleanAction { cleanMemory() }
        mDockBar.setCellStyle(showIcon = true, showLabel = true, iconSize = 44, labelSize = 14)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.BLACK

        applyTheme()
    }

    override fun onStart() {
        super.onStart()
        mSpeedClient.start()
        mMediaHelper.start()
        mTrafficLightClient.start()
        mNaviTextClient.start()
        // 启动时自动检查更新（24h 节流，错误静默）
        ensureUpdateChecker().checkOnLaunch()
    }

    private fun ensureUpdateChecker(): UpdateChecker {
        val existing = mUpdateChecker
        if (existing != null) return existing
        val created = UpdateChecker(this, object : UpdateChecker.Listener {
            override fun onUpdateStart() {}
            override fun onUpdateFound(info: UpdateChecker.UpdateInfo) {
                // 启动检查时只在屏幕外弹系统安装器（用户已交互过 Settings 后才走 UI 反馈）
                android.widget.Toast.makeText(
                    this@LauncherActivity,
                    "已下载新版本，正在打开安装器…",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            override fun onUpToDate() {}
            override fun onProgress(percent: Int) {}
            override fun onError(message: String) {
                // 自动检查失败：仅日志，不打扰用户
                android.util.Log.w("LauncherActivity", "auto update check failed: $message")
            }
        })
        mUpdateChecker = created
        return created
    }

    override fun onStop() {
        mTrafficLightClient.stop()
        mSpeedClient.stop()
        mMediaHelper.stop()
        mNaviTextClient.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        mNeedPipSync = true
        val p = Prefs.of(this)
        for (key in SettingsActivity.SHOW_KEYS) {
            mShowPrefs[key] = p.getBoolean(key, true)
        }
        for (i in SettingsActivity.INT_KEYS.indices) {
            mIntPrefs[SettingsActivity.INT_KEYS[i]] =
                p.getInt(SettingsActivity.INT_KEYS[i], SettingsActivity.INT_DEFAULTS[i])
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mNeedPipSync = true
        AppDrawer.dismissIfShowing()
    }

    override fun onDestroy() {
        AppDrawer.dismissIfShowing()
        mPip.release()
        mTrafficLightClient.stop()
        mSpeedClient.stop()
        mMediaHelper.stop()
        mNaviTextClient.stop()
        mMusicLauncher.cancelPending()
        try {
            val dv = window.decorView
            dv.handler?.removeCallbacks(mReturnHomeRunnable)
        } catch (e: Exception) {
            // 静默
        }
        super.onDestroy()
    }

    /**
     * 日夜模式切换（configChanges 声明 uiMode 后不再重建 Activity）：
     * std 模式下重建会销毁 VirtualDisplay 使高德任务回落主屏、导航中断，
     * 故手动完成主题切换——清图标配色缓存、重刷颜色覆盖与底栏。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        IconCache.clearNormalized()
        applyTheme()
        mDockBar.refresh()
        mMusicProgress.progressDrawable = resources.getDrawable(R.drawable.progress_music)
        mMusicProgress.progress = mMusicProgress.progress
        mPipPlaceholder.setBackgroundResource(R.drawable.bg_pip_frame)
        mBtnPrev.setBackgroundResource(R.drawable.bg_icon_btn)
        mBtnPlayPause.setBackgroundResource(R.drawable.bg_icon_btn)
        mBtnNext.setBackgroundResource(R.drawable.bg_icon_btn)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (mNeedPipSync) {
            mNeedPipSync = false
            mPipPlaceholder.postDelayed({ mPip.ensureStd() }, PIP_START_DELAY_MS)
        }
    }

    /** 设置快照（桌面设置重启生效）：车速卡内容开关 + 布局/字号尺寸 */
    private fun loadSettings() {
        val p = Prefs.of(this)
        mShowPrefs.clear()
        for (key in SettingsActivity.SHOW_KEYS) {
            mShowPrefs[key] = p.getBoolean(key, true)
        }
        mIntPrefs.clear()
        for (i in SettingsActivity.INT_KEYS.indices) {
            mIntPrefs[SettingsActivity.INT_KEYS[i]] =
                p.getInt(SettingsActivity.INT_KEYS[i], SettingsActivity.INT_DEFAULTS[i])
        }
    }

    /** int 尺寸快照取值（loadSettings 已预读全部 INT_KEYS） */
    private fun size(key: String, def: Int): Int = mIntPrefs[key] ?: def

    private fun applyLayout() {
        val pad = size(SettingsActivity.KEY_PAGE_PADDING, 0)
        mPageContent.setPadding(pad, pad, pad, pad)
        val gap = size(SettingsActivity.KEY_CARD_GAP, 0)
        mGapSpeedMusic.updateLp(matchH = gap)
        mGapDock.updateLp(matchH = gap)
        mGapCol.updateLp(matchW = gap)
        mLeftCol.layoutParams.width = size(SettingsActivity.KEY_SPEED_CARD_W, 260)
        mCardMusic.layoutParams.height = size(SettingsActivity.KEY_MUSIC_CARD_H, 200)
        mDockGrid.layoutParams.height = 80
    }

    /** 简化 layoutParams 更新：matchW/matchH 任一为非 0 即新建 LayoutParams */
    private fun View.updateLp(matchW: Int = 0, matchH: Int = 0) {
        layoutParams = LinearLayout.LayoutParams(
            if (matchW != 0) matchW else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (matchH != 0) matchH else ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** 全部可调字号（px 快照），onCreate 一次性设置 */
    private fun applyTextSizes() {
        mTextSizers.forEach { (view, key, def) ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size(key, def).toFloat())
        }
    }

    /** 车速卡元素可见性（onCreate 一次性） */
    private fun applySpeedVisibility() {
        setVis(mTvSpeed, SettingsActivity.KEY_SHOW_SPEED)
        setVis(mTvKm, SettingsActivity.KEY_SHOW_KMH)
        setVis(mTvLimit, SettingsActivity.KEY_SHOW_LIMIT)
        setVis(mTvTraffic, SettingsActivity.KEY_SHOW_TRAFFIC)
        setVis(mTvTrafficSec, SettingsActivity.KEY_SHOW_TRAFFIC)
    }

    /** 音乐卡内容可见性（onCreate 一次性；onTrackChanged/onProgress 仅改文本不动可见性） */
    private fun applyMusicVisibility() {
        setVis(mTvMusicName, SettingsActivity.KEY_SHOW_MUSIC_TITLE)
        setVis(mTvArtist, SettingsActivity.KEY_SHOW_MUSIC_ARTIST)
        setVis(mMusicTimeRow, SettingsActivity.KEY_SHOW_MUSIC_TIME)
        setVis(mMusicProgress, SettingsActivity.KEY_SHOW_MUSIC_BAR)
    }

    private fun setVis(v: View, key: String) {
        v.visibility = if (show(key)) View.VISIBLE else View.GONE
    }

    private data class TextSizer(val view: TextView, val key: String, val default: Int)

    private lateinit var mTextSizers: Array<TextSizer>

    private fun show(key: String): Boolean = mShowPrefs[key] ?: true

    /**
     * 深浅色应用：覆盖根背景、两张卡片底色（重建 GradientDrawable 替代 @drawable/bg_card）
     * 与各静态文字颜色；红绿灯三色/超速色/导航提醒黄在各自渲染点直接读取 R.color.* 资源。
     */
    private fun applyTheme() {
        mContentRoot.setBackgroundColor(resources.getColor(R.color.background, theme))
        val card = resources.getColor(R.color.surface, theme)
        mCardSpeed.background = GradientDrawable().apply { setColor(card) }
        mCardMusic.background = GradientDrawable().apply { setColor(card) }
        mPipPlaceholder.background = GradientDrawable().apply {
            setColor(resources.getColor(R.color.scrim, theme))
            setStroke(2, resources.getColor(R.color.outlineVariant))
        }

        val primary = resources.getColor(R.color.onSurface, theme)
        val secondary = resources.getColor(R.color.onSurfaceVariant, theme)
        val tint = ColorStateList.valueOf(primary)
        mBtnPrev.imageTintList = tint
        mBtnPlayPause.imageTintList = tint
        mBtnNext.imageTintList = tint
        applyTextColors(primary, secondary)
    }

    private fun applyTextColors(primary: Int, secondary: Int) {
        PRIMARY_TEXT_VIEWS.forEach { it.setTextColor(primary) }
        SECONDARY_TEXT_VIEWS.forEach { it.setTextColor(secondary) }
    }

    private val PRIMARY_TEXT_VIEWS get() = arrayOf(
        mTvSpeed, mTvMusicName, mTvNaviDist, mTvNaviRoad
    )
    private val SECONDARY_TEXT_VIEWS get() = arrayOf(
        mTvKm, mTvLimit, mTvArtist, mCurTime, mTotalTime,
        mTvNaviDest, mTvNaviRemain, mTvNaviTime, mTvNaviAlert
    )

    // ── 车速 ──────────────────────

    override fun onSpeedChanged(kmh: Int) {
        animateSpeedTo(kmh)
    }

    override fun onAccOff() {
        animateSpeedTo(0)
    }

    /**
     * 车速直显（无动画）：每次广播即时刷新；
     * 同值短路避免无效 setText。方法名保留以最小化调用方改动。
     */
    private fun animateSpeedTo(targetKmh: Int) {
        val target = if (mMiles) (targetKmh * MILE_RATIO).toInt() else targetKmh
        if (target == mShownSpeed) return
        mShownSpeed = target
        mTvSpeed.text = target.toString()
        refreshOverspeed()
    }

    // ── 红绿灯 ──────────────────────

    override fun onTrafficLight(dir: Int, status: Int, countdown: Int) {
        if (!show(SettingsActivity.KEY_SHOW_TRAFFIC)) return
        val color = when (status) {
            1 -> resources.getColor(R.color.trafficRed, theme)
            4 -> resources.getColor(R.color.trafficGreen, theme)
            else -> resources.getColor(R.color.trafficYellow, theme)
        }
        mTvTrafficSec.setTextColor(color)
        mTvTrafficSec.text = if (countdown >= 0) "$countdown" else "--"
    }

    override fun onTrafficLightHidden() {
        mTvTrafficSec.setTextColor(resources.getColor(R.color.onSurfaceVariant, theme))
        mTvTrafficSec.text = "--"
    }

    // ── 导航文字信息（高德 AUTONAVI_STANDARD_BROADCAST_SEND）──────────────────

    override fun onNaviInfo(info: NaviTextClient.NaviInfo) {
        // 限速：LIMITED_SPEED 缺席(-1)时回落电子眼限速（同 amap-companion 规则）
        applyLimitSpeed(if (info.limitedSpeed > 0) info.limitedSpeed else info.cameraSpeed)
        val cruise = info.mode == NaviTextClient.Mode.CRUISE
        mNaviPanel.visibility = View.VISIBLE
        applyNaviOrder(cruise, info)
    }

    private fun applyNaviOrder(cruise: Boolean, info: NaviTextClient.NaviInfo) {
        val orderKey = if (cruise) "cruise_row_order" else "navi_row_order"
        val defaultOrder = if (cruise) "road,alert" else "turn,road,dest,eta,alert"
        val order: String
        val unified = Prefs.getString(this, "all_row_order", null)
        if (!unified.isNullOrEmpty()) {
            val filtered = NaviOrder.filter(unified, cruise)
            order = if (filtered.isNotEmpty()) filtered else Prefs.getString(this, orderKey, defaultOrder)!!
        } else {
            order = Prefs.getString(this, orderKey, defaultOrder)!!
        }
        val keys = order.split(",")
        mNaviPanel.removeAllViews()
        for (key in keys) {
            when (key) {
                "turn" -> if (!cruise) {
                    val iconRes = NaviTextClient.turnIconRes(info.icon)
                    if (iconRes != 0) {
                        mIvTurnIcon.setImageResource(iconRes)
                        mIvTurnIcon.scaleX = if (NaviTextClient.turnIconMirrored(info.icon)) -1f else 1f
                    }
                    mTvNaviDist.text = formatDis(info.segRemainDis)
                    mNaviPanel.addView(mNaviRowTurn)
                }
                "road" -> {
                    var road = if (cruise) info.curRoadName else info.nextRoadName
                    if (road.isNullOrEmpty()) road = info.curRoadName
                    mTvNaviRoad.text = if (cruise) {
                        road ?: ""
                    } else {
                        if (road == null) "" else "进入 $road"
                    }
                    mNaviPanel.addView(mTvNaviRoad)
                }
                "dest" -> if (!cruise) {
                    mTvNaviDest.text = info.endPoiName ?: ""
                    mNaviPanel.addView(mTvNaviDest)
                }
                "eta" -> if (!cruise) {
                    mTvNaviTime.text = "剩${formatDuration(info.remainTime)}"
                    mTvNaviRemain.text = formatRemain(info.remainDis)
                    mNaviPanel.addView(mNaviRowEta)
                }
                "alert" -> {
                    val alertOn = if (cruise) show(SettingsActivity.KEY_SHOW_CRUISE_ALERT)
                    else show(SettingsActivity.KEY_SHOW_NAVI_ALERT)
                    renderAlert(info, alertOn)
                    mNaviPanel.addView(mTvNaviAlert)
                }
            }
        }
    }

    /** 行 5：电子眼 / 服务区提醒 */
    private fun renderAlert(info: NaviTextClient.NaviInfo, enabled: Boolean) {
        val alert = StringBuilder()
        if (info.cameraDist >= 0 && info.cameraType >= 0) {
            alert.append(NaviTextClient.cameraTypeName(info.cameraType))
                .append(' ').append(formatDis(info.cameraDist))
            if (info.cameraSpeed > 0) {
                alert.append("·限速").append(displaySpeed(info.cameraSpeed))
            }
        }
        if (info.sapaDist > 0) {
            if (alert.isNotEmpty()) {
                alert.append("  ·  ")
            }
            alert.append(info.sapaName ?: "服务区")
                .append(' ').append(formatDis(info.sapaDist))
        }
        if (enabled && alert.isNotEmpty()) {
            mTvNaviAlert.text = alert.toString()
            mTvNaviAlert.setTextColor(resources.getColor(R.color.onSurfaceVariant, theme))
        } else {
            mTvNaviAlert.text = ""
        }
    }

    override fun onNaviStopped() {
        mNaviPanel.visibility = View.GONE
        applyLimitSpeed(-1)
    }

    /** 更新限速小字并刷新超速变色 */
    private fun applyLimitSpeed(limitedKmh: Int) {
        mLimitKmh = limitedKmh
        if (limitedKmh > 0) {
            mTvLimit.text = "限速${displaySpeed(limitedKmh)}"
        } else {
            mTvLimit.text = ""
        }
        refreshOverspeed()
    }

    private fun refreshOverspeed() {
        // mShownSpeed 与限速同处显示域（mph 模式均已换算）
        val over = mLimitKmh > 0 && mShownSpeed > displaySpeed(mLimitKmh)
        val overColor = resources.getColor(R.color.error)
        val color = if (over) overColor else resources.getColor(R.color.onSurface, theme)
        if (mTvSpeed.currentTextColor != color) mTvSpeed.setTextColor(color)
        val limitColor = if (over) overColor else resources.getColor(R.color.onSurfaceVariant, theme)
        if (mTvLimit.currentTextColor != limitColor) mTvLimit.setTextColor(limitColor)
    }

    /** 显示域速度（英里模式下换算 mph） */
    private fun displaySpeed(kmh: Int): Int =
        if (mMiles) Math.round(kmh * MILE_RATIO) else kmh

    private fun formatDis(meter: Int): String = FormatUtils.formatDistance(meter)
    private fun formatRemain(meter: Int): String = FormatUtils.formatRemainDistance(meter)
    private fun formatDuration(s: Int): String = FormatUtils.formatDuration(s)

    // ── 音乐 ──────────────────────

    override fun onTrackChanged(title: String, artist: String) {
        mTvMusicName.text = title
        mTvArtist.text = artist
    }

    override fun onPlayingStateChanged(playing: Boolean) {
        mBtnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        mCurTime.text = formatMs(positionMs)
        mTotalTime.text = if (durationMs > 0) formatMs(durationMs) else "00:00"
        mMusicProgress.progress = if (durationMs > 0) (positionMs * 1000 / durationMs).toInt() else 0
    }

    private fun formatMs(ms: Long): String = FormatUtils.formatMs(ms)

    // ── 音乐卡绑定 ──────────────────────

    private fun getBoundMusicPkg(): String? = Prefs.of(this).getString(MUSIC_APP_KEY, null)

    private fun setBoundMusicPkg(pkg: String?) {
        Prefs.of(this).edit().putString(MUSIC_APP_KEY, pkg).apply()
    }

    private fun onMusicButton(control: Runnable) {
        mMusicLauncher.onButton(control, getBoundMusicPkg())
    }

    /** 0.5 秒后回到桌面（当前界面），使刚进入的音乐 app 转入后台 */
    private fun returnToHome() {
        val h = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(h)
        } catch (e: Exception) {
            // 静默
        }
    }

    private fun launchBoundApp() {
        val pkg = getBoundMusicPkg() ?: run {
            mDockBar.pickApp("选择音乐应用", null) { pkgCls -> onMusicPicked(pkgCls) }
            return
        }
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { /* 静默 */ }
    }

    /** 音乐卡绑定回调：选择器返回 "pkg/cls"，取包名持久化 */
    private fun onMusicPicked(pkgCls: String?) {
        if (pkgCls == null || !pkgCls.contains("/")) {
            Toast.makeText(this, "绑定失败：无效的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = pkgCls.split("/")[0]
        if (pkg.isEmpty()) {
            Toast.makeText(this, "绑定失败：无效的应用", Toast.LENGTH_SHORT).show()
            return
        }
        setBoundMusicPkg(pkg)
        Toast.makeText(this, "已绑定：$pkg", Toast.LENGTH_SHORT).show()
    }

    fun cleanMemory() = MemoryCleaner.cleanFromUi(this)
}
