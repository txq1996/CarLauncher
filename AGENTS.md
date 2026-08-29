# CarLauncher (Kotlin)

Android 车载桌面。包名 `com.android.launcher37`、应用名 `CarLauncher`（manifest `android:label`）。

## 规则

- **改完代码**：跑 `gradlew.bat assembleRelease` 编译（**本地默认不混淆 + 启 AdbDebug**），install 到 **所有** 已连接 adb 设备（不止一台）。**CI 不参与 adb install** —— GitHub Actions 只 build + test + 推 GitHub Release，模拟器/真机部署是开发者本地动作（GitHub Actions 网页 Actions tab 也可手动 `workflow_dispatch` 触发）。CI 通过 `-PminifyRelease=true` 走完整 R8 + 关闭 AdbDebug 后发布（详见 `app/build.gradle.kts`）。
- **改完文档 / 结构**：同步更新本文件，让它反映真实代码（不要留下过时内容）。
- **改文件**：仅用 `edit` / `write` 工具，**禁止** 批量替换。
- **搜索代码**：用 `rg`

## 常用命令

```bat
gradlew.bat assembleRelease                                  :: 产物 app\build\outputs\apk\release\app-release.apk
                                                                   :: 本地默认：minify=false，AdbDebug=true
gradlew.bat assembleRelease -PminifyRelease=true              :: CI 走法：minify=true（R8 全量混淆），AdbDebug=false
gradlew.bat :app:testDebugUnitTest                           :: 8 个测试类
adb install -r app\build\outputs\apk\release\app-release.apk
for /f "tokens=1" %i in ('adb devices ^| findstr device$') do adb -s %i install -r app\build\outputs\apk\release\app-release.apk
```

签名 key `keys/keystore.jks`（platform key，已入库），别名/密码 `android/android`。

## 构建策略

- **本地（debug / assembleRelease 默认）**：`isMinifyEnabled=false` + `BuildConfig.ADB_DEBUG=true`。release APK 类名清晰可读，AdbDebug 入口启用便于本地调试。
- **CI（`assembleRelease -PminifyRelease=true`）**：`isMinifyEnabled=true` + `isShrinkResources=true` + `BuildConfig.ADB_DEBUG=false`。R8 全量混淆包内类与成员名，AdbDebug 由 BuildConfig 短路后被 shrink 剔除（既无功能也无残留符号），最终产物推到 GitHub Releases。

## 源码目录约定

`app/src/main/kotlin/` 与 `app/src/test/kotlin/` 是源码根（标准 Kotlin 项目惯例）。
AGP 默认仍包含 `java/` 目录作为 Kotlin 源根，本项目显式改为 `kotlin/` 避免与未来
可能引入的 Java 源混淆。`app/build.gradle.kts` 的 `sourceSets` 中 `kotlin.srcDirs`
显式指向这两个目录。

## 模块索引

主入口 `LauncherActivity`（HOME Activity），装配 + 生命周期委派，业务委派到 `home/*Delegate`：

