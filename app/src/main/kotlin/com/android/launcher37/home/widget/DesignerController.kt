package com.android.launcher37.home.widget
import com.android.launcher37.R

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 主页设计器：主页即画布，工具栏占据原状态栏区域（Activity 隐藏状态栏后显示）。
 *
 * 交互（设计模式下 Widget 内部点击全部被拦截，见 [WidgetView.onInterceptTouchEvent]）：
 * - 点击 Widget = 选中；拖动 = 移动；边框/角点手柄 = 调整大小
 * - 最小 20×20，移动/缩放不超出屏幕（WidgetHost.updateRect 统一 clamp）
 * - 工具栏：添加 / 删除 / 显隐 / 属性（按 Widget 自身属性 schema 渲染面板）/ 预览
 *
 * 全部修改实时应用到主页（真实 Widget 即预览），"预览"保存布局并退出设计模式。
 */
class DesignerController(
    private val activity: Activity,
    private val host: WidgetHost,
    private val container: FrameLayout
) {
    companion object {
        private const val HANDLE_PX = 28

        /** 新添加 VD 的默认绑定应用：高德地图车机版 */
        private const val DEFAULT_VD_PKG = "com.autonavi.amapauto"

        // 缩放手柄方位标志
        private const val H_R = 4
        private const val H_B = 8
    }

    /** 退出设计模式回调（Activity 恢复状态栏/隐藏工具栏） */
    var onExit: (() -> Unit)? = null

    /** 选中框 + 缩放手柄（container 最顶层；空白区域事件穿透） */
    val selectionOverlay: FrameLayout = FrameLayout(activity).apply {
        setBackgroundResource(R.drawable.design_sel_stroke)
        visibility = View.GONE
    }

    /** 选中框顶部的尺寸/位置标签：X/Y/W/H 实时显示 */
    private val sizeLabel = TextView(activity).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0x99000000.toInt())
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f)
        setPadding(8, 2, 8, 2)
        gravity = Gravity.CENTER
    }
    private var mSelected: Int = -1
    private var mLastRawX = 0f
    private var mLastRawY = 0f

    init {
        container.addView(selectionOverlay, FrameLayout.LayoutParams(0, 0))
        // 尺寸/位置标签：框内顶部居中
        selectionOverlay.addView(sizeLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 4 })
        // 仅右/下缩放手柄：左上角定位固定（只允许向右/向下调整大小）
        addHandle(Gravity.CENTER_VERTICAL or Gravity.END, H_R, -4f, 0f)
        addHandle(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, H_B, 0f, -4f)
    }

    /** 设计器拆除（退出设计模式）：移除选中框 */
    internal fun detach() {
        container.removeView(selectionOverlay)
        mSelected = -1
    }

    // ── 选择 / 拖动 / 缩放 ───────────────────────────

    /** Widget 容器触摸（WidgetView.designMode 拦截后转发至此）：DOWN 选中，MOVE 拖动。
     *  控件间不允许重叠（含布局间距）：合法状态下撞墙分轴滑动；
     *  起始已重叠（历史布局）时允许自由拖动以便解脱。 */
    fun onWidgetTouch(v: View, ev: MotionEvent): Boolean {
        val w = v as? WidgetView ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                select(w.spec.id)
                mLastRawX = ev.rawX; mLastRawY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - mLastRawX).roundToInt()
                val dy = (ev.rawY - mLastRawY).roundToInt()
                mLastRawX = ev.rawX; mLastRawY = ev.rawY
                if (dx == 0 && dy == 0) return true
                val nx = w.spec.x + dx; val ny = w.spec.y + dy
                // 移动：全向碰撞检测；被挡时按主方向单轴滑动（滑墙）；
                // 仅真实压住其他控件（历史布局）允许自由拖动解脱
                val legal = !host.overlaps(w.spec.id, w.spec.x, w.spec.y, w.spec.w, w.spec.h)
                if (!legal) {
                    host.updateRect(w.spec.id, nx, ny, w.spec.w, w.spec.h)
                } else {
                    // 自动吸附：接近其他控件边缘/中心或画布边距线/中线时对齐
                    val (snx, sny) = snapPosition(nx, ny, w.spec.w, w.spec.h, w.spec.id)
                    if (host.collides(w.spec.id, snx, sny, w.spec.w, w.spec.h)) {
                        when {
                            !host.collides(w.spec.id, snx, w.spec.y, w.spec.w, w.spec.h) ->
                                host.updateRect(w.spec.id, snx, w.spec.y, w.spec.w, w.spec.h)
                            !host.collides(w.spec.id, w.spec.x, sny, w.spec.w, w.spec.h) ->
                                host.updateRect(w.spec.id, w.spec.x, sny, w.spec.w, w.spec.h)
                        }
                    } else {
                        host.updateRect(w.spec.id, snx, sny, w.spec.w, w.spec.h)
                    }
                }
                layoutSelection()
            }
        }
        return true
    }

    private fun select(id: Int) {
        mSelected = id
        layoutSelection()
        // 新添加的 Widget 会加到 container 末尾盖住选中框，选中时把框提到最顶层
        selectionOverlay.bringToFront()
        selectionOverlay.visibility = View.VISIBLE
    }

    private fun layoutSelection() {
        val w = host.widgetAt(mSelected) ?: run {
            selectionOverlay.visibility = View.GONE
            return
        }
        val lp = selectionOverlay.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = w.spec.x; lp.topMargin = w.spec.y
        lp.width = w.spec.w; lp.height = w.spec.h
        selectionOverlay.layoutParams = lp
        // 实时显示控件位置与尺寸
        sizeLabel.text = "X:${w.spec.x} Y:${w.spec.y}  ${w.spec.w}×${w.spec.h}"
    }

    /** 自动吸附：当前控件左/右/中心边在阈值内接近参考线时对齐。
     *  参考线：画布边距线/中线 + 其他控件边缘（含间距外扩，吸附后正好满足碰撞间距） */
    private fun snapPosition(nx: Int, ny: Int, w: Int, h: Int, id: Int): Pair<Int, Int> {
        val T = 10
        val sw = host.screenW(); val sh = host.screenH()
        val m = host.margin; val g = host.gap
        val xLines = ArrayList<Int>().apply { add(m); add(sw / 2); add(sw - m) }
        val yLines = ArrayList<Int>().apply { add(m); add(sh / 2); add(sh - m) }
        for (o in host.allWidgets()) {
            if (o.spec.id == id) continue
            xLines.add(o.spec.x - g); xLines.add(o.spec.x)
            xLines.add(o.spec.x + o.spec.w); xLines.add(o.spec.x + o.spec.w + g)
            yLines.add(o.spec.y - g); yLines.add(o.spec.y)
            yLines.add(o.spec.y + o.spec.h); yLines.add(o.spec.y + o.spec.h + g)
        }
        fun snap1(v: Int, size: Int, lines: List<Int>): Int {
            var best = v; var bestD = T + 1
            for (L in lines) {
                var d = kotlin.math.abs(v - L)                       // 左/上边对齐
                if (d <= T && d < bestD) { bestD = d; best = L }
                d = kotlin.math.abs(v + size - L)                    // 右/下边对齐
                if (d <= T && d < bestD) { bestD = d; best = L - size }
                d = kotlin.math.abs(v + size / 2 - L)                // 中心对齐
                if (d <= T && d < bestD) { bestD = d; best = L - size / 2 }
            }
            return best
        }
        return snap1(nx, w, xLines) to snap1(ny, h, yLines)
    }

    private fun addHandle(gravity: Int, flags: Int, dx: Float, dy: Float) {
        val v = View(activity)
        v.setBackgroundResource(R.drawable.design_handle)
        v.setOnTouchListener(resizeListener(flags))
        selectionOverlay.addView(v, FrameLayout.LayoutParams(HANDLE_PX, HANDLE_PX, gravity))
        v.translationX = dx
        v.translationY = dy
    }

    private fun resizeListener(flags: Int) = View.OnTouchListener { _, ev ->
        val w = host.widgetAt(mSelected) ?: return@OnTouchListener false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mLastRawX = ev.rawX; mLastRawY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - mLastRawX).roundToInt()
                val dy = (ev.rawY - mLastRawY).roundToInt()
                mLastRawX = ev.rawX; mLastRawY = ev.rawY
                if (dx == 0 && dy == 0) return@OnTouchListener true
                // 左上角定位固定：仅右/下方向调整宽高，x/y 恒定不变；
                // 宽高上限为右/下边距线——贴边后继续拖动不再变化（不会从对侧反向变大）
                var width = w.spec.w; var height = w.spec.h
                val minSize = 40
                val m = host.margin
                val maxW = (host.screenW() - m - w.spec.x).coerceAtLeast(minSize)
                val maxH = (host.screenH() - m - w.spec.y).coerceAtLeast(minSize)
                if (flags and H_R != 0) width = (width + dx).coerceIn(minSize, maxW)
                if (flags and H_B != 0) height = (height + dy).coerceIn(minSize, maxH)
                // 缩放碰撞按手柄轴向单轴检测：水平手柄仅查水平碰撞、垂直手柄仅查垂直碰撞；
                // 缩小是原矩形子集不会新增碰撞，始终放行；仅真实压住时允许自由缩放解脱
                val legal = !host.overlaps(w.spec.id, w.spec.x, w.spec.y, w.spec.w, w.spec.h)
                val shrink = width <= w.spec.w && height <= w.spec.h
                val blocked = if (flags and H_R != 0) {
                    host.collidesH(w.spec.id, w.spec.x, w.spec.y, width, height)
                } else {
                    host.collidesV(w.spec.id, w.spec.x, w.spec.y, width, height)
                }
                if (!legal || shrink || !blocked) {
                    host.updateRect(w.spec.id, w.spec.x, w.spec.y, width, height)
                }
                layoutSelection()
            }
        }
        true
    }

    // ── 工具栏 ───────────────────────────────────────

    internal fun showAddDialog() {
        val catalog = WidgetTypes.CATALOG
        AlertDialog.Builder(activity)
            .setTitle("添加部件（同类型可添加多个）")
            .setItems(catalog.map { it.first }.toTypedArray()) { _, which ->
                val type = catalog[which].second
                if (type == WidgetTypes.VD) {
                    // 默认绑定高德车机版（属性面板可改）；高德已被同页 VD 占用时弹选择器
                    val widget = host.addWidget(WidgetTypes.VD, DEFAULT_VD_PKG)
                    if (widget != null) select(widget.spec.id)
                    else showVdAppPicker { pkg ->
                        val w = host.addWidget(WidgetTypes.VD, pkg)
                        if (w != null) select(w.spec.id)
                    }
                } else {
                    val widget = host.addWidget(type, null)
                    if (widget != null) select(widget.spec.id)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    internal fun deleteSelected() {
        if (mSelected < 0) return
        if (host.removeWidget(mSelected)) {
            mSelected = -1
            selectionOverlay.visibility = View.GONE
        }
    }

    /** 属性：按选中 Widget 的属性 schema 渲染可编辑面板（每个实例独立，存自身 config） */
    internal fun showPropsDialog() {
        val w = host.widgetAt(mSelected) ?: run {
            toast("请先点击选择一个部件")
            return
        }
        val defs = w.props
        if (defs.isEmpty()) {
            toast("选中的部件暂无可配置项")
            return
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        var dialog: AlertDialog? = null
        // 标题栏：标题 + 保存（始终显示，不随内容滚动）
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(4))
            addView(TextView(activity).apply {
                text = "属性 - ${w.displayName}"
                setTextColor(activity.resources.getColor(R.color.foreground, activity.theme))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(activity).apply {
                text = "保存"
                setOnClickListener { dialog?.dismiss() }
            })
        })
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(8))
        }
        for (def in defs) box.addView(buildPropRow(w, def))
        val scroll = ScrollView(activity).apply {
            addView(box, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        // 高度自适应：先测内容高度，内容少时收缩到内容高度；默认宽 55% 屏宽、封顶 80% 屏高
        val dm = activity.resources.displayMetrics
        val dialogW = (dm.widthPixels * 0.55f).toInt()
        box.measure(
            View.MeasureSpec.makeMeasureSpec(dialogW - dp(32), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val contentH = box.measuredHeight
        val maxH = (dm.heightPixels * 0.8f).toInt()
        scroll.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            minOf(contentH, maxH)
        )
        root.addView(scroll)
        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .create()
        dialog.show()
        dialog.window?.setLayout(dialogW, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 属性行：左侧名称 + 右侧控件（SHOW_SIZE=开关+字号滑条 / BOOL=开关 / INT=滑条 / CHOICE=选择 / STRING=输入 / ORDER=行序） */
    private fun buildPropRow(w: WidgetView, def: WidgetProp): View {
        val label = TextView(activity).apply {
            text = def.label
            setTextColor(activity.resources.getColor(R.color.foreground, activity.theme))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f)
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            background = activity.resources.getDrawable(R.drawable.bg_app_order_row)
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            addView(label)
        }
        when (def.type) {
            PropType.SHOW_SIZE -> {
                // 显隐开关 + 字号滑条同行（key=显隐键，pairKey=字号键）
                val cb = CheckBox(activity).apply {
                    isChecked = w.cfgBool(def.key, true)
                    setOnCheckedChangeListener { _, checked ->
                        host.updateConfig(w.spec.id, def.key, if (checked) "1" else "0")
                    }
                }
                val sizeDef = def.default.toIntOrNull() ?: def.min
                val value = TextView(activity).apply {
                    text = "${w.cfgInt(def.pairKey, sizeDef)}"
                    setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f)
                    minWidth = dp(40)
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                }
                val bar = SeekBar(activity).apply {
                    max = (def.max - def.min) / def.step
                    progress = (w.cfgInt(def.pairKey, sizeDef) - def.min) / def.step
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(6)
                    }
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val v = def.min + progress * def.step
                            value.text = "$v"
                            host.updateConfig(w.spec.id, def.pairKey, v.toString())
                        }
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {}
                    })
                }
                row.addView(cb)
                row.addView(value)
                row.addView(bar)
            }
            PropType.BOOL -> {
                val cb = CheckBox(activity)
                cb.isChecked = w.cfgBool(def.key, def.default == "1")
                cb.setOnCheckedChangeListener { _, checked ->
                    host.updateConfig(w.spec.id, def.key, if (checked) "1" else "0")
                }
                row.addView(cb)
            }
            PropType.INT -> {
                val cur = w.cfgInt(def.key, def.default.toIntOrNull() ?: def.min).coerceIn(def.min, def.max)
                val value = TextView(activity).apply {
                    text = "$cur"
                    setTextColor(activity.resources.getColor(R.color.foreground_secondary, activity.theme))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f)
                    minWidth = dp(40)
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                }
                val bar = SeekBar(activity).apply {
                    max = (def.max - def.min) / def.step
                    progress = (cur - def.min) / def.step
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(6)
                    }
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val v = def.min + progress * def.step
                            value.text = "$v"
                            host.updateConfig(w.spec.id, def.key, v.toString())
                        }
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {}
                    })
                }
                row.addView(value)
                row.addView(bar)
            }
            PropType.CHOICE -> {
                // VD 绑定 App：过滤同页已被其他 VD 使用的 App（保留当前已选）
                val choices = if (def.key == CFG_VD_PKG) {
                    val curPkg = w.cfg(def.key, def.default)
                    val taken = host.vdBoundedPkgs() - curPkg
                    def.choices.filter { it.second !in taken }
                } else def.choices
                val cur = w.cfg(def.key, def.default)
                val currentLabel = choices.firstOrNull { it.second == cur }?.first ?: "（未选择）"
                val tv = TextView(activity).apply {
                    text = currentLabel
                    setTextColor(activity.resources.getColor(R.color.foreground, activity.theme))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    setBackgroundResource(R.drawable.design_handle)
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        AlertDialog.Builder(activity)
                            .setTitle(def.label)
                            .setItems(choices.map { it.first }.toTypedArray()) { _, which ->
                                val value = choices[which].second
                                host.updateConfig(w.spec.id, def.key, value)
                                text = choices[which].first
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                row.addView(tv)
            }
            PropType.STRING -> {
                val et = EditText(activity).apply {
                    setText(w.cfg(def.key, def.default))
                    setSingleLine(true)
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                    setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) host.updateConfig(w.spec.id, def.key, text.toString().trim())
                    }
                }
                row.addView(et)
            }
            PropType.ORDER -> {
                val cur = w.cfg(def.key, def.default)
                val ordered = cur.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val summary = ordered.joinToString("、") { k ->
                    def.choices.firstOrNull { it.second == k }?.first ?: k
                }
                val tv = TextView(activity).apply {
                    text = "行序：$summary"
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(activity.resources.getColor(R.color.foreground, activity.theme))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    setBackgroundResource(R.drawable.design_handle)
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { showOrderDialog(w, def) }
                }
                row.addView(tv)
            }
        }
        return row
    }

    /** 行序拖动对话框：RecyclerView + ItemTouchHelper 长按拖动，松手时写回 config */
    private fun showOrderDialog(w: WidgetView, def: WidgetProp) {
        val current = w.cfg(def.key, def.default).split(",").map { it.trim() }.filter { it.isNotBlank() }
        val ordered = ArrayList<String>()
        for (k in current) if (def.choices.any { it.second == k }) ordered.add(k)
        for ((_, k) in def.choices) if (k !in ordered) ordered.add(k)

        val rv = androidx.recyclerview.widget.RecyclerView(activity).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val adapter = OrderAdapter(def.choices, ordered)
        rv.adapter = adapter

        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
                override fun isLongPressDragEnabled() = true
                override fun getMovementFlags(recyclerView: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder) =
                    makeMovementFlags(androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0)
                override fun onMove(rv: androidx.recyclerview.widget.RecyclerView, from: androidx.recyclerview.widget.RecyclerView.ViewHolder, to: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
                    val fromPos = from.bindingAdapterPosition
                    val toPos = to.bindingAdapterPosition
                    if (fromPos < 0 || toPos < 0 || fromPos == toPos) return false
                    adapter.move(fromPos, toPos)
                    return true
                }
                override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
                override fun clearView(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    // 松手时一次性写回（避免拖动中反复重建面板）
                    host.updateConfig(w.spec.id, def.key, adapter.ordered().joinToString(","))
                }
            }
        )
        touchHelper.attachToRecyclerView(rv)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("${def.label}（长按 ☰ 拖动排序）")
            .setView(rv)
            .setPositiveButton("完成", null)
            .create()
        dialog.show()
        // 屏高 720px：560×640px 避免溢出（原 dp(560)×dp(600)=840×900 超出屏幕）
        dialog.window?.setLayout(560, 640)
    }

    /** 行序拖动适配器：维护有序 key 列表并渲染标签 */
    private class OrderAdapter(
        private val choices: List<Pair<String, String>>,
        private val keys: ArrayList<String>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<OrderAdapter.VH>() {

        class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.tv_order_label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_order_row, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.label.text = choices.firstOrNull { it.second == keys[position] }?.first ?: keys[position]
        }

        override fun getItemCount(): Int = keys.size

        fun move(from: Int, to: Int) {
            val item = keys.removeAt(from)
            keys.add(to, item)
            notifyItemMoved(from, to)
        }

        fun ordered(): List<String> = keys
    }

    // ── VD 承载 App 选择 ─────────────────────────────

    /** 全部可启动应用（label to packageName，字典序） */
    private fun launcherApps(): List<Pair<String, String>> = try {
        activity.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { ri ->
            val label = ri.loadLabel(activity.packageManager)?.toString() ?: return@mapNotNull null
            label to ri.activityInfo.packageName
        }.distinctBy { it.second }.sortedBy { it.first }
    } catch (_: Throwable) {
        emptyList()
    }

    /** VD 添加：必须选择一个承载 App（取消则不添加）；同页已被其他 VD 使用的 App 不展示 */
    private fun showVdAppPicker(onPicked: (String) -> Unit) {
        val taken = host.vdBoundedPkgs()
        val apps = launcherApps().filter { it.second !in taken }
        if (apps.isEmpty()) {
            toast("当前页的所有应用均已被其他 VD 使用")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("选择 VD 承载的 App")
            .setItems(apps.map { it.first }.toTypedArray()) { _, which ->
                onPicked(apps[which].second)
            }
            .setNegativeButton("取消") { _, _ ->
                toast("未选择 App，未添加部件")
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
