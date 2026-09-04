package com.android.launcher37.navi
import com.android.launcher37.navi.MapApps
import com.android.launcher37.data.Store
import android.app.Activity
import android.content.Context
import android.widget.Toast

/**
 * 回家 / 去公司 / 结束导航 三家车机地图动作的统一入口。
 *
 * - 选取地图包名 [MapApps.detect]
 * - 高德 / 腾讯 / 百度三家按各自协议分发（[MapApps.navigateHome/Company/stopNavigation]）
 * - 未安装地图时统一提示「未安装可用的地图应用」
 */
internal object MapActions {

    /**
     * 触发回家 / 去公司 / 结束导航动作。
     *
     * @param c      Activity（用于 Toast）
     * @param action "home" / "company" / 其它（按结束导航处理）
     */
    @JvmStatic
    fun run(c: Context, action: String) {
        val pkg = MapApps.detect(c)
        if (pkg == null || !Store.installed(c, pkg)) {
            if (c is Activity) Toast.makeText(c, NO_MAP_TOAST, Toast.LENGTH_SHORT).show()
            return
        }
        when (action) {
            "home" -> MapApps.navigateHome(c, pkg)
            "company" -> MapApps.navigateCompany(c, pkg)
            else -> MapApps.stopNavigation(c, pkg)
        }
    }

    private const val NO_MAP_TOAST = "no map app installed"
}
