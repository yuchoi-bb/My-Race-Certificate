plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes -PbuildNumber=<GitHub run number>; local builds default to 1.
val buildNumber = (project.findProperty("buildNumber") as String? ?: "1").toInt()

android {
    namespace = "com.yuchoi.racecert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yuchoi.racecert"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "racecert-local"
            keyAlias = System.getenv("KEY_ALIAS") ?: "racecert"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "racecert-local"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 온디바이스 한국어/라틴 OCR (기록증 텍스트 추출)
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // Health Connect — 가민 커넥트가 동기화한 몸무게/체지방 읽기
    implementation("androidx.health.connect:connect-client:1.1.0-rc03")
}
