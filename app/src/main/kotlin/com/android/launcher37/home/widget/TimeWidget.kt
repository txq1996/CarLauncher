package com.android.launcher37.home.widget
import com.android.launcher37.R

import android.app.Activity
import android.util.TypedValue
import android.widget.TextView
import com.android.launcher37.util.MainThread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间 Widget：单行/多行时间文本（原 TimeDelegate 逻辑迁移）。
 *
 * 格式/字号为实例自身 config（设计器"属性"面板独立调整）：
 * - 格式：预设 0=HH:mm / 1=HH:mm:ss / 2=yyyy-MM-dd HH:mm / 3=日期+时间两行，
 *   或自定义 SimpleDateFormat 模板（CFG_CUSTOM 优先）。
 * - 含秒的格式每秒刷新，否则对齐分钟刷新。
 */
class TimeWidget(activity: Activity, spec: WidgetSpec) : WidgetView(activity, spec, R.layout.card_time) {

    override val displayName = "时间"

    private lateinit var tvTime: TextView

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

    override val props: List<WidgetProp> = listOf(
        WidgetProp(CFG_SIZE, "字号", PropType.INT, DEFAULT_SIZE.toString(), min = 10, max = 50),
        WidgetProp(CFG_FORMAT, "格式", PropType.CHOICE, "1", choices = listOf(
            "HH:mm" to "0",
            "HH:mm:ss" to "1",
            "yyyy-MM-dd HH:mm" to "2",
            "日期+时间两行" to "3"
        )),
        WidgetProp(CFG_CUSTOM, "自定义格式", PropType.STRING, "")
    )

    override fun onBind() {
        tvTime = findViewById(R.id.tv_time)
        setCardBackground(true)
    }

    override fun onSpecApplied() {
        // 字号每次重读：设计器缩放/属性调整后保持最新
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, cfgInt(CFG_SIZE, DEFAULT_SIZE).toFloat())
    }

    override fun onPropChanged(key: String, value: String) {
        onSpecApplied()
        updateTime()
        scheduleNext()
    }

    override fun onThemeChange() {
        setCardBackground(true)
        tvTime.setTextColor(activity.resources.getColor(R.color.foreground, activity.theme))
    }

    override fun start() {
        updateTime()
        scheduleNext()
    }

    override fun stop() {
        handler.removeCallbacks(ticker)
    }

    private fun scheduleNext() {
        handler.removeCallbacks(ticker)
        val custom = customPattern()
        val hasSeconds = custom?.contains("s") == true || custom?.contains("S") == true
        val delay = if (hasSeconds) 1000L else {
            if (custom != null) 60000L - System.currentTimeMillis() % 60000L
            else {
                val fmt = cfgInt(CFG_FORMAT, 1)
                if (fmt == 1 || fmt == 3) 1000L else 60000L - System.currentTimeMillis() % 60000L
            }
        }
        handler.postDelayed(ticker, delay)
    }

    private fun updateTime() {
        val now = Date()
        val custom = customPattern()
        val text = if (custom != null) {
            customFormatter(custom).format(now)
        } else {
            when (cfgInt(CFG_FORMAT, 1)) {
                1 -> fmtHms.format(now)
                2 -> fmtDateTime.format(now)
                3 -> fmtDate.format(now) + "\n" + fmtHms.format(now)
                else -> fmtHm.format(now)
            }
        }
        tvTime.text = text
    }

    private fun customPattern(): String? = cfg(CFG_CUSTOM, "").takeIf { it.isNotBlank() }

    private fun customFormatter(pattern: String): SimpleDateFormat {
        if (customPattern != pattern || customFmt == null) {
            customPattern = pattern
            customFmt = SimpleDateFormat(pattern, Locale.getDefault())
        }
        return customFmt!!
    }

    companion object {
        const val CFG_SIZE = "time_size"
        const val CFG_FORMAT = "time_format"
        const val CFG_CUSTOM = "time_custom"
        const val DEFAULT_SIZE = 28
    }
}
