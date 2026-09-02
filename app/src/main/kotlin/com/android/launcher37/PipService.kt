package com.android.launcher37

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 独立进程持有 VirtualDisplay（PIP 导航任务真正"活着"的关键）。
 *
 * 设计目标：
 * - 进程独立（android:process=":pip"）：launcher 进程被 force-stop / APK
 *   替换时本 service 仍在 → VD 仍活 → 导航任务继续运行。
 * - 触摸由 launcher 端 SurfaceView 接收后通过 [IPipService.forwardTouch]
 *   跨进程投递给本 service 注入到 VD。
 * - 启动/搬移 Activity 也由 service 直接走 ActivityManager（同一个 VD）。
 *
 * 反射 API（InputManager / ActivityTaskManager）跟 [MapPipHost] 同源，
 * 全部走 system uid 隐式权限。
 */
class PipService : Service() {

    companion object {
        private const val TAG = "PipService"
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val LAUNCH_DELAY_MS = 500L
        private const val VD_NAME = "MyActivityViewVirtualDisplay"

        // InputManager hidden API — 9 上无 setDisplayId 方法，退化到 mDisplayId 字段反射
        private val sSetDisplayId: Method? = try {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        } catch (t: Throwable) { null }
        private val sSetDisplayIdField: java.lang.reflect.Field? = try {
            MotionEvent::class.java.getDeclaredField("mDisplayId").apply { isAccessible = true }
        } catch (_: Throwable) { null }

        private val sGetInputManager: Method? = try {
            android.hardware.input.InputManager::class.java.getMethod("getInstance")
        } catch (t: Throwable) { null }

        private val sInjectInputEvent: Method? = try {
            android.hardware.input.InputManager::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) { null }

        private val sCreateInputForwarder: Method? = try {
            android.hardware.input.InputManager::class.java.getMethod(
                "createInputForwarder", Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) { null }

        // ActivityManager hidden API（跨 display 任务搬移）
        private val sActivityTaskManagerService: Any?
        private val sMoveStackToDisplay: Method?
        private val sMoveTaskToBack: Method?

        init {
            var service: Any? = null
            var moveStack: Method? = null
            var moveBack: Method? = null
            // 优先 ActivityTaskManager（API 29+），回退 ActivityManagerNative（API 28）
            try {
                val atm = Class.forName("android.app.ActivityTaskManager")
                service = atm.getMethod("getService").invoke(null)
            } catch (_: Throwable) {}
            if (service == null) {
                try {
                    service = Class.forName("android.app.ActivityManagerNative")
                        .getMethod("getDefault").invoke(null)
                } catch (_: Throwable) {}
            }
            if (service != null) {
                val cls = service.javaClass
                val intClass = Int::class.javaPrimitiveType!!
                // 统一搬移接口：API 28 IActivityManager.moveStackToDisplay
                //               API 29-30 IActivityTaskManager.moveStackToDisplay
                //               API 31+ IActivityTaskManager.moveRootTaskToDisplay
                moveStack = findMethod(cls, "moveStackToDisplay", intClass, intClass)
                    ?: findMethod(cls, "moveRootTaskToDisplay", intClass, intClass)
                moveBack = findMethod(cls, "moveTaskToBack", intClass)
            }
            sActivityTaskManagerService = service
            sMoveStackToDisplay = moveStack
            sMoveTaskToBack = moveBack

            Log.i(
                TAG,
                "API: sdk=${Build.VERSION.SDK_INT}" +
                    " setDisplayId=${sSetDisplayId != null}" +
                    " setDisplayIdField=${sSetDisplayIdField != null}" +
                    " inject=${sInjectInputEvent != null}" +
                    " forwarder=${sCreateInputForwarder != null}" +
                    " moveStack=${sMoveStackToDisplay != null}" +
                    " moveBack=${sMoveTaskToBack != null}"
            )
        }

        private fun findMethod(
            clazz: Class<*>?, name: String, vararg parameterTypes: Class<*>
        ): Method? {
            if (clazz == null) return null
            try { return clazz.getMethod(name, *parameterTypes) } catch (ignored: Throwable) {}
            try {
                val m = clazz.getDeclaredMethod(name, *parameterTypes)
                m.isAccessible = true
                return m
            } catch (ignored: Throwable) {}
            return null
        }
    }

    private val mHandler = MainThread.handler
    private var mVd: VirtualDisplay? = null
    private var mSurface: Surface? = null
    private var mSurfaceWidth = 0
    private var mSurfaceHeight = 0
    private var mInputForwarder: Any? = null
    private var mForwardEvent: Method? = null
    private var mCurrentPkg: String? = null
    private var mPendingLaunch: String? = null

    private val mLaunchRunnable = Runnable {
        val pkg = mPendingLaunch ?: return@Runnable
        mPendingLaunch = null
        if (displayId() < 0) return@Runnable
        if (!moveStaleTask(pkg)) doStart(pkg)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 不主动退出，进程常驻
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: pid=${Process.myPid()}")
        releaseDisplay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    inner class LocalBinder : IPipService.Stub() {
        override fun getDisplayId(): Int = synchronized(this@PipService) { displayId() }

        override fun attachSurface(surface: Surface?, width: Int, height: Int): Boolean {
            Log.i(TAG, "attachSurface: w=$width h=$height valid=${surface?.isValid}")
            if (surface == null) {
                Log.w(TAG, "attachSurface: null surface")
                return false
            }
            if (width <= 0 || height <= 0) return false
            synchronized(this@PipService) {
                // 释放旧 surface（防重复 attach 泄漏）；detachSurface 也会走这里
                val old = mSurface
                if (old != null && old !== surface) {
                    try { old.release() } catch (t: RuntimeException) {
                        Log.w(TAG, "release old surface failed", t)
                    }
                }
                mSurface = surface
                mSurfaceWidth = width
                mSurfaceHeight = height
                ensureDisplay(surface, width, height)
            }
            if (mCurrentPkg != null) {
                mPendingLaunch = mCurrentPkg
                mHandler.removeCallbacks(mLaunchRunnable)
                mHandler.postDelayed(mLaunchRunnable, LAUNCH_DELAY_MS)
            }
            return true
        }

        override fun detachSurface() {
            Log.i(TAG, "detachSurface")
            mHandler.removeCallbacks(mLaunchRunnable)
            synchronized(this@PipService) {
                val s = mSurface
                if (s != null) {
                    try { s.release() } catch (ignored: Throwable) {}
                }
                mSurface = null
                mVd?.let {
                    try { it.surface = null } catch (t: Throwable) {
                        Log.w(TAG, "detach VD surface failed", t)
                    }
                }
                mInputForwarder = null
                mForwardEvent = null
            }
        }

        override fun launch(packageName: String?) {
            if (packageName.isNullOrEmpty()) {
                Log.w(TAG, "launch: empty package")
                return
            }
            mCurrentPkg = packageName
            if (displayId() < 0) {
                mPendingLaunch = packageName
                return
            }
            mHandler.removeCallbacks(mLaunchRunnable)
            if (moveStaleTask(packageName)) return
            mPendingLaunch = packageName
            mHandler.postDelayed(mLaunchRunnable, LAUNCH_DELAY_MS)
        }

        override fun forwardTouch(event: MotionEvent?): Boolean {
            if (event == null) return false
            return injectTouch(event)
        }

        override fun moveTaskToDisplay(packageName: String?, displayId: Int): Boolean {
            if (packageName.isNullOrEmpty()) return false
            return moveTaskToTargetDisplay(packageName, displayId)
        }
    }

    private fun displayId(): Int {
        val vd = mVd ?: return -1
        return try { vd.display?.displayId ?: -1 } catch (t: Throwable) { -1 }
    }

    private fun ensureDisplay(surface: Surface?, width: Int, height: Int) {
        if (surface == null || !surface.isValid) return
        if (width <= 0 || height <= 0) return
        val densityDpi = resources.displayMetrics.densityDpi
        val vd = mVd
        if (vd != null) {
            try {
                vd.surface = surface
                vd.resize(width, height, densityDpi)
                ensureInputForwarder()
                Log.i(TAG, "reuse VD: id=${displayId()} ${width}x$height")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "reuse VD failed, recreate", t)
                try { vd.release() } catch (ignored: Throwable) {}
                mVd = null
                mInputForwarder = null
                mForwardEvent = null
            }
        }
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val flags = if (Build.VERSION.SDK_INT >= 29)
            VIRTUAL_DISPLAY_FLAG_TRUSTED or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        else 0
        mVd = try {
            dm.createVirtualDisplay(VD_NAME, width, height, densityDpi, surface, flags)
        } catch (t: SecurityException) {
            Log.e(TAG, "createVirtualDisplay denied (uid=${Process.myUid()})", t)
            null
        } catch (t: RuntimeException) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            null
        }
        if (mVd == null) {
            Log.e(TAG, "createVirtualDisplay returned null")
            return
        }
        Log.i(TAG, "VD created: sdk=${Build.VERSION.SDK_INT} id=${displayId()} ${width}x$height dpi=$densityDpi")
        ensureInputForwarder()
    }

