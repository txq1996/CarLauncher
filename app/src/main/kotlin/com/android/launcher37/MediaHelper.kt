package com.android.launcher37

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * 音乐播放控制（`MediaSessionManager`）。
 *
 * - 进程内 `MediaSessionManager.getActiveSessions(null)`（需 MEDIA_CONTENT_CONTROL 特权）
 *   + `OnActiveSessionsChangedListener`；自动跟随"正在播放"的会话
 * - 进度条 500ms ticker（仅活动会话且播放中运行，暂停/无会话零轮询，
 *   恢复播放经 `onPlaybackStateChanged` 重新拉起）
 * - 播放中按 `position + elapsed*playbackSpeed` 推算实时进度
 *
 * 提供 [play] / [hasMediaSession] / [getController] / [getPlaybackState] 等便利方法
 * 供 [MusicLauncher] 在绑定音乐应用冷启动后查询会话。
 */
class MediaHelper(
    private val mContext: Context,
    private val mUi: UiCallback
) {
    interface UiCallback {
        fun onTrackChanged(title: String, artist: String)
        fun onPlayingStateChanged(playing: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)
    }

    companion object {
        private const val TAG = "MediaHelper"
        private const val TICK_MS = 500L
        private const val PLAYING_PKGS_REFRESH_MS = 2_000L

        /**
         * 最后一个 evaluate 后所有正在播放的包名集合。仅供 debug 反射读取
         * —— 不参与业务逻辑。
         * 业务应使用实例的 [playingPackages] getter（保证多实例时不会串）。
         */
        @Volatile
        var lastPlayingPkgs: Set<String> = emptySet()
            private set
    }

    private val mHandler = MainThread.handler
    private var mMsm: MediaSessionManager? = null
    private var mActive: MediaController? = null
    private var mPlaying = false
    private var mDurationMs: Long = 0
    private var mStatePosMs: Long = 0
    private var mStateSpeed: Float = 0f
    private var mStateStampMs: Long = 0

    val isPlaying: Boolean
        get() = mPlaying && mActive != null

    val activePackage: String?
        get() = mActive?.packageName

    /**
     * 所有正在播放（含 STATE_BUFFERING）的 MediaSession 包名集合。
     * 用于 [MemoryCleaner] 一次性保护多个同时在播的音乐 app，而不仅限于 [activePackage]。
     * 评估时机与 [evaluate] 同步（OnActiveSessionsChangedListener 触发 + start() 初次），
     * 以及 [mSessionCallback.onPlaybackStateChanged] 单 session 状态翻转时增量更新。
     */
    val playingPackages: Set<String>
        get() = mPlayingPkgs

    private var mPlayingPkgs: Set<String> = emptySet()

    fun start() {
        if (mMsm == null) {
            mMsm = mContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }
        try {
            evaluate(safeActiveSessions())
            mMsm?.addOnActiveSessionsChangedListener(mSessionsListener, null, mHandler)
        } catch (e: SecurityException) {
            mUi.onTrackChanged("未授权", "无法读取媒体会话")
            return
        } catch (e: Exception) {
            // 静默
        }
        startTicker()
        // 周期性重刷 playingPkgs：framework 在某些时序下不回调
        // OnActiveSessionsChangedListener（如其他 app 启动 MediaSession 时 launcher
        // listener 还没注册；或 session 状态变化但 listener 没收到通知）。
        // 不影响 evaluate 的 best 选取与 ticker，纯粹是兜底保护 MemoryCleaner。
        mHandler.removeCallbacks(mPlayingPkgsRefresher)
        mHandler.postDelayed(mPlayingPkgsRefresher, PLAYING_PKGS_REFRESH_MS)
        // 启动后短期内多次重 evaluate：有的 ROM 在 launcher start() 立刻调
        // getActiveSessions() 返回空（QQ 音乐 session 已存在但 framework 还没把
        // listener 加进通知列表）；几秒后 listener 生效，重拉就拿得到了。
        mHandler.postDelayed({ evaluate(safeActiveSessions()) }, 100)
        mHandler.postDelayed({ evaluate(safeActiveSessions()) }, 500)
        mHandler.postDelayed({ evaluate(safeActiveSessions()) }, 1500)
        mHandler.postDelayed({ evaluate(safeActiveSessions()) }, 3000)
        mHandler.postDelayed({ evaluate(safeActiveSessions()) }, 6000)
    }

    fun stop() {
        mHandler.removeCallbacks(mTicker)
        mHandler.removeCallbacks(mPlayingPkgsRefresher)
        mPlayingPkgs = emptySet()
        lastPlayingPkgs = emptySet()
        if (mMsm != null) {
            try {
                mMsm!!.removeOnActiveSessionsChangedListener(mSessionsListener)
            } catch (e: Exception) {
                // 静默
            }
            mMsm = null
        }
        detachActive()
        mPlaying = false
        mDurationMs = 0
        mUi.onProgress(0, 0)
    }

    fun prev() = transport { skipToPrevious() }
    fun next() = transport { skipToNext() }

    fun togglePlay() = transport { if (mPlaying) pause() else play() }

    /** 对当前活跃 session 的 transportControls 执行指定动作；session 缺席 / 抛错均静默。 */
    private inline fun transport(action: android.media.session.MediaController.TransportControls.() -> Unit) {
        val c = mActive ?: return
        try {
            c.transportControls.action()
        } catch (e: Exception) {
            // 静默
        }
    }

    /**
     * 按包名查找指定 APP 的 MediaController。
     */
    fun getController(packageName: String?): MediaController? {
        if (packageName.isNullOrEmpty()) return null
        if (mMsm == null) {
            mMsm = mContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }
        val msm = mMsm ?: return null
        return try {
            msm.getActiveSessions(null)?.firstOrNull {
                it != null && packageName == it.packageName
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 判断指定 APP 是否已经建立 MediaSession */
    fun hasMediaSession(packageName: String?): Boolean = getController(packageName) != null

    /** 获取指定 APP 的 PlaybackState */
    fun getPlaybackState(packageName: String?): PlaybackState? {
        val controller = getController(packageName) ?: return null
        return try {
            controller.playbackState
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 给指定 APP 发送播放命令。
     * @return 1=已播放/缓冲 0=已发送 play -1=失败
     */
    fun play(packageName: String?): Int {
        val controller = getController(packageName) ?: return -1
        return try {
            val state = controller.playbackState
            if (state != null) {
                val s = state.state
                if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
                    return 1
                }
            }
            controller.transportControls.play()
            0
        } catch (e: Exception) {
            -1
        }
    }

    private val mSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> evaluate(controllers) }

    private val mSessionCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            recordState(state)
            // 单 session 状态翻转：重新扫描所有 active sessions，更新 mPlayingPkgs。
            // 不调 evaluate() 是因为 evaluate 会 detach/reattach controller，但 mActive
            // 仍是当前 session，重新 attach 浪费。只刷新 playingPkgs 集合即可。
            refreshPlayingPackages()
            refreshPlayState()
            updateProgress()
            startTicker()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshMetadata()
        }
    }

    private fun refreshPlayingPackages() {
        val list = safeActiveSessions()
        val pkgs = collectPlayingPackages(list)
        mPlayingPkgs = pkgs
        lastPlayingPkgs = pkgs
    }

    private val mTicker = object : Runnable {
        override fun run() {
            updateProgress()
            if (mPlaying && mActive != null) {
                mHandler.postDelayed(this, TICK_MS)
            }
        }
    }

    /**
     * 周期性重刷 mPlayingPkgs。start() 时 post 一次，之后每 2s 重 post。
     * 兜底 OnActiveSessionsChangedListener 时序问题（framework 偶尔漏发通知）。
     */
    private val mPlayingPkgsRefresher: Runnable = object : Runnable {
        override fun run() {
            refreshPlayingPackages()
            mHandler.postDelayed(this, PLAYING_PKGS_REFRESH_MS)
        }
    }

    private fun evaluate(sessions: List<MediaController>?) {
        // 总是重新拉取 active sessions，不依赖 listener 回调参数。
        // 经验：listener 在某些时序下会传空 list（QQ 音乐 session 已建但 listener
        // 还没拿到引用），重拉可以保证每次 evaluate 拿到当前真实状态。
        var list = safeActiveSessions()
        if (list == null) list = sessions
        var best: MediaController? = null
        if (list != null) {
            for (c in list) {
                if (c.playbackState == null) continue
                if (isPlaying(c.playbackState)) { best = c; break }
                if (best == null) best = c
            }
        }
        if (best !== mActive) {
            detachActive()
            mActive = best
            if (mActive != null) {
                mActive!!.registerCallback(mSessionCallback, mHandler)
                refreshMetadata()
                recordState(mActive!!.playbackState)
            } else {
                mStatePosMs = 0
                mStateSpeed = 0f
                refreshMetadata()
            }
        } else if (mActive != null) {
            recordState(mActive!!.playbackState)
        }
        // 同步刷新"所有正在播放的包名集合"——遍历完整 active sessions 列表，
        // 收集每个 isPlaying 状态的 controller.packageName。与上面 best 选取独立，
        // 即使 best 因 break 只能指向第一个在播的，mPlayingPkgs 仍包含所有在播 app。
        val pkgs = collectPlayingPackages(list)
        mPlayingPkgs = pkgs
        // companion 静态字段：给 debug 反射用，业务逻辑不应读这里
        lastPlayingPkgs = pkgs
        refreshPlayState()
        updateProgress()
        startTicker()
    }

    private fun collectPlayingPackages(list: List<MediaController>?): Set<String> {
        if (list == null) return emptySet()
        val out = HashSet<String>()
        for (c in list) {
            val s = try { c.playbackState } catch (_: Exception) { null } ?: continue
            if (isPlaying(s)) {
                val pkg = try { c.packageName } catch (_: Exception) { null }
                if (!pkg.isNullOrEmpty()) out.add(pkg)
            }
        }
        return out
    }

    private fun safeActiveSessions(): List<MediaController>? = try {
        mMsm?.getActiveSessions(null)
    } catch (e: Exception) {
        Log.w(TAG, "safeActiveSessions failed", e)
        null
    }

    private fun isPlaying(state: PlaybackState?): Boolean {
        if (state == null) return false
        return state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING
    }

    private fun recordState(state: PlaybackState?) {
        if (state == null) return
        mStatePosMs = state.position
        mStateSpeed = state.playbackSpeed
        mPlaying = isPlaying(state)
        mStateStampMs = SystemClock.elapsedRealtime()
    }

    private fun refreshMetadata() {
        val c = mActive
        var title = "未在播放"
        var artist = ""
        mDurationMs = 0
        if (c != null) {
            val meta = c.metadata
            if (meta != null) {
                val t = meta.getText(MediaMetadata.METADATA_KEY_TITLE)
                var a = meta.getText(MediaMetadata.METADATA_KEY_ARTIST)
                if (a == null || a.isEmpty()) {
                    a = meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                }
                if (t != null && t.isNotEmpty()) title = t.toString()
                if (a != null && a.isNotEmpty()) artist = a.toString()
                mDurationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
            }
        }
        mUi.onTrackChanged(title, artist)
    }

    private fun refreshPlayState() {
        mUi.onPlayingStateChanged(mPlaying)
    }

    private fun updateProgress() {
        var pos = mStatePosMs
        if (mPlaying && mStateSpeed > 0) {
            pos += ((SystemClock.elapsedRealtime() - mStateStampMs) * mStateSpeed).toLong()
        }
        if (pos < 0) pos = 0
        var dur = mDurationMs
        if (dur > 0 && pos > dur) pos = dur
        mUi.onProgress(pos, dur)
    }

    private fun startTicker() {
        mHandler.removeCallbacks(mTicker)
        if (mPlaying && mActive != null) {
            mHandler.postDelayed(mTicker, TICK_MS)
        }
    }

    private fun detachActive() {
        val c = mActive ?: return
        try {
            c.unregisterCallback(mSessionCallback)
        } catch (e: Exception) {
            // 静默
        }
        mActive = null
    }
}
