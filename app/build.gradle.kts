import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 发布签名凭据放在 keys/keystore.properties（本地文件，已被 .gitignore 排除）。
// 读不到时不会注册签名配置，release 会退回未签名产物 —— 保证在没有凭据的机器上仍能编译。
val releaseKeystorePropsFile = rootProject.file("keys/keystore.properties")
val releaseKeystoreProps = Properties().apply {
    if (releaseKeystorePropsFile.exists()) {
        releaseKeystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.fpb.vault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fpb.vault"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.0.8"
    }

    signingConfigs {
        if (releaseKeystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")

                // 签名方案显式声明，不依赖 AGP 的默认值 —— 默认值会随 AGP 版本变化，
                // 而"哪几个方案被签了"是**无法事后补救**的：已经发出去的包少了 v3，
                // 将来就没有原地轮换签名密钥的余地。
                // v1（JAR 签名）关掉：minSdk 26 起它已无意义，只多一份可被篡改的清单。
                enableV1Signing = false
                // v2 是 Android 7+ 的安装基础
                enableV2Signing = true
                // v3 支持在不改变应用签名的前提下轮换签名密钥（Android 9+）
                enableV3Signing = true
                // v4 只用于 adb 增量安装，不进入分发包，留着无害
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 未配置凭据时为 null，AGP 会产出 app-release-unsigned.apk
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        // 调试包要读 BuildConfig.DEBUG 来决定"默认是否禁止截屏"（见 MainActivity）。
        // AGP 8 起 buildConfig 默认关闭，必须显式打开。
        buildConfig = true
    }

    lint {
        // 已知环境限制：本机 Gradle 跑在 Android Studio 自带的 JDK 25 上，
        // 而 AGP 8.7.3 的 Lint 解析 JDK 版本号时会抛
        // java.lang.IllegalArgumentException: 25.0.2 导致 lintVital 崩溃。
        // 因此暂不把 lint 作为 release 打包门禁（debug 编译不受影响）。
        // M7 阶段装上 JDK 21 或升级 AGP 后应重新开启。
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.biometric)
    // 覆盖 biometric 1.1.0 传递进来的 fragment 1.2.5，原因见 libs.versions.toml 注释：
    // fragment 1.2.5 的 FragmentActivity 强制 requestCode 只用低 16 位，
    // 与 activity 1.10.0 的随机 requestCode 冲突，导致所有 activity result 启动必崩。
    implementation(libs.androidx.fragment)
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 只调用 BouncyCastle 的 Argon2id 实现，不把它注册成全局 JCE Provider ——
    // 注册 BC Provider 在 Android 上会与系统自带 Conscrypt 冲突，历史上导致过 TLS 异常。
    // AES 走平台实现（Android 是 Conscrypt，JVM 是 SunJCE），行为一致。
    implementation(libs.bouncycastle.prov)

    // 加密内核是纯 JVM 逻辑（不碰 android.* API），因此可以在普通单元测试里直接跑生产代码。
    // 这也是选 BouncyCastle 纯 Java 实现而非 native 库的原因之一。
    testImplementation(libs.junit)
}
