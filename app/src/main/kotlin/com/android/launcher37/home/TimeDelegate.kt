package com.android.launcher37.home

import android.os.Handler
import android.util.TypedValue
import android.view.View
import com.android.launcher37.MainThread
import com.android.launcher37.Prefs
import com.android.launcher37.SettingsActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimeDelegate(
    private val views: HomeViews
) {
    private val handler = MainThread.handler
    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            scheduleNext()
        }
    }

    private val fmtHms = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fmtDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val fmtDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val fmtHm = SimpleDateFormat("HH:mm", Locale.getDefault())
    /** 自定义格式缓存（pattern 变化时重建） */
    private var customPattern: String? = null
    private var customFmt: SimpleDateFormat? = null

    /** 自定义时间格式（非空则优先于预设） */
    private fun customPattern(ctx: android.content.Context): String? =
        Prefs.of(ctx).getString(SettingsActivity.KEY_TIME_FORMAT_CUSTOM, null)?.takeIf { it.isNotBlank() }

    private fun customFormatter(pattern: String): SimpleDateFormat {
        if (customPattern != pattern || customFmt == null) {
            customPattern = pattern
            customFmt = SimpleDateFormat(pattern, Locale.getDefault())
        }
        return customFmt!!
    }

    fun start() {
        applyLayout()
        updateTime()
        scheduleNext()
    }

    fun stop() {
        handler.removeCallbacks(ticker)
    }

    fun applyLayout() {
        val ctx = views.cardTime.context
        val p = Prefs.of(ctx)
        val show = p.getBoolean(SettingsActivity.KEY_SHOW_TIME, false)
        views.cardTime.visibility = if (show) View.VISIBLE else View.GONE
        views.gapTimeSpeed.visibility = if (show) View.VISIBLE else View.GONE
        val h = p.getInt(SettingsActivity.KEY_TIME_CARD_H, 60)
        views.cardTime.layoutParams.height = h
        views.cardTime.requestLayout()
        val ts = p.getInt(SettingsActivity.KEY_TS_TIME, 28)
        views.tvTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts.toFloat())
    }

    private fun scheduleNext() {
        handler.removeCallbacks(ticker)
        val ctx = views.cardTime.context
        val custom = customPattern(ctx)
        val hasSeconds = custom?.contains("s") == true || custom?.contains("S") == true
        val delay = if (hasSeconds) 1000L else {
            if (custom != null) 60000L - System.currentTimeMillis() % 60000L
            else {
                val fmt = Prefs.of(ctx).getInt(SettingsActivity.KEY_TIME_FORMAT, 1)
                if (fmt == 1 || fmt == 3) 1000L else 60000L - System.currentTimeMillis() % 60000L
            }
        }
        handler.postDelayed(ticker, delay)
    }

    private fun updateTime() {
        val ctx = views.cardTime.context
        val now = Date()
        val custom = customPattern(ctx)
        val text = if (custom != null) {
            customFormatter(custom).format(now)
        } else {
            val fmtIdx = Prefs.of(ctx).getInt(SettingsActivity.KEY_TIME_FORMAT, 1)
            when (fmtIdx) {
                1 -> fmtHms.format(now)
                2 -> fmtDateTime.format(now)
                3 -> fmtDate.format(now) + "\n" + fmtHms.format(now)
                else -> fmtHm.format(now)
            }
        }
        views.tvTime.text = text
    }
}
