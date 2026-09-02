# Launcher37 Kotlin ProGuard/R8 规则
#
# 本项目无需自定义 keep 规则：
# - 组件（LauncherActivity）由 AGP 根据 AndroidManifest 自动保留；
# - AIDL Stub/Proxy 由 SpeedClient 直接引用，不会被剔除；
# - Parcelable CREATOR 由 AGP 默认规则（proguard-android-optimize.txt）保留；
# - 反射仅针对 Android framework 内部类（SystemProperties / ActivityTaskManager），不参与 app 混淆。
# - Kotlin metadata 由 AGP 默认规则保留。
#
# 仅 CI（-PminifyRelease=true）走本规则；本地默认 minify=false 不读此文件。
