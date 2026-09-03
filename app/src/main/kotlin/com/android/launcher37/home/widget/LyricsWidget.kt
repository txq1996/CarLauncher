package com.android.launcher37.home.widget

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher37.FormatUtils
import com.android.launcher37.MediaHelper
import com.android.launcher37.MusicLauncher
import com.android.launcher37.R
import com.android.launcher37.SharedExecutor
import com.android.launcher37.Store
import com.android.launcher37.home.LrcParser
import com.android.launcher37.home.LrcTimedLine
import com.android.launcher37.home.LrclibProvider
import com.android.launcher37.home.Lyrics
import com.android.launcher37.home.LyricsData
import com.android.launcher37.home.LyricsProvider
import com.android.launcher37.home.SdcardMusicStore

/**
 * 歌词 Widget：封面 + 双语歌词行 + 播放控制（原 LyricsDelegate 逻辑迁移，自包含）。
 *
 * - 自持 [MediaHelper]（跟随系统 MediaSession）+ [MusicLauncher]（冷启动唤醒）
 * - 取词走 [Lyrics]（sdcard 本地优先 → vkeys QQ音乐 → lrclib 兜底），
 *   封面按 info.cover 下载并缓存 cover.jpg（同目录）
 * - 行数/字号/间距为实例 config（设计器属性面板编辑）
 * - 有翻译时行数减半：原文/译文各占一行
 * - 歌曲信息区（歌名/作者/进度）与音乐 Widget 同功能：点击唤醒已绑定音乐 app
 *   （未绑定先选择），长按重新绑定；绑定全局共享（`music_app_pkg`）
 */
class LyricsWidget(activity: Activity, spec: WidgetSpec) : WidgetView(activity, spec, R.layout.card_lyrics) {

    override val displayName = "歌词"

