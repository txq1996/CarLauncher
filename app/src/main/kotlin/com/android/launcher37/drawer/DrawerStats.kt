package com.android.launcher37.drawer
import com.android.launcher37.util.MainThread
import com.android.launcher37.util.SharedExecutor
import android.content.Context
import android.widget.TextView

/**
 * 抽屉标题栏系统状态（CPU / 温度 / 内存）。
 * AppDrawer（Activity 弹窗）与 DrawerOverlay（系统悬浮窗）共用。
 * 两处不会同时展示（toggle 互斥），ticker 取单实例即可。
 *
 * /proc、/sys 文件读取全部调度到 [SharedExecutor.io]（主线程只收结果文本），
 * 每秒一次的文件 IO 不占 UI 线程。代数号（sGeneration）保证 stop() 后不再回填/续期。
 */
internal object DrawerStats {

    private var sTicker: Runnable? = null
    private var sGeneration = 0
    private var sPrevIdle: Long = 0
    private var sPrevTotal: Long = 0

    /** CPU 增量基点：IO 线程池 2 线程可能交替执行，用锁保证读改写的原子性 */
    private val sCpuLock = Any()

    /**
     * 启动 1s 周期刷新。[alive] 返回 false 时自动停止：
     * - AppDrawer：popup.isShowing && !activity.isDestroyed/Finishing
     * - DrawerOverlay：sOverlayRoot != null
     * alive() 只在主线程调用。
     */
    fun start(c: Context, tv: TextView, alive: () -> Boolean) {
        stop()
        val gen = ++sGeneration
        val runnable = object : Runnable {
            override fun run() {
                if (!alive()) { stop(); return }
                SharedExecutor.io().execute {
                    val text = buildStatsText(c)
                    MainThread.handler.post {
                        if (gen != sGeneration) return@post
                        if (alive()) tv.text = text
                        MainThread.handler.postDelayed(this, 1000)
                    }
                }
            }
        }
        sTicker = runnable
        MainThread.handler.post(runnable)
    }

    fun stop() {
        sGeneration++
        sTicker?.let { MainThread.handler.removeCallbacks(it) }
        sTicker = null
    }

    fun buildStatsText(c: Context): String {
        val cpu = readCpuPercent()
        val temp = readCpuTemp()
        val mem = readMemPercent(c)
        val cpuStr = if (cpu >= 0) String.format("%4s", "$cpu%") else String.format("%4s", "--%")
        val tempStr = if (temp >= 0) String.format("%4s", "${temp}°C") else String.format("%4s", "--°C")
        val memStr = if (mem >= 0) String.format("%4s", "$mem%") else String.format("%4s", "--%")
        return "CPU:$cpuStr  $tempStr  MEM:$memStr"
    }

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
            synchronized(sCpuLock) {
                val diffIdle = idleAll - sPrevIdle
                val diffTotal = total - sPrevTotal
                sPrevIdle = idleAll; sPrevTotal = total
                if (diffTotal <= 0) return -1
                ((diffTotal - diffIdle) * 100 / diffTotal).toInt().coerceIn(0, 100)
            }
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
}
