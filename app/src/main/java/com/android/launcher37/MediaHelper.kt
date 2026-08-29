package com.android.launcher37

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

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
        private const val TICK_MS = 500L
    }

    private val mHandler = Handler(Looper.getMainLooper())
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
    }

    fun stop() {
        mHandler.removeCallbacks(mTicker)
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
            refreshPlayState()
            updateProgress()
            startTicker()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshMetadata()
        }
    }

    private val mTicker = object : Runnable {
        override fun run() {
            updateProgress()
            if (mPlaying && mActive != null) {
                mHandler.postDelayed(this, TICK_MS)
            }
        }
    }

    private fun evaluate(sessions: List<MediaController>?) {
        var list = sessions
        if (list == null) list = safeActiveSessions()
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
        refreshPlayState()
        updateProgress()
        startTicker()
    }

    private fun safeActiveSessions(): List<MediaController>? = try {
        mMsm?.getActiveSessions(null)
    } catch (e: Exception) {
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