    /** 播放控制（与音乐 Widget 同一条 MediaSession 控制链路） */
    private val callback = object : MediaHelper.UiCallback {
        override fun onTrackChanged(title: String, artist: String) {
            tvTrack?.text = title
            tvArtist?.text = artist
            this@LyricsWidget.onTrackChanged(title, artist)
        }
        override fun onPlayingStateChanged(playing: Boolean) {
            findViewById<ImageButton>(R.id.btn_lyrics_play)
                ?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        override fun onProgress(positionMs: Long, durationMs: Long) {
            tvCur?.text = FormatUtils.formatMs(positionMs)
            tvTotal?.text = if (durationMs > 0) FormatUtils.formatMs(durationMs) else "00:00"
            progress?.progress = if (durationMs > 0) (positionMs * 1000 / durationMs).toInt() else 0
            this@LyricsWidget.onProgress(positionMs)
        }
    }

    private val mediaHelper = MediaHelper(activity, callback)
    private val musicLauncher = MusicLauncher(activity, mediaHelper, Runnable { returnToHome() })

    private var tvTrack: TextView? = null
    private var tvArtist: TextView? = null
    private var tvCur: TextView? = null
    private var tvTotal: TextView? = null
    private var progress: android.widget.ProgressBar? = null
    private var ivCover: ImageView? = null
    private var boxLines: LinearLayout? = null

    // 行数/字号/间距（读实例 config，属性面板调整后实时重建）
    private val lineCount: Int get() = cfgInt(CFG_LINES, 10)
    private val curSize: Int get() = cfgInt(CFG_SIZE_CUR, 20)
    private val otherSize: Int get() = cfgInt(CFG_SIZE_OTHER, 15)
    private val lineGap: Int get() = cfgInt(CFG_GAP, 15)

    override val props: List<WidgetProp> = listOf(
        WidgetProp(CFG_SHOW_TRACK, "显示歌名", PropType.BOOL, "1"),
        WidgetProp(CFG_SHOW_ARTIST, "显示作者", PropType.BOOL, "1"),
        WidgetProp(CFG_SHOW_BAR, "显示进度", PropType.BOOL, "1"),
        WidgetProp(CFG_SHOW_LYRICS, "显示歌词", PropType.BOOL, "1"),
        WidgetProp(CFG_LINES, "歌词行数", PropType.INT, "10", min = 3, max = 15),
        WidgetProp(CFG_SIZE_CUR, "当前句字号", PropType.INT, "20", min = 10, max = 50),
        WidgetProp(CFG_SIZE_OTHER, "其他行字号", PropType.INT, "15", min = 10, max = 50),
        WidgetProp(CFG_GAP, "行间距", PropType.INT, "15", min = 0, max = 30)
    )

    private class LrcLine(val timeMs: Long, val text: String)

    private fun parseLrc(lrc: String): List<LrcLine> =
        LrcParser.parse(lrc).map { LrcLine(it.timeMs, it.text) }

    /** 一行歌词 = 原文 + 翻译副行 */
    private class Row(val box: LinearLayout, val main: TextView, val sub: TextView)

    private val mProvider: LyricsProvider = LrclibProvider()
    /** 取词代数号：换歌/销毁时自增，旧任务回调作废 */
    private var mGen = 0
    /** 当前歌标识（artist|title），与上一次相同不重复取词 */
    private var mTrackKey: String? = null
    private var mLines: List<LrcLine> = emptyList()
    private var mTransLines: List<LrcLine> = emptyList()
    private var mCurIndex = -1
    private var mRows: List<Row> = emptyList()
    /** 有翻译时行数减半：原文/译文各占一行 */
    private var mHasTrans = false
    /** 纯文本歌词（无时间戳）时走整段显示 */
    private var mPlain: String? = null

    /** 实际显示行数（有翻译 = lineCount/2） */
    private val effectiveCount: Int get() = if (mHasTrans) lineCount / 2 else lineCount

    override fun onBind() {
        setCardBackground(true)
        tvTrack = findViewById(R.id.tv_lyrics_track)
        tvArtist = findViewById(R.id.tv_lyrics_artist)
        tvCur = findViewById(R.id.lyrics_cur_time)
        tvTotal = findViewById(R.id.lyrics_total_time)
        progress = findViewById(R.id.lyrics_progress)
        ivCover = findViewById(R.id.iv_lyrics_cover)
        boxLines = findViewById(R.id.box_lyrics_lines)
        // 歌曲信息区：未绑定先选择；已绑定 → 启动音乐 app，播放后自动返回桌面
        findViewById<View>(R.id.box_lyrics_info)?.setOnClickListener {
            val pkg = boundPkg()
            if (pkg == null) pickMusicApp() else musicLauncher.onButton(mediaHelper::togglePlay, pkg)
        }
        findViewById<View>(R.id.box_lyrics_info)?.setOnLongClickListener { pickMusicApp(); true }
        findViewById<View>(R.id.btn_lyrics_prev)?.setOnClickListener {
            musicLauncher.onButton(mediaHelper::prev, boundPkg())
        }
        findViewById<View>(R.id.btn_lyrics_play)?.setOnClickListener {
            musicLauncher.onButton(mediaHelper::togglePlay, boundPkg())
        }
        findViewById<View>(R.id.btn_lyrics_next)?.setOnClickListener {
            musicLauncher.onButton(mediaHelper::next, boundPkg())
        }
        buildLineViews()
    }

    override fun onSpecApplied() {
        // 信息区/进度/歌词区显隐 + 字号（每次重读实例 config，属性面板调整后保持最新）
        findViewById<View>(R.id.tv_lyrics_track)?.visibility =
            if (cfgBool(CFG_SHOW_TRACK, true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tv_lyrics_artist)?.visibility =
            if (cfgBool(CFG_SHOW_ARTIST, true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.box_lyrics_time)?.visibility =
            if (cfgBool(CFG_SHOW_BAR, true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.lyrics_progress)?.visibility =
            if (cfgBool(CFG_SHOW_BAR, true)) View.VISIBLE else View.GONE
        // 关闭歌词：歌词行区整体隐藏（封面占满剩余空间）
        findViewById<View>(R.id.box_lyrics_lines)?.visibility =
            if (cfgBool(CFG_SHOW_LYRICS, true)) View.VISIBLE else View.GONE
        tvTrack?.setTextSize(TypedValue.COMPLEX_UNIT_PX, curSize.toFloat())
        tvArtist?.setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
        tvCur?.setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
        tvTotal?.setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
    }

    override fun onPropChanged(key: String, value: String) {
        onSpecApplied()
        buildLineViews()
        refreshLines()
    }

    override fun start() = mediaHelper.start()
    override fun stop() = mediaHelper.stop()

    /** 内存清理保护读取（LauncherActivity.playingPkgs） */
    fun mediaHelper(): MediaHelper = mediaHelper

    override fun destroy() {
        mediaHelper.stop()
        musicLauncher.cancelPending()
        mGen++
    }

    override fun onThemeChange() {
        buildLineViews()
        refreshLines()
        for (id in intArrayOf(R.id.btn_lyrics_prev, R.id.btn_lyrics_play, R.id.btn_lyrics_next)) {
            findViewById<ImageButton>(id)?.setBackgroundResource(R.drawable.bg_icon_btn)
        }
        findViewById<android.widget.ProgressBar>(R.id.lyrics_progress)?.progressDrawable =
            activity.resources.getDrawable(R.drawable.progress_music)
    }

    // ── 绑定 / 唤醒（与音乐 Widget 同一全局绑定 music_app_pkg） ──

    private fun boundMusicId(): String? =
        com.android.launcher37.Prefs.of(activity).getString(MUSIC_APP_KEY, null)

    private fun pickMusicApp() {
        MusicAppPicker.pick(activity, "选择音乐应用") { pkgCls ->
            if (pkgCls.contains("/") && pkgCls.substringBefore('/').isNotEmpty()) {
                com.android.launcher37.Prefs.of(activity).edit().putString(MUSIC_APP_KEY, pkgCls).apply()
                toast("已绑定：${pkgCls.substringBefore('/')}")
            } else {
                toast("绑定失败：无效的应用")
            }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun boundPkg(): String? {
        val id = com.android.launcher37.Prefs.of(activity).getString("music_app_pkg", null)
        return id?.substringBefore('/')?.takeIf { it.isNotEmpty() }
    }

    /** 0.5 秒后回到桌面，使刚进入的音乐 app 转入后台 */
    private fun returnToHome() {
        val h = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { activity.startActivity(h) } catch (_: Exception) {}
    }

    // ── 取词与渲染（原 LyricsDelegate 迁移） ─────────────

    private fun onTrackChanged(title: String, artist: String) {
        val key = "$artist|$title"
        if (key == mTrackKey) return
        mTrackKey = key
        resetLyrics()
        if (title.isBlank() || title == "未在播放" || title == "未授权") return
        setPlaceholder("歌词获取中…")
        ivCover?.visibility = View.GONE
        val gen = ++mGen
        SharedExecutor.io().execute {
            val data = Lyrics.loadOrFetch(activity, mProvider, artist, title, 0)
            // 封面：info.cover 下载（cover.jpg 缓存后直接读文件）
            var cover: Bitmap? = null
            val info = data?.songInfo
            if (info != null && info.cover.isNotBlank()) {
                cover = try { SdcardMusicStore.loadCover(info) } catch (_: Exception) { null }
            }
            val finalCover = cover
            activity.runOnUiThread {
                if (gen != mGen || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                applyData(data)
                if (finalCover != null) {
                    ivCover?.setImageBitmap(finalCover)
                    ivCover?.visibility = View.VISIBLE
                } else {
                    ivCover?.visibility = View.GONE
                }
            }
        }
    }

    private fun onProgress(positionMs: Long) {
        if (mLines.isEmpty()) return
        var idx = -1
        for (i in mLines.indices) {
            if (mLines[i].timeMs <= positionMs) idx = i else break
        }
        if (idx != mCurIndex) {
            mCurIndex = idx
            refreshLines()
        }
    }

    /** 构建 lineCount 个歌词行（原文 main + 翻译 sub），行间距 = lineGap */
    private fun buildLineViews() {
        val secondary = activity.resources.getColor(R.color.foreground_secondary, activity.theme)
        val tertiary = activity.resources.getColor(R.color.foreground_tertiary, activity.theme)
        val container = boxLines ?: return
        container.removeAllViews()
        val rows = ArrayList<Row>(lineCount)
        for (i in 0 until lineCount) {
            val main = TextView(activity).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
                typeface = Typeface.DEFAULT
            }
            val sub = TextView(activity).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(tertiary)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize * 0.8f)
                typeface = Typeface.DEFAULT
                visibility = View.GONE
            }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (i == 0) 0 else lineGap
                }
                addView(main)
                addView(sub)
            }
            container.addView(box)
            rows.add(Row(box, main, sub))
        }
        mRows = rows
    }

    private fun resetLyrics() {
        mGen++
        mLines = emptyList()
        mTransLines = emptyList()
        mPlain = null
        mCurIndex = -1
        setPlaceholder("暂无播放")
    }

    /** 无数据占位：全部行清空，仅中间行显示提示文本 */
    private fun setPlaceholder(text: String) {
        val eff = effectiveCount
        for ((i, row) in mRows.withIndex()) {
            if (i >= eff) {
                row.box.visibility = View.GONE
                continue
            }
            row.box.visibility = View.VISIBLE
            row.main.text = if (i == eff / 2) text else ""
            row.main.setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
            row.main.setTypeface(Typeface.DEFAULT)
            row.main.setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
            row.sub.visibility = View.GONE
        }
    }

    private fun applyData(data: LyricsData?) {
        if (data == null || data.isEmpty) {
            mHasTrans = false
            setPlaceholder("暂无歌词")
            return
        }
        mTransLines = if (data.trans != null) parseLrc(data.trans) else emptyList()
        mHasTrans = mTransLines.isNotEmpty()
        val raw = data.syncedLrc
        mLines = if (raw != null) parseLrc(raw) else emptyList()
        mPlain = if (mLines.isEmpty()) (data.plain ?: raw) else null
        mCurIndex = -1
        refreshLines()
    }

    /**
     * 刷新歌词窗口：当前句 = curSize+bold+主色（+翻译副行），其余 = otherSize+副色。
     * 纯文本歌词（mPlain）在中间行整段显示。
     */
    private fun refreshLines() {
        if (mRows.isEmpty()) return
        val primary = activity.resources.getColor(R.color.foreground, activity.theme)
        val secondary = activity.resources.getColor(R.color.foreground_secondary, activity.theme)
        val tertiary = activity.resources.getColor(R.color.foreground_tertiary, activity.theme)
        val eff = effectiveCount
        for ((i, row) in mRows.withIndex()) {
            // 有翻译时超出有效行数的行隐藏（原文/译文各占一行，行数减半）
            if (i >= eff) {
                row.box.visibility = View.GONE
                continue
            }
            row.box.visibility = View.VISIBLE
            // 纯文本模式：仅中间行显示整段
            if (mPlain != null && i == eff / 2) {
                row.main.text = mPlain
                row.main.maxLines = 8
                row.main.setTextColor(secondary)
                row.main.setTypeface(Typeface.DEFAULT)
                row.main.setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
                row.sub.visibility = View.GONE
                continue
            }
            row.main.maxLines = 1
            val lineIdx = mCurIndex - (eff - 1) / 2 + i
            val isCur = lineIdx == mCurIndex
            val text = if (lineIdx in mLines.indices) mLines[lineIdx].text else ""
            row.main.text = text
            if (isCur) {
                row.main.setTextColor(primary)
                row.main.setTypeface(Typeface.DEFAULT_BOLD)
                row.main.setTextSize(TypedValue.COMPLEX_UNIT_PX, curSize.toFloat())
                // 翻译：当前句时间戳最近匹配（≤1s），空文本/"//"不显示
                val trans = if (lineIdx in mLines.indices) transFor(lineIdx) else null
                if (trans.isNullOrBlank()) {
                    row.sub.visibility = View.GONE
                } else {
                    row.sub.text = trans
                    row.sub.setTextColor(tertiary)
                    row.sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, curSize * 0.55f)
                    row.sub.visibility = View.VISIBLE
                }
            } else {
                row.main.setTextColor(secondary)
                row.main.setTypeface(Typeface.DEFAULT)
                row.main.setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
                row.sub.visibility = View.GONE
            }
        }
    }

    /** 翻译对齐：主歌词行时间戳在 trans 里找最近行（差 ≤1s） */
    private fun transFor(lineIdx: Int): String? {
        if (mTransLines.isEmpty() || lineIdx !in mLines.indices) return null
        val t = mLines[lineIdx].timeMs
        var best: LrcLine? = null
        var bestDelta = Long.MAX_VALUE
        for (l in mTransLines) {
            val delta = Math.abs(l.timeMs - t)
            if (delta < bestDelta) {
                bestDelta = delta
                best = l
            }
        }
        val b = best ?: return null
        if (bestDelta > 1000) return null
        val text = b.text.trim()
        if (text.isEmpty() || text == "//") return null
        return text
    }

    companion object {
        private const val MUSIC_APP_KEY = "music_app_pkg"

        // 实例外观属性 config 键
        const val CFG_SHOW_TRACK = "lyrics_show_track"
        const val CFG_SHOW_ARTIST = "lyrics_show_artist"
        const val CFG_SHOW_BAR = "lyrics_show_bar"
        const val CFG_SHOW_LYRICS = "lyrics_show_lines"
        const val CFG_LINES = "lyrics_lines"
        const val CFG_SIZE_CUR = "lyrics_size_cur"
        const val CFG_SIZE_OTHER = "lyrics_size_other"
        const val CFG_GAP = "lyrics_gap"
    }
}
