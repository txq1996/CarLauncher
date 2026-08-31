package com.android.launcher37

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.lang.reflect.Method

/**
 * 内存清理：杀掉所有"可清"的用户进程（uid ≥ FIRST_APPLICATION_UID），
 * 跳过：
 * - 自身（launcher）
 * - 系统核心（uid < FIRST_APPLICATION_UID）
 * - 正在播放音乐（[playingPkg]）—— MediaSession 标记的活跃 session，
 *   未播放时不保留，绑定 app 也视为普通三方进程清理
 * - PIP 地图（[pipPkg]）—— 导航任务搬移 + 触摸转发
 *
 * 激进清理：使用 [ActivityManager.forceStopPackageAsUser]（hidden @SystemApi），
 * 不受 framework `isCached` 校验限制，能杀 FGS / 前台 service 关联的进程。
 * 通过反射调用（compileSdk=34 不暴露），需 system uid 持有
 * `FORCE_STOP_PACKAGES`（sharedUserId 已满足）。
 *
 * 副作用：framework 会向所有 uid 发送 PACKAGE_RESTARTED 广播并撤销运行时权限，
 * 即使目标包不在 forceStop 列表里也可能受牵连（如其他 vendor 的 vold symlink 扫描）。
 */
object MemoryCleaner {

    private val forceStopMethod: Method? by lazy {
        val amClass = ActivityManager::class.java
        // SDK 32+ 改名为 forceStopPackageAsUser；旧版本保留 forceStopPackage(String, int)
        // getMethod 抛 NoSuchMethodException 而非返回 null，所以 try/catch 必须嵌套而非 ?:
        try {
            amClass.getMethod("forceStopPackageAsUser", String::class.java, Int::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
            try {
                amClass.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) {
                null
            }
        }
    }

    /**
     * 清理进程。
     *
     * @param playingPkgs 正在播放音乐的包名集合（含 STATE_BUFFERING）。同时多 app
     *                    在播时集合里有多个元素。null 或空集合表示无任何播放中。
     * @param pipPkg      PIP 地图包名（不清理），null 表示无
     * @return 释放的内存 MB 数（四舍五入，最小 0）
     */
    @JvmStatic
    fun clean(c: Context, playingPkgs: Set<String>?, pipPkg: String?): Long {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val beforeMb = availableMb(am)
        val self = c.packageName
        for (pi in am.runningAppProcesses) {
            if (pi == null) continue
            if (pi.uid < Process.FIRST_APPLICATION_UID) continue
            // 进程可能服务多个包（如 QQ 音乐把播放跑在子进程或与 host 进程共享 pkgList），
            // 任一 pkgList 元素命中保护集合就跳过。pkgList 为空时 fallback 到 processName
            // （子进程名带冒号后缀，正常情况下 processName.startsWith(self) 仍能识别自身，
            // 但外部 playingPkgs 不会带冒号，所以 fallback 主要靠 pkgList[0] 即可）。
            val pkgs = if (pi.pkgList != null && pi.pkgList.isNotEmpty()) pi.pkgList.toList() else listOf(pi.processName)
            val anyProtected = pkgs.any { it == self || (pipPkg != null && it == pipPkg) }
            if (anyProtected) continue
            val playingHit = playingPkgs != null && pkgs.any { playingPkgs.contains(it) }
            if (playingHit) continue
            // 取第一个非空的 pkg 名做 forceStop（framework 会杀掉同 uid 所有进程）
            val target = pkgs.firstOrNull { !it.isNullOrEmpty() } ?: continue
            forceStop(am, target)
        }
        val afterMb = availableMb(am)
        val freedMb = afterMb - beforeMb
        return if (freedMb > 0) freedMb else 0
    }

    /** 反射调用 ActivityManager.forceStopPackageAsUser，吞 SecurityException（无权限时静默） */
    private fun forceStop(am: ActivityManager, pkg: String) {
        val m = forceStopMethod ?: return
        try {
            m.invoke(am, pkg, 0)
        } catch (_: Throwable) {
            // SecurityException / IllegalAccessException / InvocationTargetException —— 跳过该包
        }
    }

    /** 获取当前可用内存（MB） */
    private fun availableMb(am: ActivityManager): Long {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1024L * 1024L)
    }

    /**
     * 触发内存清理（UI 入口）：探测 Activity 上的 MediaHelper / PipController
     * （若是 [LauncherActivity]），执行 [clean] 并 Toast 释放结果。
     *
     * 保护正在播放的所有 app：从 MediaHelper.playingPackages 拿到整个集合（不止
     * activePackage 一个），避免多 app 同时在播时漏掉第二个。
     */
    @JvmStatic
    fun cleanFromUi(a: android.app.Activity) {
        val playing = if (a is LauncherActivity) a.mediaHelper.playingPackages else null
        val pipPkg = (a as? LauncherActivity)?.pip?.resolvePkg()
        val freedMb = clean(a, playing, pipPkg)
        val msg = if (freedMb > 0) "已释放 $freedMb MB 内存" else "当前无后台进程可清理"
        // Toast 必须在主线程；从 /memoryclean 这种后台 worker 线程进来时
        // post 到主线程避免 IllegalStateException。
        MainThread.handler.post {
            try { android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show() }
            catch (_: Throwable) { /* Activity 死亡时静默 */ }
        }
    }
}