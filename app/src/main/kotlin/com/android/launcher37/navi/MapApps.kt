package com.android.launcher37.navi
import com.android.launcher37.data.Store
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject

/**
 * 三家车机地图的快捷指令封装（回家 / 去公司 / 结束导航）。
 *
 * 每家地图 SDK 协议不同：
 * - 高德：标准广播 `AUTONAVI_STANDARD_BROADCAST_RECV`，按 `KEY_TYPE`/`IS_START_NAVI` 分发
 * - 腾讯：标准广播 `WECARNAVIAUTO_STANDARD_BROADCAST_RECV`，按 `KEY_TYPE`/`EXTRA_OPERA` 分发
 * - 百度：绑定 exported AIDL 服务 `LBSApiService`，`transact(1)` 发 JSON 命令
 *
 * [detect] 选取当前生效地图：优先后台正在运行的（说明用户已在用），
 * 否则按 [PKGS] 顺序取第一个已安装的；都没有返回 `null`。
 *
 * 所有方法均无需权限，但需要对应地图应用已安装（由 `detect` 保证）。
 */
object MapApps {

    /**
     * 支持的地图应用包名，按优先级排列：高德 > 腾讯 > 百度。
     * 该顺序与 [detect] 的 fallback 顺序一致。
     */
    val PKGS: Array<String> = arrayOf(
        "com.autonavi.amapauto",   // 高德地图车机版
        "com.tencent.wecarnavi",   // 腾讯地图车机版
        "com.baidu.naviauto"       // 百度地图车机版
    )

    // ── 地图检测 ──────────────────────

    /**
     * 选择当前生效的地图包名。
     *
     * 优先选取后台正在运行的地图（用户已经在用，体验最自然）；
     * 没有运行中的地图时按 [PKGS] 顺序取第一个已安装的；
     * 三家都没装返回 `null`。
     *
     * 需要 `system` 权限才能枚举所有用户进程（普通应用仅能看自己），
     * 本应用为 `sharedUserId=android.uid.system`，满足要求。
     *
     * @param c 任意 Context
     * @return 包名或 `null`
     */
    @JvmStatic
    fun detect(c: Context): String? {
        for (pkg in PKGS) {
            if (running(c, pkg)) return pkg
        }
        for (pkg in PKGS) {
            if (Store.installed(c, pkg)) return pkg
        }
        return null
    }

    @JvmStatic
    fun running(c: Context, pkg: String): Boolean {
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val processes = am.runningAppProcesses ?: return false
            for (proc in processes) {
                if (proc.processName != null && proc.processName.startsWith(pkg)) return true
                proc.pkgList?.let {
                    for (p in it) {
                        if (pkg == p) return true
                    }
                }
            }
        } catch (e: Exception) {
            // 静默
        }
        return false
    }

    // ── 快捷指令分发 ──────────────────────

    @JvmStatic
    fun navigateHome(c: Context, pkg: String) = navigate(c, pkg, home = true)

    @JvmStatic
    fun navigateCompany(c: Context, pkg: String) = navigate(c, pkg, home = false)

    @JvmStatic
    fun stopNavigation(c: Context, pkg: String) {
        when (pkg) {
            "com.autonavi.amapauto" -> sendAmap(c, 10010, null, 0)
            "com.tencent.wecarnavi" -> sendWecar(c, 1003, 1)
            "com.baidu.naviauto" -> sendBaidu(c, "stop_navi", "")
        }
    }

    private fun navigate(c: Context, pkg: String, home: Boolean) {
        when (pkg) {
            "com.autonavi.amapauto" -> {
                // 特殊点导航：DEST 0 回家 / 1 回公司，IS_START_NAVI 0 直接开始导航
                sendAmap(c, 10040, "DEST", if (home) 0 else 1)
            }
            "com.tencent.wecarnavi" -> {
                // 回家/公司：KEY_TYPE 1004，EXTRA_OPERA 0 回家 / 1 公司
                sendWecar(c, 1004, if (home) 0 else 1)
            }
            "com.baidu.naviauto" -> {
                // 回家/公司：start_navi_to_homeorcompany，data 0 回家 / 1 公司
                sendBaidu(c, "start_navi_to_homeorcompany", if (home) "0" else "1")
            }
        }
    }

    // ── 高德：标准广播 AUTONAVI_STANDARD_BROADCAST_RECV ──────────────────────

    private fun sendAmap(c: Context, keyType: Int, extraKey: String?, extraValue: Int) {
        val i = Intent("AUTONAVI_STANDARD_BROADCAST_RECV")
            .setComponent(
                ComponentName(
                    "com.autonavi.amapauto",
                    "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"
                )
            )
            .setPackage("com.autonavi.amapauto")
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("KEY_TYPE", keyType)
        if (extraKey != null) {
            // 该版本高德语义：IS_START_NAVI 0 直接开始导航 / 1 进路线规划页
            i.putExtra(extraKey, extraValue).putExtra("IS_START_NAVI", 0)
        }
        c.sendBroadcast(i.putExtra("SOURCE_APP", "Launcher37"))
    }

    // ── 腾讯：标准广播 WECARNAVIAUTO_STANDARD_BROADCAST_RECV（KEY_TYPE + EXTRA_OPERA）──

    private fun sendWecar(c: Context, keyType: Int, opera: Int) {
        c.sendBroadcast(
            Intent("WECARNAVIAUTO_STANDARD_BROADCAST_RECV")
                .setComponent(
                    ComponentName(
                        "com.tencent.wecarnavi",
                        "com.tencent.wecarnavi.sdk.broadcast.NaviBroadcastReceiver"
                    )
                )
                .setPackage("com.tencent.wecarnavi")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("KEY_TYPE", keyType)
                .putExtra("EXTRA_OPERA", opera)
                .putExtra("SOURCE_APP", "Launcher37")
        )
    }

    // ── 百度：绑定 exported AIDL 服务 LBSApiService，transact(1) 发 JSON 命令 ─────

    private fun sendBaidu(c: Context, function: String, data: String) {
        val json = try {
            JSONObject()
                .put("function", function)
                .put("data", data)
                .put("flag", 0)
                .put("requestMap", JSONObject.NULL)
                .toString()
        } catch (e: Exception) {
            return
        }
        try {
            val intent = Intent("com.baidu.naviauto.imaplbs.lbsservice")
                .setComponent(
                    ComponentName(
                        "com.baidu.naviauto",
                        "com.baidu.naviauto.imaplbs.LBSApiService"
                    )
                )
                .setPackage("com.baidu.naviauto")
            c.bindService(intent, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                    val dataParcel = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        dataParcel.writeInterfaceToken("com.baidu.naviauto.imaplbs.IMapAutoAPIService")
                        dataParcel.writeString(json)
                        service.transact(1, dataParcel, reply, 0)
                        reply.readException()
                    } catch (e: Exception) {
                        // 静默
                    } finally {
                        dataParcel.recycle()
                        reply.recycle()
                    }
                    try {
                        c.unbindService(this)
                    } catch (e: Exception) {
                        // 静默
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // 服务端死亡也解绑：BIND_AUTO_CREATE 下不 unbind 会一直挂着连接
                    try { c.unbindService(this) } catch (e: Exception) {
                        // 静默
                    }
                }
            }, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            // 静默
        }
    }
}
