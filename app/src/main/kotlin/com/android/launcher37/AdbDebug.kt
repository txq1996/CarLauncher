package com.android.launcher37

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * ADB 调试入口。
 *
 * 由 [BuildConfig.ADB_DEBUG] 控制：
 * - 本地构建（debug / `assembleRelease` 默认）：true，[tryStartIfEnabled] 起 HTTP server
 * - CI 走 `assembleRelease -PminifyRelease=true`：false，入口直接 return，
 *   R8 shrink 剔除全部反射/HTTP/输入注入代码
 *
 * 行为细节不写入公开源码注释；本地开发者请参见私有调试笔记。
 */
object AdbDebug {

    private const val TAG = "AdbDebug"
    private const val HTTP_PORT = 10837

    // ── 反射核心 ────────────────────────────────────────────

    /**
     * 处理单个反射操作。`cmd` ∈ help/list/dump/get/set/call。
     *
     * 参数语义：
     * - help：无参
     * - list：pkg 可空
     * - dump：klass 必填
     * - get：klass + name（字段或 0-arg 静态方法）
     * - set：klass + name + value（按字段类型 coerce）
     * - call：klass + name + target? + types:Array + args:Array
     *
     * 返回人类可读字符串（多行）。出错返回 `"ERR: <msg>"`。
     */
    fun dispatch(
        cmd: String, klass: String?, name: String?, value: String?,
        target: String?, types: Array<String>, args: Array<String>, pkg: String?
    ): String {
        return try {
            when (cmd) {
                "help" -> doHelp()
                "list" -> doList(pkg)
                "dump" -> doDump(klass, target)
                "get" -> doGet(klass, name, target)
                "set" -> doSet(klass, name, value)
                "call" -> doCall(klass, name, target, types, args)
                else -> "ERR: unknown cmd '$cmd', supported: help,list,dump,get,set,call"
            }
        } catch (t: Throwable) {
            "ERR: $cmd failed: ${rootCauseMessage(t)}"
        }
    }

    /** 已知可调类（按调试需求列出常用入口；用户传任意 FQN 也走 `Class.forName`） */
    private val KNOWN_CLASSES = listOf(
        "android.app.ActivityManager",
        "android.content.pm.PackageManager",
        "android.os.SystemProperties",
        "android.os.ServiceManager",
        "com.android.launcher37.LauncherApp",
        "com.android.launcher37.LauncherActivity",
        "com.android.launcher37.SettingsActivity",
        "com.android.launcher37.Prefs",
        "com.android.launcher37.MediaHelper",
        "com.android.launcher37.MusicLauncher",
        "com.android.launcher37.UpdateChecker",
        "com.android.launcher37.SysProps",
        "com.android.launcher37.SharedExecutor",
        "com.android.launcher37.Store",
        "com.android.launcher37.IconCache",
        "com.android.launcher37.IconNormalizer",
        "com.android.launcher37.AppQuery",
        "com.android.launcher37.MapApps",
        "com.android.launcher37.MapActions",
        "com.android.launcher37.HoloPopup",
        "com.android.launcher37.MainThread",
        "com.android.launcher37.NaviTextClient",
        "com.android.launcher37.AmapNaviListener",
        "com.android.launcher37.MapFeature",
        "com.android.launcher37.NaviOrder",
        "com.android.launcher37.NumberPickerView",
        "com.android.launcher37.SplitRepository",
    )

    private fun doHelp(): String = buildString {
        appendLine("supported cmds:")
        appendLine("  help                       list all commands")
        appendLine("  list[?pkg=...]             list known classes (filter by prefix)")
        appendLine("  dump?klass=<fqn>           dump fields/methods + static field values")
        appendLine("  get?klass=&name=           read static field or 0-arg method")
        appendLine("  set?klass=&name=&value=    write static field (coerced to type)")
        appendLine("  call?klass=&name=&target=&types=a,b&args=x,y   call method")
        appendLine("  /layout[?depth=N]          dump current Activity view tree (class/id/text/bounds)")
        appendLine("  /input/tap?x=&y=           inject tap (screen px, 1280x720)")
        appendLine("  /input/tap-by-id?id=       inject tap at center of view with resourceId")
        appendLine("  /input/tap-by-text?text=   inject tap at center of view containing text")
        appendLine("  /input/swipe?x1=&y1=&x2=&y2=&ms=200   inject swipe (default 200ms)")
        appendLine("  /input/keyevent?code=      inject key event (KeyEvent.KEYCODE_* int)")
        appendLine("  /sp/get?key=&type=         read Prefs key (type: string|int|long|bool|float)")
        appendLine("  /sp/put?key=&value=&type=  write Prefs key (type: string|int|long|bool|float)")
        appendLine("  /exec?cmd=<shell>          run shell command (split by space); combine with grep=. Use instead of /pid /dumpsys /logcat /activity/start when more flexible shell is needed")
        appendLine("HTTP: GET /... on 0.0.0.0:$HTTP_PORT (loopback and LAN, no auth)")
    }

