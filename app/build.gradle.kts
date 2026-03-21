plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.new_tv_app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.new_tv_app"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "IPTV_BASE_URL", "\"https://ilvip.net\"")
        buildConfigField("String", "IPTV_USERNAME", "\"roku1234\"")
        buildConfigField("String", "IPTV_PASSWORD", "\"11111111\"")

        // TV/phones are ARM. Omitting x86/x86_64 avoids packaging LibVLC emulator ABIs
        // Play often reports 16 KB issues on those .so first. Use an ARM64 emulator to debug.
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.recyclerview)
    implementation(libs.glide)
    implementation(libs.videolan.libvlc)
}