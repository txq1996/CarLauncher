# AGENTS.md

CarLauncher（`com.android.launcher37`）车载桌面 Launcher 项目的 AI 开发速查。
本文档用于让 AI / 开发者快速理解结构与约定，避免踩坑。

详细文档见 [docs/code-wiki/README.md](/workspace/docs/code-wiki/README.md)。

---

## 1. 项目是什么

面向 **1280×720 Android 车载中控** 的 **HOME 启动器**，以 **system app**（`sharedUserId=android.uid.system`
+ platform key）运行，获取系统级特权。核心能力：

- PIP 地图悬浮窗（`VirtualDisplay` 多槽位，独立 `:pip` 进程承载）
- 自由画布 Widget 桌面（时间 / 歌词 / 车速导航 / VD 应用窗 / 应用列表）
- 音乐卡（MediaSession）
- Dock + 应用抽屉
- 后台进程清理（forceStop）
- 应用内 GitHub Releases 更新

技术栈：**Kotlin + Android SDK**（compileSdk 34 / minSdk 28 / targetSdk 29 / Java 17），Gradle 构建。

## 2. 目录速览

```
app/src/main/
├── aidl/com/android/launcher37/   IPipService.aidl（多槽位 VD 契约）
├── aidl/com/syu/ipc/              车机 IPC（IRemoteToolkit 等，SpeedClient 依赖）
├── kotlin/com/android/launcher37/
│   ├── LauncherApp.kt              Application / 全局 activeHost
│   ├── LauncherActivity.kt         主 HOME Activity
│   ├── SettingsActivity.kt         设置页
│   ├── home/widget/                Widget 框架（PageHost/WidgetHost/WidgetView/Designer/5类Widget）
│   ├── home/                       LyricsSource（歌词数据层）、UpdateDelegate
│   ├── navi/                       AmapNaviListener、NaviTextClient、SpeedClient、MapPipHost…
│   ├── pip/                        PipService（:pip 独立进程）
│   ├── music/                      MediaHelper、MusicLauncher
│   ├── data/                       Store、MemoryCleaner、UpdateChecker、AppQuery…
│   ├── drawer/                     AppDrawer、DrawerActions、DrawerOverlay…
│   └── util/                       MainThread、SharedExecutor、Prefs、IconNormalizer…
├── res / res-home / res-widget / res-drawer / res-settings   资源分层
└── AndroidManifest.xml
```

## 3. 关键架构事实

- **多进程**：VD + 导航任务在 `PipService`（`:pip`）进程，launcher 强杀/升级导航不中断。
  launcher 侧 `MapPipHost` 经 AIDL 把 `SurfaceView.Surface` 交给 service。
- **全局入口**：`LauncherApp.activeHost: PageHost?`；Activity `onCreate` 赋值、`onDestroy` 清空。
  无 Activity 上下文的调用方（`Store`/`MemoryCleaner`/`DrawerOverlay`）靠它查询 VD 绑定包名。
- **导航数据非侵入**：`AmapNaviListener` 与 `NaviTextClient` 各自 `registerReceiver` 监听同一
  `AUTONAVI_STANDARD_BROADCAST_SEND` 广播，可共存。
- **应用标识**：统一 `pkg/cls` 字符串。启动/图标/标签/排序/隐藏都基于它。
- **线程模型**：IO/网络一律走 `SharedExecutor.io()`；UI 走 `MainThread.handler`。

## 4. 硬性约定（改动前必读）

1. **AIDL 方法顺序即 transaction 号** —— `IPipService`（多槽位）与 `com.syu.ipc` 只允许在
   末尾追加方法，**禁止改动/删除既有方法**。
2. **IO 统一用 `SharedExecutor.io()`**，**禁止自建 Executor**（`newSingleThreadExecutor` 等）。
   网络检查 + 下载 + 安装等异步逻辑须调度到它，UI 回调经 `MainThread.handler`。
3. **system uid 特权依赖**：静默安装走 `PackageInstaller` session（勿改回
   `ACTION_INSTALL_PACKAGE + content URI`，system uid 下被 UriGrantsManager 拒绝）。
   内存清理反射 `forceStopPackageAsUser`（勿退化为普通 `killBackgroundProcesses`）。
4. **导航不中断**：VD/导航进程归属 `PipService`，不要把 VD 相关逻辑改回 launcher 进程持有。
5. **同页同 App 的 VD 重复绑定被禁止**（`WidgetHost.isVdPkgTaken` 校验）。
6. **`MediaHelper` 进度 ticker 仅播放中运行**，暂停/无会话零轮询（性能约定）。
7. **资源分层**：layout 按模块放 `res-home/res-widget/res-drawer/res-settings`，`values/drawable`
   留在主 `res`。转向图标日夜切换靠 `drawable-nodpi` / `drawable-night-nodpi` 同名资源。

## 5. 常见任务切入点

| 想改什么 | 从哪开始 |
|----------|----------|
| 桌面布局 / 新增 Widget 类型 | `home/widget/WidgetHost.kt`（装配）、`WidgetSpec.kt`（`WidgetTypes`）、新建 `*Widget.kt` 继承 `WidgetView`、`res-widget/layout` |
| 设计模式（拖/缩放/增删） | `home/widget/DesignerController.kt` + `WidgetHost` spec 修改方法 |
| 导航面板 / 限速 / 转向图标 | `navi/NaviTextClient.kt` + `navi/AmapNaviListener.kt` |
| GPS 车速 | `navi/SpeedClient.kt` |
| 地图悬浮窗 / VD | `home/widget/VdWidget.kt` + `navi/MapPipHost.kt` + `pip/PipService.kt` + `IPipService.aidl` |
| 音乐播放 / 歌词 | `music/MediaHelper.kt` + `home/LyricsSource.kt` + `home/widget/LyricsWidget.kt` |
| 后台清理保护规则 | `data/MemoryCleaner.kt` |
| 应用内更新 | `data/UpdateChecker.kt` + `home/UpdateDelegate.kt` |
| 抽屉 / 排序 / 隐藏 | `drawer/*` + `data/Store.kt` |
| 设置页 | `SettingsActivity.kt`（`res-settings/layout`） |

## 6. 构建 / 测试 / 运行

```bash
./gradlew assembleRelease                 # 本地构建（默认不 R8，便于调试）
./gradlew assembleRelease -PminifyRelease=true   # CI 用，完整 R8
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/release/app-release.apk
```

- `versionCode` = 构建 epoch 秒（单调）；`versionName` = YYYYMMDD（北京时间）。
- 需 `keys/keystore.jks`（platform key，alias/password `android/android`）签名。

## 7. 运行前提（真机/车机）

- 必须以 system app 装入系统镜像（sharedUserId=system + platform key + privapp 白名单）。
- 车机 GPS 车速依赖 `com.syu.ms`（缺失时 `SpeedClient` 退避重连，不空转）。
- 导航数据依赖高德地图广播；歌词依赖 vkeys / lrclib 网络。

## 8. 质量 / 风格

- 避免过度设计：优先改现有文件，不建多余抽象/文档。
- 不改未读过行为的代码；说明改动意图。
- 涉及跨模块/跨进程改动时，先读对应 AIDL 与依赖方，保持后端契约不破坏。