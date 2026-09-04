package com.android.launcher37.music
import com.android.launcher37.music.MediaHelper
import com.android.launcher37.util.MainThread
import android.app.Activity
import android.content.Intent
import android.os.Handler

/**
 * 音乐应用冷启动 + 轮询唤醒/回桌面。
 *
 * 用户按下「上一首 / 播放暂停 / 下一首」时，若已绑定包名 (`boundPkg`)：
 * - 如果该 APP 已有 MediaSession：直接发 play，然后 `control.run()` 控制
 * - 否则：冷启动该 APP，200ms × N 次轮询检查 `isPlaying()`，
 *   播放后调 [returnHome] 返回桌面（让 app 转后台播放）
 *
 * 8s 超时仍未播放 → 取消轮询（cancelPending），等待下次按键。
 */
class MusicLauncher(
    private val mActivity: Activity,
    private val mMediaHelper: MediaHelper,
    private val mReturnHome: Runnable?
) {
    companion object {
        private const val CHECK_INTERVAL_MS = 200L
        private const val TIMEOUT_MS = 8_000L
    }

    private val mHandler = MainThread.handler
    private var mPendingPackage: String? = null
    private var mStartTime: Long = 0

    private val mCheckRunnable: Runnable = Runnable {
        if (mActivity.isDestroyed || mActivity.isFinishing) {
            cancelPending()
            return@Runnable
        }
        val packageName = mPendingPackage
        if (packageName.isNullOrEmpty()) return@Runnable
        val state = mMediaHelper.getPlaybackState(packageName)
        if (state != null) {
            val playbackState = state.state
            // PLAYING / BUFFERING：已开始播放，返回桌面
            if (playbackState == android.media.session.PlaybackState.STATE_PLAYING
                || playbackState == android.media.session.PlaybackState.STATE_BUFFERING
            ) {
                mReturnHome?.run()
                cancelPending()
                return@Runnable
            }
            // PAUSED / NONE / STOPPED：每次检查都发送 play()
            val result = mMediaHelper.play(packageName)
            if (result == 1) {
                mReturnHome?.run()
                cancelPending()
                return@Runnable
            }
        }
        // 超时：停在当前界面，不做任何操作
        if (System.currentTimeMillis() - mStartTime >= TIMEOUT_MS) {
            cancelPending()
            return@Runnable
        }
        scheduleCheck()
    }

    /**
     * 处理音乐按钮点击：
     * - 未绑定包名：直接执行传入的控制（如切换播放/暂停全局媒体）
     * - 已绑定包名：有 session → 发 play + control；无 session → 冷启动 + 轮询
     */
    fun onButton(control: Runnable?, boundPkg: String?) {
        if (boundPkg.isNullOrEmpty()) {
            control?.run()
            return
        }
        // 已经有指定 APP 的 MediaSession，直接发送 play()
        if (mMediaHelper.hasMediaSession(boundPkg)) {
            mMediaHelper.play(boundPkg)
            control?.run()
            return
        }
        // 没有 MediaSession，启动绑定的音乐 APP
        val intent = mActivity.packageManager.getLaunchIntentForPackage(boundPkg) ?: return
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mActivity.startActivity(intent)
        } catch (e: Exception) {
            return
        }
        cancelPending()
        mPendingPackage = boundPkg
        mStartTime = System.currentTimeMillis()
        // 启动后开始轮询播放状态
        scheduleCheck()
    }

    /** 取消所有待执行的轮询（Activity 销毁时调用） */
    fun cancelPending() {
        mHandler.removeCallbacks(mCheckRunnable)
        mPendingPackage = null
        mStartTime = 0
    }

    private fun scheduleCheck() {
        mHandler.removeCallbacks(mCheckRunnable)
        mHandler.postDelayed(mCheckRunnable, CHECK_INTERVAL_MS)
    }
}
