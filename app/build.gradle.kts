import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.android.launcher37"
    compileSdk = 34
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.android.launcher37"
        minSdk = 28
        targetSdk = 29
        // 用构建时刻（北京时）作为版本号：
        //   versionCode = 当前 epoch 秒数（CST 同样适用 epoch 比较，单调）
        //   versionName = YYYYMMDD（北京时间，Asia/Shanghai）
        // 同一天多次构建 versionName 相同 → 字典序等于日历序；
        // 配合 launcher 端字典序比较，远端 >= 本地 时升级。
        versionCode = (System.currentTimeMillis() / 1000L).toInt()
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        versionName = fmt.format(Date())

        // buildConfigField 暴露构建 epoch 秒数到 BuildConfig.BUILD_TIME_EPOCH；
        // 设置页读取并格式化为北京时间显示。
        buildConfigField("long", "BUILD_TIME_EPOCH", "${System.currentTimeMillis() / 1000L}L")
    }

    // 源码根目录：标准 Kotlin 工程用 kotlin/ 而非 java/。
    // 资源分层：layout 按模块拆分到独立资源根（res-home / res-widget / res-drawer / res-settings），
    // 各目录内为标准 res 结构（layout/ 子目录）；values/drawable 等仍在主 res。
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            res.srcDirs(
                "src/main/res",
                "src/main/res-home",
                "src/main/res-widget",
                "src/main/res-drawer",
                "src/main/res-settings"
            )
        }
        getByName("test").kotlin.srcDirs("src/test/kotlin")
    }

    signingConfigs {
        create("platform") {
            keyAlias = "android"
            keyPassword = "android"
            storeFile = file("../keys/keystore.jks")
            storePassword = "android"
        }
    }

    // 发布型 release 的混淆开关（本地不混淆）；
// CI 通过 -PminifyRelease=true 走完整 R8（详见 release.yml）。
    val minifyRelease: Boolean = (project.findProperty("minifyRelease") as String?)?.toBoolean() == true
    buildTypes {
        release {
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            if (minifyRelease) {
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
            signingConfig = signingConfigs.getByName("platform")
        }
        debug {
            signingConfig = signingConfigs.getByName("platform")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    // 设置页排序/隐藏列表：RecyclerView + ItemTouchHelper（长按拖拽），唯一第三方运行时依赖
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
