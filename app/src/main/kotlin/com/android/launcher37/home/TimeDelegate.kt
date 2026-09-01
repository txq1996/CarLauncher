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
        val fmt = Prefs.of(views.cardTime.context).getInt(SettingsActivity.KEY_TIME_FORMAT, 1)
        val delay = if (fmt == 1 || fmt == 3) 1000L else 60000L - System.currentTimeMillis() % 60000L
        handler.postDelayed(ticker, delay)
    }

    private fun updateTime() {
        val ctx = views.cardTime.context
        val fmtIdx = Prefs.of(ctx).getInt(SettingsActivity.KEY_TIME_FORMAT, 1)
        val now = Date()
        val text = when (fmtIdx) {
            1 -> fmtHms.format(now)
            2 -> fmtDateTime.format(now)
            3 -> fmtDate.format(now) + "\n" + fmtHms.format(now)
            else -> fmtHm.format(now)
        }
        views.tvTime.text = text
    }
}