| 模块 | 职责 | 关键约束 |
|---|---|---|
| `home/HomeViews` / `home/SettingsSnapshot` | 视图快照 + 设置快照 | 一次性构造后 immutable，5 个 delegate 共用 |
| `home/LayoutDelegate` | 布局 / 主题 / 字号 / 可见性 | 一次性设置（日/夜切换走 `applyTheme`） |
| `home/SpeedDelegate` | 车速 / 限速 / 超速变色 / 红绿灯 | 持 Activity；mMiles 构造时一次性读 SysProps |
| `home/NaviPanelDelegate` | 导航面板 / 行序 / 电子眼 / 服务区 | 通过 `setLimit` 回调 SpeedDelegate |
| `home/MusicDelegate` | 音乐卡 / 按钮 / 绑定 / 回桌面 | 持 MediaHelper + MusicLauncher；appPicker lambda 由 Activity 注入 |
| `home/UpdateDelegate` | 自动检查 / Listener null-safe | UpdateChecker 内部持 application，Activity 引用可 `release()` |
| `PipController` / `MapPipHost` | std VirtualDisplay PIP 地图 | 持 **applicationContext**（非 Activity）；`surfaceDestroyed` **绝不** release VirtualDisplay；surfaceChanged 后 500ms 再 startActivity；VD flags 按 minSdk 28/29 分支；**`LauncherActivity.onCreate` 必须 `pip.setPlaceholder(views.pipPlaceholder)`** 把新 placeholder 喂给老 mHost，让 SurfaceView 重新 attach 并触发 surfaceChanged（否则 Activity 重建后 PIP surface 永久消失） |
| `MediaHelper` | 音乐 session 监听 | 500ms 进度条 ticker；PLAYING/BUFFERING 视为播放中 |
| `MusicLauncher` | 音乐 app 冷启动 + 轮询唤醒 | 200ms × 40 轮询（8000ms 超时）；PLAYING/BUFFERING → `returnHome()` |
| `MemoryCleaner` | 后台进程清理 | 跳过 uid<10000 / 自身 / 正在播放 / PIP 地图 |
| `DockBar` / `AppDrawer` | 底栏 10 格 + 全部应用弹窗 | `MAX_DOCK_BUTTONS=9`；5 种 `V2Button` type 在 `Store` |
| `SettingsActivity` | 4 标签设置（布局/车速/音乐/通用） | 改完即时写 SP，**关闭设置后重启桌面生效**（`onCreate` 快照）；通用 tab 含「检查更新」 |
| `AdbDebug` | 调试辅助模块（debug / 本地 release） | `BuildConfig.ADB_DEBUG` 控制：本地默认 true（启 HTTP server），CI 走 `-PminifyRelease=true` 时 `ADB_DEBUG=false` + R8 shrink 完全剔除（既无功能也无残留符号） |
| `Store` | `V2Button` JSON 持久化 + 启动动作 | `pkgOf(id)` / `launchSplit`（广播 + 反射 `resizeDockedStack`） |
| `UpdateChecker` | GitHub Releases 在线更新 | 拉 `api.github.com/.../releases/latest` → 解析 body 中的 `versionCode` 整数比较（构建 epoch 秒数，**单调递增**）→ 下载到 `cacheDir/update.apk` → 调 [PackageInstaller] session API 装。body 解析不到 versionCode 时回退 `versionName` 字典序。**24h 节流**（SP 写 `update_last_auto_check`），启动自动检查；手动按钮跳过节流；异步走 `SharedExecutor.io()`（AGENTS #5）。持 application + nullable Activity，`release()` 清引用避免死 Activity 回调 crash。下载可取消（`AtomicBoolean mCancelled`）。**system uid 下必须用 [PackageInstaller] session API** — `Intent.ACTION_INSTALL_PACKAGE + content URI + FLAG_GRANT_READ_URI_PERMISSION` 路径在 `sharedUserId=android.uid.system"` 进程下被 framework 端 `UriGrantsManagerService` 拒 grant（"For security reasons, the system cannot issue a Uri permission grant"），目标 installer 读 URI 报 `UID xxx does not have permission` crash。PackageInstaller session 走 system_server 内部路径，caller 用 `INSTALL_PACKAGES` 权限 stream APK 进 session，**不走 URI grant 检查**。self-update 时 framework 走 `installPackageLI` 静默 install（不弹 dialog，system app 特权），装完 force-stop caller 进程；非 self-update 场景会弹系统确认框。 |
| `MapApps` / `MapActions` | 高德/腾讯/百度三家地图 | `detect()` 优先在跑的地图；统一入口 `MapActions.run(c, action)` |
| `NaviTextClient` | 高德导航文字（4 类 KEY_TYPE） | 限速取 12110 `LIMITED_SPEED`（10001 恒 -1）；10s 看门狗 |
| `TrafficLightClient` | 高德红绿灯 | 2s 无更新隐藏；status≤0 且 countdown≤0 视为无效 |
| `AppQuery` | 可启动应用查询（排除自身） | 排序：用户应用优先 + label 字典序 |
| `Prefs` | SP 统一入口 | 文件名 `launcher37_config` |
| `HoloPopup` | 弹窗统一封装 | 日/夜主题随 Activity；anchor 固定 decorView |
| `IconNormalizer` / `IconCache` | 图标归一化（128×128 + 20% 圆角） | 16MB LRU 封顶；日/夜切换 `IconCache.clearNormalized()` |
| `SharedExecutor` | 全局 2 线程 IO 池 | 任务 < 1s；长任务另起池 |
| `SysProps` | `SystemProperties` 反射 | 写权限需 system uid |
| `NaviOrder` | 长键集 → 渲染短键 | `filter(unified, cruise)` 保留 `navi_*` / `cruise_*` 子集 |
| `NumberPickerView` | `[-] [value] [+]` 数值编辑 | 失焦/回车自动夹紧到 `[min, max]` |
| `SpeedClient` | GPS 车速 IPC（`com.syu.ms`） | AIDL 方法顺序即 transaction 号，**不可改** |
| `MainThread` | 全局主线程 Handler 单例 | 替代 8 个 Client 各自 `Handler(Looper.getMainLooper())`；post callback 用法不变 |
| `MapFeature` | 6 个底栏 emoji + action 名称常量表 | 统一 emoji unicode escape 避免 DockBar + AppDrawer 重复 9 处 |
| `SplitRepository` | 分屏项 SP 仓储 | `pkg/cls1|pkg/cls2\n...` 格式；`add` 自动去重 |

