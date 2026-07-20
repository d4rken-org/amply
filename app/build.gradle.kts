import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.android.build.api.dsl.SigningConfig
import java.util.Properties

// Lives here rather than in buildSrc because it references AGP types — see buildSrc/build.gradle.kts.
// Returns whether a complete configuration was loaded; callers only assign the signing config when
// ready, so credential-less machines still build (unsigned) release variants.
fun SigningConfig.setupCredentials(path: File): Boolean {
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
    val ready = storeFile != null && !storePassword.isNullOrBlank() &&
        !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()
    if (!ready) println("WARNING: No valid signing configuration ($path), builds will be unsigned.")
    return ready
}

plugins {
    id("projectConfig")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = projectConfig.packageName
    compileSdk = projectConfig.compileSdk

    defaultConfig {
        applicationId = projectConfig.packageName
        minSdk = projectConfig.minSdk
        targetSdk = projectConfig.targetSdk
        versionCode = projectConfig.version.code.toInt()
        versionName = projectConfig.version.name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("boolean", "ENABLE_PIXEL_LAB_ADAPTER", "true")
    }

    val signingBase = File(System.getProperty("user.home"), ".config/projects/${projectConfig.packageName}")
    var fossSigningReady = false
    var gplaySigningReady = false
    signingConfigs {
        create("releaseFoss") {
            fossSigningReady = setupCredentials(File(signingBase, "signing-foss.properties"))
        }
        create("releaseGplay") {
            gplaySigningReady = setupCredentials(File(signingBase, "signing-gplay-upload.properties"))
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
        }
        create("beta") {
            lint {
                abortOnError = true
                fatal.add("StopShip")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            lint {
                abortOnError = true
                fatal.add("StopShip")
            }
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
    addBaseKotlin()
    addBaseAndroid()

    addDagger()

    addCompose()
    addNavigation3()
    addDataStore()
    addGlance()

    addShizuku()

    addTesting()
}
