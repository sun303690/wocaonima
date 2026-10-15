package dev.sun.wechat.features.items.scripting_center

import dev.sun.wechat.utils.WeLogger
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 在线插件市场下载器：下载 zip → 校验（sha256 可选、大小上限、路径逃逸）→ 落位。
 *
 * - Java 插件：解压到 `scripts_java/<id>/`（main.java + info.prop + 可选附属文件）；
 * - Python 插件：解压到 `scripts_python/<id>/`（plugin.json + 入口脚本）。
 *
 * 落位后由引擎侧的 discover/load 流程接管；本类不做任何激活。
 */
object ScriptMarketDownloader {
    private const val TAG = "ScriptMarketDownloader"
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 30000
    private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024
    private const val MAX_EXTRACTED_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_ENTRIES = 256

    /** 下载并安装一个插件。返回结果描述（成功/失败原因），供 UI toast 展示。 */
    fun downloadAndInstall(
        entry: MarketEntry,
        javaScriptsDir: File,
        pythonPluginsDir: File,
    ): String {
        val targetRoot = File(if (entry.runtime == MarketRuntime.PYTHON) pythonPluginsDir else javaScriptsDir, entry.id)
        val staging = File(targetRoot.parentFile, ".${entry.id}-installing")
        return try {
            staging.deleteRecursively()
            staging.mkdirs()
            val bytes = download(entry.download) ?: return "下载失败：源不可达"
            if (bytes.size > MAX_DOWNLOAD_BYTES) return "下载失败：超过大小上限"
            if (entry.sha256.isNotBlank() && !sha256Matches(bytes, entry.sha256)) {
                return "下载失败：sha256 校验不通过"
            }
            extractZip(bytes, staging)
            validateExtracted(staging, entry.runtime)
            targetRoot.deleteRecursively()
            if (!staging.renameTo(targetRoot)) {
                staging.deleteRecursively()
                return "安装失败：落位失败"
            }
            WeLogger.i(TAG, "installed market plugin '${entry.id}' (${entry.runtime})")
            "已安装 ${entry.name}"
        } catch (t: Throwable) {
            staging.deleteRecursively()
            WeLogger.w(TAG, "install failed for '${entry.id}': ${t.message}")
            "安装失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** 删除一个本地安装的插件目录。 */
    fun uninstall(targetRoot: File): Boolean = runCatching {
        targetRoot.deleteRecursively()
        !targetRoot.exists()
    }.getOrDefault(false)

    private fun download(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/zip, */*")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                WeLogger.w(TAG, "download HTTP ${conn.responseCode}: $url")
                return null
            }
            input = conn.inputStream
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_DOWNLOAD_BYTES) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (t: Throwable) {
            WeLogger.w(TAG, "download failed: ${t.message}")
            null
        } finally {
            runCatching { input?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    /** 解压 zip 到 [targetRoot]，拒绝路径逃逸（zip slip）与符号链接风格的异常条目名。 */
    private fun extractZip(bytes: ByteArray, targetRoot: File) {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var count = 0
            while (true) {
                val e = zip.nextEntry ?: break
                count++
                if (count > MAX_ENTRIES) error("压缩包条目过多")
                val name = e.name
                if (name.isBlank() || name.contains("..") || name.startsWith("/")) {
                    error("压缩包含非法路径条目: $name")
                }
                val outFile = File(targetRoot, name)
                if (!outFile.canonicalPath.startsWith(targetRoot.canonicalPath + File.separator)) {
                    error("压缩包条目逃逸其根目录: $name")
                }
                if (e.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use(zip::copyTo)
                    if (outFile.length() > MAX_EXTRACTED_FILE_BYTES) error("解压文件过大: $name")
                }
                zip.closeEntry()
            }
        }
        if (targetRoot.list().isNullOrEmpty()) error("压缩包为空")
    }

    /** 校验解压后的插件目录结构（Java: main.java+info.prop；Python: plugin.json）。 */
    private fun validateExtracted(root: File, runtime: MarketRuntime) {
        when (runtime) {
            MarketRuntime.JAVA -> {
                require(File(root, "main.java").isFile) { "缺少 main.java" }
                require(File(root, "info.prop").isFile) { "缺少 info.prop" }
            }
            MarketRuntime.PYTHON -> {
                require(File(root, "plugin.json").isFile) { "缺少 plugin.json" }
            }
        }
    }

    private fun sha256Matches(bytes: ByteArray, expected: String): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        digest.equals(expected.lowercase(), ignoreCase = true)
    }.getOrDefault(false)
}