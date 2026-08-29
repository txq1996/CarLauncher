package com.android.launcher37

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 自实现 `ActivityView` 宿主（std PIP 模式）。
 *
 * Android 12 已移除 `android.app.ActivityView`，本类以公开 API 等价实现：
 * - `SurfaceView` 承载 VirtualDisplay 画面
 * - `DisplayManager.createVirtualDisplay` + `ActivityOptions.setLaunchDisplayId` 启动任务
 * - 触摸转发优先用 `MotionEvent.setDisplayId` + `InputManager.injectInputEvent`（29+），
 *   缺席时回落 `InputManager.createInputForwarder().forwardEvent`（28）
 *
 * 关键设计：
 * - **surfaceDestroyed 只摘 surface 绝不 release**：保 display 与任务原位
 *   不动，避免回桌面时任务被清冷启动
 * - **VirtualDisplay 滞后启动**：surfaceChanged 后 500ms 再 startActivity，
 *   避免 TaskDisplayArea 未初始化导致任务回落主屏
 * - **主屏滞留任务搬移**：`moveTaskToDisplay` / `moveRootTaskToDisplay` 反射调用
 * - **flags 分版本**：29+ 用 `TRUSTED | OWN_CONTENT_ONLY`，28 用 0
 */
internal class MapPipHost private constructor(private val mContext: Context) {

