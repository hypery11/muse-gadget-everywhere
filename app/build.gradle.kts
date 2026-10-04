plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

val modernRuntime = providers.gradleProperty("modernRuntime").orNull == "true"

android {
    namespace = "ai.muse.gadgeteverywhere"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.muse.gadgeteverywhere"
        // 24 is the floor: Chaquopy needs 21+, and BLE peripheral stacks
        // before 24 are too buggy to pair against. Everything 26+ (channels,
        // startForegroundService) is runtime-gated in GadgetService.
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = if (modernRuntime) "0.3.0-modern" else "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // One APK for every Android device. Python 3.11 is the newest
            // interpreter Chaquopy ships for 32-bit ABIs, and it still
            // covers all four: ARM phones/TV (arm64 + 32-bit userspace
            // like Sabrina), Intel Chromebooks and emulators (x86_64),
            // and legacy 32-bit emulators (x86).
            abiFilters += if (modernRuntime) listOf("arm64-v8a", "x86_64") else listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }
    // Release keys stay outside source control. Missing values produce an
    // explicitly unsigned APK, never a release signed with the debug key.
    val releaseStore = providers.environmentVariable("MUSE_RELEASE_STORE").orNull
    if (releaseStore != null) {
        signingConfigs.create("production") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("MUSE_RELEASE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("MUSE_RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("MUSE_RELEASE_KEY_PASSWORD").get()
        }
    }
    splits {
        abi {
            isEnable = providers.gradleProperty("splitApks").orNull == "true"
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("production")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Target 35 is the runtime-validated baseline. Newer SDKs and emulator
        // images exist, but their behavior changes have not been validated here.
        // New issues still fail the build; only baselined ones are silent.
        baseline = file("lint-baseline.xml")
    }
}

chaquopy {
    defaultConfig {
        // Build machine must have this exact Python (Chaquopy looks for
        // `python3.11` on PATH). 3.11 is the newest Python with 32-bit
        // (armeabi-v7a) Chaquopy support; cryptography wheels exist for cp311.
        version = if (modernRuntime) "3.13" else "3.11"
        providers.environmentVariable("MUSE_BUILD_PYTHON").orNull?.let { buildPython(it) }
        pip {
            options("--find-links", project.file("wheels").absolutePath)
            install("-r", "requirements-android.txt")
        }
    }
    sourceSets {
        getByName("main") {
            // Our overlay plus upstream musegadget straight from the submodule:
            // no fork, no copy, `git submodule update` tracks upstream.
            srcDirs("src/main/python", "../vendor/muse-gadget-sdk/linux/src")
        }
    }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
