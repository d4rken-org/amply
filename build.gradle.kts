plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    // Renders @Preview composables to PNGs on the JVM for Play Store screenshots (see fastlane/).
    id("com.android.compose.screenshot") version "0.0.1-alpha15" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

