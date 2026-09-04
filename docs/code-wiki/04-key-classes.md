# 关键类与函数说明

> Code Wiki · 04 · 关键类与函数说明

按模块整理最核心的类、职责与关键方法。

---

## 1. 入口与全局

### `LauncherApp`（[LauncherApp.kt](/workspace/app/src/main/kotlin/com/android/launcher37/LauncherApp.kt)）
- `activeHost: PageHost?`：跨 Activity 全局主页 host，`LauncherActivity.onCreate` 赋值、`onDestroy` 清空。供 `Store.launchApp` / `MemoryCleaner` / `DrawerOverlay` 查询 VD 绑定包名。
- `onCreate()`：`AmapNaviListener.start(this)` 启动高德广播全数据监听。

### `LauncherActivity`（[LauncherActivity.kt](/workspace/app/src/main/kotlin/com/android/launcher37/LauncherActivity.kt)）
`Activity`，`singleTask`。
- `onCreate`：HOME/Drawer 分发布局、装配 `PageHost`、注册 `LauncherApp.activeHost`、初始化 `UpdateDelegate`。
- 关键成员：`playingPkgs()` / `vdPkgs()`（供 `MemoryCleaner` 保护集合）、`applyStatusBarVisibility`、`exitDesign`。
- 生命周期：`onDestroy` 清 `activeHost`。

---

## 2. Widget 框架（`home/widget`）

### `PageHost`（[PageHost.kt](/workspace/app/src/main/kotlin/com/android/launcher37/home/widget/PageHost.kt)）
管理单页 widget 组，桥接 Activity 与 `WidgetHost`。
- `install(designRequested)`：加载 `LayoutRepository.loadActive` 布局，无则用默认，`rebuild(layout, designRequested)`。
- `isDesignMode` / `onDesignerExit` / `onToggleStatusBar` 回调。

### `WidgetHost`（[WidgetHost.kt](/workspace/app/src/main/kotlin/com/android/launcher37/home/widget/WidgetHost.kt)）
私有构造，单页容器。`companion.instance`：当前设计目标 host（静态，开发者用）。
- `install(layout, sw, sh, designRequested)`：归一化布局并创建全部 Widget（支持同类型多实例）。
- 碰撞检测：`collides`（双轴）/ `collidesH` / `collidesV`（单轴）/ `overlaps`（真实相交）。
- 生命周期：`startAll` / `stopAll` / `destroyAll` / `onThemeChange` / `ensureVdLaunched`。
- 设计模式：`enterDesignMode` / `exitDesignMode` / `reflow`。
- spec 修改：`updateRect` / `updateConfig`（含 `CFG_VD_PKG` 重复校验）/ `addWidget` / `removeWidget` / `findFreeSpot`。
- 外部查询：`vdBoundedPkgs()`（内存清理保护）、`expandVdToFullscreen(pkg)`（抽屉/应用列表点击 VD 同款 App 全屏搬移）。

### `WidgetView`（基类，`home/widget/WidgetView.kt`）
`View` 子类，持 `WidgetSpec`。
- `bind / destroy / start / stop / onThemeChange`：生命周期。
- `designMode`：设计模式标记（拦截触摸）。
- `applySpec` / `onPropChanged(key,value)`：Spec 应用与属性变更实时应用。
- `minSizeW/H`：最小尺寸。

### `WidgetSpec` / `WidgetTypes` / `HomeLayout` / `NamedLayout`（[WidgetSpec.kt](/workspace/app/src/main/kotlin/com/android/launcher37/home/widget/WidgetSpec.kt)）
- `WidgetSpec(id,type,x,y,w,h,visible,config)`：绝对定位数据模型。`config` 为 key→value 字符串。
- `WidgetTypes`：`time/lyrics/speed/vd/applist` 常量 + `CATALOG`（设计器添加列表）。
- `CFG_VD_PKG = "pkg"`：VD 绑定包名 key。
- `HomeLayout(version,screenWidth,screenHeight,widgets,gap,margin,hideStatusBar)`：单页完整布局。
- `NamedLayout(name,pages)`：命名布局 = 名称 + 多页。

### `WidgetProp`（[WidgetProp.kt](/workspace/app/src/main/kotlin/com/android/launcher37/home/widget/WidgetProp.kt)）
可编辑属性描述。`PropType { INT, BOOL, CHOICE, STRING, ORDER, SHOW_SIZE }`；`SHOW_SIZE` 为"开关+字号滑条"配对。

