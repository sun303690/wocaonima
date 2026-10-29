import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

/** Prevents Miuix-owned settings and feature configuration surfaces regressing to Material3. */
abstract class CheckMiuixPurityTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceRoot: DirectoryProperty

    @TaskAction
    fun check() {
        val root = sourceRoot.get().asFile
        val roots = listOf(
            root.resolve("ui/content/miuix"),
            root.resolve("ui/utils/theme/MiuixModuleTheme.kt"),
            root.resolve("activity/settings/MiuixSettingsContent.kt"),
            root.resolve("activity/self_check"),
            root.resolve("features/items"),
        )
        val files = roots.flatMap { target ->
            when {
                target.isDirectory -> target.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                target.isFile -> listOf(target)
                else -> emptyList()
            }
        }
        val offenders = files.filter { file ->
            file.useLines { lines -> lines.any { it.trim().startsWith("import androidx.compose.material3") } }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("Miuix scope must not import Material3:\n" + offenders.joinToString("\n") { it.relativeTo(root).path })
        }
    }
}
