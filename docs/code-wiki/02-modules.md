# 主要模块职责

> Code Wiki · 02 · 主要模块职责

按包划分，说明每个模块的职责、核心对象与其交互。

## 1. `com.android.launcher37` — 入口与全局

| 文件 | 职责 |
|------|------|
| `LauncherApp.kt` | `Application` 单例。跨 Activity 持有 `activeHost: PageHost?`；`onCreate` 启动 `AmapNaviListener` |
| `LauncherActivity.kt` | 主 Activity（`launchMode=singleTask`，`HOME` + `LAUNCHER` intent-filter）。装配 `PageHost`、`UpdateDelegate`、VD，处理状态栏切换/设计器退出 |
| `SettingsActivity.kt` | 设置页（应用排序/隐藏、字号、检查更新等） |

## 2. `navi` — 导航 / 地图 / 车速

> **统一入口**：`AmapNaviListener`（object，全局静态）+ `NaviTextClient`（也独立收广播）。

| 文件 | 职责 |
|------|------|
| `AmapNaviListener.kt` | object。监听 `AUTONAVI_STANDARD_BROADCAST_SEND`，解析 10001/60073/10019/13011/13012/12110，缓存车速/红绿灯/路名/限速/转向/电子眼/服务区/TMC/车道线/区间测速。静态字段 + `addListener` 订阅 |
| `NaviTextClient.kt` | 高德文字信息客户端（移植自 launcher36）。解析 10001/12110/10019/60021，模式机 `IDLE/NAV/CRUISE`，含广播看门狗（异常退出检测）、转向图标映射 `turnIconRes` |
| `SpeedClient.kt` | GPS 车速 IPC 客户端，`bindService("com.syu.ms.toolkit")` 订阅 `U_GPS_SPEED(0x65)` / `U_ACC_ON(0x32)`，断线指数退避重连 |
| `MapPipHost.kt` | 把 launcher 端 `SurfaceView` 桥接到 `:pip` 进程的 `PipService`，支持多槽位 VD、触摸转发、任务搬移/全屏 |
| `MapActions.kt` | 地图相关动作聚合（启动地图/导航等） |
| `MapApps.kt` | 地图 App 元数据/候选列表 |
| `MapFeature.kt` / `NaviOrder.kt` | 地图功能开关 / 导航指令排序 |

## 3. `pip` — 独立进程 VirtualDisplay

| 文件 | 职责 |
|------|------|
| `PipService.kt` | `:pip` 进程 `Service`。创建/持有多个 `VirtualDisplay`（槽位），实现 `IPipService.Stub`：`attachSurfaceToSlot`、`launchToSlot`、`forwardTouchToSlot`、`moveTaskToDisplay` 等；注入触摸、搬移任务 |

## 4. `home` — 桌面 Widget 框架与更新

| 文件 | 职责 |
|------|------|
| `widget/PageHost.kt` | 管理单页 widget 组；加载布局、rebuild、设计模式入口、layout 持久化 |
| `widget/WidgetHost.kt` | 单页 widget 容器：装配/生命周期转发/碰撞检测/设计模式切换/spec 修改/VD 查询 |
| `widget/WidgetView.kt` | Widget 基类（`View` 子类），持有 `WidgetSpec`，生命周期/`designMode`/触摸手势 |
| `widget/DesignerController.kt` | 设计模式控制器：选中、拖动、缩放、显隐、删除、添加 overlay |
| `widget/LayoutRepository.kt` | 布局持久化（命名布局/内置模板/多页）、`normalize` |
| `widget/TimeWidget.kt` / `LyricsWidget.kt` / `SpeedWidget.kt` / `VdWidget.kt` / `AppListWidget.kt` | 五类具体 Widget |
| `widget/WidgetProp.kt` / `WidgetSpec.kt` | 可编辑属性描述 / Widget 数据模型 |
| `widget/MusicAppPicker.kt` | 歌词卡绑定音乐 App 的选配器 |
| `LyricsSource.kt` | 歌词/歌曲信息数据层（见下） |
| `UpdateDelegate.kt` | 更新 UI 委托，桥接 `UpdateChecker` 与设置页 |

