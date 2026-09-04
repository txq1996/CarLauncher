package com.android.launcher37.data
import com.android.launcher37.LauncherActivity
import com.android.launcher37.util.MainThread
import com.android.launcher37.util.Dbg
import com.android.launcher37.util.SharedExecutor
import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.lang.reflect.Method

/**
 * 内存清理：杀掉所有"可清"的用户进程（uid ≥ FIRST_APPLICATION_UID），
 * 跳过：
 * - 自身（launcher）
 * - 系统核心（uid < FIRST_APPLICATION_UID）
 * - 正在播放音乐（[playingPkgs]，MediaSession 在播的全部包）
 * - VD 承载的应用（[vdPkgs]）—— 导航任务搬移 + 触摸转发
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

    private const val TAG = "Cleaner"

    private val forceStopMethod: Method? by lazy {
        val amClass = ActivityManager::class.java
        resolveForceStop(amClass, "forceStopPackageAsUser")
            ?: resolveForceStop(amClass, "forceStopPackage")
    }

    private fun resolveForceStop(cls: Class<*>, name: String): Method? {
        try { return cls.getMethod(name, String::class.java, Int::class.javaPrimitiveType) } catch (_: Throwable) {}
        return try {
            cls.getDeclaredMethod(name, String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        } catch (_: Throwable) { null }
    }

    /**
     * 清理进程。
     *
     * @param playingPkgs 正在播放音乐的包名集合（含 STATE_BUFFERING）。同时多 app
     *                    在播时集合里有多个元素。null 或空集合表示无任何播放中。
     * @param vdPkgs      VDWidget 承载的 App 包名集合（不清理，导航任务搬移 + 触摸转发），null 表示无
     * @return 释放的内存 MB 数（四舍五入，最小 0）
     */
    @JvmStatic
    fun clean(c: Context, playingPkgs: Set<String>?, vdPkgs: Set<String>?): Long {
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
            val anyProtected = pkgs.any { it == self || (vdPkgs != null && it in vdPkgs) }
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
     * 触发内存清理（UI 入口）：探测 Activity 上 Widget 的 MediaHelper / VD 绑定包
     * （若是 [LauncherActivity]），异步执行 [clean] 并 Toast 释放结果。
     *
     * 保护正在播放的所有 app：从 MediaHelper.playingPackages 拿到整个集合（不止
     * activePackage 一个），避免多 app 同时在播时漏掉第二个。
     */
    @JvmStatic
    fun cleanFromUi(a: android.app.Activity) {
        val playing = if (a is LauncherActivity) a.playingPkgs() else null
        val vdPkgs = (a as? LauncherActivity)?.vdPkgs()
        cleanAsync(a, playing, vdPkgs)
    }

    /**
     * 异步清理 + Toast 结果（AGENTS #5：forceStop 为同步 binder + killProcessGroup，
     * 进程多时主线程阻塞秒级有 ANR 风险，须调度到 SharedExecutor.io）。
     * playing/vdPkgs 在调用线程（主线程）先捕获，避免跨线程读 Widget 状态。
     */
    @JvmStatic
    fun cleanAsync(c: Context, playingPkgs: Set<String>?, vdPkgs: Set<String>?) {
        Dbg.i(TAG) { "cleanAsync: playing=$playingPkgs vd=$vdPkgs" }
        SharedExecutor.io().execute {
            val freedMb = clean(c, playingPkgs, vdPkgs)
            val msg = if (freedMb > 0) "已释放 $freedMb MB 内存" else "当前无后台进程可清理"
            Dbg.i(TAG) { "clean done: freed=${freedMb}MB" }
            // Toast 统一 post 回主线程；Activity 死亡时静默
            MainThread.handler.post {
                try { android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show() }
                catch (_: Throwable) { }
            }
        }
    }
}