    private fun doList(pkg: String?): String {
        val filtered = if (pkg.isNullOrEmpty()) KNOWN_CLASSES
        else KNOWN_CLASSES.filter { it.startsWith(pkg) }
        return "list pkg='${pkg ?: ""}' count=${filtered.size}:\n  " +
            filtered.joinToString("\n  ")
    }

    private fun doDump(klass: String?, target: String?): String {
        val cls = resolveClass(klass) ?: return "ERR: missing or invalid klass"
        val inst = resolveTarget(cls, target)
        val useInstance = inst != null
        return buildString {
            appendLine("dump ${cls.name}${if (useInstance) " (instance: ${inst!!.javaClass.name})" else ""}:")
            appendLine("  [fields]")
            for (f in cls.declaredFields) {
                f.isAccessible = true
                val mods = modifierString(f.modifiers)
                val value = when {
                    java.lang.reflect.Modifier.isStatic(f.modifiers) ->
                        try { fmt(f.get(null)) } catch (t: Throwable) { "<${rootCauseMessage(t)}>" }
                    useInstance ->
                        try { fmt(f.get(inst)) } catch (t: Throwable) { "<${rootCauseMessage(t)}>" }
                    else -> "<instance>"
                }
                appendLine("    $mods ${f.type.simpleName} ${f.name} = $value")
            }
            appendLine("  [methods]")
            for (m in cls.declaredMethods) {
                val params = m.parameterTypes.joinToString { it.simpleName }
                appendLine("    ${modifierString(m.modifiers)} ${m.returnType.simpleName} ${m.name}($params)")
            }
        }
    }

    private fun doGet(klass: String?, name: String?, target: String?): String {
        val cls = resolveClass(klass) ?: return "ERR: get needs name=<field|method>"
        if (name.isNullOrEmpty()) return "ERR: get needs name=<field|method>"
        val inst = resolveTarget(cls, target)
        val useInstance = inst != null
        if (useInstance) {
            val f = findField(cls, name)
            if (f != null) {
                return try {
                    f.isAccessible = true
                    "get ${cls.name}.$name (instance field) = ${fmt(f.get(inst))}"
                } catch (t: Throwable) {
                    "ERR: get instance field failed: ${rootCauseMessage(t)}"
                }
            }
            val m0 = findZeroArgMethod(cls, name)
                ?: return "ERR: get: no field or 0-arg method '$name' on ${cls.name}"
            return try {
                m0.isAccessible = true
                "get ${cls.name}.$name() (instance method) = ${fmt(m0.invoke(inst))}"
            } catch (t: Throwable) {
                "ERR: get instance method failed: ${rootCauseMessage(t)}"
            }
        }
        val f = findField(cls, name)
        if (f != null) {
            return try {
                f.isAccessible = true
                "get ${cls.name}.$name (static field) = ${fmt(f.get(null))}"
            } catch (t: Throwable) {
                "ERR: get static field failed: ${rootCauseMessage(t)}"
            }
        }
        val m = findStaticMethod(cls, name, arrayOf<Class<*>>())
            ?: return "ERR: get: no static field or 0-arg method '$name' on ${cls.name}"
        return try {
            m.isAccessible = true
            "get ${cls.name}.$name() = ${fmt(m.invoke(null))}"
        } catch (t: Throwable) {
            "ERR: get method failed: ${rootCauseMessage(t)}"
        }
    }

