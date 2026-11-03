plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.astraplay.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.astraplay.tv"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 24          // Android 7.0 — поддержка TV-приставок 7/8/9+
        targetSdk = 34
        versionCode = 7
        versionName = "1.5"
        vectorDrawables.useSupportLibrary = true
        // Только нужные локали — меньше ресурсов в APK
        resourceConfigurations += listOf("ru", "en")
    }

    // Приложение не содержит нативного кода (.so), поэтому один универсальный APK
    // работает на всех архитектурах (armeabi-v7a / arm64-v8a / x86 / x86_64)
    // и на Android 7.0–14. Отдельные ABI-сборки не требуются.

    signingConfigs {
        create("release") {
            storeFile = file("astraplay.keystore")
            storePassword = System.getenv("ASTRAPLAY_STORE_PASSWORD") ?: "astraplay2025"
            keyAlias = System.getenv("ASTRAPLAY_KEY_ALIAS") ?: "astraplay"
            keyPassword = System.getenv("ASTRAPLAY_KEY_PASSWORD") ?: "astraplay2025"
        }
    }

    buildTypes {
        debug {
            if (project.hasProperty("localSignedDebug") && file("astraplay.keystore").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Локально — наш keystore; на CI его нет, подписываем debug-ключом,
            // чтобы GitHub Actions мог собирать установочные APK
            signingConfig = if (file("astraplay.keystore").exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0", "META-INF/LGPL2.1"
        )
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    implementation("org.jsoup:jsoup:1.17.2")
    testImplementation("junit:junit:4.13.2")
    // Базовые AndroidX — минимум для легкого APK на 1 ГБ RAM
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Плеер: Media3 ExoPlayer + HLS (IPTV-потоки)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Сеть и картинки
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0") // превью локальных видео
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") // SMB2/3 — сетевые папки
}
