import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.util.Properties

open class ProjectConfig {
    val packageName = "eu.darken.amply"
    val minSdk = 26

    val compileSdk = 36
    val targetSdk = 36

    lateinit var version: Version

    override fun toString(): String {
        return "ProjectConfig($packageName, min=$minSdk, compile=$compileSdk, target=$targetSdk, version=$version)"
    }

    fun init(project: Project) {
        val versionProperties = Properties().apply {
            File(project.rootDir, "version.properties").inputStream().use(::load)
        }
        version = Version(
            major = versionProperties.getProperty("project.versioning.major").toInt(),
            minor = versionProperties.getProperty("project.versioning.minor").toInt(),
            patch = versionProperties.getProperty("project.versioning.patch").toInt(),
            build = versionProperties.getProperty("project.versioning.build").toInt(),
            type = versionProperties.getProperty("project.versioning.type"),
        )
    }

    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val build: Int,
        val type: String,
    ) {
        init {
            require(major >= 0) { "major must be >= 0, was $major" }
            require(minor in 0..99) { "minor must be 0..99 (100 collides with major+1), was $minor" }
            require(patch in 0..99) { "patch must be 0..99 (100 collides with minor+1), was $patch" }
            require(build in 0..99) { "build must be 0..99 (100 collides with patch+1), was $build" }
            require(type.isNotBlank() && type.all { it.isLetter() }) { "type must be letters only, was '$type'" }
            require(code in 1..2_100_000_000L) { "versionCode $code outside Android's supported range" }
        }

        val name: String
            get() = "$major.$minor.$patch-$type$build"
        val code: Long
            get() = major * 10_000_000L + minor * 100_000L + patch * 1_000L + build * 10L
    }
}

class ProjectConfigPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("projectConfig", ProjectConfig::class.java)
        extension.init(project)
        project.afterEvaluate { println("ProjectConfigPlugin loaded: $extension") }
    }
}