    private fun ensureInputForwarder() {
        if (mInputForwarder != null && mForwardEvent != null) return
        val create = sCreateInputForwarder ?: return
        val getInstance = sGetInputManager ?: return
        val display = displayId()
        if (display < 0) return
        try {
            val im = getInstance.invoke(null)
            mInputForwarder = create.invoke(im, display) ?: return
            mForwardEvent = findMethod(
                mInputForwarder!!.javaClass, "forwardEvent", InputEvent::class.java
            )
            Log.i(TAG, "InputForwarder: display=$display method=${mForwardEvent != null}")
        } catch (t: Throwable) {
            mInputForwarder = null
            mForwardEvent = null
            Log.w(TAG, "createInputForwarder failed", t)
        }
    }

    private fun injectTouch(event: MotionEvent): Boolean {
        val display = displayId()
        if (display < 0) return false
        if ((sSetDisplayId != null || sSetDisplayIdField != null) && sGetInputManager != null && sInjectInputEvent != null) {
            val copy = MotionEvent.obtain(event)
            try {
                if (sSetDisplayId != null) sSetDisplayId.invoke(copy, display)
                else sSetDisplayIdField?.setInt(copy, display)
                val im = sGetInputManager.invoke(null)
                val r = sInjectInputEvent.invoke(im, copy, 0)
                return true == r
            } catch (t: Throwable) {
                Log.w(TAG, "injectInputEvent failed", t)
            } finally {
                copy.recycle()
            }
        }
        ensureInputForwarder()
        val fwd = mInputForwarder
        val m = mForwardEvent
        if (fwd != null && m != null) {
            return try {
                true == m.invoke(fwd, event)
            } catch (t: Throwable) {
                Log.w(TAG, "InputForwarder failed", t)
                false
            }
        }
        return false
    }

