package com.android.launcher37

import android.app.ActivityManager
import android.content.Context
import android.os.Process

/**
 * 内存清理：杀掉所有"可清"的用户进程（uid ≥ FIRST_APPLICATION_UID），
 * 跳过：
 * - 自身（launcher）
 * - 系统核心（uid < FIRST_APPLICATION_UID）
 * - Dock 栏所有 app（避免用户主动放上去的快捷方式被清）
 * - 正在播放音乐（[playingPkg]）—— MediaSession 标记的活跃 session
 * - PIP 地图（[pipPkg]）—— 导航任务搬移 + 触摸转发
 *
 * 与原版区别：取消 `IMPORTANCE_FOREGROUND` 跳过规则，强力清理所有前台进程
 * （除音乐 / 地图外）。QQ 音乐播放中通常 `FOREGROUND_SERVICE` importance，
 * 因 [playingPkg] 白名单保留。
 */
object MemoryCleaner {

    /**
     * 清理进程。
     *
     * @param dockPkgs   Dock 栏应用包名（不清理）
     * @param playingPkg 正在播放音乐的包名（isPlaying 为 true 时传入，否则 null）
     * @param pipPkg     PIP 地图包名（不清理），null 表示无
     * @return 释放的内存 MB 数（四舍五入，最小 0）
     */
    @JvmStatic
    fun clean(c: Context, dockPkgs: Set<String>?, playingPkg: String?, pipPkg: String?): Long {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val beforeMb = availableMb(am)
        val self = c.packageName
        for (pi in am.runningAppProcesses) {
            if (pi == null) continue
            // 跳过系统核心进程
            if (pi.uid < Process.FIRST_APPLICATION_UID) continue
            val pkg = if (pi.pkgList != null && pi.pkgList.isNotEmpty()) pi.pkgList[0] else pi.processName
            if (pkg.isNullOrEmpty()) continue
            // 白名单：自身 / Dock / 音乐 / 地图
            if (pkg == self) continue
            if (dockPkgs != null && pkg in dockPkgs) continue
            if (playingPkg != null && pkg == playingPkg) continue
            if (pipPkg != null && pkg == pipPkg) continue
            am.killBackgroundProcesses(pkg)
        }
        val afterMb = availableMb(am)
        val freedMb = afterMb - beforeMb
        return if (freedMb > 0) freedMb else 0
    }

    /** 获取当前可用内存（MB） */
    private fun availableMb(am: ActivityManager): Long {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1024L * 1024L)
    }

    /**
     * 触发内存清理（UI 入口）：自动从 [Store.v2Buttons] 收 dockPkgs +
     * 探测 Activity 上的 MediaHelper / PipController（若是 [LauncherActivity]），
     * 执行 [clean] 并 Toast 释放结果。
     */
    @JvmStatic
    fun cleanFromUi(a: android.app.Activity) {
        val dockPkgs = LinkedHashSet<String>()
        for (b in Store.v2Buttons(a)) {
            if (b.type == "app" && b.id.contains("/")) dockPkgs.add(b.id.split("/")[0])
        }
        val playing = if (a is LauncherActivity && a.mMediaHelper.isPlaying)
            a.mMediaHelper.activePackage else null
        val pipPkg = (a as? LauncherActivity)?.mPip?.resolvePkg()
        val freedMb = clean(a, dockPkgs, playing, pipPkg)
        val msg = if (freedMb > 0) "已释放 $freedMb MB 内存" else "当前无后台进程可清理"
        android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
