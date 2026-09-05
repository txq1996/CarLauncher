package com.android.launcher37.pip
import com.android.launcher37.IPipService
import com.android.launcher37.navi.MapPipHost
import com.android.launcher37.util.MainThread
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
 * 多槽位：slot 0 = launcher 主地图卡（既有行为不变）；slot ≥1 = 设计器/预览
 * 的 VD 卡片，每槽位独立 VirtualDisplay + Surface + InputForwarder + 当前任务。
 *
 * 反射 API（InputManager / ActivityTaskManager）跟 [MapPipHost] 同源，
 * 全部走 system uid 隐式权限。
 */
class PipService : Service() {

    companion object {
        private const val TAG = "PipService"
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
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

        // A10+ per-display focus：VD 上的应用窗口会把输入焦点带去 VD 显示器，
        // 主屏（桌面）随之收不到任何触摸（A9 无 per-display focus 不受影响）。
        // 探测 setFocusedDisplay 用于把焦点归还主屏：
        // 优先客户端 InputManager 实例方法，回退 IInputManager binder 代理（mIm 字段）。
        private val sFocusTarget: Any?
        private val sSetFocusedDisplay: Method?

        // ActivityManager hidden API（跨 display 任务搬移）
        private val sActivityTaskManagerService: Any?
        private val sMoveStackToDisplay: Method?
        private val sMoveTaskToBack: Method?

        init {
            val intClass = Int::class.javaPrimitiveType!!
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

            // 焦点归还能力探测（A9 无 setFocusedDisplay → null，调用侧自动跳过）
            var focusTarget: Any? = null
            var focusMethod: Method? = null
            if (sGetInputManager != null) {
                try {
                    val im = sGetInputManager.invoke(null)
                    focusMethod = findMethod(
                        android.hardware.input.InputManager::class.java,
                        "setFocusedDisplay", intClass
                    )
                    if (focusMethod != null) focusTarget = im
                } catch (t: Throwable) {
                    Log.w(TAG, "probe client setFocusedDisplay failed", t)
                }
                if (focusMethod == null) {
                    try {
                        val im = sGetInputManager.invoke(null)
                        val f = android.hardware.input.InputManager::class.java
                            .getDeclaredField("mIm")
                        f.isAccessible = true
                        val proxy = f.get(im)
                        focusMethod = findMethod(proxy.javaClass, "setFocusedDisplay", intClass)
                        if (focusMethod != null) focusTarget = proxy
                    } catch (t: Throwable) {
                        Log.w(TAG, "probe IInputManager.setFocusedDisplay failed", t)
                    }
                }
            }
            sFocusTarget = focusTarget
            sSetFocusedDisplay = focusMethod

            Log.i(
                TAG,
                "API: sdk=${Build.VERSION.SDK_INT}" +
                    " setDisplayId=${sSetDisplayId != null}" +
                    " setDisplayIdField=${sSetDisplayIdField != null}" +
                    " inject=${sInjectInputEvent != null}" +
                    " forwarder=${sCreateInputForwarder != null}" +
                    " moveStack=${sMoveStackToDisplay != null}" +
                    " moveBack=${sMoveTaskToBack != null}" +
                    " setFocusedDisplay=${sSetFocusedDisplay != null}"
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

    /** 一个槽位 = 独立 VirtualDisplay + Surface + 输入转发 + 当前任务 */
    private inner class Slot(val id: Int) {
        var vd: VirtualDisplay? = null
        var surface: Surface? = null
        var surfaceWidth = 0
        var surfaceHeight = 0
        var inputForwarder: Any? = null
        var forwardEvent: Method? = null
        var currentPkg: String? = null
        var pendingLaunch: String? = null

        val launchRunnable = Runnable {
            val pkg = pendingLaunch ?: return@Runnable
            pendingLaunch = null
            if (displayId() < 0) return@Runnable
            if (!moveStaleTask(pkg, displayId())) doStart(this, pkg)
            // VD 应用窗口就位后会抢走输入焦点（A10+ per-display focus），
            // 主屏桌面随之收不到触摸：分三档延迟把焦点归还主屏，
            // 覆盖应用冷/热启动的时序差异（A9 探测为空自动跳过）
            for (delay in longArrayOf(600, 2500, 5000)) {
                mHandler.postDelayed({ focusBackToDefaultDisplay() }, delay)
            }
        }

        fun displayId(): Int = try { vd?.display?.displayId ?: -1 } catch (t: Throwable) { -1 }
    }

    private val mSlots = HashMap<Int, Slot>()
    private fun slot(id: Int): Slot = synchronized(mSlots) { mSlots.getOrPut(id) { Slot(id) } }

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
        synchronized(mSlots) {
            for (s in mSlots.values) releaseDisplay(s)
            mSlots.clear()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    inner class LocalBinder : IPipService.Stub() {
        override fun getDisplayId(): Int = slotDisplayId(0)

        override fun attachSurface(surface: Surface?, width: Int, height: Int, launchDelayMs: Long): Boolean =
            attachSurfaceToSlot(0, surface, width, height, launchDelayMs)

        override fun detachSurface() = detachSurfaceSlot(0)

        override fun launch(packageName: String?, launchDelayMs: Long) =
            launchToSlot(packageName, launchDelayMs, 0)

        override fun forwardTouch(event: MotionEvent?): Boolean = forwardTouchToSlot(0, event)

        override fun moveTaskToDisplay(packageName: String?, displayId: Int): Boolean {
            if (packageName.isNullOrEmpty()) return false
            return moveTaskToTargetDisplay(packageName, displayId)
        }

        override fun attachSurfaceToSlot(slotId: Int, surface: Surface?, width: Int, height: Int, launchDelayMs: Long): Boolean {
            Log.i(TAG, "attachSurface slot=$slotId w=$width h=$height valid=${surface?.isValid}")
            if (surface == null) {
                Log.w(TAG, "attachSurface: null surface")
                return false
            }
            if (width <= 0 || height <= 0) return false
            val s = slot(slotId)
            synchronized(s) {
                // 释放旧 surface（防重复 attach 泄漏）；detachSurfaceSlot 也会走这里
                val old = s.surface
                if (old != null && old !== surface) {
                    try { old.release() } catch (t: RuntimeException) {
                        Log.w(TAG, "release old surface failed", t)
                    }
                }
                s.surface = surface
                s.surfaceWidth = width
                s.surfaceHeight = height
                ensureDisplay(s, surface, width, height)
            }
            if (s.currentPkg != null) {
                s.pendingLaunch = s.currentPkg
                mHandler.removeCallbacks(s.launchRunnable)
                if (launchDelayMs > 0) mHandler.postDelayed(s.launchRunnable, launchDelayMs)
                else mHandler.post(s.launchRunnable)
            }
            return true
        }

        override fun detachSurfaceSlot(slotId: Int) {
            Log.i(TAG, "detachSurface slot=$slotId")
            val s = slot(slotId)
            mHandler.removeCallbacks(s.launchRunnable)
            synchronized(s) {
                val surf = s.surface
                if (surf != null) {
                    try { surf.release() } catch (ignored: Throwable) {}
                }
                s.surface = null
                s.vd?.let {
                    try { it.surface = null } catch (t: Throwable) {
                        Log.w(TAG, "detach VD surface failed", t)
                    }
                }
                s.inputForwarder = null
                s.forwardEvent = null
            }
        }

        override fun launchToSlot(packageName: String?, launchDelayMs: Long, slotId: Int) {
            if (packageName.isNullOrEmpty()) {
                Log.w(TAG, "launch: empty package")
                return
            }
            val s = slot(slotId)
            s.currentPkg = packageName
            if (s.displayId() < 0) {
                s.pendingLaunch = packageName
                return
            }
            mHandler.removeCallbacks(s.launchRunnable)
            if (moveStaleTask(packageName, s.displayId())) return
            s.pendingLaunch = packageName
            if (launchDelayMs > 0) mHandler.postDelayed(s.launchRunnable, launchDelayMs)
            else mHandler.post(s.launchRunnable)
        }

        override fun forwardTouchToSlot(slotId: Int, event: MotionEvent?): Boolean {
            if (event == null) return false
            return injectTouch(slot(slotId), event)
        }

        override fun getSlotDisplayId(slotId: Int): Int = slotDisplayId(slotId)
    }

    private fun slotDisplayId(slotId: Int): Int = slot(slotId).displayId()

    private fun ensureDisplay(s: Slot, surface: Surface?, width: Int, height: Int) {
        if (surface == null || !surface.isValid) return
        if (width <= 0 || height <= 0) return
        val densityDpi = resources.displayMetrics.densityDpi
        val vd = s.vd
        if (vd != null) {
            try {
                vd.surface = surface
                vd.resize(width, height, densityDpi)
                ensureInputForwarder(s)
                Log.i(TAG, "reuse VD slot=${s.id}: id=${s.displayId()} ${width}x$height")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "reuse VD failed, recreate", t)
                try { vd.release() } catch (ignored: Throwable) {}
                s.vd = null
                s.inputForwarder = null
                s.forwardEvent = null
            }
        }
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val flags = if (Build.VERSION.SDK_INT >= 29)
            VIRTUAL_DISPLAY_FLAG_TRUSTED or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        else 0
        s.vd = try {
            dm.createVirtualDisplay("${VD_NAME}#slot${s.id}", width, height, densityDpi, surface, flags)
        } catch (t: SecurityException) {
            Log.e(TAG, "createVirtualDisplay denied (uid=${Process.myUid()})", t)
            null
        } catch (t: RuntimeException) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            null
        }
        if (s.vd == null) {
            Log.e(TAG, "createVirtualDisplay returned null")
            return
        }
        Log.i(TAG, "VD created slot=${s.id}: sdk=${Build.VERSION.SDK_INT} id=${s.displayId()} ${width}x$height dpi=$densityDpi")
        ensureInputForwarder(s)
    }

    private fun ensureInputForwarder(s: Slot) {
        if (s.inputForwarder != null && s.forwardEvent != null) return
        val create = sCreateInputForwarder ?: return
        val getInstance = sGetInputManager ?: return
        val display = s.displayId()
        if (display < 0) return
        try {
            val im = getInstance.invoke(null)
            s.inputForwarder = create.invoke(im, display) ?: return
            s.forwardEvent = findMethod(
                s.inputForwarder!!.javaClass, "forwardEvent", InputEvent::class.java
            )
            Log.i(TAG, "InputForwarder slot=${s.id}: display=$display method=${s.forwardEvent != null}")
        } catch (t: Throwable) {
            s.inputForwarder = null
            s.forwardEvent = null
            Log.w(TAG, "createInputForwarder failed", t)
        }
    }

    private fun injectTouch(s: Slot, event: MotionEvent): Boolean {
        val display = s.displayId()
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
        ensureInputForwarder(s)
        val fwd = s.inputForwarder
        val m = s.forwardEvent
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
     * - [stackMoveOnlyToDefault] = true 时仅在目标为主屏时才走 moveStackToDisplay
     *   （moveStaleTask 场景：目标=VD，反射跨屏移到 VD 在 Android 9 上会触发
     *   部分 app force-finish，必须跳过、交由 doStart 的 setLaunchDisplayId 拉回）
     * - [allowMoveToBack] = true 时主屏目标额外尝试 moveTaskToBack（moveTaskToTargetDisplay 场景）
     * 返回 true 表示已找到并处理（无需再 startActivity）。
     */
    private fun findAndMoveTask(packageName: String, targetDisplay: Int, stackMoveOnlyToDefault: Boolean, allowMoveToBack: Boolean): Boolean {
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
                // moveStackToDisplay：搬到主屏时安全；跨 display 移到 VD 在 Android 9 上
                // 会触发部分 app force-finish —— moveStaleTask（目标=VD）跳过此路径，
                // 由调用方 doStart 的 setLaunchDisplayId(VD) 安全拉回。
                if (sMoveStackToDisplay != null && (!stackMoveOnlyToDefault || targetDisplay == Display.DEFAULT_DISPLAY)) {
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

    private fun moveStaleTask(packageName: String, targetDisplay: Int): Boolean =
        findAndMoveTask(packageName, targetDisplay, stackMoveOnlyToDefault = true, allowMoveToBack = false)

    private fun moveTaskToTargetDisplay(packageName: String, targetDisplay: Int): Boolean =
        findAndMoveTask(packageName, targetDisplay, stackMoveOnlyToDefault = false, allowMoveToBack = true)

    /**
     * A10+ per-display focus：把输入焦点归还主屏（A9 无此 API，探测为空时静默跳过）。
     * VD 拉起应用后其窗口会抢走输入焦点，主屏桌面随之收不到任何触摸
     * （弹窗点击、桌面交互全部失效），启动后必须显式归还。
     */
    private fun focusBackToDefaultDisplay() {
        val m = sSetFocusedDisplay ?: return
        val target = sFocusTarget ?: return
        try {
            m.invoke(target, Display.DEFAULT_DISPLAY)
            Log.i(TAG, "setFocusedDisplay -> default(0)")
        } catch (t: Throwable) {
            Log.w(TAG, "setFocusedDisplay failed", t)
        }
    }

    private fun doStart(s: Slot, packageName: String) {
        val targetDisplay = s.displayId()
        if (targetDisplay < 0) {
            s.pendingLaunch = packageName
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
        val w = maxOf(s.surfaceWidth, 1)
        val h = maxOf(s.surfaceHeight, 1)
        val options = ActivityOptions.makeBasic()
        options.setLaunchBounds(android.graphics.Rect(0, 0, w, h))
        options.setLaunchDisplayId(targetDisplay)
        Log.i(TAG, "startActivity slot=${s.id}: pkg=$packageName display=$targetDisplay size=${w}x$h")
        try {
            startActivity(intent, options.toBundle())
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity on VD failed", t)
        }
    }

    private fun releaseDisplay(s: Slot) {
        synchronized(s) {
            s.inputForwarder = null
            s.forwardEvent = null
            val surf = s.surface
            if (surf != null) {
                try { surf.release() } catch (ignored: Throwable) {}
                s.surface = null
            }
            val vd = s.vd ?: return
            try {
                Log.i(TAG, "release VD slot=${s.id}: id=${s.displayId()}")
                vd.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release VD failed", t)
            } finally {
                s.vd = null
            }
        }
    }
}
