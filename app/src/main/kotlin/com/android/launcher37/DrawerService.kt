package com.android.launcher37

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 全部应用悬浮窗宿主，参考 FYT autodock PanelService 极简版：
 * - TYPE_APPLICATION_OVERLAY 居中，无动画无圆角，直接复用 DrawerOverlay
 * - onStartCommand 切换：已显示则关闭，否则显示
 */
class DrawerService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("VDFocusDbg", "DrawerService.onStartCommand (悬浮窗路径)")  // debug-point lifecy-v1
        if (DrawerOverlay.isShowing()) {
            DrawerOverlay.dismiss()
            stopSelf()
        } else {
            DrawerOverlay.show(applicationContext, null)
            if (!DrawerOverlay.isShowing()) {
                android.widget.Toast.makeText(this, "需要悬浮窗权限", android.widget.Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        DrawerOverlay.dismiss()
        super.onDestroy()
    }
}
