# 构建与运行

> Code Wiki · 06 · 构建与运行方式

## 1. 环境要求

| 项 | 值 |
|----|----|
| JDK | 17 |
| Android SDK | compileSdk 34 / build-tools 36.0.0；CI 用 build-tools 35.0.0 |
| Gradle Wrapper | 见 `gradle/wrapper/gradle-wrapper.properties` |
| 根项目 | `Launcher37Kotlin`，单模块 `:app` |

## 2. 构建命令

```bat
:: Windows
gradlew.bat assembleRelease

:: Linux / CI
chmod +x ./gradlew
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

- **本地默认**：release 不混淆（`isMinifyEnabled` 默认关，便于调试）。
- **CI 完整 R8**：`./gradlew assembleRelease -PminifyRelease=true`

单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

> ⚠️ **开发注意**：若无 `keys/keystore.jks`，本地 build 的 `release/debug` 签名配置会失败；
> 该文件需从受控渠道获取（platform key），开发可临时改用调试签名。

## 3. 安装与运行

```bat
adb install -r app\build\outputs\apk\release\app-release.apk
```

由于 `sharedUserId="android.uid.system"`，真实车机需**以 platform key 签名并装入系统镜像**
（system app），否则普通安装会因 sharedUserId 冲突失败。安装后设置为默认 HOME 应用。

- 产出属主：`com.android.launcher37` / 应用名 **CarLauncher**。
- 入口：`LauncherActivity`（`HOME` + `LAUNCHER`）。

## 4. CI 流水线（`.github/workflows/release.yml`）

| job | 触发 | 内容 |
|-----|------|------|
| `build` | push 任意分支 / PR / dispatch | setup-java(17) + setup-android + `assembleRelease -PminifyRelease=true` + `testDebugUnitTest` + aapt2 校验 label |
| `release` | main push / dispatch(非 skip) | 下载 APK → 单 tag `latest` 重建 release（清理旧 release/tag/artifact） |

- 版本：`versionCode` = 构建 epoch 秒（单调递增）；`versionName` = `YYYYMMDD`（北京时间）。
- 触发条件：`push`（含 tag `v*`）、`pull_request`、`workflow_dispatch`。

## 5. 应用内更新使用方法

桌面 → 设置 → 通用 → **检查更新**。
- 自动检查：App 启动 24h 节流一次。
- 手动检查：立即执行，发现新版本需用户确认后 `PackageInstaller` 安装（system app 静默，不弹确认）。

## 6. 运行时的系统前提（特权依赖）

| 能力 | 依赖 |
|------|------|
| VD 承载三方应用 | system uid + `INTERNAL_SYSTEM_WINDOW` + `REAL_GET_TASKS` |
| 内存清理 `forceStopPackageAsUser` | system uid（反射）+ `FORCE_STOP_PACKAGES` |
| 静默安装 | `REQUEST_INSTALL_PACKAGES` + system 特权 / `sharedUserId` |
| 车机 GPS 车速 IPC | 本机须已装 `com.syu.ms` 服务（`bindService("com.syu.ms.toolkit")`）；缺失时 `SpeedClient` 自动退避不空转 |
| 导航数据 | 高德地图发布 `AUTONAVI_STANDARD_BROADCAST_SEND` 广播 |

## 7. 调试提示

- 更新强制走下载路径：往 `Prefs.FILE` 写 `test_fake_local_code`（epoch 秒）伪造"本地更老"。
- `MediaHelper` 未授权（无 MEDIA_CONTENT_CONTROL）时 UI 显示"未授权 / 无法读取媒体会话"。
- AIDL 改动必须在末尾追加（transaction 号顺序固定）。

## 8. 相关文档

- [01-overview 整体架构](/workspace/docs/code-wiki/01-overview.md)
- [02-modules 模块职责](/workspace/docs/code-wiki/02-modules.md)
- [05-dependencies 依赖关系](/workspace/docs/code-wiki/05-dependencies.md)
- [AGENTS.md](/workspace/AGENTS.md)