### `DesignerController`
设计控制器：选中/拖动/缩放/显隐/删除/添加；`selectionOverlay` 覆盖层；退出调 `onExit`。

### `LayoutRepository`
布局持久化：命名布局、内置模板（`BUILTIN_NAME`）、`loadActive` / `normalize` / `save`。

### 五类具体 Widget
| Widget | 职责 |
|--------|------|
| `TimeWidget` | 时间显示 |
| `LyricsWidget` | 歌词卡：`MediaHelper` + `LyricsSource` 渲染，绑定音乐 App（`MusicAppPicker`） |
| `SpeedWidget` | 车速/导航：`SpeedClient`（GPS 车速）+ `AmapNaviListener`/`NaviTextClient`（导航信息） |
| `VdWidget` | VD 应用窗：通过 `MapPipHost` 桥接 `PipService`，`boundPkg` 返回绑定包名 |
| `AppListWidget` | 应用列表/快捷启动 |

---

## 3. 导航（`navi`）

### `AmapNaviListener`（object，[AmapNaviListener.kt](/workspace/app/src/main/kotlin/com/android/launcher37/navi/AmapNaviListener.kt)）
- `start(ctx)`：注册广播；后续可 `addListener/removeListener`（主线程回调）。
- 静态缓存：`lastNaviInfo` / `lastCruiseInfo` / `lastTrafficLight` / `lastCruiseTrafficLights` / `lastTmcJson` / `lastLaneJson` / `lastIntervalSpeed` / `dayNightState`。
- 解析 KEY_TYPE：10001/60073/10019/13011/13012/12110。

### `NaviTextClient`（[NaviTextClient.kt](/workspace/app/src/main/kotlin/com/android/launcher37/navi/NaviTextClient.kt)）
- `Mode { IDLE, NAV, CRUISE }`；`NaviInfo` 聚合字段（`limitedSpeed` 等）。
- 广播看门狗：`STALE_MS=10s` 超时按退出处理。
- `turnIconRes(icon)`：高位 IC 转向类型 → 资源 id（19 个位图，日夜自动切换）。
- 限速：`LIMITED_SPEED` 缺席回退 `CAMERA_SPEED`（有效值来自 12110）。

### `SpeedClient`（[SpeedClient.kt](/workspace/app/src/main/kotlin/com/android/launcher37/navi/SpeedClient.kt)）
`ServiceConnection`。订阅 `CODE_GPS_SPEED=0x65` / `CODE_ACC_ON=0x32`；`speed==1` 归零滤噪；断线指数退避（1s→30s）。
- `start()` / `stop()`：启停与绑定管理。

### `MapPipHost`（bridge）
`attach(parent)` 绑定 `:pip` 服务；`launch(packageName)` 在 VD 启动；转发触摸。

---

## 4. PIP 服务（`pip`）

### `PipService`（[PipService.kt](/workspace/app/src/main/kotlin/com/android/launcher37/pip/PipService.kt)）
`Service`，`:pip` 进程，常驻持有 VD。`LocalBinder : IPipService.Stub`。
- `attachSurfaceToSlot(slotId, surface, w, h, launchDelayMs)`：建/复用 VD（`mSlots: HashMap<Int,Slot>`）。
- `launchToSlot(pkg, delay, slot)`：在 VD 启动/搬移任务。
- `forwardTouchToSlot` / `moveTaskToDisplay`：触摸注入与全屏搬移。
- `getSlotDisplayId(slotId)`：未创建返回 -1。

> AIDL 契约见 [IPipService.aidl](/workspace/app/src/main/aidl/com/android/launcher37/IPipService.aidl)：**方法顺序即 transaction 号，只允许末尾追加。**

---

## 5. 音乐（`music`）

### `MediaHelper`（[MediaHelper.kt](/workspace/app/src/main/kotlin/com/android/launcher37/music/MediaHelper.kt)）
- 需 `MEDIA_CONTENT_CONTROL` 特权；`getActiveSessions(null)` + 监听自动跟播。
- `start()`：初次 evaluate + 预热重评估（100ms~6s×5）+ 周期重刷 `playingPackages`；`stop()` 清理所有回调。
- 关键方法：`play(pkg)`（返回 0/1/-1）、`prev/next/togglePlay`、`getController`、`hasMediaSession`、`getPlaybackState`。
- `playingPackages: Set<String>`：保护 `MemoryCleaner`（含 `STATE_BUFFERING`，支持多 app 在播）。
- UI 回调 `UiCallback`：`onTrackChanged` / `onPlayingStateChanged` / `onProgress`。
- 进度：`position + elapsed*playbackSpeed` 推算实时位置，500ms ticker（仅播放中运行）。

