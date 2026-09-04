package com.android.launcher37.home

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 歌词/歌曲信息数据层。
 *
 * 取词顺序（本地优先）：
 * 1. [SdcardMusicStore] /sdcard/CarLauncher/music/<歌手>/<歌名>/ 命中 → 直接用；
 * 2. [VkeysProvider]（QQ音乐搜索+歌词 API）成功 → 持久化到 sdcard；
 * 3. 兜底 [LrclibProvider] → 缓存在 filesDir/lyrics（app 内部存储）。
 *
 * [LyricsProvider] 为歌词源抽象，后续可切换其他 API。
 */
interface LyricsProvider {
    /** 提供方标识 */
    val id: String

    /** 同步取词（IO 线程调用）。syncedLrc=带 [mm:ss.xx] 时间戳的 LRC 文本；plain=纯文本 */
    fun fetch(artist: String, title: String, durationSec: Int): LyricsData?
}

/** 一行带时间戳的歌词 */
data class LrcTimedLine(val timeMs: Long, val text: String)

/** LRC 解析（公开供设计器预览复用） */
object LrcParser {
    /**
     * 解析 LRC：`[mm:ss.xx]` / `[mm:ss.xxx]` 时间标签（一行可含多个标签），
     * 忽略元数据标签（[ar]/[ti]/[offset] 等）与纯时间标签空行；输出按时间排序。
     */
    fun parse(lrc: String): List<LrcTimedLine> {
        val out = ArrayList<LrcTimedLine>()
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
                out.add(LrcTimedLine(min * 60_000 + sec * 1000 + frac, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}

/** QQ 音乐歌曲条目（vkeys 搜索结果），持久化到 info.json */
data class SongInfo(
    val id: Long,
    val mid: String,
    val vid: String,
    val song: String,
    val singer: String,
    val album: String,
    val cover: String,
    val pay: String,
    val quality: String,
    val subtitle: String
)

data class LyricsData(
    val syncedLrc: String?,
    val plain: String?,
    /** 歌词翻译（vkeys trans，可空） */
    val trans: String? = null,
    /** 搜索命中的歌曲信息（vkeys，可空） */
    val songInfo: SongInfo? = null
) {
    val isEmpty: Boolean get() = syncedLrc.isNullOrBlank() && plain.isNullOrBlank()
}

object Lyrics {

    private val mVkeys = VkeysProvider()

    /** filesDir 缓存文件名（lrclib 兜底路径）：歌手_标题.providerId.lrc */
    fun cacheKey(artist: String, title: String, providerId: String): String {
        fun safe(s: String) = s.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(80)
        return "${safe(artist)}_${safe(title)}.$providerId.lrc"
    }

    /** 同步取词全流程（IO 线程调用）：sdcard 本地优先 → vkeys → lrclib 兜底 */
    fun loadOrFetch(ctx: Context, provider: LyricsProvider, artist: String, title: String, durationSec: Int): LyricsData? {
        if (title.isBlank()) return null
        // 1. 本地优先：/sdcard/CarLauncher/music/ 结构化存储
        SdcardMusicStore.load(artist, title)?.let { return it }
        // 2. vkeys QQ音乐（搜索→mid→歌词），成功即持久化歌曲信息+歌词到 sdcard
        try {
            mVkeys.fetch(artist, title, durationSec)?.let { data ->
                if (!data.isEmpty) {
                    SdcardMusicStore.save(artist, title, data)
                    return data
                }
            }
        } catch (e: Exception) {
            Log.w("Lyrics", "vkeys fetch failed", e)
        }
        // 3. 兜底：传入 provider（lrclib），缓存到内部存储
        val key = cacheKey(artist, title, provider.id)
        LyricsCache.load(ctx, key)?.let { return it }
        val data = try {
            provider.fetch(artist, title, durationSec)
        } catch (e: Exception) {
            Log.w("Lyrics", "fetch failed", e)
            null
        } ?: return null
        if (!data.isEmpty) LyricsCache.save(ctx, key, data)
        return data
    }
}

/** vkeys.cn V2 QQ音乐 API：/v2/music/tencent/search/song（搜索）+ /v2/music/tencent/lyric（歌词） */
class VkeysProvider : LyricsProvider {
    override val id = "vkeys"

    companion object {
        private const val BASE = "https://api.vkeys.cn/v2/music/tencent"
        private const val UA = "CarLauncher/1.0"
        private const val TIMEOUT_MS = 8000
    }

    /**
     * 用歌名搜索（word），结果中按歌手名匹配条目，取其 mid/vid/cover 等，
     * 再用 mid 请求歌词。匹配不上歌手时取第一条。
     */
    override fun fetch(artist: String, title: String, durationSec: Int): LyricsData? {
        // ── 搜索 ──
        val root = httpJson("$BASE/search/song?word=${enc(title)}&num=20") ?: return null
        if (root.optInt("code") != 200) return null
        val list = root.optJSONArray("data") ?: return null
        var pick: JSONObject? = null
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (singerMatches(o.optString("singer"), artist)) {
                pick = o
                break
            }
            if (pick == null) pick = o
        }
        pick ?: return null

        // ── 歌词（mid 查询）──
        val mid = pick.optString("mid", "")
        val lrcRoot = if (mid.isNotEmpty()) httpJson("$BASE/lyric?mid=$mid") else null
        val lrcData = lrcRoot?.takeIf { it.optInt("code") == 200 }?.optJSONObject("data")
        val lrc = lrcData?.optString("lrc", "")?.takeIf { it.isNotBlank() }
        val trans = lrcData?.optString("trans", "")?.takeIf { it.isNotBlank() }
        if (lrc == null) return null

        return LyricsData(
            syncedLrc = lrc,
            plain = null,
            trans = trans,
            songInfo = SongInfo(
                id = pick.optLong("id", 0L),
                mid = mid,
                vid = pick.optString("vid", ""),
                song = pick.optString("song", ""),
                singer = pick.optString("singer", ""),
                album = pick.optString("album", ""),
                cover = pick.optString("cover", ""),
                pay = pick.optString("pay", ""),
                quality = pick.optString("quality", ""),
                subtitle = pick.optString("subtitle", "")
            )
        )
    }

    /** 歌手名匹配：忽略大小写与空白，互相包含即命中 */
    private fun singerMatches(singer: String, artist: String): Boolean {
        if (artist.isBlank() || singer.isBlank()) return false
        fun norm(s: String) = s.replace(Regex("\\s+"), "").lowercase()
        val a = norm(artist)
        val s = norm(singer)
        return s.contains(a) || a.contains(s)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpJson(url: String): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", UA)
        conn.inputStream.use { JSONObject(it.bufferedReader().readText()) }
    } catch (e: Exception) {
        null
    }
}

/** lrclib.net 歌词 API（兜底源）。GET /api/get 精确查询，404 回退 /api/search 取首个有词结果 */
class LrclibProvider : LyricsProvider {
    override val id = "lrclib"

    companion object {
        private const val BASE = "https://lrclib.net/api"
        private const val UA = "CarLauncher/1.0 (https://github.com/txq1996/CarLauncher)"
        private const val TIMEOUT_MS = 8000
    }

    override fun fetch(artist: String, title: String, durationSec: Int): LyricsData? {
        if (title.isBlank()) return null
        val exact = getJson("$BASE/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
            if (durationSec > 0) "&duration=$durationSec" else "")
        parse(exact)?.let { return it }
        // 精确查询失败：模糊搜索取第一条有 synced 的结果
        val list = getJsonArray("$BASE/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
        for (i in 0 until list.length()) {
            parse(list.optJSONObject(i) ?: continue)?.let { return it }
        }
        // 只按歌名再搜一次（歌手名不匹配常见于第三方媒体库元数据）
        val list2 = getJsonArray("$BASE/search?track_name=${enc(title)}")
        for (i in 0 until list2.length()) {
            parse(list2.optJSONObject(i) ?: continue)?.let { return it }
        }
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", UA)
        conn.inputStream.use { JSONObject(it.bufferedReader().readText()) }
    } catch (e: Exception) {
        null
    }

    private fun getJsonArray(url: String): JSONArray = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", UA)
        conn.inputStream.use { JSONArray(it.bufferedReader().readText()) }
    } catch (e: Exception) {
        JSONArray()
    }

    private fun parse(o: JSONObject?): LyricsData? {
        o ?: return null
        val synced = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
        val plain = o.optString("plainLyrics", "").takeIf { it.isNotBlank() }
        if (synced == null && plain == null) return null
        return LyricsData(synced, plain)
    }
}

/**
 * 歌词/歌曲信息持久化：/sdcard/CarLauncher/music/<歌手>/<歌名>/ 目录结构
 *   info.json       搜索命中的歌曲条目（mid/vid/cover/album/pay/quality...全字段）
 *   lyric.lrc       原文歌词（LRC）
 *   lyric.trans.lrc 翻译歌词（存在时）
 */
object SdcardMusicStore {
    private const val TAG = "LyricsStore"

    private fun root(): File = File(Environment.getExternalStorageDirectory(), "CarLauncher/music")

    private fun dirFor(singer: String, song: String): File {
        fun safe(s: String) = s.trim().replace(Regex("[\\\\/:*?\"<>|]+"), "_").take(60).ifEmpty { "_" }
        return File(root(), "${safe(singer)}/${safe(song)}")
    }

    /** 本地命中：读 lyric.lrc（+翻译 lyric.trans.lrc +info.json）。任何 IO 失败视为未命中 */
    fun load(singer: String, song: String): LyricsData? = try {
        val d = dirFor(singer, song)
        val lrcFile = File(d, "lyric.lrc")
        if (!lrcFile.isFile || lrcFile.length() == 0L) null
        else {
            val info = File(d, "info.json").takeIf { it.isFile }?.let { f ->
                try { songInfoFromJson(JSONObject(f.readText())) } catch (_: Exception) { null }
            }
            val trans = File(d, "lyric.trans.lrc").takeIf { it.isFile && it.length() > 0L }?.readText()
            LyricsData(syncedLrc = lrcFile.readText(), plain = null, trans = trans, songInfo = info)
        }
    } catch (e: Exception) {
        Log.w(TAG, "sdcard load failed", e)
        null
    }

    /** 持久化歌曲信息 + 歌词（+翻译）。存储不可用静默跳过（不影响本次显示） */
    fun save(singer: String, song: String, data: LyricsData) {
        try {
            val info = data.songInfo ?: return
            val d = dirFor(info.singer.ifBlank { singer }, info.song.ifBlank { song })
            d.mkdirs()
            File(d, "info.json").writeText(songInfoToJson(info).toString(2))
            data.syncedLrc?.let { File(d, "lyric.lrc").writeText(it) }
            data.trans?.let { File(d, "lyric.trans.lrc").writeText(it) }
        } catch (e: Exception) {
            Log.w(TAG, "sdcard save failed", e)
        }
    }

    private fun songInfoToJson(o: SongInfo) = JSONObject().apply {
        put("id", o.id)
        put("mid", o.mid)
        put("vid", o.vid)
        put("song", o.song)
        put("singer", o.singer)
        put("album", o.album)
        put("cover", o.cover)
        put("pay", o.pay)
        put("quality", o.quality)
        put("subtitle", o.subtitle)
    }

    /**
     * 专辑封面：优先读同目录 cover.jpg 缓存；未命中按 info.cover URL 下载并落盘。
     * 失败返回 null（UI 层隐藏封面）。IO 线程调用。
     */
    fun loadCover(info: SongInfo): android.graphics.Bitmap? {
        val d = dirFor(info.singer, info.song)
        val f = File(d, "cover.jpg")
        if (f.isFile && f.length() > 0L) {
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
        }
        if (info.cover.isBlank()) return null
        val bmp = try {
            val conn = URL(info.cover).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.w(TAG, "cover download failed", e)
            null
        } ?: return null
        try {
            d.mkdirs()
            java.io.FileOutputStream(f).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (_: Exception) {
        }
        return bmp
    }

    private fun songInfoFromJson(o: JSONObject) = SongInfo(
        id = o.optLong("id", 0L),
        mid = o.optString("mid"),
        vid = o.optString("vid"),
        song = o.optString("song"),
        singer = o.optString("singer"),
        album = o.optString("album"),
        cover = o.optString("cover"),
        pay = o.optString("pay"),
        quality = o.optString("quality"),
        subtitle = o.optString("subtitle")
    )
}

/** 歌词缓存（lrclib 兜底路径）：filesDir/lyrics/，文件内容 = synced 原文或 plain */
object LyricsCache {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "lyrics").apply { mkdirs() }

    fun load(ctx: Context, key: String): LyricsData? = try {
        val f = File(dir(ctx), key)
        if (!f.isFile || f.length() == 0L) null
        else {
            val text = f.readText()
            if (text.isBlank()) null else LyricsData(syncedLrc = text, plain = null)
        }
    } catch (e: Exception) {
        null
    }

    fun save(ctx: Context, key: String, data: LyricsData) {
        try {
            val text = data.syncedLrc ?: data.plain ?: return
            val tmp = File(dir(ctx), "$key.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir(ctx), key))
        } catch (e: Exception) {
            // 静默：缓存失败不影响本次显示
        }
    }
}
