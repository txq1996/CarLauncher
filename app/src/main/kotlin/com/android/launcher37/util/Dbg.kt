package com.android.launcher37.util
import com.android.launcher37.BuildConfig
import com.android.launcher37.LauncherActivity
/**
 * 生命周期调试日志（VDFocusDbg）统一出口。
 * 仅 debug 构建输出；release 下 lambda 不求值——
 * [LauncherActivity.dbgTop] 这类 getRunningTasks binder 调用也一并短路。
 */
internal object Dbg {
    inline fun i(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) android.util.Log.i(tag, msg())
    }
}
