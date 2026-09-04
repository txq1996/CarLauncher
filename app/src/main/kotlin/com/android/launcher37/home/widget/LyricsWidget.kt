package com.android.launcher37.home.widget
import com.android.launcher37.LauncherActivity
import com.android.launcher37.R

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
import com.android.launcher37.util.FormatUtils
import com.android.launcher37.util.Dbg
import com.android.launcher37.music.MediaHelper
import com.android.launcher37.music.MusicLauncher
import com.android.launcher37.util.SharedExecutor
import com.android.launcher37.data.Store
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
 * - 歌曲信息区（歌名/作者/进度）与播放控制：点击唤醒已绑定音乐 app
 *   （未绑定先选择），长按重新绑定；绑定存实例 config（`music_pkg`）
 */
class LyricsWidget(activity: Activity, spec: WidgetSpec) : WidgetView(activity, spec, R.layout.card_lyrics) {

    override val displayName = "音乐"

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
    private val trackSize: Int get() = cfgInt(CFG_SIZE_TRACK, 20)
    private val artistSize: Int get() = cfgInt(CFG_SIZE_ARTIST, 14)
    private val timeSize: Int get() = cfgInt(CFG_SIZE_TIME, 14)
    private val lineGap: Int get() = cfgInt(CFG_GAP, 15)
    private val lineSpacing: Int get() = cfgInt(CFG_LINE_SPACING, 7)

    // getter 惰性求值：launcherChoices() 是主线程 PackageManager 全量枚举，
    // 若在构造期（val 初始化）执行，每次退出设置页 CLEAR_TASK 重启桌面都阻塞首帧；
    // 改为打开属性面板时才构建（与 VdWidget.props 一致）
    override val props: List<WidgetProp>
        get() = listOf(
            WidgetProp(CFG_MUSIC_PKG, "绑定音乐应用", PropType.CHOICE, "", choices = launcherChoices()),
            WidgetProp(CFG_SHOW_TRACK, "显示歌名", PropType.BOOL, "1"),
            WidgetProp(CFG_SHOW_ARTIST, "显示作者", PropType.BOOL, "1"),
            WidgetProp(CFG_SHOW_BAR, "显示进度", PropType.BOOL, "1"),
            WidgetProp(CFG_SHOW_LYRICS, "显示歌词", PropType.BOOL, "1"),
            WidgetProp(CFG_LINES, "歌词行数", PropType.INT, "10", min = 3, max = 15),
            WidgetProp(CFG_SIZE_CUR, "当前句字号", PropType.INT, "20", min = 10, max = 50),
            WidgetProp(CFG_SIZE_OTHER, "其他行字号", PropType.INT, "15", min = 10, max = 50),
            WidgetProp(CFG_SIZE_TRACK, "歌名字号", PropType.INT, "20", min = 10, max = 50),
            WidgetProp(CFG_SIZE_ARTIST, "作者字号", PropType.INT, "14", min = 10, max = 50),
            WidgetProp(CFG_SIZE_TIME, "时间字号", PropType.INT, "14", min = 10, max = 50),
            WidgetProp(CFG_GAP, "内部间距", PropType.INT, "7", min = 0, max = 30),
            WidgetProp(CFG_LINE_SPACING, "歌词行距", PropType.INT, "7", min = 0, max = 30)
        )

    /** 全部可启动应用（label to packageName，字典序）供绑定选择 */
    private fun launcherChoices(): List<Pair<String, String>> = try {
        activity.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { ri ->
            val label = ri.loadLabel(activity.packageManager)?.toString()
                ?: return@mapNotNull null
            label to ri.activityInfo.packageName
        }.sortedBy { it.first }
    } catch (_: Throwable) {
        emptyList()
    }

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

