package com.android.launcher37.navi
import com.android.launcher37.IPipService
import com.android.launcher37.SettingsActivity
import com.android.launcher37.pip.PipService
import com.android.launcher37.util.Prefs
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup

/**
 * Launcher 端 PIP 桥接：把 SurfaceView 收到的 surface 投递给独立进程的 [PipService]，
 * 由后者持有 VirtualDisplay 和导航任务。
 *
 * 关键：
 * - 自身不创建/释放 VD；VD 由 :pip 进程持有
 * - Surface 跨进程通过 [IPipService.attachSurface] 投递
 * - 触摸事件通过 [IPipService.forwardTouch] 投递
 * - Activity 销毁 / 进程被杀不影响 :pip 进程；导航持续
 *
 * slotId：0 = launcher 主地图卡（走既有接口，行为不变）；
 * ≥1 = 布局设计器/预览的 VD 卡片（走 slot 系列接口，每槽位独立 VD）。
 */
internal class MapPipHost private constructor(
    private val mContext: Context,
    private val mSlotId: Int
) {

    companion object {
        private const val TAG = "MapPipLocal"

        /**
         * 按 slotId 缓存复用：同一槽位（spec.id+1 唯一）同一时间只有一个活跃 VdWidget，
         * 设计器反复删除/重建 Widget 时复用同一 host，避免每次新建都 bindService
         * （BIND_AUTO_CREATE 引用计数累积，且 :pip 需常驻故 Widget 销毁不 unbind）。
         * 仅主线程调用（Widget 生命周期均在主线程），无需并发容器。
         */
        private val sHosts = HashMap<Int, MapPipHost>()

        fun create(context: Context, slotId: Int = 0): MapPipHost =
            sHosts.getOrPut(slotId) { MapPipHost(context.applicationContext, slotId) }
    }

    private val mSurfaceView = SurfaceView(mContext).apply {
        isFocusable = false
        isFocusableInTouchMode = false
    }
    private var mService: IPipService? = null
    private var mBound = false
    private var mBinding = false
    private var mAttached = false
    private var mSurfaceWidth = 0
    private var mSurfaceHeight = 0
    private var mLastSurface: Surface? = null
    private var mPendingLaunch: Intent? = null
    private var mSurfacePaused = false

    /**
     * SurfaceHolder 回调：单例，attach 时复用。
     * 之前在 attach() 内联 addCallback，每次 Activity 重建都新增一个匿名实例，
     * 旧实例的 surfaceDestroyed 仍会触发 detachSurface()，与新 surface 的 attachSurface 竞争。
     */
    private val mSurfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceCreated")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(TAG, "surfaceChanged ${width}x$height")
            if (width <= 0 || height <= 0) return
            mSurfaceWidth = width
            mSurfaceHeight = height
            mLastSurface = holder.surface
            pushSurfaceToService(holder.surface, width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceDestroyed")
            mLastSurface = null
            detachSurface()
        }
    }

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "onServiceConnected")
            mService = IPipService.Stub.asInterface(service)
            mBound = true
            mBinding = false
            // service 刚连上时 surfaceChanged 早已发生过，service 不会自己知道 surface，
            // 主动把最近一次的 surface 推过去。
            val s = mLastSurface
            if (s != null && s.isValid && mSurfaceWidth > 0 && mSurfaceHeight > 0) {
                pushSurfaceToService(s, mSurfaceWidth, mSurfaceHeight)
            }
            mPendingLaunch?.let { intent ->
                val pkg = intent.getPackage() ?: intent.component?.packageName
                if (!pkg.isNullOrEmpty()) {
                    try { launchOnService(pkg) } catch (t: RemoteException) {
                        Log.w(TAG, "launch on connect failed", t)
                    }
                }
                mPendingLaunch = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "onServiceDisconnected")
            mService = null
            mBound = false
            mBinding = false
        }
    }

    fun attach(parent: ViewGroup) {
        if (mAttached) {
            (mSurfaceView.parent as? ViewGroup)?.removeView(mSurfaceView)
        }
        mAttached = true
        val lp = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        parent.addView(mSurfaceView, lp)

        mSurfaceView.setOnTouchListener { _, event -> forwardTouch(event) }

        // 复用单例 callback，Activity 重建时先 removeCallback 避免累积监听器
        mSurfaceView.holder.removeCallback(mSurfaceCallback)
        mSurfaceView.holder.addCallback(mSurfaceCallback)

        bindServiceIfNeeded()
    }

    private fun bindServiceIfNeeded() {
        if (mBound || mBinding) return
        val i = Intent(mContext, PipService::class.java)
        try {
            val ok = mContext.bindService(i, mConnection, Context.BIND_AUTO_CREATE)
            if (ok) mBinding = true
        } catch (t: Throwable) {
            Log.e(TAG, "bindService failed", t)
        }
    }

    private fun pushSurfaceToService(surface: Surface?, width: Int, height: Int) {
        if (surface == null || !surface.isValid) return
        if (mSurfacePaused) return
        val svc = mService
        if (svc == null) {
            Log.w(TAG, "service not bound yet, surface will be retried")
            return
        }
        try {
            val ok = if (mSlotId == 0) {
                svc.attachSurface(surface, width, height, launchDelayMs())
            } else {
                svc.attachSurfaceToSlot(mSlotId, surface, width, height, launchDelayMs())
            }
            Log.i(TAG, "attachSurface slot=$mSlotId result=$ok")
        } catch (t: RemoteException) {
            Log.w(TAG, "attachSurface remote failed", t)
        }
    }

    /** 摘本槽位 surface（不影响 VD，VD 由 :pip 进程持有） */
    private fun detachSurface() {
        try {
            if (mSlotId == 0) mService?.detachSurface()
            else mService?.detachSurfaceSlot(mSlotId)
        } catch (t: RemoteException) {
            Log.w(TAG, "detachSurface failed", t)
        }
    }

    /** 按槽位 launch：slot 0 走既有接口，≥1 走槽位接口 */
    private fun launchOnService(packageName: String) {
        val svc = mService ?: return
        if (mSlotId == 0) svc.launch(packageName, launchDelayMs())
        else svc.launchToSlot(packageName, launchDelayMs(), mSlotId)
    }

    /** VD 拉起延迟（设置项 vd_launch_delay，毫秒，0=立即；surface 绑定/任务拉回前等待） */
    private fun launchDelayMs(): Long = try {
        Prefs.of(mContext).getInt(SettingsActivity.KEY_VD_LAUNCH_DELAY, 250).toLong()
    } catch (t: Throwable) { 250L }

    fun launch(packageName: String) {
        if (packageName.isEmpty()) return
        val svc = mService
        if (svc == null) {
            mPendingLaunch = mContext.packageManager.getLaunchIntentForPackage(packageName)
            bindServiceIfNeeded()
            return
        }
        try {
            launchOnService(packageName)
        } catch (t: RemoteException) {
            Log.w(TAG, "launch failed", t)
        }
    }

    fun moveTaskToDefault(packageName: String): Boolean {
        val svc = mService ?: return false
        return try { svc.moveTaskToDisplay(packageName, 0) } catch (t: RemoteException) {
            Log.w(TAG, "moveTaskToDefault failed", t)
            false
        }
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        val svc = mService ?: return false
        return try {
            if (mSlotId == 0) svc.forwardTouch(event)
            else svc.forwardTouchToSlot(mSlotId, event)
        } catch (t: RemoteException) {
            Log.w(TAG, "forwardTouch failed", t)
            false
        }
    }

    /**
     * 瞬时释放：摘 surface（不影响 VD，VD 由 :pip 进程持有）。
     */
    fun releaseTransient() {
        Log.i(TAG, "releaseTransient")
        detachSurface()
        mPendingLaunch = null
    }

    /**
     * 设计模式下暂停 surface 投递：摘掉已推送的 surface 并阻止再推送，
     * 避免 :pip 因 surface 重挂而自动拉起应用（进设计器不应打开全部应用窗口）。
     * 恢复时把最近一次 surface 重新推回。
     */
    fun setSurfacePaused(paused: Boolean) {
        mSurfacePaused = paused
        if (paused) {
            detachSurface()
        } else if (mLastSurface != null && mLastSurface!!.isValid && mSurfaceWidth > 0 && mSurfaceHeight > 0) {
            pushSurfaceToService(mLastSurface, mSurfaceWidth, mSurfaceHeight)
        }
    }

    /**
     * 完整释放：解绑 service 并通知 service 退出（让 VD 释放）。
     * 当前代码库无调用点（保留以支持将来"明确退出导航"路径）；调用后从缓存移除，
     * 下次 create 会新建实例。
     */
    fun release() {
        Log.i(TAG, "release")
        releaseTransient()
        sHosts.remove(mSlotId)
        if (mBound || mBinding) {
            try { mContext.unbindService(mConnection) } catch (ignored: Throwable) {}
            mBound = false
            mBinding = false
        }
        mService = null
        try { mContext.stopService(Intent(mContext, PipService::class.java)) }
        catch (ignored: Throwable) {}
    }
}
