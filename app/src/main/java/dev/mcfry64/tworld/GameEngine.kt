package dev.mcfry64.tworld

import androidx.annotation.Keep
import android.content.Context
import android.graphics.Bitmap
import java.io.File

@Keep
object GameEngine {

    init { System.loadLibrary("tworld") }

    var onSfxTextListener: ((String) -> Unit)? = null

    @JvmStatic
    fun onSfxTextFromNative(text: String) {
        onSfxTextListener?.invoke(text)
    }

    // ── JNI declarations ──────────────────────────────────────────────────

    @JvmStatic external fun nativeInit(
        dataDir: String,
        resDir: String,
        setsDir: String,
        saveDir: String,
    ): Boolean

    @JvmStatic external fun nativeStart(levelSet: String, levelNum: Int, tileset: String): Boolean
    @JvmStatic external fun nativeSendKey(twk: Int, down: Boolean)
    @JvmStatic external fun nativeSendTouch(x: Int, y: Int, down: Boolean)
    @JvmStatic external fun nativeCopyPixels(bitmap: Bitmap)
    @JvmStatic external fun nativeGetScreenWidth(): Int
    @JvmStatic external fun nativeGetScreenHeight(): Int
    @JvmStatic external fun nativeIsRunning(): Boolean
    @JvmStatic external fun nativeStop()
    /** Returns +1 (show keyboard), -1 (hide keyboard), or 0 (no change). */
    @JvmStatic external fun nativeGetKeyboardRequest(): Int
    /** Send a typed character directly (queued as down+up pair in C). */
    @JvmStatic external fun nativeTypeChar(twk: Int)
    @JvmStatic external fun nativeAudioPause()
    @JvmStatic external fun nativeAudioResume()
    @JvmStatic external fun nativeSetSfxEnabled(enabled: Boolean)
    @JvmStatic external fun nativeSetSfxVolume(volume: Int)
    @JvmStatic external fun nativeSetSfxTheme(theme: String)
    @JvmStatic external fun nativeShowMessage(text: String)
    @JvmStatic external fun nativeSetGameSpeed(percent: Int)
    @JvmStatic external fun nativeSetUnlimitedTime(unlimited: Boolean)
    @JvmStatic external fun nativeIsGamePaused(): Boolean
    @JvmStatic external fun nativeGetLevelEndState(): Int
    @JvmStatic external fun nativeGetCurrentLevelNumber(): Int
    @JvmStatic external fun nativeGetCurrentLevelName(): String
    @JvmStatic external fun nativeGetCurrentLevelAuthor(): String

    // ── TWK constants (must match oshw-android/oshwbind.h) ───────────────
    const val TWK_BACKSPACE = 8
    const val TWK_RETURN    = 13
    const val TWK_ESCAPE    = 27
    const val TWK_UP        = 256
    const val TWK_DOWN      = 257
    const val TWK_LEFT      = 258
    const val TWK_RIGHT     = 259
    const val TWK_PAGEUP    = 269
    const val TWK_PAGEDOWN  = 270

    const val TWC_SEESCORES = 200
    const val TWC_PAUSEGAME = 206
    const val TWC_SAMELEVEL = 207
    const val TWC_GOTOLEVEL = 210
    const val TWC_KEYS      = 217
    const val TWC_TILESET   = 218

    // ── Asset extraction & Themes ────────────────────────────────────────

    /** Scans res/sfx/ on disk for directories containing an rc file.
     *  Returns a list starting with "Tile World" (default theme). */
    fun getAvailableSfxThemes(context: Context): List<String> {
        val (res, _, _) = extractAssets(context)
        val sfxDir = File(res, "sfx")
        val themes = mutableSetOf("Tile World")

        if (sfxDir.exists() && sfxDir.isDirectory) {
            sfxDir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    val rcFile = File(file, "rc")
                    if (rcFile.exists() && rcFile.isFile && (file.name != "Tile World")) {
                        themes.add(file.name)
                    }
                }
            }
        }
        val sortedSubThemes = themes.asSequence().filter { it != "Tile World" }.sortedWith(String.CASE_INSENSITIVE_ORDER).toList()
        return listOf("Tile World") + sortedSubThemes
    }

    /** Scans res/bgm/ on disk for directories containing .ogg files.
     *  Note: "MS" theme is ONLY included if c1.ogg, c2.ogg, and ca.ogg are all present. */
    fun getAvailableBgmThemes(context: Context): List<String> {
        val (res, _, _) = extractAssets(context)
        val bgmDir = File(res, "bgm")
        val themes = mutableSetOf<String>()

        if (bgmDir.exists() && bgmDir.isDirectory) {
            bgmDir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    val oggFiles = file.listFiles()?.map { it.name.lowercase() } ?: emptyList()
                    if (file.name.equals("MS", ignoreCase = true)) {
                        val hasC1 = oggFiles.contains("c1.ogg")
                        val hasC2 = oggFiles.contains("c2.ogg")
                        val hasCA = oggFiles.contains("ca.ogg")
                        if (hasC1 && hasC2 && hasCA) {
                            themes.add("MS")
                        }
                    } else {
                        val hasOgg = oggFiles.any { it.endsWith(".ogg") }
                        if (hasOgg) {
                            themes.add(file.name)
                        }
                    }
                }
            }
        }
        return themes.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** Extract APK assets to /sdcard/Android/data/dev.mcfry64.tworld/ so files
     *  can be replaced via ADB without clearing app data. Falls back to internal
     *  storage if external is unavailable. */
    fun extractAssets(context: Context): Triple<String, String, String> {
        val base = context.getExternalFilesDir(null)?.parent ?: context.filesDir.absolutePath
        val res  = "$base/res"
        val data = "$base/data"
        val sets = "$base/sets"
        val save = "$base/save"
        for (dir in listOf(res, data, sets, save)) File(dir).mkdirs()

        extractDir(context, "res",  res)
        extractDir(context, "data", data)
        extractDir(context, "sets", sets)

        return Triple(res, data, sets)
    }

    private fun extractDir(context: Context, assetSubDir: String, destDir: String) {
        val names = try { context.assets.list(assetSubDir) } catch (_: Exception) { null }
            ?: return

        // Clean up files in destination that no longer exist in assets
        val destFileDir = File(destDir)
        if (destFileDir.exists()) {
            destFileDir.listFiles()?.forEach { existingFile ->
                if (!names.contains(existingFile.name)) {
                    // Do not delete user custom theme directories inside res/sfx or res/bgm
                    val isCustomSfxTheme = assetSubDir.endsWith("sfx") && existingFile.isDirectory && File(existingFile, "rc").exists()
                    val isCustomBgmTheme = assetSubDir.endsWith("bgm") && existingFile.isDirectory && (existingFile.listFiles()?.any { it.extension.lowercase() == "ogg" } == true)
                    if (!isCustomSfxTheme && !isCustomBgmTheme) {
                        existingFile.deleteRecursively()
                    }
                }
            }
        }

        for (name in names) {
            val assetPath = if (assetSubDir.isEmpty()) name else "$assetSubDir/$name"
            val dest = File(destDir, name)

            val children = try { context.assets.list(assetPath) } catch (_: Exception) { null }
            if (!children.isNullOrEmpty()) {
                // It's a directory, recurse
                dest.mkdirs()
                extractDir(context, assetPath, dest.absolutePath)
            } else {
                // It's a file (or empty directory)
                try {
                    context.assets.open(assetPath).use { src ->
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { dst -> src.copyTo(dst) }
                    }
                } catch (_: Exception) {
                    // If it's not a file we can open, it might be an empty directory
                    dest.mkdirs()
                }
            }
        }
    }
}
