import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release signing comes from keystore.properties at the repo root, which is
// git-ignored (see keystore.properties.example). Without it, release builds
// are produced unsigned.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// The Play build ships the Quran model inside the app so it works offline from
// the first launch. The model is not in git (57 MB); produce it with
// scripts/convert_whisper_model.sh, or point -PtahsinModel=/path/to.bin at one.
val bundledModel: String = providers.gradleProperty("tahsinModel").getOrElse(
    rootProject.file("build/models/ggml-tarteel-ai-whisper-base-ar-quran-q5_1.bin").path,
)

/** Stages the model as assets/models/ggml-base-ar-quran.bin for release builds. */
abstract class BundleModelTask : DefaultTask() {
    @get:InputFile
    abstract val model: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val models = outputDir.get().asFile.resolve("models")
        models.deleteRecursively()
        models.mkdirs()
        model.get().asFile.copyTo(models.resolve("ggml-base-ar-quran.bin"))
    }
}

val bundleModel = tasks.register<BundleModelTask>("bundleModel") {
    description = "Copies the Quran model into release assets " +
        "(make it with scripts/convert_whisper_model.sh, or pass -PtahsinModel=...)."
    model.set(file(bundledModel))
}

android {
    namespace = "com.tahsin.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tahsin.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"

        // Only the ABIs whisper is built for. Without this, a library's stray
        // 32-bit .so would make Play offer the app to phones it crashes on.
        ndk {
            abiFilters += ((findProperty("tahsinAbis") as String?) ?: "arm64-v8a")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Off on purpose: R8 would rename WhisperLib, whose JNI symbol
            // names are fixed by its class name, and the code is small anyway.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    androidResources {
        // The prepackaged Quran database must not be compressed, otherwise Room
        // cannot stream it out of the APK when creating the local copy.
        noCompress += "db"
        // Quantised weights barely compress; storing them saves the install-time
        // inflate when the model is copied out on first launch.
        noCompress += "bin"
    }

    testOptions {
        unitTests {
            // The Room asset test opens assets/databases/quran.db off-device.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    // Release only: debug installs stay small, and a developer's model already
    // lives in the app's files/models.
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleModel, BundleModelTask::outputDir)
    }
}

// Room's schema JSON is the contract that scripts/import_quran.py builds the
// prepackaged database against, so it has to be exported into the repo.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":whisper"))

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