    private fun findZeroArgMethod(cls: Class<*>, name: String): Method? =
        cls.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }

    private fun doSet(klass: String?, name: String?, value: String?): String {
        val cls = resolveClass(klass) ?: return "ERR: missing or invalid klass"
        if (name.isNullOrEmpty()) return "ERR: set needs name=<field>"
        if (value == null) return "ERR: set needs value=<v>"
        val f = findField(cls, name) ?: return "ERR: set: no field '$name' on ${cls.name}"
        return try {
            f.isAccessible = true
            val coerced = coerce(value, f.type)
                ?: return "ERR: set: cannot coerce '$value' to ${f.type.name}"
            f.set(null, coerced)
            "set ${cls.name}.$name = ${fmt(coerced)} (${f.type.simpleName})"
        } catch (t: Throwable) {
            "ERR: set failed: ${rootCauseMessage(t)}"
        }
    }

    private fun doCall(
        klass: String?, name: String?, target: String?,
        types: Array<String>, args: Array<String>
    ): String {
        val cls = resolveClass(klass) ?: return "ERR: missing or invalid klass"
        if (name.isNullOrEmpty()) return "ERR: call needs name=<method>"
        if (types.size != args.size) return "ERR: types.size=${types.size} != args.size=${args.size}"
        val argTypes = Array(types.size) { resolveType(types[it]) }
        val m = findMethod(cls, name, argTypes)
            ?: return "ERR: call: no method '$name(${argTypes.joinToString { it.simpleName }})' on ${cls.name}"
        return try {
            m.isAccessible = true
            val coerced = Array(args.size) { coerce(args[it], m.parameterTypes[it]) }
            val instance = resolveTarget(cls, target)
            val ret = m.invoke(instance, *coerced)
            "call ${cls.name}.$name(${coerced.joinToString { fmt(it) }}) -> ${fmt(ret)}"
        } catch (t: Throwable) {
            "ERR: call failed: ${rootCauseMessage(t)}"
        }
    }

    /**
     * 反射调用 `get/set/invoke` 时的 `InvocationTargetException` 包了实际抛错，
     * unwrap 拿到 root cause 才能告诉用户真正出了什么。
     */
    private fun rootCauseMessage(t: Throwable): String {
        val cause = if (t is InvocationTargetException) t.cause ?: t else t
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun resolveClass(klass: String?): Class<*>? {
        if (klass.isNullOrEmpty()) return null
        return try { Class.forName(klass) } catch (_: Throwable) { null }
    }

    private fun findField(cls: Class<*>, name: String): Field? =
        cls.declaredFields.firstOrNull { it.name == name }

    private fun findStaticMethod(cls: Class<*>, name: String, types: Array<Class<*>>): Method? =
        cls.declaredMethods.firstOrNull { m ->
            java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.name == name && m.parameterTypes.contentEquals(types)
        }

    private fun findMethod(cls: Class<*>, name: String, types: Array<Class<*>>): Method? =
        cls.declaredMethods.firstOrNull { m ->
            m.name == name && m.parameterTypes.contentEquals(types)
        }

    private fun resolveTarget(cls: Class<*>, target: String?): Any? {
        if (target.isNullOrEmpty()) return null
        // 魔术 target：$app / $activity / $ctx
        // 不走 INSTANCE 反射，直接拿进程内常见单例
        when (target) {
            "\$app" -> return currentApplication()
            "\$activity" -> {
                val a = currentActivity()
                Log.i(TAG, "resolveTarget \$activity -> ${a?.javaClass?.name}")
                return a
            }
            "\$ctx" -> return currentApplication()
        }
        // 普通 target：FQN，期望该类有静态 INSTANCE 字段（Kotlin object 模式）
        val targetCls = try { Class.forName(target) } catch (_: Throwable) { return null }
        val inst = try {
            targetCls.getDeclaredField("INSTANCE").get(null)
        } catch (_: Throwable) { return null }
        return if (targetCls.isAssignableFrom(cls)) inst else null
    }

    private fun resolveType(name: String): Class<*> = when (name) {
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "String" -> String::class.java
        "Object" -> Any::class.java
        else -> try { Class.forName(name) } catch (_: Throwable) { Any::class.java }
    }

    private fun coerce(value: Any?, type: Class<*>): Any? {
        if (value == null) return null
        if (type.isInstance(value)) return value
        val nonPrim = if (type.isPrimitive) wrap(type) else type
        if (nonPrim.isInstance(value)) return value
        val str = value.toString()
        return when {
            nonPrim == String::class.java -> str
            nonPrim == Int::class.java -> str.toIntOrNull()
            nonPrim == Long::class.java -> str.toLongOrNull()
            nonPrim == Boolean::class.java -> str.toBooleanStrictOrNull()
            nonPrim == Double::class.java -> str.toDoubleOrNull()
            nonPrim == Float::class.java -> str.toFloatOrNull()
            nonPrim == Byte::class.java -> str.toByteOrNull()
            nonPrim == Short::class.java -> str.toShortOrNull()
            nonPrim == Char::class.java -> str.firstOrNull()
            nonPrim == Class::class.java -> try { Class.forName(str) } catch (_: Throwable) { null }
            else -> try { nonPrim.getMethod("valueOf", String::class.java).invoke(null, str) }
                catch (_: Throwable) { value }
        }
    }

    private fun wrap(primitive: Class<*>): Class<*> = when (primitive) {
        Int::class.javaPrimitiveType -> Int::class.java
        Long::class.javaPrimitiveType -> Long::class.java
        Boolean::class.javaPrimitiveType -> Boolean::class.java
        Double::class.javaPrimitiveType -> Double::class.java
        Float::class.javaPrimitiveType -> Float::class.java
        Byte::class.javaPrimitiveType -> Byte::class.java
        Short::class.javaPrimitiveType -> Short::class.java
        Char::class.javaPrimitiveType -> Char::class.java
        else -> primitive
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> "null"
        is Array<*> -> v.joinToString(prefix = "[", postfix = "]", transform = ::fmt)
        else -> v.toString()
    }

    private fun modifierString(m: Int): String = buildString {
        if (java.lang.reflect.Modifier.isPublic(m)) append("public") else append("private")
        if (java.lang.reflect.Modifier.isStatic(m)) append(" static")
        if (java.lang.reflect.Modifier.isFinal(m)) append(" final")
        if (java.lang.reflect.Modifier.isAbstract(m)) append(" abstract")
        if (java.lang.reflect.Modifier.isSynchronized(m)) append(" synchronized")
    }

    // ── 输入注入（InputManager.injectInputEvent，system uid 满足 INJECT_EVENTS） ──

    /**
     * 注入一次 tap。`x`/`y` 屏幕坐标（px，720p 桌面坐标系与 view 树 bounds 一致）。
     * 通过反射 `InputManager.injectInputEvent` 走 hidden 路径，不经过 shell，
     * 单次 < 30ms。
     */
    fun injectTap(x: Int, y: Int): String {
        val down = motionEventAt(x, y, android.view.MotionEvent.ACTION_DOWN, 0L)
        val up = motionEventAt(x, y, android.view.MotionEvent.ACTION_UP, 30L)
        return try {
            injectInput(down, sync = false)
            injectInput(up, sync = false)
            "tap $x,$y"
        } catch (t: Throwable) {
            "ERR: tap failed: ${rootCauseMessage(t)}"
        } finally {
            // MotionEvent.obtain 必须配对 recycle()，否则 native buffer 泄漏
            runCatching { down.recycle() }
            runCatching { up.recycle() }
        }
    }

    /**
     * 在视图树中找第一个 `resourceId == id` 的 view，计算其中心点并 tap。
     * `id` 可以是 `android:id/content` 这种全名，也可只给 `content` 后缀。
     */
    fun injectTapById(id: String): String {
        val target = id.trim()
        if (target.isEmpty()) return "ERR: id required"
        val root = currentRootView() ?: return "ERR: no activity / root view"
        val view = findViewByResourceId(root, target) ?: return "ERR: no view with id '$target'"
        return tapCenterOf(view, "id=$target")
    }

    /**
     * 在视图树中找第一个 `TextView.text == text` 的 view，计算其中心点并 tap。
     * 空文本或未命中都返回 ERR。
     */
    fun injectTapByText(text: String): String {
        if (text.isEmpty()) return "ERR: text required"
        val root = currentRootView() ?: return "ERR: no activity / root view"
        val view = findViewByText(root, text) ?: return "ERR: no view with text '$text'"
        return tapCenterOf(view, "text=\"$text\"")
    }

    private fun tapCenterOf(v: android.view.View, desc: String): String {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val x = loc[0] + v.width / 2
        val y = loc[1] + v.height / 2
        return injectTap(x, y).let { if (it.startsWith("ERR")) it else "$it (at $desc [$x,$y])" }
    }

    /**
     * 注入一次 swipe。从 (x1,y1) 平移到 (x2,y2)，持续 `ms` 毫秒。
     */
    fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): String {
        val events = ArrayList<android.view.MotionEvent>(8)
        events += motionEventAt(x1, y1, android.view.MotionEvent.ACTION_DOWN, 0L)
        val steps = (ms / 16).coerceIn(2, 30)
        for (i in 1..steps) {
            val frac = i.toFloat() / steps
            val x = (x1 + (x2 - x1) * frac).toInt()
            val y = (y1 + (y2 - y1) * frac).toInt()
            events += motionEventAt(x, y, android.view.MotionEvent.ACTION_MOVE, (ms * i / steps).toLong())
        }
        events += motionEventAt(x2, y2, android.view.MotionEvent.ACTION_UP, ms.toLong())
        return try {
            events.forEach { injectInput(it, sync = false) }
            "swipe ($x1,$y1)->($x2,$y2) ${ms}ms"
        } catch (t: Throwable) {
            "ERR: swipe failed: ${rootCauseMessage(t)}"
        } finally {
            // MotionEvent.obtain 必须配对 recycle()，否则 native buffer 泄漏
            events.forEach { runCatching { it.recycle() } }
        }
    }

    /**
     * 注入一次 key event。`code` 是 `KeyEvent.KEYCODE_*` 整数（如 4=BACK, 3=HOME,
     * 24=DPAD_UP, 26=DPAD_DOWN）。默认发送 DOWN+UP 一对。
     */
    fun injectKey(code: Int): String = try {
        val now = android.os.SystemClock.uptimeMillis()
        injectInput(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0),
            sync = true
        )
        injectInput(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0),
            sync = true
        )
        "keyevent code=$code action=down+up"
    } catch (t: Throwable) {
        "ERR: keyevent failed: ${rootCauseMessage(t)}"
    }

    /**
     * 构造一个 MotionEvent。`eventTimeOffsetMs` 相对 downTime 的偏移（ms），最终
     * 转成 `eventTime`（uptimeMillis+offset）。offset=0 用于 DOWN，30+ 用于 UP 模拟点击节奏。
     */
    private fun motionEventAt(
        x: Int, y: Int, action: Int, eventTimeOffsetMs: Long
    ): android.view.MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        return android.view.MotionEvent.obtain(
            now, now + eventTimeOffsetMs, action, x.toFloat(), y.toFloat(), 0
        )
    }

    /**
     * 反射 `android.hardware.input.InputManager.injectInputEvent` (hidden @SystemApi)。
     * `InputManager` 是公开类，但 `getInstance()` 静态方法 + `injectInputEvent` 是 hidden；
     * 入口实际桥到 `InputManagerGlobal`。
     * system uid + `INJECT_EVENTS` 权限自动满足。
     */
    private fun injectInput(ev: android.view.InputEvent, sync: Boolean) {
        val cls = android.hardware.input.InputManager::class.java
        val inst = cls.getMethod("getInstance").invoke(null)
        val m = cls.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java, Int::class.javaPrimitiveType
        )
        // 0 = INPUT_EVENT_INJECTION_SYNC_NONE, 2 = SYNC_WAIT_FOR_FINISH
        m.invoke(inst, ev, if (sync) 2 else 0)
    }

    // ── View 树查找（按 resourceId / text 定位） ──────────────

    /**
     * 取当前 Activity 的 content root view；找不到时返回 null。
     * 跨进程 view 不可见（其他 app 的 view 在自己进程内），本进程只看到 LauncherActivity。
     */
    private fun currentRootView(): android.view.View? {
        val activity = currentActivity() ?: return null
        return activity.findViewById<android.view.View?>(android.R.id.content)?.rootView
            ?: activity.window?.decorView
    }

    /**
     * 序列化当前 foreground Activity 的 view 树（class / resourceId / text / bounds /
     * clickable / visibility）。`depth` 默认 4，最大 10 防 DoS。
     */
    fun dumpLayout(depth: Int): String = try {
        val root = currentRootView() ?: return "ERR: no activity / root view"
        val maxDepth = depth.coerceIn(1, 10)
        val sb = StringBuilder()
        appendNode(sb, root, 0, maxDepth)
        sb.toString()
    } catch (t: Throwable) {
        "ERR: layout failed: ${rootCauseMessage(t)}"
    }

    /** `depth` 视为最大可见层数（depth=3 表示打印 level 0/1/2/3 共 4 行） */
    private fun appendNode(sb: StringBuilder, v: android.view.View, level: Int, maxDepth: Int) {
        if (level > maxDepth) return
        val indent = "  ".repeat(level)
        val cls = v.javaClass.simpleName
        val id = try {
            if (v.id == android.view.View.NO_ID) ""
            else {
                val name = if (v.id == android.R.id.content) "android:id/content"
                else try { v.resources.getResourceEntryName(v.id) } catch (_: Throwable) { "0x${Integer.toHexString(v.id)}" }
                "#$name"
            }
        } catch (_: Throwable) { "" }
        val text = when (v) {
            is android.widget.TextView -> " text=\"${v.text}\""
            else -> ""
        }
        val r = android.graphics.Rect()
        @Suppress("DEPRECATION")
        v.getHitRect(r)
        val bounds = " [${r.left},${r.top},${r.right},${r.bottom}]"
        val click = if (v.isClickable) " clickable" else ""
        val vis = if (v.visibility != android.view.View.VISIBLE) " gone" else ""
        sb.append(indent).append(cls).append(id).append(bounds).append(click).append(vis).append(text).append('\n')
        if (v is android.view.ViewGroup && level < maxDepth) {
            for (i in 0 until v.childCount) appendNode(sb, v.getChildAt(i), level + 1, maxDepth)
        }
    }

    /**
     * 深度优先找 `id` 匹配的 view。`id` 可以是：
     * - 全名如 `android:id/content`
     * - 短名如 `content`（命中第一个同短名的 view）
     */
    private fun findViewByResourceId(root: android.view.View, id: String): android.view.View? {
        if (matchesId(root, id)) return root
        if (root !is android.view.ViewGroup) return null
        for (i in 0 until root.childCount) {
            val found = findViewByResourceId(root.getChildAt(i), id)
            if (found != null) return found
        }
        return null
    }

    private fun matchesId(v: android.view.View, id: String): Boolean {
        if (v.id == android.view.View.NO_ID) return false
        if (id.contains(':')) {
            // 全名匹配：直接比对 `resources.getResourceEntryName` + 已知常量
            val resName = when (v.id) {
                android.R.id.content -> "android:id/content"
                else -> try { v.resources.getResourceEntryName(v.id) } catch (_: Throwable) { return false }
            }
            return resName == id || resName.endsWith(":$id")
        }
        // 短名：直接用资源 entry name
        return try { v.resources.getResourceEntryName(v.id) == id } catch (_: Throwable) { false }
    }

    /**
     * 深度优先找第一个 `TextView` 且 `text == target` 的 view。
     */
    private fun findViewByText(root: android.view.View, target: String): android.view.View? {
        if (root is android.widget.TextView && root.text?.toString() == target) return root
        if (root !is android.view.ViewGroup) return null
        for (i in 0 until root.childCount) {
            val found = findViewByText(root.getChildAt(i), target)
            if (found != null) return found
        }
        return null
    }

    // ── SharedPreferences 读写（Prefs） ─────────────────────

    /**
     * 读 Prefs key。`type` 决定调用哪个 `getXxx`：string|int|long|bool|float。
     * 不指定 type 时按 string 读。读出后转字符串。
     */
    fun spGet(ctx: Context, key: String, type: String): String {
        if (key.isEmpty()) return "ERR: key required"
        val sp = Prefs.of(ctx)
        return try {
            when (type) {
                "int" -> "int: ${sp.getInt(key, 0)}"
                "long" -> "long: ${sp.getLong(key, 0L)}"
                "bool" -> "bool: ${sp.getBoolean(key, false)}"
                "float" -> "float: ${sp.getFloat(key, 0f)}"
                else -> "string: ${sp.getString(key, null) ?: "null"}"
            }
        } catch (t: Throwable) {
            "ERR: ${rootCauseMessage(t)}"
        }
    }

    /**
     * 写 Prefs key。`type` ∈ string|int|long|bool|float。
     * 拒绝 `adb_debug_enabled` 写入 —— server 关闭后只能从 UI 重新打开，
     * 否则会自锁（server 跑着才能写 SP，但写完 SP 关闭 server）。
     */
    fun spPut(ctx: Context, key: String, value: String, type: String): String {
        if (key.isEmpty()) return "ERR: key required"
        if (key == SettingsActivity.KEY_ADB_DEBUG) {
            return "ERR: writing adb_debug_enabled via /sp/put is forbidden (use Settings UI)"
        }
        val ed = Prefs.of(ctx).edit()
        val ok = when (type) {
            "string" -> ed.putString(key, value).apply().let { true }
            "int" -> ed.putInt(key, value.toIntOrNull() ?: return "ERR: not an int").let { true }
            "long" -> ed.putLong(key, value.toLongOrNull() ?: return "ERR: not a long").let { true }
            "bool" -> ed.putBoolean(key, value.toBooleanStrictOrNull() ?: return "ERR: not a bool").let { true }
            "float" -> ed.putFloat(key, value.toFloatOrNull() ?: return "ERR: not a float").let { true }
            else -> return "ERR: type must be string|int|long|bool|float"
        }
        if (ok) ed.apply()
        return "sp.put $key = $value ($type)"
    }

    // ── 远程 shell（替代 /pid /dumpsys /logcat /activity/start） ─────────────────────────

    /**
     * 远程 shell 执行入口。`cmd` 按空格 split 喂给 ProcessBuilder；可选 `grep`
     * 关键字过滤输出（首 200 行）。system uid 进程无鉴权，等同 adb shell 权限：
     * pidof / dumpsys / logcat / am start / settings / pm / wm 都能跑。
     * 替代旧的 /pid /dumpsys /logcat /activity/start 四个路由：
     *   /exec?cmd=pidof+com.x                        → 同 /pid
     *   /exec?cmd=dumpsys+activity+activities&grep=topResumedActivity
     *   /exec?cmd=logcat+-d+-t+50+-s+PipService&clear=0  → 需先 /exec?cmd=logcat+-c 清空
     *   /exec?cmd=am+start+-n+com.x/.Y+-f+0x10008000 → 同 /activity/start
     * 输出可能挂 worker 线程（无 soTimeout），与原 /logcat /dumpsys 行为对齐。
     */
    fun execShell(cmd: String, grep: String): String {
        if (cmd.isEmpty()) return "ERR: cmd required"
        val args = cmd.split(' ').filter { it.isNotEmpty() }
        if (args.isEmpty()) return "ERR: cmd empty after split"
        val text = try {
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            try {
                proc.inputStream.bufferedReader().readText()
            } finally {
                try { proc.inputStream.close() } catch (_: Throwable) {}
                try { proc.waitFor() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            return "ERR: $cmd failed: ${rootCauseMessage(t)}"
        }
        val lines = text.lineSequence()
        val filtered = if (grep.isEmpty()) lines else lines.filter { it.contains(grep) }
        val out = filtered.take(200).joinToString("\n")
        return if (out.isEmpty()) "(no output from $cmd)" else out
    }

    /**
     * 取当前 foreground Activity。system uid 下可反射 `ActivityThread.currentActivityThread()`
     * 然后从 `mActivities` 拿一个未 finish/destroy 的 Activity（顺序不定，**最佳努力**）。
     * 多数场景下只有一个 Activity，未 finish 即代表前台。
     */
    private fun currentActivity(): android.app.Activity? {
        val threadCls = Class.forName("android.app.ActivityThread")
        val thread = threadCls.getMethod("currentActivityThread").invoke(null)
        val activitiesField = threadCls.getDeclaredField("mActivities").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val activities = activitiesField.get(thread) as android.util.ArrayMap<*, *>
        for ((_, record) in activities) {
            try {
                val activityField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val activity = activityField.get(record) as? android.app.Activity ?: continue
                if (!activity.isFinishing && !activity.isDestroyed) return activity
            } catch (_: Throwable) { }
        }
        return null
    }

    /**
     * 取当前 Application 实例。`ActivityThread.currentApplication()` 是 hidden，
     * 通过反射调用。
     */
    private fun currentApplication(): Context = try {
        val threadCls = Class.forName("android.app.ActivityThread")
        val thread = threadCls.getMethod("currentActivityThread").invoke(null)
        threadCls.getMethod("getApplication").invoke(thread) as Context
    } catch (_: Throwable) {
        // 兜底：在 activity 已死的极端情况下用不到 sp/activity 路由
        Log.w(TAG, "currentApplication() failed, sp/activity routes will fail")
        object : android.content.ContextWrapper(null) {}
    }

    // ── 启用开关 ─────────────────────────────────────────────

    /** 当前开关状态（从 SP 读）。默认 true。 */
    fun enabled(ctx: Context): Boolean =
        Prefs.of(ctx).getBoolean(SettingsActivity.KEY_ADB_DEBUG, true)

    // ── HTTP server ──────────────────────────────────────────

    @Volatile private var serverSocket: ServerSocket? = null

    /**
     * 启动 HTTP server（幂等）。开关关闭时不启，已启则关。
     * 监听 `0.0.0.0:10837`（同网段任意设备可访问，无鉴权），每连接一个
     * `ServerSocket.accept()` 后扔 `SharedExecutor.io()`。
     */
    @Synchronized
    fun tryStartIfEnabled(ctx: Context) {
        if (!BuildConfig.ADB_DEBUG) return
        if (enabled(ctx)) {
            if (serverSocket == null) startHttp()
        } else {
            stopHttp()
        }
    }

    @Synchronized
    private fun startHttp() {
        try {
            // 0.0.0.0 = 全部接口。同 WiFi 网段任意设备可直连，无鉴权（按用户要求）。
            val sock = ServerSocket(HTTP_PORT, 50, java.net.InetAddress.getByName("0.0.0.0"))
            serverSocket = sock
            val t = Thread({
                while (!sock.isClosed) {
                    val client = try { sock.accept() } catch (_: Throwable) { break }
                    SharedExecutor.io().execute { handleHttp(client) }
                }
            }, "AdbDebug-Http").apply { isDaemon = true }
            t.start()
            Log.i(TAG, "http listening on 0.0.0.0:$HTTP_PORT (no auth)")
        } catch (t: Throwable) {
            Log.w(TAG, "http start failed: ${t.message}")
        }
    }

    @Synchronized
    fun stopHttp() {
        val sock = serverSocket ?: return
        serverSocket = null
        try { sock.close() } catch (_: Throwable) {}
        Log.i(TAG, "http stopped")
    }

    /**
     * 单连接处理：读 1 个 HTTP request（GET），dispatch 到反射，回复纯文本。
     * 不支持 keep-alive；不解析 header 大小，硬截断 8 KiB 防 DoS。
     */
    private fun handleHttp(client: Socket) {
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            // 读 request line + headers（最多 8 KiB）
            val sb = StringBuilder()
            var total = 0
            while (total < 8192) {
                val line = reader.readLine() ?: break
                total += line.length + 1
                sb.append(line).append('\n')
                if (line.isEmpty()) break
            }
            val requestLine = sb.toString().lineSequence().firstOrNull() ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2 || parts[0] != "GET") {
                writeResponse(client, 405, "text/plain", "method not allowed")
                return
            }
            val path = parts[1]
            val queryIdx = path.indexOf('?')
            val route = if (queryIdx < 0) path else path.substring(0, queryIdx)
            val params = if (queryIdx < 0) emptyMap()
            else parseQuery(path.substring(queryIdx + 1))
            val ctx: Context = currentApplication()
            val body: String = when (route) {
                "/help" -> dispatch("help", null, null, null, null, emptyArray(), emptyArray(), null)
                "/list" -> dispatch("list", null, null, null, null, emptyArray(), emptyArray(), params["pkg"])
                "/dump" -> dispatch("dump", params["klass"], null, null, params["target"], emptyArray(), emptyArray(), null)
                "/get" -> dispatch("get", params["klass"], params["name"], null, params["target"], emptyArray(), emptyArray(), null)
                "/set" -> dispatch("set", params["klass"], params["name"], params["value"],
                    null, emptyArray(), emptyArray(), null)
                "/call" -> {
                    val types = params["types"]?.split(',')?.toTypedArray() ?: emptyArray()
                    val args = params["args"]?.split(',')?.toTypedArray() ?: emptyArray()
                    dispatch("call", params["klass"], params["name"], null, params["target"], types, args, null)
                }
                "/layout" -> {
                    val depth = params["depth"]?.toIntOrNull() ?: 4
                    dumpLayout(depth)
                }
                "/input/tap" -> {
                    val x = params["x"]?.toIntOrNull() ?: return replyBadRequest(client, "x required")
                    val y = params["y"]?.toIntOrNull() ?: return replyBadRequest(client, "y required")
                    injectTap(x, y)
                }
                "/input/tap-by-id" -> {
                    val id = params["id"] ?: return replyBadRequest(client, "id required")
                    injectTapById(id)
                }
                "/input/tap-by-text" -> {
                    val text = params["text"] ?: return replyBadRequest(client, "text required")
                    injectTapByText(text)
                }
                "/input/swipe" -> {
                    val x1 = params["x1"]?.toIntOrNull() ?: return replyBadRequest(client, "x1 required")
                    val y1 = params["y1"]?.toIntOrNull() ?: return replyBadRequest(client, "y1 required")
                    val x2 = params["x2"]?.toIntOrNull() ?: return replyBadRequest(client, "x2 required")
                    val y2 = params["y2"]?.toIntOrNull() ?: return replyBadRequest(client, "y2 required")
                    val ms = params["ms"]?.toIntOrNull() ?: 200
                    injectSwipe(x1, y1, x2, y2, ms)
                }
                "/input/keyevent" -> {
                    val code = params["code"]?.toIntOrNull() ?: return replyBadRequest(client, "code required")
                    injectKey(code)
                }
                "/sp/get" -> {
                    val key = params["key"] ?: return replyBadRequest(client, "key required")
                    val type = params["type"] ?: "string"
                    spGet(ctx, key, type)
                }
                "/sp/put" -> {
                    val key = params["key"] ?: return replyBadRequest(client, "key required")
                    val value = params["value"] ?: return replyBadRequest(client, "value required")
                    val type = params["type"] ?: "string"
                    spPut(ctx, key, value, type)
                }
                "/exec" -> {
                    val cmd = params["cmd"] ?: return replyBadRequest(client, "cmd required")
                    execShell(cmd, params["grep"].orEmpty())
                }
                "/" -> "AdbDebug HTTP at 0.0.0.0:$HTTP_PORT\nTry GET /help"
                else -> "ERR: unknown route '$route'"
            }
            writeResponse(client, 200, "text/plain; charset=utf-8", body)
        } catch (h: BadRequestSentinel) {
            // replyBadRequest 已发 400，正常路径
        } catch (t: Throwable) {
            try { writeResponse(client, 500, "text/plain", "ERR: ${t.message}") } catch (_: Throwable) {}
        } finally {
            try { client.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 写 400 + 抛 sentinel 让 `?: return replyBadRequest(...)` 同时具备：
     * 1) `?:` 两侧类型对齐（Nothing 透传）
     * 2) handleHttp 外层 `catch (h: BadRequestSentinel)` 静默吞掉
     */
    private fun replyBadRequest(client: Socket, msg: String): Nothing {
        try { writeResponse(client, 400, "text/plain", "ERR: $msg") } catch (_: Throwable) {}
        throw BadRequestSentinel()
    }

    /** 内部 sentinel：throw 表示"已发响应、handleHttp 该 return" */
    private class BadRequestSentinel : Throwable() {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun writeResponse(client: Socket, code: Int, contentType: String, body: String) {
        val resp = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(
                if (code == 200) "OK" else if (code == 405) "Method Not Allowed" else "Internal Server Error"
            ).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
        client.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
        client.getOutputStream().flush()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split('&').associate { kv ->
            val idx = kv.indexOf('=')
            val k = if (idx < 0) kv else kv.substring(0, idx)
            val v = if (idx < 0) "" else kv.substring(idx + 1)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}