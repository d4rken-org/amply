import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.DependencyHandlerScope

private fun DependencyHandler.implementation(dependencyNotation: Any): Dependency? =
    add("implementation", dependencyNotation)

private fun DependencyHandler.debugImplementation(dependencyNotation: Any): Dependency? =
    add("debugImplementation", dependencyNotation)

private fun DependencyHandler.testImplementation(dependencyNotation: Any): Dependency? =
    add("testImplementation", dependencyNotation)

private fun DependencyHandler.testRuntimeOnly(dependencyNotation: Any): Dependency? =
    add("testRuntimeOnly", dependencyNotation)

private fun DependencyHandler.androidTestImplementation(dependencyNotation: Any): Dependency? =
    add("androidTestImplementation", dependencyNotation)

private fun DependencyHandler.ksp(dependencyNotation: Any): Dependency? =
    add("ksp", dependencyNotation)

private fun DependencyHandler.coreLibraryDesugaring(dependencyNotation: Any): Dependency? =
    add("coreLibraryDesugaring", dependencyNotation)

fun DependencyHandlerScope.addBaseKotlin() {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.Kotlin.coroutines}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.Kotlin.coroutines}")
}

fun DependencyHandlerScope.addBaseAndroid() {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${Versions.AndroidX.lifecycle}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${Versions.AndroidX.lifecycle}")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}

fun DependencyHandlerScope.addDagger() {
    implementation("com.google.dagger:hilt-android:${Versions.Dagger.core}")
    ksp("com.google.dagger:hilt-compiler:${Versions.Dagger.core}")
}

fun DependencyHandlerScope.addCompose() {
    val composeBom = platform("androidx.compose:compose-bom:${Versions.Compose.bom}")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:${Versions.AndroidX.lifecycle}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:${Versions.AndroidX.lifecycle}")
}

fun DependencyHandlerScope.addNavigation3() {
    implementation("androidx.navigation3:navigation3-runtime-android:${Versions.Navigation3.core}")
    implementation("androidx.navigation3:navigation3-ui-android:${Versions.Navigation3.core}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3-android:${Versions.AndroidX.lifecycle}")
}

fun DependencyHandlerScope.addDataStore() {
    implementation("androidx.datastore:datastore-preferences:1.2.0")
}

fun DependencyHandlerScope.addGlance() {
    implementation("androidx.glance:glance-appwidget:${Versions.Glance.core}")
    implementation("androidx.glance:glance-material3:${Versions.Glance.core}")
}

fun DependencyHandlerScope.addShizuku() {
    implementation("dev.rikka.shizuku:api:${Versions.Shizuku.core}")
    implementation("dev.rikka.shizuku:provider:${Versions.Shizuku.core}")
}

fun DependencyHandlerScope.addTesting() {
    testImplementation(platform("org.junit:junit-bom:${Versions.JUnit.bom}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.kotest:kotest-assertions-core:${Versions.Kotest.core}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Vintage engine runs the JUnit 4 Robolectric tests on the JUnit Platform.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