    /** 歌词容器高度缓存：尺寸变化（设计器缩放/属性调整）时重算可容纳行数 */
    private var mBoxH = -1

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
        // 容器尺寸变化（设计器缩放/显隐/字号调整）时重算可容纳行数并刷新窗口
        boxLines?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.height != mBoxH) {
                mBoxH = v.height
                refreshLines()
            }
        }
        // 歌曲信息区：点击 = 启动/唤醒音乐 app（默认 QQ 音乐），播放后自动返回桌面；长按换绑
        findViewById<View>(R.id.box_lyrics_info)?.setOnClickListener {
            musicLauncher.onButton(mediaHelper::togglePlay, boundPkg())
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
        refreshAppBadge()
        applyControlTint()
        buildLineViews()
    }

    /** 左上角角标：显示当前绑定音乐 app 的图标与名称 */
    private fun refreshAppBadge() {
        val pkg = boundPkg()
        findViewById<ImageView>(R.id.iv_lyrics_app_icon)?.setImageDrawable(
            Store.normalizedIcon(activity, pkg)
        )
        findViewById<TextView>(R.id.tv_lyrics_app_name)?.text =
            Store.label(activity, pkg)
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
        tvTrack?.setTextSize(TypedValue.COMPLEX_UNIT_PX, trackSize.toFloat())
        tvArtist?.setTextSize(TypedValue.COMPLEX_UNIT_PX, artistSize.toFloat())
        tvCur?.setTextSize(TypedValue.COMPLEX_UNIT_PX, timeSize.toFloat())
        tvTotal?.setTextSize(TypedValue.COMPLEX_UNIT_PX, timeSize.toFloat())
        applyInnerGap()
    }

    /** 内部间距：统一作用于 歌名/作者/封面/歌词区/时间/进度条/控制按钮 之间的间隔（歌词行间同步） */
    private fun applyInnerGap() {
        val g = lineGap
        fun margin(id: Int, top: Boolean = true) {
            val v = findViewById<View>(id) ?: return
            (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (top) lp.topMargin = g else lp.bottomMargin = g
                v.layoutParams = lp
            }
        }
        margin(R.id.box_lyrics_info)
        margin(R.id.tv_lyrics_artist)
        margin(R.id.iv_lyrics_cover)
        margin(R.id.box_lyrics_lines, top = false)
        margin(R.id.box_lyrics_time)
        margin(R.id.lyrics_progress)
        margin(R.id.box_lyrics_controls)
    }

    override fun onPropChanged(key: String, value: String) {
        onSpecApplied()
        if (key == CFG_MUSIC_PKG) refreshAppBadge()
        buildLineViews()
        refreshLines()
    }

    override fun start() {
        Dbg.i(TAG) { "start" }
        mediaHelper.start()
    }

    override fun stop() {
        Dbg.i(TAG) { "stop" }
        mediaHelper.stop()
    }

    /** 内存清理保护读取（LauncherActivity.playingPkgs） */
    fun mediaHelper(): MediaHelper = mediaHelper

    override fun destroy() {
        Dbg.i(TAG) { "destroy" }
        mediaHelper.stop()
        musicLauncher.cancelPending()
        mGen++
    }

    override fun onThemeChange() {
        setCardBackground(true)
        buildLineViews()
        refreshLines()
        for (id in intArrayOf(R.id.btn_lyrics_prev, R.id.btn_lyrics_play, R.id.btn_lyrics_next)) {
            findViewById<ImageButton>(id)?.setBackgroundResource(R.drawable.bg_icon_btn)
        }
        applyControlTint()
        findViewById<android.widget.ProgressBar>(R.id.lyrics_progress)?.progressDrawable =
            activity.resources.getDrawable(R.drawable.progress_music)
        // 静态文字色重读主题（歌名主色，其余副色/三级色）
        fun text(id: Int, color: Int) {
            findViewById<TextView>(id)?.setTextColor(
                activity.resources.getColor(color, activity.theme)
            )
        }
        text(R.id.tv_lyrics_track, R.color.foreground)
        text(R.id.tv_lyrics_artist, R.color.foreground_tertiary)
        text(R.id.lyrics_cur_time, R.color.foreground_tertiary)
        text(R.id.lyrics_total_time, R.color.foreground_tertiary)
        text(R.id.tv_lyrics_app_name, R.color.foreground_tertiary)
    }

    /** 播放控制按钮图标着色：矢量图 fillColor 只在 inflate 时解析，日夜切换需重设 tint */
    private fun applyControlTint() {
        val tint = android.content.res.ColorStateList.valueOf(
            activity.resources.getColor(R.color.foreground, activity.theme)
        )
        for (id in intArrayOf(R.id.btn_lyrics_prev, R.id.btn_lyrics_play, R.id.btn_lyrics_next)) {
            findViewById<ImageButton>(id)?.imageTintList = tint
        }
    }

    // ── 绑定 / 唤醒（绑定存实例 config；未绑定时默认 QQ 音乐车机版） ──

    private fun boundPkg(): String =
        (spec.config[CFG_MUSIC_PKG] ?: DEFAULT_MUSIC_PKG)
            .substringBefore('/')
            .takeIf { it.isNotEmpty() } ?: DEFAULT_MUSIC_PKG

    private fun pickMusicApp() {
        MusicAppPicker.pick(activity, "选择音乐应用") { pkgCls ->
            if (pkgCls.contains("/") && pkgCls.substringBefore('/').isNotEmpty()) {
                WidgetHost.instance?.updateConfig(spec.id, CFG_MUSIC_PKG, pkgCls)
                toast("已绑定：${pkgCls.substringBefore('/')}")
            } else {
                toast("绑定失败：无效的应用")
            }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()

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
        if (title.isBlank() || title == "未在播放" || title == "未授权") {
            Dbg.d(TAG) { "onTrackChanged skip: '$key'" }
            return
        }
        Dbg.i(TAG) { "onTrackChanged '$key' -> fetch lyrics (gen=${mGen + 1})" }
        setPlaceholder("歌词获取中…")
        // 封面延后加载：未就绪前不显示专辑图片
        ivCover?.visibility = View.GONE
        val gen = ++mGen
        SharedExecutor.io().execute {
            // 1. 歌词优先：取词完成立即上屏
            val data = Lyrics.loadOrFetch(activity, mProvider, artist, title, 0)
            activity.runOnUiThread {
                if (gen != mGen || activity.isDestroyed || activity.isFinishing) {
                    Dbg.d(TAG) { "stale lyrics dropped gen=$gen cur=$mGen" }
                    return@runOnUiThread
                }
                applyData(data)
            }
            // 2. 封面延后：歌词上屏后再下载（cover.jpg 缓存后直接读文件）
            val info = data?.songInfo
            val cover = if (info != null && info.cover.isNotBlank()) {
                try { SdcardMusicStore.loadCover(info) } catch (_: Exception) { null }
            } else null
            activity.runOnUiThread {
                if (gen != mGen || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (cover != null) {
                    ivCover?.setImageBitmap(cover)
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
                // 超宽自动换行（最多 4 行，超出省略）
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
                typeface = Typeface.DEFAULT
            }
            val sub = TextView(activity).apply {
                gravity = Gravity.CENTER
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(tertiary)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
                typeface = Typeface.DEFAULT
                visibility = View.GONE
            }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (i == 0) 0 else lineSpacing
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
        // 停播/换歌时同步隐藏封面（取词成功后由异步任务重新显示）
        ivCover?.visibility = View.GONE
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
            Dbg.i(TAG) { "applyData: no lyrics" }
            setPlaceholder("暂无歌词")
            return
        }
        mTransLines = if (data.trans != null) parseLrc(data.trans) else emptyList()
        mHasTrans = mTransLines.isNotEmpty()
        val raw = data.syncedLrc
        mLines = if (raw != null) parseLrc(raw) else emptyList()
        mPlain = if (mLines.isEmpty()) (data.plain ?: raw) else null
        Dbg.i(TAG) { "applyData: lines=${mLines.size} trans=${mTransLines.size} plain=${mPlain != null} from=${data.songInfo?.singer ?: "-"}" }
        mCurIndex = -1
        refreshLines()
    }

    /**
     * 刷新歌词窗口：当前句 = curSize+bold+主色（+翻译副行），其余 = otherSize+副色。
     * 纯文本歌词（mPlain）在中间行整段显示。
     * 窗口默认显示歌词中段；播放中当前句居中，靠近首尾时贴边（不越界、不出空行）。
     */
    private fun refreshLines() {
        if (mRows.isEmpty()) return
        val primary = activity.resources.getColor(R.color.foreground, activity.theme)
        val secondary = activity.resources.getColor(R.color.foreground_secondary, activity.theme)
        val tertiary = activity.resources.getColor(R.color.foreground_tertiary, activity.theme)
        // 有效行数 = min(配置行数, 容器实际可容纳行数)：高度不够时收缩窗口，保证播放行始终可见且居中
        val fit = fitCount()
        val eff = if (fit > 0) minOf(effectiveCount, fit) else effectiveCount
        // 窗口起始行：未播放 → 歌词中段；播放中 → 当前句居中并 clamp 到有效范围
        val winStart = if (mCurIndex < 0) {
            ((mLines.size - eff) / 2).coerceAtLeast(0)
        } else {
            (mCurIndex - (eff - 1) / 2).coerceIn(0, maxOf(0, mLines.size - eff))
        }
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
            row.main.maxLines = 4
            val lineIdx = winStart + i
            val isCur = lineIdx == mCurIndex
            val text = if (lineIdx in mLines.indices) mLines[lineIdx].text else ""
            row.main.text = text
            // 翻译：每行都显示（该行时间戳最近匹配 ≤1s），字号与该行原文字号一致，空文本/"//"不显示
            val trans = if (lineIdx in mLines.indices) transFor(lineIdx) else null
            if (isCur) {
                row.main.setTextColor(primary)
                row.main.setTypeface(Typeface.DEFAULT_BOLD)
                row.main.setTextSize(TypedValue.COMPLEX_UNIT_PX, curSize.toFloat())
                if (trans.isNullOrBlank()) {
                    row.sub.visibility = View.GONE
                } else {
                    // 当前句译文与原文同字号同颜色
                    row.sub.text = trans
                    row.sub.setTextColor(primary)
                    row.sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, curSize.toFloat())
                    row.sub.visibility = View.VISIBLE
                }
            } else {
                row.main.setTextColor(secondary)
                row.main.setTypeface(Typeface.DEFAULT)
                row.main.setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
                if (trans.isNullOrBlank()) {
                    row.sub.visibility = View.GONE
                } else {
                    row.sub.text = trans
                    row.sub.setTextColor(tertiary)
                    row.sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, otherSize.toFloat())
                    row.sub.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * 容器实际可容纳的歌词行数：按字号估算行高（普通行 + 1 个当前行/译文行），
     * 以"当前行居中"的窗口为前提计算。0 = 容器尚未布局（回退为配置行数）。
     */
    private fun fitCount(): Int {
        val box = boxLines ?: return 0
        val avail = box.height
        if (avail <= 0) return 0
        // includeFontPadding=false 的行高约为字号的 1.35 倍（保守估算，宁少勿裁）
        // 有翻译时每行 = 原文行 + 等字号译文行
        val f = 1.35f
        val transMul = if (mHasTrans) 2 else 1
        val otherH = (otherSize * f).toInt() * transMul
        val curH = (curSize * f).toInt() * transMul
        val rowH = otherH + lineSpacing
        var n = 1
        while (n < effectiveCount && curH + rowH * n <= avail) n++
        return n
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
        private const val TAG = "Lyrics"

        /** 默认绑定的音乐 app：QQ 音乐车机版（属性面板/长按可改） */
        const val DEFAULT_MUSIC_PKG = "com.tencent.qqmusiccar"

        // 实例外观属性 config 键
        const val CFG_MUSIC_PKG = "music_pkg"
        const val CFG_SHOW_TRACK = "lyrics_show_track"
        const val CFG_SHOW_ARTIST = "lyrics_show_artist"
        const val CFG_SHOW_BAR = "lyrics_show_bar"
        const val CFG_SHOW_LYRICS = "lyrics_show_lines"
        const val CFG_LINES = "lyrics_lines"
        const val CFG_SIZE_CUR = "lyrics_size_cur"
        const val CFG_SIZE_OTHER = "lyrics_size_other"
        const val CFG_SIZE_TRACK = "lyrics_size_track"
        const val CFG_SIZE_ARTIST = "lyrics_size_artist"
        const val CFG_SIZE_TIME = "lyrics_size_time"
        const val CFG_GAP = "lyrics_gap"
        const val CFG_LINE_SPACING = "lyrics_line_spacing"
    }
}
