package com.android.launcher37.home

import android.graphics.Bitmap
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher37.R
import com.android.launcher37.SharedExecutor

/**
 * 歌词模块委派：右栏歌词卡（封面 + 双语歌词行 + 底部播放控制按钮）。
 *
 * 数据回调由 [MusicDelegate] 转发（onTrackChanged/onPlayingStateChanged/onProgress）；
 * 播放控制经 [Controls] 走音乐卡同一条 MediaHelper 链路。
 * 取词走 [Lyrics]（sdcard 本地优先 → vkeys QQ音乐 → lrclib 兜底），
 * 封面按 info.cover 下载并缓存 cover.jpg（同目录）。
 * 行数/字号/间距来自构造参数（LauncherActivity 读 snapshot，设置页重启桌面生效）；
 * 日/夜切换时经 [rebuildForThemeChange] 重建行取新色。
 */
class LyricsDelegate(
    private val activity: android.app.Activity,
    private val views: HomeViews,
    private val lineCount: Int,
    private val curSize: Int,
    private val otherSize: Int,
    private val lineGap: Int,
    private val controls: Controls
) {
    /** 播放控制（实现=MusicDelegate.onMusicButton 包装） */
    interface Controls {
        fun prev()
        fun togglePlay()
        fun next()
    }

    private data class LrcLine(val timeMs: Long, val text: String)

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

    init {
        buildLineViews()
    }

    fun bindListeners() {
        views.btnLyricsPrev.setOnClickListener { controls.prev() }
        views.btnLyricsPlay.setOnClickListener { controls.togglePlay() }
        views.btnLyricsNext.setOnClickListener { controls.next() }
    }

    fun stop() {
        mGen++
    }

    /** 日/夜主题切换后重建行（重取主题色）与封面可见性；已有歌词与进度索引保留 */
    fun rebuildForThemeChange() {
        buildLineViews()
        refreshLines()
    }

    // ── MediaHelper 回调转发入口 ──────────────────────

    fun onTrackChanged(title: String, artist: String) {
        val key = "$artist|$title"
        if (key == mTrackKey) return
        mTrackKey = key
        resetLyrics()
        if (title.isBlank() || title == "未在播放" || title == "未授权") return
        setPlaceholder("歌词获取中…")
        views.ivLyricsCover.visibility = View.GONE
        val gen = ++mGen
        SharedExecutor.io().execute {
            val data = Lyrics.loadOrFetch(activity, mProvider, artist, title, 0)
            // 封面：info.cover 下载（cover.jpg 缓存后直接读文件）
            var cover: Bitmap? = null
            val info = data?.songInfo
            if (info != null && info.cover.isNotBlank()) {
                cover = try {
                    SdcardMusicStore.loadCover(info)
                } catch (_: Exception) {
                    null
                }
            }
            val finalCover = cover
            activity.runOnUiThread {
                if (gen != mGen || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                applyData(data)
                if (finalCover != null) {
                    views.ivLyricsCover.setImageBitmap(finalCover)
                    views.ivLyricsCover.visibility = View.VISIBLE
                } else {
                    views.ivLyricsCover.visibility = View.GONE
                }
            }
        }
    }

    fun onPlayingStateChanged(playing: Boolean) {
        views.btnLyricsPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    fun onProgress(positionMs: Long, durationMs: Long) {
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

    // ── 内部 ─────────────────────────────────────────

    /** 构建 lineCount 个歌词行（原文 main + 翻译 sub），行间距 = lineGap */
    private fun buildLineViews() {
        val secondary = activity.resources.getColor(R.color.foreground_secondary, activity.theme)
        val tertiary = activity.resources.getColor(R.color.foreground_tertiary, activity.theme)
        val container = views.boxLyricsLines
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

    /**
     * 解析 LRC：`[mm:ss.xx]` / `[mm:ss.xxx]` 时间标签（一行可含多个标签），
     * 忽略元数据标签（[ar]/[ti]/[offset] 等）与纯时间标签空行；输出按时间排序。
     */
    private fun parseLrc(lrc: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        for (rawLine in lrc.lineSequence()) {
            val matches = tag.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val text = rawLine.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                out.add(LrcLine(min * 60_000 + sec * 1000 + frac, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
