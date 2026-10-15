import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a source file's name doesn't end in an allowed role suffix, or when a source file (not a test)
 * passes [MAX_LINES] lines: split it with a qualifier, never a new suffix (DESIGN §3.3).
 */
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
        val long = sources.files
            .filter { it.isFile && it.extension == "kt" && !isTest(it.path) }
            .map { it to it.readLines().size }
            .filter { (_, n) -> n > MAX_LINES }
            .map { (f, n) -> "${f.path.substringAfter("/src/")} ($n lines)" }
        if (long.isNotEmpty()) {
            throw GradleException(
                "Files over $MAX_LINES lines (DESIGN §3.3): split each with a qualifier, e.g. FooRules → FooParseRules + FooFormatRules:\n" +
                    long.joinToString("\n") { "  - $it" },
            )
        }
    }

    private fun isAllowed(path: String, name: String): Boolean {
        if (name in ENTRY_POINTS) return true
        if (path.contains("/spike/")) return true // M0 only; deleted at M0.12
        if (isTest(path)) {
            return name.endsWith("Test") || name.endsWith("Fakes") || name.endsWith("Fixtures")
        }
        return ROLES.any { name.endsWith(it) && name.length > it.length }
    }

    private fun isTest(path: String) =
        path.contains("/src/test/") || path.contains("/src/androidTest/") || path.contains("/src/testFixtures/")

    private companion object {
        const val MAX_LINES = 300
        val ROLES = listOf(
            "Model", "Rules", "Ports", "UseCase", "Session", "Parser", "Client", "Store", "Dto", "Mapper",
            "Renderer", "ViewModel", "UiState", "Screen", "Component", "View", "Device", "Registry",
            "Animator", "Cache", "Database", "Colors", "Type", "Theme",
        )
        val ENTRY_POINTS = setOf("NisabaApp", "MainActivity", "AppGraph", "Navigation", "ToolsMain")
    }
}
