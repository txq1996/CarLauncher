package com.android.launcher37.music
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 媒体会话共享源：单例包装一个 [MediaHelper]（MediaSessionManager 监听 + 兜底轮询），
 * 多个音乐卡（LyricsWidget）实例共用同一会话连接与轮询器。
 *
 * - [LauncherApp.onCreate] 调 [attach] 提供上下文
 * - 首个 UI 回调加入时 start，最后一个移除时 stop（与 Widget start/stop 配对）
 * - [playingPackages] 供内存清理保护读取
 * - [helper] 供 [MusicLauncher] 冷启动唤醒链路复用
 */
object MediaHub : MediaHelper.UiCallback {

    private val uis = CopyOnWriteArrayList<MediaHelper.UiCallback>()

    @Volatile private var mContext: Context? = null
    @Volatile private var mHelper: MediaHelper? = null

    fun attach(context: Context) {
        mContext = context.applicationContext
    }

    val helper: MediaHelper
        get() = mHelper ?: MediaHelper(
            mContext ?: error("MediaHub.attach(context) 未调用"),
            this
        ).also { mHelper = it }

    /** 所有正在播放（含 BUFFERING）的包名集合（内存清理保护用） */
    val playingPackages: Set<String>
        get() = helper.playingPackages

    /** 注册 UI 回调；首个加入时启动底层 MediaHelper */
    fun start(ui: MediaHelper.UiCallback) {
        if (uis.addIfAbsent(ui) && uis.size == 1) helper.start()
    }

    /** 注销 UI 回调；最后一个移除时停止底层 MediaHelper */
    fun stop(ui: MediaHelper.UiCallback) {
        if (uis.remove(ui) && uis.isEmpty()) helper.stop()
    }

    override fun onTrackChanged(title: String, artist: String) {
        for (ui in uis) runCatching { ui.onTrackChanged(title, artist) }
    }

    override fun onPlayingStateChanged(playing: Boolean) {
        for (ui in uis) runCatching { ui.onPlayingStateChanged(playing) }
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        for (ui in uis) runCatching { ui.onProgress(positionMs, durationMs) }
    }
}
