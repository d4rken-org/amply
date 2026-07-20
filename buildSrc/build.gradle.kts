plugins {
    `kotlin-dsl`
    `java-library`
}

gradlePlugin {
    plugins {
        create("projectConfigPlugin") {
            id = "projectConfig"
            implementationClass = "ProjectConfigPlugin"
        }
    }
}

repositories {
    google()
    mavenCentral()
}

// Deliberately no AGP/Kotlin plugin dependencies here: anything on the buildSrc classpath becomes a
// parent of every build-script classloader, and AGP 9 transitively drags its bundled Kotlin Gradle
// plugin along, clashing with the toolchain versions declared in the root plugins block. AGP-typed
// helpers (the signing loader) therefore live in app/build.gradle.kts instead.
