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

    signingConfigs {
        create("platform") {
            keyAlias = "android"
            keyPassword = "android"
            storeFile = file("../keys/keystore.jks")
            storePassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
        }
    }

    dependencies {
        testImplementation("junit:junit:4.13.2")
    }
}
