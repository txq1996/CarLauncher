package com.android.launcher37;

import android.view.MotionEvent;
import android.view.Surface;

/**
 * PipService 跨进程接口：launcher 端 ↔ :pip 进程
 *
 * 关键：
 * - attachSurface: 把 launcher 端 SurfaceView 的 Surface 传进 service 进程，
 *   service 把它绑到自己持有的 VirtualDisplay。VD 不再由 launcher 进程持有，
 *   launcher 被 force-stop / APK 替换时 service 仍在，VD 和导航任务都保留。
 * - detachSurface: launcher 主动摘 surface（不影响 VD）
 * - forwardTouch: launcher 把触摸事件交给 service 注入到虚拟 display
 * - launch: 启动/搬移指定包到 VD
 * - getDisplayId: 查询当前 displayId（用于 surfaceCreated 后状态判断）
 */
interface IPipService {
    int getDisplayId();

    /** 把 launcher 的 surface 绑到 service 持有的 VD。返回是否成功。
     *  launchDelayMs: 绑定后重新拉起当前任务前的延迟（毫秒，0=立即） */
    boolean attachSurface(in Surface surface, int width, int height, long launchDelayMs);

    /** 摘 surface（不销毁 VD） */
    void detachSurface();

    /** 启动/搬移 packageName 到 PIP VD 上（launchDelayMs: 拉起前延迟，毫秒，0=立即） */
    void launch(String packageName, long launchDelayMs);

    /** 从 launcher 端转发触摸事件到 VD（service 进程内注入） */
    boolean forwardTouch(in MotionEvent event);

    /** 将指定包的任务搬移到目标 display（用于全屏展开/收回） */
    boolean moveTaskToDisplay(String packageName, int displayId);
}
