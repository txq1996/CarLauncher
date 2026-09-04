package com.android.launcher37.util
import com.android.launcher37.BuildConfig
/**
 * 调试日志统一出口（支持 release 装机调试）。
 *
 * - [i]：关键路径日志（生命周期/装配/保存/启动等低频事件）。
 *   debug 构建恒开；release 构建需运行时开关 [VERBOSE_PROP]。
 * - [d]：高频路径日志（拖动/缩放 MOVE、广播解析、进度、车速等）。
 *   仅 [verbose] 开启时输出，release 默认 lambda 不求值，零开销。
 *
 * 运行时开关（release 装机调试用，persist 前缀重启保持，进程启动时读取一次）：
 * ```
 * adb shell setprop persist.launcher37.verbose 1   # 开启（需重启桌面生效）
 * adb shell setprop persist.launcher37.verbose 0   # 关闭（需重启桌面生效）
 * adb logcat -s Launcher PageHost WidgetHost Designer Lyrics Media Music SpeedClient Navi AmapNavi VdWidget AppList Drawer Cleaner LayoutRepo LyricsSrc
 * ```
 *
 * 全量查看本应用日志：`adb logcat --pid=$(adb shell pidof com.android.launcher37)`
 */
internal object Dbg {

    /** 运行时详细日志开关（系统属性，persist 前缀重启保持） */
    @JvmField
    internal val verbose: Boolean = SysProps.get("persist.launcher37.verbose", "0") == "1"

    /** 关键路径日志：debug 构建恒开；release 需 [verbose] 开关 */
    inline fun i(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG || verbose) android.util.Log.i(tag, msg())
    }

    /** 高频路径日志：仅 [verbose] 开启时输出 */
    inline fun d(tag: String, msg: () -> String) {
        if (verbose) android.util.Log.d(tag, msg())
    }
}