### `LyricsSource.kt` 歌词数据流

取词顺序：**本地 `/sdcard/CarLauncher/music/` → vkeys（QQ 音乐）→ lrclib 兜底**。
`LyricsProvider`（fetch）/ `LrcParser`（LRC 解析）/ `VkeysProvider` / `LrclibProvider` /
`SdcardMusicStore`（落盘持久化）/ `LyricsCache`（app 内部缓存）。

## 5. `music` — 音乐播放控制

| 文件 | 职责 |
|------|------|
| `MediaHelper.kt` | `MediaSessionManager.getActiveSessions` 跟播、500ms 进度 ticker、元数据/播放状态回调、`playingPackages`（供内存清理保护）。提供 `play/next/prev/togglePlay/getController/hasMediaSession` |
| `MusicLauncher.kt` | 绑定音乐 App 冷启动后查询会话/发起播放 |

## 6. `data` — 数据 / 工具动作

| 文件 | 职责 |
|------|------|
| `Store.kt` | PackageManager 工具 + 启动动作（app/split）+ 抽屉排序/隐藏持久化。应用标识统一 `pkg/cls`，label/icon LRU 缓存 + `IconNormalizer` 归一化 |
| `AppQuery.kt` | 可启动应用查询 |
| `MemoryCleaner.kt` | 后台进程清理：`forceStopPackageAsUser`（反射），保护自身/播放音乐/PIP 地图；`cleanFromUi` 提供 UI 入口 |
| `SplitRepository.kt` | 分屏配置持久化 |
| `UpdateChecker.kt` | GitHub Releases 自动更新客户端（见下） |
| `UpdateVersion.kt` | 版本号解析/比较（versionCode 优先，versionName 字典序兜底） |

### `UpdateChecker` 更新链路

GitHub API `releases/latest` → 解析 `UpdateInfo` → 版本比较 → 挂起 `mPending` 待确认 →
`confirmUpdate` 下载到 `cacheDir/update.apk` → `PackageInstaller` session 安装。
24h 自动检查节流；手动检查不受限；`PackageInstaller`（非 URI grant，规避 system uid 限制）。

## 7. `drawer` — 应用抽屉

| 文件 | 职责 |
|------|------|
| `AppDrawer.kt` | 全应用弹窗入口，GridView + `DrawerAdapter` |
| `DrawerAdapter.kt` | 抽屉 Grid 适配 |
| `DrawerActions.kt` | 抽屉项点击动作分发（启动/内存清理/分屏/VD） |
| `DrawerOverlay.kt` | 全应用悬浮窗（`SYSTEM_ALERT_WINDOW`） |
| `DrawerService.kt` | 抽屉/split 服务容器 |
| `DrawerStats.kt` | 统计信息 |

## 8. `util` — 平台工具

| 文件 | 职责 |
|------|------|
| `MainThread.kt` | 主线程 `Handler` 统一出口 |
| `SharedExecutor.kt` | 共享 IO 线程池（2 线程 fixed pool），全局禁止自建 Executor |
| `Prefs.kt` | `SharedPreferences` 封装（`Prefs.FILE`） |
| `FormatUtils.kt` | 格式化工具 |
| `IconCache.kt` / `IconNormalizer.kt` | 图标缓存 / 归一化（日夜底色自适应、`normalizedSplitIcon`） |
| `HoloPopup.kt` | Holo 风格弹窗 |
| `Dbg.kt` | 调试输出 |
| `SysProps.kt` | 系统属性读取 |
| `WrapRecyclerView.kt` | 支持测量包装的 RecyclerView（设置页列表） |

## 9. `com.syu.ipc` — 车机 IPC AIDL 适配

- `ModuleObject.kt`：`com.syu.ms` 车机模块对象封装。
- AIDL：`IRemoteToolkit` / `IRemoteModule` / `IModuleCallback`（`SpeedClient` 依赖）。

## 10. AIDL 跨进程契约

- `IPipService.aidl`：launcher ↔ `:pip` 进程（多槽位 VD）。见 [04-key-classes](/workspace/docs/code-wiki/04-key-classes.md)。
- `com.syu.ipc/*.aidl`：与 `com.syu.ms` 车机服务通信。