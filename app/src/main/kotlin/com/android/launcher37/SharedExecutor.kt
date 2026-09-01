package com.android.launcher37

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 全局共享 IO 线程池。
 *
 * 固定 2 线程的 [Executors.newFixedThreadPool]，供图标解析、应用列表加载、
 * 分屏项 IO 等"短时、偶尔阻塞"的离线任务复用。任务应避免长时占用线程（建议 < 1s），
 * 否则可能因排队而感知到 UI 延迟。
 *
 * 线程池为进程级单例，进程退出时由系统回收，无需显式 shutdown。
 *
 * 不应用于：网络请求（应另起 OkHttp 风格的池）、CPU 密集计算（应使用
 * `ForkJoinPool`）、短至立即返回的轻量任务（直接 main 线程即可）。
 */
object SharedExecutor {

    private val IO: ExecutorService = Executors.newFixedThreadPool(2)

    /**
     * 取全局 IO 线程池。
     *
     * @return 共享的 `ExecutorService`（不会为 `null`）
     */
    @JvmStatic
    fun io(): ExecutorService = IO
}
