package com.android.launcher37

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * 归一化图标 LRU 缓存封装。
 *
 * 内部维护一个 16MB 封顶的 [LruCache]，key 为归一化键（见 [IconNormalizer]），
 * value 为 [Drawable]；条目大小按位图字节数估算（[BitmapDrawable.getBitmap] 命中时）。
 */
object IconCache {

    private val NORMALIZED: LruCache<String, Drawable> = object : LruCache<String, Drawable>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Drawable): Int {
            if (value is BitmapDrawable) {
                value.bitmap?.let { return it.byteCount }
            }
            return 1
        }
    }

    /** 清空归一化缓存（日/夜模式切换、Activity 重建时调用） */
    @JvmStatic
    fun clearNormalized() {
        NORMALIZED.evictAll()
    }

    /** 包内访问：归一化缓存 */
    internal fun normalizedCache(): LruCache<String, Drawable> = NORMALIZED
}
