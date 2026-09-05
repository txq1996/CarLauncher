package com.android.launcher37.home.widget
import com.android.launcher37.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.android.launcher37.navi.MapPipHost
import com.android.launcher37.util.Dbg
import com.android.launcher37.util.Prefs
import com.android.launcher37.SettingsActivity

/**
 * VD 应用窗口 Widget：通用 VirtualDisplay 宿主（原地图/PIP 卡通用化）。
 *
 * - 每个实例独立 VirtualDisplay（:pip 进程多槽位，slot = id + 1；slot 0 保留）
 * - config["pkg"] 指定承载的 App（添加 VD 时或"属性"面板里必须选择，不自动回退地图/系统属性）
 * - 不负责导航数据：高德导航/巡航广播由 [SpeedWidget] 独立接收，与本 Widget 无关
 * - 销毁仅摘 surface（releaseTransient）：VD 与任务留在 :pip 进程，回桌面即恢复；
 *   设计器删除时先把任务搬回主屏
 */
class VdWidget(activity: Activity, spec: WidgetSpec) : WidgetView(activity, spec, R.layout.card_vd) {

    override val displayName = "VD 应用窗口"

    private val host: MapPipHost = MapPipHost.create(activity.applicationContext, spec.id + 1)
    private var tvLabel: TextView? = null

    /** 绑定的应用包名（实例 config；未配置时不拉起，必须显式选择） */
    val boundPkg: String?
        get() = spec.config[CFG_VD_PKG]?.takeIf { it.isNotBlank() }

    /** 属性面板：绑定应用（候选 = 全部可启动应用） */
    override val props: List<WidgetProp>
        get() = listOf(
            WidgetProp(
                key = CFG_VD_PKG,
                label = "绑定应用",
                type = PropType.CHOICE,
                default = "",
                choices = launcherChoices()
            )
        )

    override fun onBind() {
        tvLabel = findViewById(R.id.tv_vd_label)
        setCardBackground(false)
        background = activity.resources.getDrawable(R.drawable.bg_pip_frame)
        refreshLabel()
        applyLabelVisibility()
        // 设计模式（进入设计器时安装）不挂 surface，避免 :pip 自动拉起应用；
        // 普通模式立即挂载 surface。
        Dbg.i(TAG) { "onBind slot=${spec.id + 1} pkg=$boundPkg attachSurface=${!designMode}" }
        if (!designMode) host.attach(findViewById(R.id.vd_container), this)
    }

    /** 设计模式下隐藏 surface（SurfaceView 独立图层会拦截触摸，导致无法选中/拖动），
     *  仅保留卡片底与标签；退出设计模式恢复显示。
     *  同时暂停 surface 投递，避免 :pip 因 surface 重挂而自动拉起全部应用。 */
    override fun onDesignModeChanged(design: Boolean) {
        val container = findViewById<View>(R.id.vd_container)
        container?.visibility = if (design) View.INVISIBLE else View.VISIBLE
        host.setSurfacePaused(design)
        applyLabelVisibility()
        // 恢复且 surface 尚未挂载时补挂（进入设计器时 onBind 跳过挂载的场景）
        if (!design && container is ViewGroup && container.childCount == 0) {
            host.attach(container, this)
        }
    }

    /** 「VD：app 名称」标签仅设计模式显示（运行模式只留纯净画面） */
    private fun applyLabelVisibility() {
        tvLabel?.visibility = if (designMode) View.VISIBLE else View.GONE
    }

    override fun onThemeChange() {
        // 边框 stroke（divider 色）随日夜主题重取；标签文字为固定白（叠视频黑遮罩，不随主题）
        background = activity.resources.getDrawable(R.drawable.bg_pip_frame)
    }

    override fun ensureLaunched() {
        val pkg = boundPkg ?: return
        Dbg.i(TAG) { "ensureLaunched slot=${spec.id + 1} pkg=$pkg" }
        host.launch(pkg)
    }

    /** config 更新后由 WidgetHost 调用：刷新标签并拉起新绑定的 App（设计模式不拉起） */
    internal fun onAppChanged() {
        Dbg.i(TAG) { "onAppChanged slot=${spec.id + 1} pkg=$boundPkg designMode=$designMode" }
        refreshLabel()
        if (!designMode) boundPkg?.let { host.launch(it) }
    }

    /** 设计器删除：任务搬回主屏再摘 surface（避免任务困在无 surface 的 VD 上） */
    fun removeWithTaskRecovery() {
        boundPkg?.let { host.moveTaskToDefault(it) }
        host.releaseTransient(this)
    }

    /** 把本 Widget 承载的任务搬回主屏全屏（dock/抽屉启动同款 App 时调用） */
    fun moveTaskToMainScreen(): Boolean = boundPkg?.let { host.moveTaskToDefault(it) } ?: false

    override fun destroy() {
        // 摘 surface（VD 与任务留在 :pip，Activity 重建后恢复显示）；
        // 传归属：旧 Activity 延迟销毁时不得误摘新 Widget 已挂的 surface
        Dbg.i(TAG) { "destroy slot=${spec.id + 1} (surface detached, VD kept in :pip)" }
        host.releaseTransient(this)
    }

    private fun refreshLabel() {
        val pkg = boundPkg
        tvLabel?.text = when {
            pkg == null -> "VD：未选择应用"
            else -> "VD：${appLabel(pkg) ?: pkg}"
        }
    }

    private fun appLabel(pkg: String): String? = try {
        activity.packageManager.getApplicationLabel(
            activity.packageManager.getApplicationInfo(pkg, 0)
        )?.toString()
    } catch (_: Exception) {
        null
    }

    private fun launcherChoices(): List<Pair<String, String>> = try {
        activity.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { ri ->
            val label = ri.loadLabel(activity.packageManager)?.toString() ?: return@mapNotNull null
            label to ri.activityInfo.packageName
        }.distinctBy { it.second }.sortedBy { it.first }
    } catch (_: Throwable) {
        emptyList()
    }

    companion object {
        private const val TAG = "VdWidget"

        /** 默认延迟（桌面拉起 VD 前，0=立即；原 pip_start_delay 语义） */
        fun startDelayMs(context: Context): Int =
            try {
                Prefs.of(context).getInt(SettingsActivity.KEY_PIP_START_DELAY, 250)
            } catch (_: Exception) {
                250
            }
    }
}
