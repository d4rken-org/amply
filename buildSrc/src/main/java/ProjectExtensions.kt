import org.gradle.api.Project

val Project.projectConfig: ProjectConfig
    get() = extensions.findByType(ProjectConfig::class.java)!!
