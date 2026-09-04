package com.android.launcher37.home.widget
import com.android.launcher37.R

import android.app.Activity
import android.content.pm.ResolveInfo
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import com.android.launcher37.data.AppQuery
import com.android.launcher37.util.HoloPopup
import com.android.launcher37.util.SharedExecutor

/**
 * 音乐应用选择器（歌词 Widget 长按换绑用）：列出全部可启动应用，回调 `pkg/cls` 标识。
 * 绑定存储与语义由调用方决定（当前存歌词 Widget 实例 config 的 `music_pkg`）。
 */
internal object MusicAppPicker {

    fun pick(activity: Activity, title: String, onPicked: (String) -> Unit) {
        val themed: android.content.Context = HoloPopup.themedContext(activity)
        val list = ListView(themed)
        val popup: PopupWindow = HoloPopup.showWithWidth(
            activity, HoloPopup.titledPanel(themed, title, list), HoloPopup.WIDTH_SMALL
        )
        list.onItemClickListener = android.widget.AdapterView.OnItemClickListener { parent, _, position, _ ->
            popup.dismiss()
            val ri = parent.adapter.getItem(position) as? ResolveInfo ?: return@OnItemClickListener
            onPicked("${ri.activityInfo.packageName}/${ri.activityInfo.name}")
        }
        val entries: List<ResolveInfo> = AppQuery.launcherEntries(activity, null)
        SharedExecutor.io().execute {
            val adapter = object : BaseAdapter() {
                private val labels = ArrayList<String>()
                private val icons = ArrayList<android.graphics.drawable.Drawable?>()
                init {
                    val pm = activity.packageManager
                    for (ri in entries) {
                        labels.add(ri.loadLabel(pm)?.toString() ?: ri.activityInfo.packageName)
                        icons.add(ri.loadIcon(pm))
                    }
                }
                override fun getCount() = entries.size
                override fun getItem(position: Int) = entries[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val v = convertView ?: activity.layoutInflater.inflate(R.layout.item_app, parent, false)
                    v.findViewById<android.widget.ImageView>(R.id.app_icon).setImageDrawable(icons[position])
                    v.findViewById<TextView>(R.id.app_name).text = labels[position]
                    return v
                }
            }
            list.post {
                if (!activity.isDestroyed && !activity.isFinishing) list.adapter = adapter
            }
        }
    }
}