## 关键不变量

1. `sharedUserId="android.uid.system"` 必须在 `<manifest>`（不是 `<application>`），否则拿不到 system uid。
2. **应用图标必须是 PNG**，不能用 VectorDrawable（`com.syu.settings` 闪退）。
3. 全项目 px 硬编码，仅适配 1280×720。
4. API 33+ 广播注册必须 `RECEIVER_NOT_EXPORTED`。
5. 后台线程统一用 `SharedExecutor.io()`（2 线程 fixed pool）。**禁止**自建 `Executors.newSingleThreadExecutor` / `newFixedThreadPool(N)` / `newCachedThreadPool()`、裸 `Thread {}` 或自起 `HandlerThread` —— 任何异步执行都要走 `SharedExecutor.io()`。
6. `uiMode` 切换靠 `configChanges` 拦截 + 手动 `applyTheme()`，**不重建 Activity**（避免 std PIP 中断）。
7. 渲染限速值 = 12110 的 `LIMITED_SPEED`；10001 中该字段恒 -1。
8. R8 走 `-PminifyRelease=true`（仅 CI 走）。本地 `assembleRelease` 默认 `isMinifyEnabled=false` + `BuildConfig.ADB_DEBUG=true`，便于本地调试。CI 走 R8 时 AdbDebug 由 BuildConfig 短路后被 shrink 剔除，无需自定义 `-keep`。

## 公开仓库敏感信息策略

仓库公开在 `github.com/txq1996/CarLauncher`。需要注意：

- **AGENTS.md / commit message / 测试报告**不得包含 AdbDebug 的具体协议、路径、命令格式、HTTP route 列表、反射 API 列表等可被外部复现的细节。这些信息只写在私有调试笔记中。
- **测试报告 / 构建日志 / 调试截图**不进 git 仓库（`.gitignore` 已忽略 `*.log` 与 `test-screenshots/`，新加的 `test-report-*.md` 等临时报告同理不进库）。
- **`keys/keystore.jks`** 当前在仓库内。**该 key 已暴露，视为公开**。若需更换签名 key，必须重新生成并清理 git 历史（旧版本 APK 与新版本不可覆盖升级，因签名不符）。
- **历史改写**：使用 `git filter-repo` 清理敏感信息（含敏感内容的 AGENTS.md blob / commit message）。涉及 force push。