package com.android.launcher37.home

import android.content.Intent
import android.widget.Toast
import com.android.launcher37.DockBar
import com.android.launcher37.FormatUtils
import com.android.launcher37.MediaHelper
import com.android.launcher37.MusicLauncher
import com.android.launcher37.Prefs
import com.android.launcher37.R

/**
 * 音乐委派：`MediaHelper.UiCallback` + 按钮事件 + 音乐卡绑定 + 回到桌面。
 *
 * 持有 Activity 强引用 + MediaHelper + MusicLauncher；
 * 选音乐应用通过 [appPicker] 回调（由 LauncherActivity 注入 DockBar.pickApp），
 * 让 delegate 不直接依赖 DockBar。
 */
class MusicDelegate(
    private val activity: android.app.Activity,
    private val views: HomeViews,
    private val appPicker: (pkgCls: String?) -> Unit
) : MediaHelper.UiCallback {

    private val mMediaHelper: MediaHelper = MediaHelper(activity, this)
    private val mReturnHomeRunnable = Runnable { returnToHome() }
    private val mMusicLauncher: MusicLauncher = MusicLauncher(
        activity, mMediaHelper, mReturnHomeRunnable
    )

    fun start() = mMediaHelper.start()
    fun stop() = mMediaHelper.stop()
    fun cancelPending() = mMusicLauncher.cancelPending()

    fun bindListeners() {
        views.btnPrev.setOnClickListener { onMusicButton(mMediaHelper::prev) }
        views.btnPlayPause.setOnClickListener { onMusicButton(mMediaHelper::togglePlay) }
        views.btnNext.setOnClickListener { onMusicButton(mMediaHelper::next) }
        views.musicInfo.setOnClickListener {
            if (getBoundMusicPkg() == null) {
                appPicker(null)
            } else {
                launchBoundApp()
            }
        }
        views.musicInfo.setOnLongClickListener {
            appPicker(null)
            true
        }
    }

    fun mediaHelper(): MediaHelper = mMediaHelper
    fun musicLauncher(): MusicLauncher = mMusicLauncher

    override fun onTrackChanged(title: String, artist: String) {
        views.tvMusicName.text = title
        views.tvArtist.text = artist
    }

    override fun onPlayingStateChanged(playing: Boolean) {
        views.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        views.curTime.text = FormatUtils.formatMs(positionMs)
        views.totalTime.text = if (durationMs > 0) FormatUtils.formatMs(durationMs) else "00:00"
        views.musicProgress.progress =
            if (durationMs > 0) (positionMs * 1000 / durationMs).toInt() else 0
    }

    fun onMusicButton(control: Runnable) {
        mMusicLauncher.onButton(control, getBoundMusicPkg())
    }

    /** 音乐卡绑定回调（选 pkg/cls 后存完整 id，与 Store 格式一致） */
    fun onMusicPicked(pkgCls: String?) {
        if (pkgCls == null || !pkgCls.contains("/") || pkgCls.substringBefore('/').isEmpty()) {
            Toast.makeText(activity, "绑定失败：无效的应用", Toast.LENGTH_SHORT).show()
            return
        }
        setBoundMusicId(pkgCls)
        Toast.makeText(activity, "已绑定：${pkgCls.substringBefore('/')}", Toast.LENGTH_SHORT).show()
    }

    fun launchBoundApp() {
        val id = getBoundMusicId() ?: run {
            appPicker(null)
            return
        }
        // 优先按 pkg/cls 完整 id 启动指定 Activity（多入口应用更准确）。
        // 旧 SP 数据只有 pkg（不含 cls）时 entryIntent 返回 null，兜底走系统默认入口。
        val i = com.android.launcher37.Store.entryIntent(activity, id)
            ?: activity.packageManager.getLaunchIntentForPackage(id.substringBefore('/'))
            ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(i)
        } catch (e: Exception) {
            // 静默
        }
    }

    /** 0.5 秒后回到桌面，使刚进入的音乐 app 转入后台 */
    private fun returnToHome() {
        val h = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(h)
        } catch (e: Exception) {
            // 静默
        }
    }

    /**
     * 取绑定 id（pkg/cls 完整格式）。SP key 复用老的 music_app_pkg 以兼容旧数据：
     * 旧版本只存 pkg 没有 cls，新版本存 pkg/cls。
     * - 旧数据（如 "com.tencent.qqmusiccar"）会当成只有 pkg 的 id 走兜底启动；
     * - 新绑定存完整 "pkg/cls"。
     */
    private fun getBoundMusicId(): String? = Prefs.of(activity).getString(MUSIC_APP_KEY, null)
    private fun setBoundMusicId(id: String) {
        Prefs.of(activity).edit().putString(MUSIC_APP_KEY, id).apply()
    }

    /** 取 pkg 部分（兼容旧 SP 数据无 cls） */
    private fun getBoundMusicPkg(): String? = getBoundMusicId()?.substringBefore('/')?.takeIf { it.isNotEmpty() }

    companion object {
        private const val MUSIC_APP_KEY = "music_app_pkg"
    }
}