    /**
     * 在 running tasks 中查找 packageName 的 task，按需搬到 targetDisplay。
     * - [allowMoveToDefault] = false 时跳过搬到主屏（moveStaleTask 场景：VD→主屏安全，主屏→VD 危险）
     * - [allowMoveToBack] = true 时主屏目标额外尝试 moveTaskToBack（moveTaskToTargetDisplay 场景）
     * 返回 true 表示已找到并处理（无需再 startActivity）。
     */
    private fun findAndMoveTask(packageName: String, targetDisplay: Int, allowMoveToDefault: Boolean, allowMoveToBack: Boolean): Boolean {
        if (sActivityTaskManagerService == null) return false
        if (targetDisplay < 0) return false
        return try {
            val am = getSystemService(ActivityManager::class.java) ?: return false
            val tasks = am.getRunningTasks(100) ?: return false
            for (task in tasks) {
                if (task == null) continue
                val taskPkg = task.baseActivity?.packageName?.takeIf { it.isNotEmpty() }
                    ?: task.topActivity?.packageName
                if (taskPkg != packageName) continue
                val taskId = task.id
                if (taskId < 0) continue
                var detected = false
                val currentDisplay = try {
                    val f = findMethod(task.javaClass, "getDisplayId")
                        ?: try { task.javaClass.getField("displayId") } catch (ignored: Throwable) { null }
                    when (f) {
                        is Method -> { detected = true; (f.invoke(task) as? Int) ?: Display.DEFAULT_DISPLAY }
                        is java.lang.reflect.Field -> { detected = true; f.getInt(task) }
                        else -> Display.DEFAULT_DISPLAY
                    }
                } catch (t: Throwable) { Display.DEFAULT_DISPLAY }
                Log.i(TAG, "findTask: id=$taskId pkg=$taskPkg display=$currentDisplay(target=$targetDisplay) detected=$detected")
                if (detected && currentDisplay == targetDisplay) return true
                if (detected && currentDisplay >= 0 && currentDisplay != Display.DEFAULT_DISPLAY && targetDisplay != Display.DEFAULT_DISPLAY) continue
                // moveStackToDisplay：搬到主屏时使用（VD→主屏正常），
                // 搬到 VD 时跳过（Android 9 上跨 display 移到 VD 会触发部分 app force-finish）
                if (sMoveStackToDisplay != null && (allowMoveToDefault || targetDisplay != Display.DEFAULT_DISPLAY)) {
                    try {
                        val rootTaskId = try {
                            task.javaClass.getField("stackId").getInt(task)
                        } catch (_: Throwable) { taskId }
                        sMoveStackToDisplay.invoke(sActivityTaskManagerService, rootTaskId, targetDisplay)
                        Log.i(TAG, "moveStackToDisplay ok: task=$taskId rootTask=$rootTaskId display=$targetDisplay")
                        return true
                    } catch (t: InvocationTargetException) {
                        val cause = t.cause
                        if (cause is IllegalArgumentException && cause.message?.contains("current display") == true) {
                            Log.i(TAG, "moveStackToDisplay: already on target display, task=$taskId")
                            return true
                        }
                        Log.w(TAG, "moveStackToDisplay failed", t)
                    } catch (t: Throwable) {
                        Log.w(TAG, "moveStackToDisplay failed", t)
                    }
                }
                // 先把 VD 上的任务移到后台，调用方再 startActivity 到主屏时不会复用 VD 旧 task
                if (allowMoveToBack && targetDisplay == Display.DEFAULT_DISPLAY && sMoveTaskToBack != null) {
                    try {
                        sMoveTaskToBack.invoke(sActivityTaskManagerService, taskId)
                        Log.i(TAG, "moveTaskToBack fallback: task=$taskId")
                        return false
                    } catch (t: Throwable) {
                        Log.w(TAG, "moveTaskToBack failed", t)
                    }
                }
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "findAndMoveTask failed", t)
            false
        }
    }

