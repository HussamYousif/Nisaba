import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails the build when a source file's name doesn't end in an allowed role suffix (DESIGN §3.3). */
abstract class VerifyFileRolesTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val bad = sources.files
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filterNot { isAllowed(it.path, it.nameWithoutExtension) }
            .map { it.path.substringAfter("/src/") }
        if (bad.isNotEmpty()) {
            throw GradleException(
                "File-role convention violated (DESIGN §3.3). Rename or use an allowed suffix:\n" +
                    bad.joinToString("\n") { "  - $it" } +
                    "\nAllowed: ${ROLES.joinToString(", ")}",
            )
        }
    }

    private fun isAllowed(path: String, name: String): Boolean {
        if (name in ENTRY_POINTS) return true
        if (path.contains("/spike/")) return true // M0 only; deleted at M0.12
        if (path.contains("/src/test/") || path.contains("/src/androidTest/") || path.contains("/src/testFixtures/")) {
            return name.endsWith("Test") || name.endsWith("Fakes") || name.endsWith("Fixtures")
        }
        return ROLES.any { name.endsWith(it) && name.length > it.length }
    }

    private companion object {
        val ROLES = listOf(
            "Model", "Rules", "Ports", "UseCase", "Session", "Parser", "Client", "Store", "Dto", "Mapper",
            "Renderer", "ViewModel", "UiState", "Screen", "Component", "View", "Device", "Registry",
            "Animator", "Cache", "Database", "Colors", "Type", "Theme",
        )
        val ENTRY_POINTS = setOf("NisabaApp", "MainActivity", "AppGraph", "Navigation", "ToolsMain")
    }
}