### `MusicLauncher`
绑定音乐 App 冷启动后查询会话/发播放。

---

## 6. 数据 / 动作（`data`）

### `Store`（object，[Store.kt](/workspace/app/src/main/kotlin/com/android/launcher37/data/Store.kt)）
- 标识 `pkg/cls`；`pkgOf(id)` / `label` / `icon`（LRU 缓存）+ `normalizedIcon/Glyph/Split/Emoji`。
- `launchApp(c, id)`：若目标是 VDWidget 承载的 App 且任务在 VD 上，先 `expandVdToFullscreen` 全屏再启动。
- `launchSplit(c, leftId, rightId)`：发 `com.syu.splitscreenbutton` 广播 + 反射 `resizeDockedStack`（默认比例 65%）。
- 抽屉持久化：`drawerOrder` / `drawerHidden` + 保存。

### `MemoryCleaner`（object，[MemoryCleaner.kt](/workspace/app/src/main/kotlin/com/android/launcher37/data/MemoryCleaner.kt)）
- `clean(c, playingPkgs?, vdPkgs?)`：遍历 `runningAppProcesses`，uid ≥ `FIRST_APPLICATION_UID` 且非保护（自身/播放音乐/VD 承载应用）则 `forceStopPackageAsUser`（反射，需 system uid）。返回释放 MB。
- `cleanFromUi(a)`：UI 入口，从 `LauncherActivity` 取保护集合；`cleanAsync` 调度到 `SharedExecutor.io()` 异步执行（forceStop 同步 binder，避免主线程 ANR），Toast 回主线程。

### `UpdateChecker`（[UpdateChecker.kt](/workspace/app/src/main/kotlin/com/android/launcher37/data/UpdateChecker.kt)）
- `checkOnLaunch()`（24h 节流） / `checkManually()` / `confirmUpdate()` / `release()`。
- `Listener`：`onUpdateStart/Found/UpToDate/Progress/Error`。
- `data class UpdateInfo(tag,versionName,versionCode,apkUrl,notes,sizeBytes)`。
- 版本比较：`versionCode`（构建 epoch）优先，body 解析不出回退 `versionName` 字典序。
- 安装：`installViaPackageInstaller(apk)` 走 `PackageInstaller` session（规避 system uid 下 URI grant 被拒）。

### `UpdateVersion`
`compareVersionName` / `parseVersionName` / `parseVersionCodeFromBody`。

---

## 7. 抽屉（`drawer`）

- `AppDrawer.show(activity)`：建 PopupWindow + GridView + `DrawerAdapter`，点击分发 `DrawerActions.handleNormal`。
- `DrawerActions`：启动 / 内存清理（`cleanFromUi`）/ 分屏（`SplitRepository`）/ VD 相关。
- `DrawerOverlay`：全应用悬浮窗（SYSTEM_ALERT_WINDOW）。

---

## 8. 歌词数据（`home/LyricsSource.kt`）
- `LyricsProvider.fetch(artist,title,durationSec)` → `LyricsData(syncedLrc,plain,trans,songInfo)`。
- `Lyrics.loadOrFetch(ctx, provider, artist, title, durationSec)`：本地 sdcard → vkeys → lrclib（内部缓存）。
- `LrcParser.parse(lrc)`：解析 `[mm:ss.xx]` 多标签，按时间排序。
- `VkeysProvider`（QQ 音乐）/ `LrclibProvider`（兜底）。
- `SdcardMusicStore`：`/sdcard/CarLauncher/music/<歌手>/<歌名>/`（info.json / lyric.lrc / lyric.trans.lrc / cover.jpg）；`loadCover`。
- `LyricsCache`：`filesDir/lyrics/<key>.lrc`（lrclib 兜底缓存）。

---

## 9. 工具（`util`）
- `MainThread.handler`：主线程 Handler。
- `SharedExecutor.io()`：2 线程 fixed pool，全局唯一 IO 池（**禁止自建 Executor**）。
- `Prefs.of(c)` / `Prefs.FILE`：SP 封装。
- `FormatUtils` / `HoloPopup` / `IconCache` / `IconNormalizer` / `Dbg` / `SysProps` / `WrapRecyclerView`。