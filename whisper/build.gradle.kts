plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ABIs are opt-in because each one is a full whisper.cpp + ggml native build.
// Override with -PtahsinAbis=arm64-v8a,x86_64 (or in gradle.properties).
val tahsinAbis: List<String> = (
    (project.findProperty("tahsinAbis") as String?) ?: "arm64-v8a"
    ).split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.tahsin.whisper"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += tahsinAbis
        }
        externalNativeBuild {
            cmake {
                // Static C++ runtime: ggml is linked into libwhisper.so, so it is
                // the only native library and owns its own copy safely. NDK r26's
                // libc++_shared.so is not 16 KB aligned, which Play rejects.
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
