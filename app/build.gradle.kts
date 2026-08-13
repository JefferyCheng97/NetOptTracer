import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 从 local.properties 读高德 API Key。这个文件被 .gitignore 排除，
// Key 不会随代码上传；build 时再注入到 AndroidManifest 的 meta-data。
val amapApiKey: String = run {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return@run ""
    Properties().apply { f.inputStream().use(::load) }.getProperty("AMAP_API_KEY", "")
}

android {
    namespace = "com.jeffery.cellularmonitor"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.jeffery.cellularmonitor"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // AndroidManifest 里 ${AMAP_API_KEY} 会被替换成这里的值
        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // release 用调试签名，方便分享 APK 测试，不用管理密钥
        // 如果要上架应用商店，改用 create("release") { storeFile/storePassword/... }
        getByName("debug") {
            // 使用 Android Studio 自动生成的调试密钥
        }
    }

    buildTypes {
        release {
            // 用调试签名，避免"测试版本"拦截
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = false
            }
            // 不混淆，保留日志，方便排查问题
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.amap.map3d)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}