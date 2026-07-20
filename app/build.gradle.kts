import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.android.build.api.dsl.SigningConfig
import java.util.Properties

fun SigningConfig.loadAmplySigning(path: File): Boolean {
    val environmentStore = System.getenv("STORE_PATH")?.let(::File)?.takeIf(File::exists)
    if (environmentStore != null) {
        storeFile = environmentStore
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
    } else if (path.canRead()) {
        val properties = Properties().apply { path.inputStream().use(::load) }
        storeFile = properties.getProperty("release.storePath")?.let(::File)?.takeIf(File::exists)
        storePassword = properties.getProperty("release.storePassword")
        keyAlias = properties.getProperty("release.keyAlias")
        keyPassword = properties.getProperty("release.keyPassword")
    }
    return storeFile != null && !storePassword.isNullOrBlank() &&
        !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "eu.darken.amply"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.darken.amply"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.1.0-spike1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("boolean", "ENABLE_PIXEL_LAB_ADAPTER", "true")
    }

    val signingBase = File(System.getProperty("user.home"), ".config/projects/eu.darken.amply")
    var fossSigningReady = false
    var gplaySigningReady = false
    signingConfigs {
        create("releaseFoss") {
            fossSigningReady = loadAmplySigning(File(signingBase, "signing-foss.properties"))
        }
        create("releaseGplay") {
            gplaySigningReady = loadAmplySigning(File(signingBase, "signing-gplay-upload.properties"))
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            if (fossSigningReady) signingConfig = signingConfigs.getByName("releaseFoss")
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }
        }
        create("gplay") {
            dimension = "distribution"
            if (gplaySigningReady) signingConfig = signingConfigs.getByName("releaseGplay")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("beta") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            versionNameSuffix = "-beta"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += setOf(
        "META-INF/AL2.0",
        "META-INF/LGPL2.1",
    )

    testOptions.unitTests.apply {
        isIncludeAndroidResources = true
        all { it.useJUnitPlatform() }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-Xannotation-default-target=param-property",
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.navigation3:navigation3-runtime-android:1.0.1")
    implementation("androidx.navigation3:navigation3-ui-android:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3-android:2.10.0")

    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.glance:glance-appwidget:1.2.0-rc01")
    implementation("androidx.glance:glance-material3:1.2.0-rc01")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-compiler:2.59.2")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Vintage engine runs the JUnit 4 Robolectric tests on the JUnit Platform.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
