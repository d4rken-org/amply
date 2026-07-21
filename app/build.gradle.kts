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
    id("com.android.compose.screenshot")
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

    // Enables the Compose screenshot test source set. The plugin requires BOTH this module-level flag
    // and the matching android.experimental.enableScreenshotTest property in gradle.properties.
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

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
        all {
            it.useJUnitPlatform()
            it.setupTestLogging()
        }
    }
}

androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType ?: return@onVariants
        if (buildType != "release" && buildType != "beta") return@onVariants

        val formattedVariantName = variant.name
            .replace(Regex("([a-z])([A-Z])"), "$1-$2")
            .uppercase()

        val apkFolder = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
        val loader = variant.artifacts.getBuiltArtifactsLoader()
        val packageName = projectConfig.packageName

        val renameTask = tasks.register("rename${variant.name.replaceFirstChar { it.uppercase() }}Apk") {
            inputs.files(apkFolder)
            outputs.upToDateWhen { false }

            doLast {
                val builtArtifacts = loader.load(apkFolder.get()) ?: return@doLast

                val multipleOutputs = builtArtifacts.elements.size > 1
                builtArtifacts.elements.forEach { element ->
                    val apkFile = File(element.outputFile)
                    // Unsigned builds must stay recognizable; split outputs must not overwrite each other.
                    val unsignedMarker = if (apkFile.name.contains("unsigned")) "-UNSIGNED" else ""
                    val splitMarker = if (multipleOutputs) "-${apkFile.nameWithoutExtension}" else ""
                    val outputFileName =
                        "$packageName-v${element.versionName}-${element.versionCode}" +
                            "-$formattedVariantName$unsignedMarker$splitMarker.apk"
                    if (apkFile.exists() && apkFile.name != outputFileName) {
                        apkFile.copyTo(File(apkFile.parentFile, outputFileName), overwrite = true)
                    }
                }
            }
        }

        tasks.matching { it.name == "assemble${variant.name.replaceFirstChar { it.uppercase() }}" }.configureEach {
            finalizedBy(renameTask)
        }
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

    // Compose Preview Screenshot Testing — renders the Play Store screenshot composables
    // (app/src/screenshotTest) to PNGs on the JVM. Enabled via the experimental flag in
    // gradle.properties. Keep this version aligned with the plugin version in the root build script.
    "screenshotTestImplementation"(platform("androidx.compose:compose-bom:${Versions.Compose.bom}"))
    "screenshotTestImplementation"("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha15")
    "screenshotTestImplementation"("androidx.compose.ui:ui-tooling")
}
