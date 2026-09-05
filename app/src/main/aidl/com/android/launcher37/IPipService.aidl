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
 * * - forwardTouch: launcher 把触摸事件交给 service 注入到虚拟 display
 * - launch: 启动/搬移指定包到 VD
 * - getDisplayId: 查询当前 displayId（用于 surfaceCreated 后状态判断）
 *
 * 多槽位（slotId）：slot 0 = launcher 主地图卡（既有方法全部等价于 slot 0）；
 * slot ≥1 = 布局设计器/预览的 VD 卡片（每卡一个独立 VirtualDisplay）。
 * AIDL 方法顺序即 transaction 号：只允许在末尾追加，禁止改动既有方法。
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

    // ── 多槽位扩展（slot 0 = 主地图卡，与上述方法等价）──────────

    /** 把 surface 绑到指定槽位独立持有的 VD（每个槽位一个 VirtualDisplay） */
    boolean attachSurfaceToSlot(int slotId, in Surface surface, int width, int height, long launchDelayMs);

    /** 摘指定槽位的 surface（不销毁该槽位 VD） */
    void detachSurfaceSlot(int slotId);

    /** 启动/搬移 packageName 到指定槽位的 VD */
    void launchToSlot(String packageName, long launchDelayMs, int slotId);

    /** 转发触摸事件到指定槽位的 VD */
    boolean forwardTouchToSlot(int slotId, in MotionEvent event);

    /** 查询指定槽位 VD 的 displayId（未创建返回 -1） */
    int getSlotDisplayId(int slotId);

    /** 把所有槽位 VD 上的任务移回主屏（升级前调用，保证 Activity 不中断） */
    void moveAllTasksToDefault();
}
