package com.android.launcher37

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 不依赖 androidx 的最小 FileProvider。
 *
 * 用途：把 `cacheDir/update.apk` 暴露为 `content://com.android.launcher37.updater/update_apk/update.apk`，
 * 配合 `Intent.FLAG_GRANT_READ_URI_PERMISSION` 供系统 package installer 读取。
 *
 * 路径映射见 `res/xml/file_paths.xml`：
 *   <cache-path name="update_apk" path="update.apk" />
 */
class UpdateFileProvider : ContentProvider() {

    private val cacheRoot: File
        get() = requireNotNull(context).cacheDir

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolveFile(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = MatrixCursor(cols)
        val values = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> values[i] = file.name
                OpenableColumns.SIZE -> values[i] = file.length()
            }
        }
        row.addRow(values)
        return row
    }

    override fun getType(uri: Uri): String? {
        val name = resolveFile(uri)?.name ?: return null
        return when {
            name.endsWith(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolveFile(uri) ?: throw FileNotFoundException("not found: $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val pfd = openFile(uri, mode) ?: return null
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * 解析 `content://AUTHORITY/update_apk/<path>` → `cacheDir/<path>`。
     * 只允许 cache 根目录下文件（防越权）。
     */
    private fun resolveFile(uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val root = segments[0]  // "update_apk"
        if (root != "update_apk") return null
        val rel = segments.drop(1).joinToString("/")
        val file = File(cacheRoot, rel)
        // 限定 cacheDir 之内：防止 ".." 越权
        if (!file.canonicalPath.startsWith(cacheRoot.canonicalPath + File.separator) &&
            file.canonicalPath != cacheRoot.canonicalPath
        ) return null
        return if (file.exists() && file.isFile) file else null
    }
}
