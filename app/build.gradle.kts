plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// CI passes -PbuildNumber=<GitHub run number>; local builds default to 1.
val buildNumber = (project.findProperty("buildNumber") as String? ?: "1").toInt()

android {
    namespace = "com.yuchoi.racecert"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yuchoi.racecert"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // Strava 연동: Client ID는 공개값이라 코드에 두고, Secret은 CI의 GitHub Secret에서 주입.
        buildConfigField("String", "STRAVA_CLIENT_ID", "\"264239\"")
        buildConfigField(
            "String",
            "STRAVA_CLIENT_SECRET",
            "\"${System.getenv("STRAVA_CLIENT_SECRET") ?: ""}\"",
        )
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
    // Google 로그인 (Drive 자동 동기화용 계정/토큰)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // 헬스커넥트: 삼성헬스/가민커넥트가 넣어둔 몸무게·체지방 읽기
    implementation("androidx.health.connect:connect-client:1.1.0-rc03")
    // Firestore: 여러 기기 간 실시간 동기화
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    // Crashlytics: 앱이 죽으면 스택 트레이스를 Firebase 콘솔로 자동 업로드
    implementation("com.google.firebase:firebase-crashlytics-ktx")
}
