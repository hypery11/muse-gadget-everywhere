plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

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
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            // One APK for every Android device. Python 3.11 is the newest
            // interpreter Chaquopy ships for 32-bit ABIs, and it still
            // covers all four: ARM phones/TV (arm64 + 32-bit userspace
            // like Sabrina), Intel Chromebooks and emulators (x86_64),
            // and legacy 32-bit emulators (x86).
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Accepted warnings live here (currently: the SDK-36/37 treadmill —
        // no emulator images exist to verify against, so target stays 35).
        // New issues still fail the build; only baselined ones are silent.
        baseline = file("lint-baseline.xml")
    }
}

chaquopy {
    defaultConfig {
        // Build machine must have this exact Python (Chaquopy looks for
        // `python3.11` on PATH). 3.11 is the newest Python with 32-bit
        // (armeabi-v7a) Chaquopy support; cryptography wheels exist for cp311.
        version = "3.11"
        pip {
            install("cryptography")
            install("websockets")
            install("pychromecast==14.0.10")
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
