# 包结构与文件清单

> Code Wiki · 03 · 包结构与源码文件清单

## 1. 目录总览

```
/workspace
├── app/
│   └── src/main/
│       ├── aidl/                      # AIDL 接口（跨进程契约）
│       │   ├── com/android/launcher37/IPipService.aidl
│       │   └── com/syu/ipc/           # 车机 IPC（IRemoteToolkit/IRemoteModule/IModuleCallback/ModuleObject）
│       ├── kotlin/com/android/launcher37/   # 全部 Kotlin 源码
│       ├── kotlin/com/syu/ipc/ModuleObject.kt  # 车机模块对象
│       ├── res/                       # 主资源（drawable/values/drawable-nodpi/drawable-night-nodpi）
│       ├── res-home/layout/           # 主页布局 activity_main.xml
│       ├── res-widget/layout/         # 各 Widget 卡片布局（card_*.xml, widget_applist.xml）
│       ├── res-drawer/layout/         # 抽屉布局
│       ├── res-settings/layout/       # 设置页布局
│       └── AndroidManifest.xml
├── gradle/                            # Gradle wrapper
├── .github/workflows/release.yml      # CI：build + R8 + 推 GitHub Releases
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/build.gradle.kts               # 应用构建配置
├── app/proguard-rules.pro
├── privapp-permissions-com.android.launcher37.xml   # Privileged Permission Denylist 白名单
├── build.bat
├── gradlew / gradlew.bat
└── README.md
```

## 2. 源码清单（`app/src/main/kotlin/`）

### `com.android.launcher37`（根包）
| 文件 | 说明 |
|------|------|
| `LauncherApp.kt` | Application / 全局 activeHost |
| `LauncherActivity.kt` | 主 Activity |
| `SettingsActivity.kt` | 设置页 |

### `data/`
AppQuery · MemoryCleaner · SplitRepository · Store · UpdateChecker · UpdateVersion

### `drawer/`
AppDrawer · DrawerActions · DrawerAdapter · DrawerOverlay · DrawerService · DrawerStats

### `home/`
LyricsSource · UpdateDelegate

### `home/widget/`
AppListWidget · DesignerController · LayoutRepository · LyricsWidget · MusicAppPicker ·
PageHost · SpeedWidget · TimeWidget · VdWidget · WidgetHost · WidgetProp · WidgetSpec · WidgetView

### `music/`
MediaHelper · MusicLauncher

### `navi/`
AmapNaviListener · MapActions · MapApps · MapFeature · MapPipHost · NaviOrder · NaviTextClient · SpeedClient

### `pip/`
PipService

### `util/`
Dbg · FormatUtils · HoloPopup · IconCache · IconNormalizer · MainThread · Prefs · SharedExecutor · SysProps · WrapRecyclerView

## 3. 资源分层约定

layout 按模块拆分到独立资源根，各目录内为标准 `res` 结构（`layout/` 子目录）；
`values` / `drawable` 等仍留在主 `res`。构建时在 [app/build.gradle.kts](/workspace/app/build.gradle.kts) 的 `sourceSets` 中合并：

```kotlin
res.srcDirs(
    "src/main/res",
    "src/main/res-home",
    "src/main/res-widget",
    "src/main/res-drawer",
    "src/main/res-settings"
)
```

- **`res-drawable-nodpi/` vs `drawable-night-nodpi/`**：导航转向图标（`navinfo_icon2..20.png`）。
  日/夜双色由资源系统自动切换（等价原运行时 `SRC_IN` 染色），见 `NaviTextClient.turnIconRes`。
- **`res-widget/`** 卡片：`card_lyrics` / `card_speed` / `card_time` / `card_vd` / `widget_applist` / `item_order_row`。

## 4. 资源命名速查（主 `res/`）

| 分类 | 说明 |
|------|------|
| `values/colors.xml` `styles.xml` `themes.xml` + `values-night/` | 主题/颜色，日夜切换 |
| `drawable/bg_*.xml` | 背景/容器（按钮、抽屉、PIP 框、弹窗面板） |
| `drawable/checkbox_px*.xml` | 像素风复选框 |
| `drawable/ic_*.xml` | 图标（drawer/layout/pause/play/prev/next/close/check） |
| `drawable/seekbar_px*.xml` `progress_music.xml` | 进度条/滑条 |
| `drawable/design_handle.xml` `design_sel_stroke.xml` | 设计模式手柄/选中框 |
| `drawable-nodpi/launcher_bk.png` | 应用图标/背景 |

## 5. AIDL

- `IPipService.aidl`：多槽位 VD 契约（attach/launch/forwardTouch/moveTaskToDisplay + Slot 系列）。
- `com.syu/ipc/`：`IModuleCallback`（update 回调）、`IRemoteModule`、`IRemoteToolkit`、`ModuleObject`。

> ⚠️ method 顺序即 transaction 号，只允许在末尾追加，禁止改动既有方法。