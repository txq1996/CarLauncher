package com.android.launcher37

/**
 * 版本号解析与比较的纯函数工具，供 [UpdateChecker] 使用。
 *
 * - [parseVersionName] 把 tag_name / release_name 解析为 versionName 字符串
 *   （YYYYMMDD[.HHMMSS]）。
 * - [parseVersionCodeFromBody] 从 release Markdown body 提取整数 versionCode
 *   （构建 epoch 秒数）。失败返回 0L，由调用方决定 fallback。
 * - [compareVersionName] 字典序比较。
 * - [compareVersionCode] 整数比较（构建 epoch 秒数天然单调递增）。
 *
 * 这些函数独立、无状态、依赖最小，如需补充单测可直接 JUnit 覆盖。
 */
object UpdateVersion {

    private val TAG = "UpdateVersion"

    /**
     * 从 release 元数据解析 versionName。
     *
     * 优先：tag_name 去掉 v 前缀（如 v20260829.044047 → 20260829.044047）
     * 回退：当 tag_name 是 "latest"（GitHub 单一 release alias），
     *       从 release `name`（"CarLauncher 20260829.044047"）提取 YYYYMMDD[.HHMMSS]。
     * 最终兜底：tag_name 原串。
     */
    fun parseVersionName(tagName: String, releaseName: String): String {
        val fromTag = tagName.removePrefix("v")
        if (fromTag.isNotEmpty() && fromTag != "latest") return fromTag
        val m = Regex("""(\d{8}(?:\.\d+)?)""").find(releaseName)
        if (m != null) return m.groupValues[1]
        return tagName
    }

    /**
     * 从 release body 解析整数 versionCode。
     *
     * GitHub Actions release.yml 写出的 body 含一行：
     *   - versionCode: `1787996903`
     * 匹配该格式（`` ` `` 反引号可选；`:` 或 `=` 都可；大小写不敏感）。
     * 失败返回 0L。
     */
    fun parseVersionCodeFromBody(body: String?): Long {
        if (body.isNullOrEmpty()) return 0L
        val m = Regex("""versionCode\s*[:=]\s*`?(\d{8,})`?""", RegexOption.IGNORE_CASE).find(body)
            ?: return 0L
        return m.groupValues[1].toLongOrNull() ?: 0L
    }

    /** versionName 字典序比较（YYYYMMDD[.HHMMSS]）：a<b 返回负，相等返回 0，a>b 返回正 */
    fun compareVersionName(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val da = pa.getOrElse(i) { 0 }
            val db = pb.getOrElse(i) { 0 }
            if (da != db) return da - db
        }
        return 0
    }
}
