package com.mifan.kt.rom

import android.annotation.SuppressLint
import android.os.Build
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Loads [libdexkit] once per process and opens short-lived [DexKitBridge] instances.
 *
 * Under LSPosed, [System.loadLibrary] often fails because [LspModuleClassLoader] only looks
 * at `base.apk!/lib/<abi>` (zip path) while AGP may keep compressed libs or extract them to
 * `/data/app/.../lib/arm64/libdexkit.so`. This loader tries, in order:
 * 1. [System.loadLibrary]
 * 2. Absolute path next to the module APK (`lib/<abi>/libdexkit.so`)
 * 3. Extract from the module APK into the host cache, then [System.load]
 */
internal object DexKitSupport {
    private const val MODULE_PACKAGE = "com.mifan.kt"
    private const val LIB_NAME = "dexkit"
    private const val SO_FILE = "libdexkit.so"

    private val libraryLoaded = AtomicBoolean(false)
    private val loadLock = Any()

    @Volatile
    private var loadError: Throwable? = null

    @Volatile
    private var loadSource: String = "none"

    fun ensureLibraryLoaded(): Boolean {
        if (libraryLoaded.get()) return true
        synchronized(loadLock) {
            if (libraryLoaded.get()) return true
            val errors = mutableListOf<String>()

            // 1) Normal load (works when classloader can see the so).
            try {
                System.loadLibrary(LIB_NAME)
                libraryLoaded.set(true)
                loadError = null
                loadSource = "System.loadLibrary"
                return true
            } catch (t: Throwable) {
                errors += "loadLibrary: ${t.message}"
            }

            val moduleApk = resolveModuleApkPath()
            if (moduleApk.isNullOrBlank()) {
                loadError = IllegalStateException("module apk not found; " + errors.joinToString(" | "))
                return false
            }

            // 2) Prefer install-extracted native library directory.
            for (candidate in candidateExtractedLibPaths(moduleApk)) {
                if (!candidate.isFile) continue
                try {
                    System.load(candidate.absolutePath)
                    libraryLoaded.set(true)
                    loadError = null
                    loadSource = "extracted:${candidate.absolutePath}"
                    return true
                } catch (t: Throwable) {
                    errors += "load(${candidate.absolutePath}): ${t.message}"
                }
            }

            // 3) Extract so from APK into a writable host-side cache.
            val extracted = extractSoFromApk(moduleApk)
            if (extracted != null) {
                try {
                    System.load(extracted.absolutePath)
                    libraryLoaded.set(true)
                    loadError = null
                    loadSource = "apk-extract:${extracted.absolutePath}"
                    return true
                } catch (t: Throwable) {
                    errors += "load(extracted ${extracted.absolutePath}): ${t.message}"
                }
            } else {
                errors += "apk extract failed from $moduleApk"
            }

            loadError = UnsatisfiedLinkError(errors.joinToString(" || "))
            return false
        }
    }

    fun lastLoadError(): Throwable? = loadError

    fun loadSource(): String = loadSource

    /**
     * Opens a bridge for the given host APK path. Caller must close the bridge
     * (prefer `use { }`).
     */
    fun open(apkPath: String): DexKitBridge? {
        if (apkPath.isBlank()) return null
        if (!ensureLibraryLoaded()) return null
        return try {
            DexKitBridge.create(apkPath)
        } catch (t: Throwable) {
            loadError = t
            null
        }
    }

    inline fun <T> withBridge(apkPath: String?, block: (DexKitBridge) -> T): T? {
        if (apkPath.isNullOrBlank()) return null
        val bridge = open(apkPath) ?: return null
        return bridge.use(block)
    }

    private fun candidateExtractedLibPaths(moduleApk: String): List<File> {
        val apkFile = File(moduleApk)
        val baseDir = apkFile.parentFile ?: return emptyList()
        val abis = Build.SUPPORTED_ABIS.toList() + listOf("arm64-v8a", "armeabi-v7a", "arm64", "arm")
        val dirs = listOf("lib", "lib/arm64", "lib/arm")
        val out = LinkedHashSet<File>()
        for (abi in abis.distinct()) {
            out += File(baseDir, "lib/$abi/$SO_FILE")
            out += File(baseDir, "lib/${abi.replace("-v8a", "").replace("armeabi-v7a", "arm")}/$SO_FILE")
        }
        // Common AGP extract path on 64-bit devices.
        out += File(baseDir, "lib/arm64/$SO_FILE")
        out += File(baseDir, "lib/arm/$SO_FILE")
        for (dir in dirs) {
            out += File(baseDir, "$dir/$SO_FILE")
        }
        return out.toList()
    }

    private fun extractSoFromApk(moduleApk: String): File? {
        val cacheRoot = resolveWritableCacheDir() ?: return null
        val abis = Build.SUPPORTED_ABIS.toList() + listOf("arm64-v8a", "armeabi-v7a")
        return try {
            ZipFile(moduleApk).use { zip ->
                for (abi in abis.distinct()) {
                    val entryName = "lib/$abi/$SO_FILE"
                    val entry = zip.getEntry(entryName) ?: continue
                    val destDir = File(cacheRoot, "mifan-dexkit/$abi").apply { mkdirs() }
                    val dest = File(destDir, SO_FILE)
                    if (!dest.exists() || dest.length() != entry.size) {
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(dest).use { output -> input.copyTo(output) }
                        }
                        dest.setReadable(true, false)
                        dest.setExecutable(true, false)
                    }
                    if (dest.isFile && dest.length() > 0L) return dest
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    private fun resolveModuleApkPath(): String? {
        // Host Application context → installed module package path.
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null) as? android.content.Context
            if (app != null) {
                val ai = app.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
                if (!ai.sourceDir.isNullOrBlank()) return ai.sourceDir
            }
        } catch (_: Throwable) {
        }

        // Code source of this class (sometimes the module APK path).
        try {
            val url = DexKitSupport::class.java.protectionDomain?.codeSource?.location
            val path = url?.toURI()?.path
            if (!path.isNullOrBlank() && path.endsWith(".apk") && File(path).isFile) return path
        } catch (_: Throwable) {
        }

        // Parse LspModuleClassLoader toString: "module=/data/app/.../base.apk, ..."
        try {
            val text = DexKitSupport::class.java.classLoader?.toString().orEmpty()
            val marker = "module="
            val idx = text.indexOf(marker)
            if (idx >= 0) {
                val start = idx + marker.length
                val end = text.indexOf(',', start).takeIf { it > start } ?: text.indexOf(']', start)
                if (end > start) {
                    val path = text.substring(start, end).trim()
                    if (path.endsWith(".apk") && File(path).isFile) return path
                }
            }
            val match = Regex("""(/data/app/[^\]\s,]+/base\.apk)""").find(text)
            if (match != null && File(match.groupValues[1]).isFile) return match.groupValues[1]
        } catch (_: Throwable) {
        }
        return null
    }

    @SuppressLint("PrivateApi")
    private fun resolveWritableCacheDir(): File? {
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null) as? android.content.Context
            val cache = app?.codeCacheDir ?: app?.cacheDir
            if (cache != null) {
                cache.mkdirs()
                return cache
            }
        } catch (_: Throwable) {
        }
        // Last resort: module-side files dir may not be writable from host UID; try java tmp.
        val tmp = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        return if (tmp.exists() || tmp.mkdirs()) tmp else null
    }
}
