plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

android {
    namespace = "ai.muse.gadgettv"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.muse.gadgettv"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Sabrina (Chromecast with Google TV) runs 32-bit userspace:
            // armeabi-v7a is mandatory. arm64-v8a covers other Android TV.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
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