    private fun moveStaleTask(packageName: String): Boolean =
        findAndMoveTask(packageName, displayId(), allowMoveToDefault = true, allowMoveToBack = false)

    private fun moveTaskToTargetDisplay(packageName: String, targetDisplay: Int): Boolean =
        findAndMoveTask(packageName, targetDisplay, allowMoveToDefault = true, allowMoveToBack = true)

    private fun doStart(packageName: String) {
        val targetDisplay = displayId()
        if (targetDisplay < 0) {
            mPendingLaunch = packageName
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: run {
            Log.w(TAG, "launch intent not found: $packageName")
            return
        }
        intent.flags = 0
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_ACTIVITY_SINGLE_TOP
                or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        val w = maxOf(mSurfaceWidth, 1)
        val h = maxOf(mSurfaceHeight, 1)
        val options = ActivityOptions.makeBasic()
        options.setLaunchBounds(android.graphics.Rect(0, 0, w, h))
        options.setLaunchDisplayId(targetDisplay)
        Log.i(TAG, "startActivity: pkg=$packageName display=$targetDisplay size=${w}x$h")
        try {
            startActivity(intent, options.toBundle())
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity on VD failed", t)
        }
    }

    private fun releaseDisplay() {
        synchronized(this) {
            mInputForwarder = null
            mForwardEvent = null
            val s = mSurface
            if (s != null) {
                try { s.release() } catch (ignored: Throwable) {}
                mSurface = null
            }
            val vd = mVd ?: return
            try {
                Log.i(TAG, "release VD: id=${displayId()}")
                vd.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release VD failed", t)
            } finally {
                mVd = null
            }
        }
    }
}
