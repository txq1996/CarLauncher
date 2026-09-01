package com.android.launcher37.home

import android.view.View
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * HOME Activity 视图快照：[LauncherActivity] 装配一次后传给 5 个委派模块。
 *
 * 把 35 个视图/控制器字段收成一份 immutable snapshot，
 * 让委派模块不直接持有 Activity 中的 view 引用（避免互相读写次序不一致）。
 */
class HomeViews(
    val contentRoot: View,
    val pageContent: View,
    val leftCol: View,
    val gapTimeSpeed: View,
    val gapSpeedMusic: View,
    val gapDock: View,
    val gapCol: View,
    val cardTime: View,
    val cardSpeed: View,
    val cardMusic: View,
    val musicInfo: View,
    val musicTimeRow: View,
    val dockGrid: GridView,
    val pipPlaceholder: View,
    val tvMusicName: TextView,
    val tvArtist: TextView,
    val curTime: TextView,
    val totalTime: TextView,
    val musicProgress: ProgressBar,
    val btnPlayPause: ImageButton,
    val btnPrev: ImageButton,
    val btnNext: ImageButton,
    val naviPanel: LinearLayout,
    val naviRowTurn: LinearLayout,
    val naviRowEta: LinearLayout,
    val ivTurnIcon: ImageView,
    val tvNaviDist: TextView,
    val tvNaviRoad: TextView,
    val tvNaviDest: TextView,
    val tvNaviTime: TextView,
    val tvNaviRemain: TextView,
    val tvNaviAlert: TextView,
    val tvNaviEtaText: TextView,
    val tvNaviLightCount: TextView,
    val tvNaviExit: TextView,
    val tvNaviDirection: TextView,
    val tvTime: TextView
)
