// Top-level build file. Versions are pinned here; see README for why.
plugins {
    // AGP 9 has built-in Kotlin (KGP 2.2.10); the kotlin.android plugin must NOT be applied.
    id("com.android.application") version "9.2.1" apply false
    id("com.chaquo.python") version "17.0.0" apply false
}
