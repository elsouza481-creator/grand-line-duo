plugins {
    id("com.android.application") version "9.3.0"
}

android {
    namespace = "com.grandlineduo.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grandlineduo.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets.named("main") {
        kotlin.directories += "../core/src/main/kotlin"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
