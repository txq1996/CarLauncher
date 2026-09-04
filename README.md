# CarLauncher

Android 车载桌面 launcher。包名 `com.android.launcher37`、应用名 **CarLauncher**。

针对 1280×720 车机屏优化的 `HOME` 启动器：VD 地图悬浮窗、自由画布 Widget 桌面（时间/音乐/车速导航/VD 应用窗/应用列表）、全部应用抽屉、后台进程清理、应用内在线更新。

## 构建

```bat
gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

CI 走 `assembleRelease -PminifyRelease=true` 启 R8，体积显著缩小。

## 应用内更新

桌面设置 → 通用 → 检查更新。
从 GitHub Releases 拉最新 APK（`sharedUserId="android.uid.system"` 走 `PackageInstaller` session 静默安装，system app 特权不弹确认框）。

## 签名

`keys/keystore.jks`（Android platform key）：

- alias / password：`android` / `android`
- 因为 `AndroidManifest.xml` 声明 `android:sharedUserId="android.uid.system"`，APK 必须以 platform key 签名才能以 system app 身份装到车机 system 镜像。
