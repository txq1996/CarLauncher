package com.android.launcher37

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * WinForms 风格 `[-] [value] [+]` 数值编辑器。
 *
 * 三个控件等高等宽，视觉一致：
 * - `[-]` / `[+]`：点击按 [setRange] step 步进
 * - 中间 [EditText]：点击直接唤起输入法（`selectAll + showSoftInput`），
 *   失焦或按回车时通过 [commitInput] 提交并夹紧到 `[min, max]`
 *
 * 值变化来源（步进按钮 / 失焦提交 / 输入法回车）都会回调 [OnValueChangeListener]，
 * 使设置页能即时写入 SP。
 *
 * 布局见 `res/layout/view_number_picker.xml`，仅依赖通用
 * `android.R.drawable` / `R.color`，可在桌面设置全部数值编辑项中复用。
 */
class NumberPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    fun interface OnValueChangeListener {
        /**
         * 值变化回调。`newValue` 已夹紧到 `[min, max]`。
         */
        fun onValueChange(picker: NumberPickerView, newValue: Int)
    }

    private val etValue: EditText
    private var value: Int = 0
    private var min: Int = 0
    private var max: Int = 0
    private var step: Int = 1
    private var listener: OnValueChangeListener? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_number_picker, this, true)
        etValue = findViewById(R.id.et_value)
        val btnMinus = findViewById<TextView>(R.id.btn_minus)
        val btnPlus = findViewById<TextView>(R.id.btn_plus)

        btnMinus.setOnClickListener {
            if (value - step >= min) {
                value -= step
                etValue.setText(value.toString())
                listener?.onValueChange(this, value)
            }
        }

        btnPlus.setOnClickListener {
            if (value + step <= max) {
                value += step
                etValue.setText(value.toString())
                listener?.onValueChange(this, value)
            }
        }

        etValue.setOnEditorActionListener { _, _, _ ->
            commitInput()
            true
        }
        etValue.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                etValue.selectAll()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etValue, InputMethodManager.SHOW_IMPLICIT)
            } else {
                commitInput()
            }
        }
    }

    private fun commitInput() {
        val s = etValue.text.toString().trim()
        if (s.isNotEmpty()) {
            try {
                var v = s.toInt()
                v = v.coerceIn(min, max)
                value = v
            } catch (e: NumberFormatException) {
                // 输入非法：忽略，恢复原值
            }
        }
        etValue.setText(value.toString())
        etValue.setSelection(etValue.text.length)
        listener?.onValueChange(this, value)
    }

    /**
     * 设置值域和步长。`step` 用于 `[-]` / `[+]` 按钮，
     * [commitInput] 提交时不做步长对齐（用户可直接输入任意值，再夹紧到 `[min, max]`）。
     */
    fun setRange(min: Int, max: Int, step: Int) {
        this.min = min
        this.max = max
        this.step = step
    }

    /**
     * 设置当前值（不夹紧、不触发监听器）。供初始化或外部重置时使用。
     */
    fun setValue(value: Int) {
        this.value = value
        etValue.setText(value.toString())
    }

    /**
     * 取当前内部值。注意可能与 EditText 显示的字符串不同步（用户编辑中）。
     */
    fun getValue(): Int = value

    /**
     * 注册值变化监听器。任何来源的提交（步进 / 失焦 / 回车）都会触发。
     */
    fun setOnValueChangeListener(listener: OnValueChangeListener) {
        this.listener = listener
    }
}
