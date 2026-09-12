package dev.mcfry64.tworld

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import kotlin.math.abs

object MusicManager {
    private const val TAG = "MusicManager"
    private var mediaPlayer: MediaPlayer? = null
    private var isEnabled = true
    private var isPaused = false

    private var activeBgmTheme: String = "MS"
    private var currentTrackPath: String? = null
    private var volume: Float = 1.0f

    fun isPaused() = isPaused

    fun isPlaying(): Boolean = try {
        mediaPlayer?.isPlaying == true
    } catch (_: Exception) {
        false
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0.0f, 1.0f)
        try {
            mediaPlayer?.setVolume(volume, volume)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
        }
    }



    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            stopMusic()
        }
    }

    fun setActiveBgmTheme(themeName: String) {
        if (activeBgmTheme != themeName) {
            activeBgmTheme = themeName
            stopMusic()
        }
    }

    @JvmStatic
    fun stopMusicFromNative() {
        stopMusic()
    }



    private fun getBgmDirectory(context: Context): File {
        val base = context.getExternalFilesDir(null)?.parent ?: context.filesDir.absolutePath
        val resBgmDir = File(base, "res/bgm/$activeBgmTheme")
        if (resBgmDir.exists() && resBgmDir.isDirectory) return resBgmDir

        // Fallback to base res/bgm
        val baseBgmDir = File(base, "res/bgm")
        if (baseBgmDir.exists() && baseBgmDir.isDirectory) return baseBgmDir

        return resBgmDir
    }

    fun playPreviewTrack(context: Context) {
        if (!isEnabled) return
        if (isPlaying()) return

        val dir = getBgmDirectory(context)
        val oggFiles = dir.listFiles()?.filter { it.isFile && (it.extension.lowercase() == "ogg") }
        if (oggFiles.isNullOrEmpty()) {
            Log.e(TAG, "No ogg files found in ${dir.absolutePath}")
            return
        }
        val randomFile = oggFiles.random()
        playFile(randomFile)
    }

    fun playMusicForLevel(context: Context, levelNum: Int) {
        if (!isEnabled) return

        val dir = getBgmDirectory(context)
        val oggFiles = dir.listFiles()?.filter { it.isFile && (it.extension.lowercase() == "ogg") }
        if (oggFiles.isNullOrEmpty()) {
            Log.e(TAG, "No ogg files found in ${dir.absolutePath}")
            return
        }

        val oggMap = oggFiles.associateBy { it.name.lowercase() }
        val hasC1 = oggMap.containsKey("c1.ogg")
        val hasC2 = oggMap.containsKey("c2.ogg")
        val hasCA = oggMap.containsKey("ca.ogg")

        val targetFile: File = if (hasC1 && hasC2 && hasCA) {
            // Standard C1/C2/CA rotation engine
            when (levelNum % 3) {
                1 -> oggMap["c2.ogg"]!!
                2 -> oggMap["ca.ogg"]!!
                0 -> oggMap["c1.ogg"]!!
                else -> oggMap["c1.ogg"]!!
            }
        } else {
            // Other ogg files: Choose random ogg file per level
            val sortedFiles = oggFiles.sortedBy { it.name.lowercase() }
            val index = abs((levelNum * 31) + activeBgmTheme.hashCode()) % sortedFiles.size
            sortedFiles[index]
        }

        if ((currentTrackPath == targetFile.absolutePath) && isPlaying()) {
            return
        }

        playFile(targetFile)
    }

    private fun playFile(musicFile: File) {
        try {
            stopMusic()
            isPaused = false

            if (!musicFile.exists()) {
                Log.e(TAG, "Music file not found: ${musicFile.absolutePath}")
                return
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .build(),
                )
                setDataSource(musicFile.absolutePath)
                isLooping = true
                setVolume(this@MusicManager.volume, this@MusicManager.volume)
                prepare()
                start()
            }
            currentTrackPath = musicFile.absolutePath
            Log.d(TAG, "Playing track: ${musicFile.name} from ${musicFile.parent}")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing music file: ${musicFile.absolutePath}", e)
            stopMusic()
        }
    }

    fun stopMusic() {
        try {
            mediaPlayer?.apply {
                try {
                    if (isPlaying) stop()
                } catch (_: Exception) {}
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping music", e)
        } finally {
            mediaPlayer = null
            currentTrackPath = null
            isPaused = false
        }
    }

    fun pauseMusic() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing music", e)
        } finally {
            isPaused = true
        }
    }

    fun resumeMusic() {
        if (!isEnabled) return
        try {
            mediaPlayer?.start()
            isPaused = false
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming music", e)
        }
    }
}