    companion object {
        private const val TAG = "MapPip"
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10

        // InputManager hidden API。
        private val sSetDisplayId: Method? = try {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            Log.w(TAG, "resolve MotionEvent.setDisplayId failed", t)
            null
        }

        private val sGetInputManager: Method? = try {
            android.hardware.input.InputManager::class.java.getMethod("getInstance")
        } catch (t: Throwable) {
            Log.w(TAG, "resolve InputManager APIs failed", t)
            null
        }

        private val sInjectInputEvent: Method? = try {
            android.hardware.input.InputManager::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            null
        }

        // InputForwarder 作为兼容 fallback
        private val sCreateInputForwarder: Method? = try {
            android.hardware.input.InputManager::class.java.getMethod(
                "createInputForwarder", Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            Log.i(TAG, "InputManager.createInputForwarder unavailable")
            null
        }

        // ActivityTaskManager hidden API
        private val sMoveRootTaskToDisplay: Method?
        private val sMoveTaskToDisplay: Method?
        private val sActivityTaskManagerService: Any?

        init {
            var moveRootTaskToDisplay: Method? = null
            var moveTaskToDisplay: Method? = null
            var activityTaskManagerService: Any? = null
            val intClass: Class<*> = Int::class.javaPrimitiveType!!
            try {
                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val getService = atmClass.getMethod("getService")
                activityTaskManagerService = getService.invoke(null)
                if (activityTaskManagerService != null) {
                    val serviceClass = activityTaskManagerService.javaClass
                    moveRootTaskToDisplay = findMethod(serviceClass, "moveRootTaskToDisplay", intClass, intClass)
                    moveTaskToDisplay = findMethod(serviceClass, "moveTaskToDisplay", intClass, intClass)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "resolve ActivityTaskManager failed", t)
            }
            sMoveRootTaskToDisplay = moveRootTaskToDisplay
            sMoveTaskToDisplay = moveTaskToDisplay
            sActivityTaskManagerService = activityTaskManagerService

            Log.i(
                TAG,
                "API: sdk=${Build.VERSION.SDK_INT}" +
                    " setDisplayId=${sSetDisplayId != null}" +
                    " injectInputEvent=${sInjectInputEvent != null}" +
                    " inputForwarder=${sCreateInputForwarder != null}" +
                    " moveRootTaskToDisplay=${sMoveRootTaskToDisplay != null}" +
                    " moveTaskToDisplay=${sMoveTaskToDisplay != null}"
            )
        }

        // 精确查找方法，不遍历 getMethods()
        private fun findMethod(clazz: Class<*>?, name: String, vararg parameterTypes: Class<*>): Method? {
            if (clazz == null) return null
            try {
                return clazz.getMethod(name, *parameterTypes)
            } catch (ignored: Throwable) {
            }
            // 某些厂商 framework 方法可能不是 public
            try {
                val method = clazz.getDeclaredMethod(name, *parameterTypes)
                try {
                    method.isAccessible = true
                } catch (ignored: Throwable) {
                }
                return method
            } catch (ignored: Throwable) {
            }
            return null
        }

        // 精确查找 Field
        private fun findField(clazz: Class<*>?, name: String): Field? {
            if (clazz == null) return null
            try {
                return clazz.getField(name)
            } catch (ignored: Throwable) {
            }
            try {
                val field = clazz.getDeclaredField(name)
                try {
                    field.isAccessible = true
                } catch (ignored: Throwable) {
                }
                return field
            } catch (ignored: Throwable) {
            }
            return null
        }

        // Android 10 ~ 15
        fun available(): Boolean = Build.VERSION.SDK_INT in 29..35

        fun create(context: Context): MapPipHost = MapPipHost(context.applicationContext)
    }

    private val mSurfaceView = SurfaceView(mContext).apply {
        isFocusable = false
        isFocusableInTouchMode = false
    }
    private val mHandler = Handler(Looper.getMainLooper())

    private var mVd: VirtualDisplay? = null
    private var mSurfaceReady = false
    private var mAttached = false
    private var mSurfaceWidth = 0
    private var mSurfaceHeight = 0
    private var mPendingLaunch: Intent? = null
    private var mInputForwarder: Any? = null
    private var mForwardEvent: Method? = null

    // 延迟启动
    private val mLaunchRunnable = Runnable { launchPendingIfAny() }

    fun view(): View = mSurfaceView

    // 返回 VirtualDisplay ID
    fun displayId(): Int {
        val vd = mVd ?: return -1
        return try {
            vd.display?.displayId ?: -1
        } catch (t: Throwable) {
            Log.w(TAG, "getDisplayId failed", t)
            -1
        }
    }

    // 将 SurfaceView 挂到 PIP 容器
    fun attach(parent: ViewGroup) {
        if (mAttached) {
            Log.w(TAG, "attach called more than once")
            return
        }
        mAttached = true
        val lp = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        parent.addView(mSurfaceView, lp)

        // 触摸事件
        mSurfaceView.setOnTouchListener { _, event -> forwardTouch(event) }

        // Surface 生命周期
        mSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "surfaceChanged ${width}x$height")
                if (width <= 0 || height <= 0) {
                    Log.w(TAG, "invalid surface size")
                    return
                }
                mSurfaceReady = true
                mSurfaceWidth = width
                mSurfaceHeight = height
                ensureDisplay(holder.surface, width, height)

                // surfaceChanged 可能执行多次，先删除旧 Runnable
                mHandler.removeCallbacks(mLaunchRunnable)

                // VirtualDisplay 创建后，TaskDisplayArea 可能需要一点时间
                if (mPendingLaunch != null) {
                    mHandler.postDelayed(mLaunchRunnable, 500)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceDestroyed")
                mSurfaceReady = false
                // 这里只摘掉 Surface，不 release VirtualDisplay
                mVd?.let {
                    try {
                        it.surface = null
                    } catch (t: Throwable) {
                        Log.w(TAG, "detach VD surface failed", t)
                    }
                }
            }
        })
    }

    // 完整释放，只有整个 PIP 生命周期真正结束时调用
    fun release() {
        Log.i(TAG, "release")
        mHandler.removeCallbacks(mLaunchRunnable)
        mPendingLaunch = null
        mInputForwarder = null
        mForwardEvent = null
        mSurfaceReady = false
        releaseDisplay()
    }

    // 触摸事件转发：优先 injectInputEvent，失败后使用 InputForwarder
    private fun forwardTouch(event: MotionEvent): Boolean {
        val targetDisplay = displayId()
        if (targetDisplay < 0) return false

        // Path 1：setDisplayId + injectInputEvent
        if (sSetDisplayId != null && sGetInputManager != null && sInjectInputEvent != null) {
            val copy = MotionEvent.obtain(event)
            try {
                sSetDisplayId.invoke(copy, targetDisplay)
                val inputManager = sGetInputManager.invoke(null)
                val result = sInjectInputEvent.invoke(inputManager, copy, 0)
                if (true == result) return true
                Log.w(TAG, "injectInputEvent returned false")
            } catch (e: InvocationTargetException) {
                val cause = e.cause
                Log.w(TAG, "injectInputEvent failed: $cause", cause ?: e)
            } catch (t: Throwable) {
                Log.w(TAG, "injectInputEvent failed", t)
            } finally {
                copy.recycle()
            }
        }

        // Path 2：InputForwarder
        ensureInputForwarder()
        val forwarder = mInputForwarder
        val method = mForwardEvent
        if (forwarder != null && method != null) {
            return try {
                val result = method.invoke(forwarder, event)
                true == result
            } catch (e: InvocationTargetException) {
                val cause = e.cause
                Log.w(TAG, "InputForwarder failed: $cause", cause ?: e)
                false
            } catch (t: Throwable) {
                Log.w(TAG, "InputForwarder failed", t)
                false
            }
        }
        return false
    }

    // 启动地图
    fun launch(packageName: String) {
        if (packageName.isEmpty()) {
            Log.w(TAG, "launch: empty package")
            return
        }
        val intent = mContext.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            Log.w(TAG, "launch intent not found: $packageName")
            return
        }

        // 保持原有 Task 状态，不使用 CLEAR_TOP
        intent.flags = 0
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_ACTIVITY_SINGLE_TOP
                or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )

        // VirtualDisplay 尚未建立
        if (displayId() < 0 || !mSurfaceReady) {
            Log.i(TAG, "display not ready, pending:$packageName")
            mPendingLaunch = intent
            return
        }

        // 如果地图已有 Task，优先搬移 Task
        if (moveStaleTask(packageName)) return

        doStart(intent, mSurfaceWidth, mSurfaceHeight)
    }

    // 找到地图现有 Task，并搬到 VirtualDisplay
    private fun moveStaleTask(packageName: String): Boolean {
        if (sActivityTaskManagerService == null) {
            Log.w(TAG, "ActivityTaskManager unavailable")
            return false
        }
        val targetDisplay = displayId()
        if (targetDisplay < 0) return false

        return try {
            val am = mContext.getSystemService(ActivityManager::class.java) ?: return false
            val tasks = am.getRunningTasks(100) ?: return false
            for (task in tasks) {
                if (task == null) continue
                val taskPackage = getTaskPackage(task)
                if (packageName != taskPackage) continue
                val taskId = task.id
                if (taskId < 0) continue
                val currentDisplay = getTaskDisplayId(task)
                Log.i(
                    TAG, "found task: id=$taskId pkg=$taskPackage" +
                        " display=$currentDisplay target=$targetDisplay"
                )
                // 已经在目标 VirtualDisplay
                if (currentDisplay == targetDisplay) {
                    Log.i(TAG, "task already on target display")
                    return true
                }
                // 只处理主屏 Task，不去碰其它真实 Display
                if (currentDisplay >= 0 && currentDisplay != Display.DEFAULT_DISPLAY) {
                    Log.i(TAG, "task is on another display, skip:$currentDisplay")
                    continue
                }

                // Android 10 ~ 15 主 API
                if (sMoveRootTaskToDisplay != null) {
                    try {
                        sMoveRootTaskToDisplay.invoke(sActivityTaskManagerService, taskId, targetDisplay)
                        Log.i(TAG, "moveRootTaskToDisplay success: task=$taskId display=$targetDisplay")
                        return true
                    } catch (e: InvocationTargetException) {
                        val cause = e.cause
                        Log.w(TAG, "moveRootTaskToDisplay failed: $cause", cause ?: e)
                    } catch (t: Throwable) {
                        Log.w(TAG, "moveRootTaskToDisplay failed", t)
                    }
                }

                // 厂商 framework fallback
                if (sMoveTaskToDisplay != null) {
                    try {
                        sMoveTaskToDisplay.invoke(sActivityTaskManagerService, taskId, targetDisplay)
                        Log.i(TAG, "moveTaskToDisplay success: task=$taskId display=$targetDisplay")
                        return true
                    } catch (e: InvocationTargetException) {
                        val cause = e.cause
                        Log.w(TAG, "moveTaskToDisplay failed: $cause", cause ?: e)
                    } catch (t: Throwable) {
                        Log.w(TAG, "moveTaskToDisplay failed", t)
                    }
                }
                Log.w(TAG, "no usable task move API")
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "moveStaleTask failed", t)
            false
        }
    }

    // 获取 Task package，优先 baseActivity
    private fun getTaskPackage(task: ActivityManager.RunningTaskInfo): String? {
        try {
            task.baseActivity?.packageName?.takeIf { it.isNotEmpty() }?.let { return it }
        } catch (ignored: Throwable) {
        }
        try {
            task.topActivity?.packageName?.let { return it }
        } catch (ignored: Throwable) {
        }
        return null
    }

    // 获取 Task displayId
    private fun getTaskDisplayId(task: ActivityManager.RunningTaskInfo): Int {
        return try {
            val field = findField(task.javaClass, "displayId")
            field?.getInt(task) ?: Display.DEFAULT_DISPLAY
        } catch (t: Throwable) {
            Log.w(TAG, "get task displayId failed", t)
            Display.DEFAULT_DISPLAY
        }
    }

    // 启动挂起的 Intent
    private fun launchPendingIfAny() {
        if (mPendingLaunch == null || !mSurfaceReady) return
        val display = displayId()
        if (display < 0) return
        val intent = mPendingLaunch ?: return
        mPendingLaunch = null
        var packageName: String? = intent.getPackage()
        if (packageName.isNullOrEmpty()) {
            intent.component?.let { packageName = it.packageName }
        }
        Log.i(TAG, "launch pending: pkg=$packageName display=$display")
        // 优先复用已有 Task
        if (!packageName.isNullOrEmpty() && moveStaleTask(packageName)) return
        doStart(intent, mSurfaceWidth, mSurfaceHeight)
    }

    // 真正启动 Activity
    private fun doStart(intent: Intent, width: Int, height: Int) {
        val targetDisplay = displayId()
        if (targetDisplay < 0) {
            Log.w(TAG, "doStart: invalid display")
            mPendingLaunch = intent
            return
        }
        val w = maxOf(width, 1)
        val h = maxOf(height, 1)

        val options = ActivityOptions.makeBasic()
        // 只设置 Window Bounds，不设置 FREEFORM
        options.setLaunchBounds(android.graphics.Rect(0, 0, w, h))
        // Activity 启动到 VirtualDisplay
        options.setLaunchDisplayId(targetDisplay)
        val bundle: Bundle = options.toBundle()

        Log.i(
            TAG,
            "startActivity: pkg=${intent.getPackage()}" +
                " component=${intent.component}" +
                " display=$targetDisplay size=${w}x$h"
        )
        try {
            mContext.startActivity(intent, bundle)
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "startActivity on virtual display failed: pkg=${intent.getPackage()}" +
                    " component=${intent.component} display=$targetDisplay",
                t
            )
        }
    }

    // 创建 / 更新 VirtualDisplay
    private fun ensureDisplay(surface: Surface?, width: Int, height: Int) {
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "ensureDisplay: invalid surface")
            return
        }
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "ensureDisplay: invalid size ${width}x$height")
            return
        }
        val densityDpi = mContext.resources.displayMetrics.densityDpi

        // 已存在 VirtualDisplay 时重新绑定 Surface
        val vd = mVd
        if (vd != null) {
            try {
                vd.surface = surface
                vd.resize(width, height, densityDpi)
                ensureInputForwarder()
                Log.i(TAG, "reuse VirtualDisplay: id=${displayId()} size=${width}x$height")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "reuse VirtualDisplay failed", t)
                try {
                    vd.release()
                } catch (ignored: Throwable) {
                }
                mVd = null
                mInputForwarder = null
                mForwardEvent = null
            }
        }

        val dm = mContext.getSystemService(DisplayManager::class.java)
        if (dm == null) {
            Log.e(TAG, "DisplayManager unavailable")
            return
        }
        // TRUSTED + OWN_CONTENT_ONLY
        val flags = VIRTUAL_DISPLAY_FLAG_TRUSTED or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        mVd = try {
            dm.createVirtualDisplay(
                "MyActivityViewVirtualDisplay",
                width,
                height,
                densityDpi,
                surface,
                flags
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed: sdk=${Build.VERSION.SDK_INT}" +
                " size=${width}x$height dpi=$densityDpi flags=$flags", t)
            null
        }
        if (mVd == null) {
            Log.e(TAG, "createVirtualDisplay returned null")
            return
        }
        Log.i(
            TAG,
            "VirtualDisplay created: sdk=${Build.VERSION.SDK_INT}" +
                " id=${displayId()} size=${width}x$height dpi=$densityDpi flags=$flags"
        )
        ensureInputForwarder()
    }

    // 初始化 InputForwarder
    private fun ensureInputForwarder() {
        if (mInputForwarder != null && mForwardEvent != null) return
        val create = sCreateInputForwarder ?: return
        val getInstance = sGetInputManager ?: return
        val display = displayId()
        if (display < 0) return
        try {
            val inputManager = getInstance.invoke(null)
            mInputForwarder = create.invoke(inputManager, display)
            if (mInputForwarder == null) {
                Log.w(TAG, "createInputForwarder returned null")
                return
            }
            // forwardEvent() 只查一次
            mForwardEvent = findMethod(mInputForwarder!!.javaClass, "forwardEvent", InputEvent::class.java)
            Log.i(TAG, "InputForwarder: display=$display method=${mForwardEvent != null}")
        } catch (t: Throwable) {
            mInputForwarder = null
            mForwardEvent = null
            Log.w(TAG, "createInputForwarder failed", t)
        }
    }

    // 完整释放 VirtualDisplay，surfaceDestroyed() 不调用这里
    private fun releaseDisplay() {
        mInputForwarder = null
        mForwardEvent = null
        val vd = mVd ?: return
        try {
            Log.i(TAG, "release VirtualDisplay: id=${displayId()}")
            vd.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release VirtualDisplay failed", t)
        } finally {
            mVd = null
        }
    }
}
