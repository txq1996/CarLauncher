# CarLauncher

Android 车载桌面。包名 `com.android.launcher37`、应用名 **CarLauncher**。

## 特性

- HOME 启动器（`singleTask`），针对 1280×720 车载屏优化
- PIP 地图悬浮窗（自实现 `ActivityView` 宿主 + `VirtualDisplay`）
- 音乐应用冷启动 + 媒体会话监听
- Dock 栏（9 个自定义格 + 1 个「全部应用」格）
- 后台进程清理（白名单保护 Dock / 正在播放 / PIP 地图）
- 4 标签设置页：布局 / 车速 / 音乐 / 通用
- AIDL 接入 `SpeedClient` / `TrafficLightClient` / `NaviTextClient` 三个车机服务
- **应用内在线更新**（设置 → 通用 → 检查更新）

## 技术栈

- AGP 8.13.2 / Gradle 8.13 / Kotlin 2.0.21
- compileSdk 34 / minSdk 28 / targetSdk 29
- JVM 17
- 单元测试：JUnit 4

## CI / 发布

`.github/workflows/release.yml` 监听所有 push 触发构建（`assembleRelease` + 单元测试），
push tag `v*` 时创建/覆盖 GitHub Release 并上传 APK；流程末尾清理全部旧 release/tag，
保证只保留最新一份。

发布流程：
1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`
2. 提交并 push 到 main：`git push`
3. 标 tag：`git tag v1.0.1 && git push --tags`
4. Action 跑完 → 出现 release `v1.0.1` 带 APK

## 应用内更新

桌面设置 → 通用 → 检查更新。
调 `https://api.github.com/repos/txq1996/CarLauncher/releases/latest`，
按 `tag_name` 与本地 `versionName` 字典序对比，下载最新 `app-release.apk` 后弹系统安装器。

## 构建

```bat
gradlew.bat assembleRelease
gradlew.bat :app:testDebugUnitTest
adb install -r app\build\outputs\apk\release\app-release.apk
```

产物路径：`app/build/outputs/apk/release/app-release.apk`

## 签名

使用 `keys/keystore.jks`（Android platform 签名 key）：
- 别名：`android`
- 密码：`android`
- SHA-1：`27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`

因为 `AndroidManifest.xml` 声明了 `android:sharedUserId="android.uid.system"`，APK 需以 platform key 签名才能以 system app 身份安装到车机 system 镜像。

## 详细模块说明

参见 [AGENTS.md](./AGENTS.md)。
