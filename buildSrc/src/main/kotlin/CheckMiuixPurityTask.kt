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
        // 从 WcS 移植的 TTS/音色选择界面保持 Material3 实现（与上游一致），
        // 待后续整屏迁移到 Miuix 时再移出白名单。
        val allowlist = setOf(
            "features/items/chat/TextToSpeech.kt",
            "features/items/chat/DoubaoVoicePicker.kt",
            // 引擎桥接层必须同时引用 M3 与 Miuix，属于分派实现而非 Miuix 页面。
            "ui/content/miuix/EngineBridge.kt",
            // WCS「AI 候选回复」整屏按上游 Material3 实现移植，待迁 Miuix 后移出。
            "features/items/chat/AiReplyCandidates.kt",
        )
        val offenders = files.filter { file ->
            file.relativeTo(root).path !in allowlist &&
                file.useLines { lines -> lines.any { it.trim().startsWith("import androidx.compose.material3") } }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("Miuix scope must not import Material3:\n" + offenders.joinToString("\n") { it.relativeTo(root).path })
        }
    }
}
