plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mandro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mandro.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            buildConfigField("Boolean", "USE_MOCK_BLE", "false")
            buildConfigField("String", "BASE_URL", "\"https://api.mandro.com/\"") // 예시 운영 서버 주소
        }
        debug {
            buildConfigField("String", "BASE_URL", "\"http://127.0.0.1:8000/\"") // adb reverse로 PC localhost 터널링
            buildConfigField("Boolean", "USE_MOCK_BLE", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    kotlin {
        jvmToolchain(21)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    // ── Jetpack Compose ───────────────────────────────────────
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    // ── Architecture ──────────────────────────────────────────
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)

    // ── DI: Hilt ─────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── 네트워크 ──────────────────────────────────────────────
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)

    // ── 로컬 DB: Room ─────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── DataStore ─────────────────────────────────────────────
    implementation(libs.datastore.preferences)

    // ── 애니메이션 ────────────────────────────────────────────
    implementation(libs.lottie)

    // ── ML: TFLite ────────────────────────────────────────────
    implementation(libs.tflite)
    implementation(libs.tflite.support)

    // ── 테스트 ────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.compose.ui.test)

    implementation(libs.compose.material.icons.extended)
}
