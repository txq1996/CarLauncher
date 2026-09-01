package com.android.launcher37

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
import android.view.View
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
 */
internal class MapPipHost private constructor(private val mContext: Context) {

    companion object {
        private const val TAG = "MapPipLocal"
        fun available(): Boolean = android.os.Build.VERSION.SDK_INT in 28..35
        fun create(context: Context): MapPipHost = MapPipHost(context.applicationContext)
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
            try { mService?.detachSurface() } catch (t: RemoteException) {
                Log.w(TAG, "detachSurface failed", t)
            }
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
                    try { mService?.launch(pkg) } catch (t: RemoteException) {
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

    fun view(): View = mSurfaceView

    fun displayId(): Int = try { mService?.displayId ?: -1 } catch (t: Throwable) { -1 }

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
        val svc = mService
        if (svc == null) {
            Log.w(TAG, "service not bound yet, surface will be retried")
            return
        }
        try {
            val ok = svc.attachSurface(surface, width, height)
            Log.i(TAG, "attachSurface result=$ok")
        } catch (t: RemoteException) {
            Log.w(TAG, "attachSurface remote failed", t)
        }
    }

    fun launch(packageName: String) {
        if (packageName.isEmpty()) return
        val svc = mService
        if (svc == null) {
            mPendingLaunch = mContext.packageManager.getLaunchIntentForPackage(packageName)
            bindServiceIfNeeded()
            return
        }
        try { svc.launch(packageName) } catch (t: RemoteException) {
            Log.w(TAG, "launch failed", t)
        }
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        val svc = mService ?: return false
        return try { svc.forwardTouch(event) } catch (t: RemoteException) {
            Log.w(TAG, "forwardTouch failed", t)
            false
        }
    }

    /**
     * 瞬时释放：摘 surface（不影响 VD，VD 由 :pip 进程持有）。
     */
    fun releaseTransient() {
        Log.i(TAG, "releaseTransient")
        try { mService?.detachSurface() } catch (t: RemoteException) { /* */ }
        mPendingLaunch = null
    }

    /**
     * 完整释放：解绑 service 并通知 service 退出（让 VD 释放）。
     * 只有用户明确"退出导航"才调。
     */
    fun release() {
        Log.i(TAG, "release")
        releaseTransient()
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
