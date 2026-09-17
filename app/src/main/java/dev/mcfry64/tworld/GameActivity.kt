@file:Suppress("SetTextI18n")

package dev.mcfry64.tworld

import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import android.annotation.SuppressLint
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import android.media.AudioAttributes
import android.media.SoundPool
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import kotlin.math.abs

class GameActivity : ComponentActivity() {

    private lateinit var gameView: GameSurfaceView
    private lateinit var setsSpinner: Spinner
    private lateinit var msTilesSpinner: Spinner
    private lateinit var lynxTilesSpinner: Spinner
    private lateinit var levelsSpinner: Spinner
    private lateinit var setProgressBar: ProgressBar
    private lateinit var progressOverlayText: TextView
    private lateinit var startButton: Button
    private lateinit var btnTouchToggle: View
    private lateinit var tvTouchLabel: TextView
    private var isLynx = false
    private var gameTitleText: TextView? = null

    private var currentSetProgress: SetProgress? = null
    private var isGameRunning = false
    private var isEnginePaused = false
    private var activeSetName: String = ""
    private var activeTileset: String = ""

    private val msSets = linkedMapOf(
        "Chip's Challenge (MS)" to "cc-ms.dac",
        "Intro (MS)" to "intro-ms.dac",
        "Level Pack 1 (MS)" to "CCLP1-MS.dac",
        "Level Pack 2 (MS)" to "CCLP2.dac",
        "Level Pack 3 (MS)" to "CCLP3-MS.dac",
        "Level Pack 4 (MS)" to "CCLP4-MS.dac",
        "Level Pack 5 (MS)" to "CCLP5-MS.dac",
    )
    private val lynxSets = linkedMapOf(
        "Chip's Challenge (LYNX)" to "cc-fixlynx.dac",
        "Intro (Lynx)" to "intro-lynx.dac",
        "Level Pack 1 (Lynx)" to "CCLP1-Lynx.dac",
        "Level Pack 2 (Lynx)" to "CCLXP2.dac",
        "Level Pack 3 (Lynx)" to "CCLP3-Lynx.dac",
        "Level Pack 4 (Lynx)" to "CCLP4-Lynx.dac",
        "Level Pack 5 (Lynx)" to "CCLP5-Lynx.dac",
    )

    private fun getCurrentMap(): Map<String, String> {
        val base = getExternalFilesDir(null)?.parent ?: filesDir.absolutePath
        val chipsFile = File("$base/data", "CHIPS.dat")
        val hasChips = (chipsFile.exists() && (chipsFile.length() > 0))

        val baseMap = LinkedHashMap(if (isLynx) lynxSets else msSets)
        val filteredMap = if (hasChips) {
            baseMap
        } else {
            LinkedHashMap(baseMap.filterKeys { (it != "Chip's Challenge (MS)") && (it != "Chip's Challenge (LYNX)") && (it != "Chip's Challenge (Lynx)") })
        }

        val bundledDacNames = msSets.values + lynxSets.values

        // Scan custom .dac files in sets/ (excluding bundled MS/Lynx .dac files)
        val setsDir = File("$base/sets")
        if (setsDir.exists() && setsDir.isDirectory) {
            setsDir.listFiles { _, name -> name.lowercase().endsWith(".dac") }?.forEach { dacFile ->
                val dacName = dacFile.name
                if (!bundledDacNames.contains(dacName) && !filteredMap.containsValue(dacName)) {
                    val friendlyName = dacFile.nameWithoutExtension
                    filteredMap[friendlyName] = dacName
                }
            }
        }
        return filteredMap
    }

    private var soundPool: SoundPool? = null
    private var tingSoundId: Int = 0
    private var sfxStreamId: Int = 0
    private var lastSfxPlayTime: Long = 0L

    companion object {
        private const val TAG = "TileWorldLauncher"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fix Android launcher re-launch bug: If app is already running, prevent launching a new launcher activity
        if ((!isTaskRoot) && (intent != null) && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && (Intent.ACTION_MAIN == intent.action)) {
            finish()
            return
        }

        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attribs = window.attributes
            attribs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attribs
        }

        onBackPressedDispatcher.addCallback(this) {
            handleBackPress()
        }

        showLauncher()
    }

    private fun handleBackPress() {
        if (isGameRunning) {
            if (::gameView.isInitialized && !GameEngine.nativeIsGamePaused()) {
                GameEngine.nativeTypeChar(GameEngine.TWC_PAUSEGAME)
            }
            moveTaskToBack(true)
        } else {
            moveTaskToBack(true)
        }
    }

    private fun showLauncher() {
        Log.d(TAG, "showLauncher: Resetting UI to level selector")
        isGameRunning = false
        isEnginePaused = false
        val prefs = getSharedPreferences("tworld_prefs", MODE_PRIVATE)
        hideSystemUI()
        MusicManager.stopMusic()
        setContentView(R.layout.activity_main)

        val rootLauncher = findViewById<View>(R.id.root_launcher)
        try {
            val bgFile = File(filesDir, "res/background.bmp")
            val bmp = if (bgFile.exists()) {
                BitmapFactory.decodeFile(bgFile.absolutePath)
            } else {
                BitmapFactory.decodeStream(assets.open("res/background.bmp"))
            }
            if (bmp != null) {
                // Match the exact scaling factor used by C++ GameSurfaceView (screenWidth / 288f)
                val gameScale = resources.displayMetrics.widthPixels.toFloat() / (9 * 32f)
                val scaledW = maxOf(1, (bmp.width * gameScale).toInt())
                val scaledH = maxOf(1, (bmp.height * gameScale).toInt())
                val scaledBmp = bmp.scale(scaledW, scaledH)

                val tiledDrawable = scaledBmp.toDrawable(resources).apply {
                    setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                }
                rootLauncher?.background = tiledDrawable
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting menu tiled background", e)
        }
        
        val (res, data, sets) = GameEngine.extractAssets(this)

        setsSpinner = findViewById(R.id.spinner_sets)
        msTilesSpinner = findViewById(R.id.spinner_ms_tiles)
        lynxTilesSpinner = findViewById(R.id.spinner_lynx_tiles)
        levelsSpinner = findViewById(R.id.spinner_levels)
        setProgressBar = findViewById(R.id.progress_set)
        progressOverlayText = findViewById(R.id.text_progress_overlay)
        startButton = findViewById(R.id.btn_start)
        btnTouchToggle = findViewById(R.id.btn_touch_toggle)
        tvTouchLabel = findViewById(R.id.tv_touch_label)
        val save = "${filesDir.absolutePath}/save"

        val passwordButton = findViewById<ImageButton>(R.id.btn_password)
        passwordButton.setOnClickListener {
            showPasswordDialog(data, sets)
        }

        Log.d(TAG, "Assets paths: res=$res data=$data sets=$sets save=$save")

        val bgmSeekBar = findViewById<SeekBar>(R.id.sb_bgm_volume)
        val sfxSeekBar = findViewById<SeekBar>(R.id.sb_sfx_volume)

        if (soundPool == null) {
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(audioAttrs)
                .build()
            try {
                val afd = assets.openFd("res/ting.wav")
                tingSoundId = soundPool?.load(afd, 1) ?: 0
            } catch (e: Exception) {
                Log.e(TAG, "Error loading ting.wav", e)
            }
        }

        val audioMarqueeText = findViewById<TextView>(R.id.text_audio_marquee)
        audioMarqueeText?.isSingleLine = true
        audioMarqueeText?.isSelected = true

        val bgmThemeSpinner = findViewById<Spinner>(R.id.spinner_bgm_theme)
        val sfxThemeSpinner = findViewById<Spinner>(R.id.spinner_sfx_theme)

        val marqueeHandler = Handler(Looper.getMainLooper())
        var revertMarqueeRunnable: Runnable? = null
        var startScrollRunnable: Runnable? = null

        fun getBgmThemeDescription(themeName: String): String {
            return when (themeName.trim().uppercase()) {
                "AKI" -> "+++ [♪] AKI — Demoscene legend since 1994. Discover her music: YT▶ @aki_128 ☕ ko-fi.com/aki128 +++"
                "MS" -> "[♪] MS — Chip's Challenge Original Microsoft MIDI Soundtrack"
                else -> "+++ [★] TILE WORLD MENU: [♪] Pick your soundtrack (AKI / MS / Custom) | [>] Set controls (D-Pad / Arrows / Touch) | [#] Select level sets | [*] Toggle SFX Assist (Haptics & Subs) | Press START GAME to begin! +++"
            }
        }

        fun updateAudioMarquee() {
            revertMarqueeRunnable?.let { marqueeHandler.removeCallbacks(it) }
            startScrollRunnable?.let { marqueeHandler.removeCallbacks(it) }
            val bgmThemeName = bgmThemeSpinner.selectedItem?.toString() ?: "AKI"
            val desc = getBgmThemeDescription(bgmThemeName)

            audioMarqueeText?.setTextColor("#00FF00".toColorInt())
            audioMarqueeText?.isSingleLine = true
            audioMarqueeText?.ellipsize = TextUtils.TruncateAt.MARQUEE
            audioMarqueeText?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            audioMarqueeText?.text = desc
            audioMarqueeText?.isSelected = false
            audioMarqueeText?.isSelected = true
        }

        fun showFeedback(text: CharSequence, durationMs: Long = 2500, color: Int? = null, scrollAfterFreeze: Boolean = false) {
            revertMarqueeRunnable?.let { marqueeHandler.removeCallbacks(it) }
            startScrollRunnable?.let { marqueeHandler.removeCallbacks(it) }
            audioMarqueeText?.setTextColor(color ?: "#00FF00".toColorInt())

            if (scrollAfterFreeze) {
                val bgmThemeName = bgmThemeSpinner.selectedItem?.toString() ?: "AKI"
                val bgmDesc = getBgmThemeDescription(bgmThemeName)
                val combinedText = "$text                    $bgmDesc"

                // 1) Freeze static for durationMs (2.5s)
                audioMarqueeText?.ellipsize = null
                audioMarqueeText?.isSelected = false
                audioMarqueeText?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                audioMarqueeText?.text = combinedText

                // 2) Start smooth marquee scrolling of combinedText
                val scrollRunnable = Runnable {
                    audioMarqueeText?.isSingleLine = true
                    audioMarqueeText?.ellipsize = TextUtils.TruncateAt.MARQUEE
                    audioMarqueeText?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    audioMarqueeText?.text = combinedText
                    audioMarqueeText?.isSelected = false
                    audioMarqueeText?.isSelected = true
                }
                startScrollRunnable = scrollRunnable
                marqueeHandler.postDelayed(scrollRunnable, durationMs)

                // 3) Exactly as $text finishes scrolling off screen, switch text to ONLY bgmDesc so bgmDesc loops continuously (touch -> bgm -> bgm -> bgm...)
                val scrollTimeMs = (text.length + 5) * 240L
                val revertRunnable = Runnable {
                    updateAudioMarquee()
                }
                revertMarqueeRunnable = revertRunnable
                marqueeHandler.postDelayed(revertRunnable, durationMs + scrollTimeMs)
            } else {
                // Short message: show static for durationMs, then revert to standard BGM marquee
                audioMarqueeText?.ellipsize = null
                audioMarqueeText?.isSelected = false
                audioMarqueeText?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                audioMarqueeText?.text = text

                val runnable = Runnable { updateAudioMarquee() }
                revertMarqueeRunnable = runnable
                marqueeHandler.postDelayed(runnable, durationMs)
            }
        }

        fun showStaticVolumeFeedback(text: CharSequence, durationMs: Long = 1500, color: Int? = null) {
            showFeedback(text, durationMs, color, scrollAfterFreeze = false)
        }

        // Restore settings
        val lastBgmVol = prefs.getInt("bgm_volume", 100)
        val lastSfxVol = prefs.getInt("sfx_volume", 100)

        bgmSeekBar.progress = lastBgmVol
        updateSeekBarTrackColor(bgmSeekBar, lastBgmVol)
        if (lastBgmVol < 5) {
            MusicManager.setEnabled(enabled = false)
            MusicManager.setVolume(0.0f)
        } else {
            MusicManager.setEnabled(enabled = true)
            MusicManager.setVolume(lastBgmVol / 100.0f)
        }

        sfxSeekBar.progress = lastSfxVol
        updateSeekBarTrackColor(sfxSeekBar, lastSfxVol)
        if (lastSfxVol < 5) {
            GameEngine.nativeSetSfxEnabled(enabled = false)
            GameEngine.nativeSetSfxVolume(0)
        } else {
            GameEngine.nativeSetSfxEnabled(enabled = true)
            GameEngine.nativeSetSfxVolume((lastSfxVol / 10).coerceIn(0, 10))
        }

        bgmSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSeekBarTrackColor(seekBar, progress)
                prefs.edit { putInt("bgm_volume", progress) }
                if (progress < 5) {
                    MusicManager.setEnabled(enabled = false)
                    MusicManager.setVolume(0.0f)
                } else {
                    MusicManager.setEnabled(enabled = true)
                    MusicManager.setVolume(progress / 100.0f)
                    if (fromUser) {
                        MusicManager.playPreviewTrack(this@GameActivity)
                    }
                }
                if (fromUser) {
                    val volStr = if (progress < 5) "0%" else "$progress%"
                    showStaticVolumeFeedback("[Vol: $volStr • BGM]")
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 100
                if (progress >= 5) {
                    MusicManager.playPreviewTrack(this@GameActivity)
                }
                val volStr = if (progress < 5) "0%" else "$progress%"
                showStaticVolumeFeedback("[Vol: $volStr • BGM]")
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                MusicManager.stopMusic()
                revertMarqueeRunnable?.let { marqueeHandler.removeCallbacks(it) }
                val runnable = Runnable { updateAudioMarquee() }
                revertMarqueeRunnable = runnable
                marqueeHandler.postDelayed(runnable, 1500)
            }
        },
        )

        sfxSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSeekBarTrackColor(seekBar, progress)
                prefs.edit { putInt("sfx_volume", progress) }
                if (progress < 5) {
                    GameEngine.nativeSetSfxEnabled(enabled = false)
                    GameEngine.nativeSetSfxVolume(0)
                } else {
                    GameEngine.nativeSetSfxEnabled(enabled = true)
                    GameEngine.nativeSetSfxVolume((progress / 10).coerceIn(0, 10))
                    if ((fromUser && (tingSoundId != 0))) {
                        val now = System.currentTimeMillis()
                        if ((now - lastSfxPlayTime) > 120) {
                            lastSfxPlayTime = now
                            val vol = progress / 100.0f
                            if (sfxStreamId != 0) {
                                soundPool?.stop(sfxStreamId)
                            }
                            sfxStreamId = soundPool?.play(tingSoundId, vol, vol, 1, 0, 1.0f) ?: 0
                        }
                    }
                }
                if (fromUser) {
                    val volStr = if (progress < 5) "0%" else "$progress%"
                    showStaticVolumeFeedback("[Vol: $volStr • SFX]")
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 100
                if ((progress >= 5) && (tingSoundId != 0)) {
                    val vol = (seekBar?.progress ?: 100) / 100.0f
                    if (sfxStreamId != 0) {
                        soundPool?.stop(sfxStreamId)
                    }
                    sfxStreamId = soundPool?.play(tingSoundId, vol, vol, 1, 0, 1.0f) ?: 0
                }
                val volStr = if (progress < 5) "0%" else "$progress%"
                showStaticVolumeFeedback("[Vol: $volStr • SFX]")
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (sfxStreamId != 0) {
                    soundPool?.stop(sfxStreamId)
                    sfxStreamId = 0
                }
                revertMarqueeRunnable?.let { marqueeHandler.removeCallbacks(it) }
                val runnable = Runnable { updateAudioMarquee() }
                revertMarqueeRunnable = runnable
                marqueeHandler.postDelayed(runnable, 1500)
            }
        },
        )

        // BGM Theme Dropdown Initialization
        val availableBgmThemes = GameEngine.getAvailableBgmThemes(this)
        val bgmThemeAdapter = createCustomFontAdapter(availableBgmThemes)
        bgmThemeSpinner.adapter = bgmThemeAdapter

        val savedBgmTheme = prefs.getString("bgm_theme", "MS") ?: "MS"
        var bgmThemeIdx = availableBgmThemes.indexOf(savedBgmTheme)
        if (bgmThemeIdx < 0) bgmThemeIdx = 0
        bgmThemeSpinner.setSelection(bgmThemeIdx)

        MusicManager.setActiveBgmTheme(availableBgmThemes[bgmThemeIdx])

        bgmThemeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val selectedTheme = availableBgmThemes[pos.coerceIn(0, availableBgmThemes.size - 1)]
                prefs.edit { putString("bgm_theme", selectedTheme) }
                MusicManager.setActiveBgmTheme(selectedTheme)
                updateAudioMarquee()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // SFX Theme Dropdown Initialization
        val availableThemes = GameEngine.getAvailableSfxThemes(this)
        val sfxThemeAdapter = createCustomFontAdapter(availableThemes)
        sfxThemeSpinner.adapter = sfxThemeAdapter

        val savedSfxTheme = prefs.getString("sfx_theme", "Tile World") ?: "Tile World"
        var themeIdx = availableThemes.indexOf(savedSfxTheme)
        if (themeIdx < 0) themeIdx = 0
        sfxThemeSpinner.setSelection(themeIdx)

        val initialSfxTheme = availableThemes[themeIdx]
        GameEngine.nativeSetSfxTheme(initialSfxTheme)

        sfxThemeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val selectedTheme = availableThemes[pos.coerceIn(0, availableThemes.size - 1)]
                prefs.edit { putString("sfx_theme", selectedTheme) }
                GameEngine.nativeSetSfxTheme(selectedTheme)
                updateAudioMarquee()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        
        val btnSfxToggle = findViewById<View>(R.id.btn_sfx_toggle)
        val tvSfxLabel = findViewById<TextView>(R.id.tv_sfx_label)
        val ivSfxZigzags = findViewById<ImageView>(R.id.iv_sfx_zigzags)
        
        // 0 = Normal SFX, 1 = Haptic, 2 = Haptic + Subs, 3 = Subs Only
        var sfxAssistMode = prefs.getInt("sfx_assist_mode", 0) 
        
        // Migrate old boolean to int mode if needed
        if (prefs.contains("haptic_sfx")) {
            if (prefs.getBoolean("haptic_sfx", false)) sfxAssistMode = 1
            prefs.edit { remove("haptic_sfx") }
        }
        
        fun updateSfxButtonUI() {
            when (sfxAssistMode) {
                0 -> {
                    tvSfxLabel?.setTextColor("#888888".toColorInt())
                    ivSfxZigzags?.visibility = View.GONE
                }
                1 -> {
                    tvSfxLabel?.setTextColor("#00FF00".toColorInt())
                    ivSfxZigzags?.setImageResource(R.drawable.ic_sfx_haptic_only)
                    ivSfxZigzags?.visibility = View.VISIBLE
                }
                2 -> {
                    tvSfxLabel?.setTextColor("#00FF00".toColorInt())
                    ivSfxZigzags?.setImageResource(R.drawable.ic_sfx_haptic_subs)
                    ivSfxZigzags?.visibility = View.VISIBLE
                }
                3 -> {
                    tvSfxLabel?.setTextColor("#00FF00".toColorInt())
                    ivSfxZigzags?.setImageResource(R.drawable.ic_sfx_subs_only)
                    ivSfxZigzags?.visibility = View.VISIBLE
                }
            }
        }
        updateSfxButtonUI()

        btnSfxToggle?.setOnClickListener {
            sfxAssistMode = (sfxAssistMode + 1) % 4
            prefs.edit { putInt("sfx_assist_mode", sfxAssistMode) }
            updateSfxButtonUI()
            
            when (sfxAssistMode) {
                0 -> showStaticVolumeFeedback("[ SFX Assist Disabled ]", 1500)
                1 -> showStaticVolumeFeedback("[ HAPTIC SFX Enabled ]", 1500)
                2 -> showStaticVolumeFeedback("[ HAPTIC + SUBS Enabled ]", 1500)
                3 -> showStaticVolumeFeedback("[ SUBS ONLY Enabled ]", 1500)
            }
        }

        updateAudioMarquee()

        val btnPixelPerfect = findViewById<View>(R.id.btn_pixel_perfect_toggle)
        val tvPixelPerfectLabel = findViewById<TextView>(R.id.tv_pixel_perfect_label)
        var isPixelPerfect = prefs.getBoolean("pixel_perfect_enabled", false)

        fun updatePixelPerfectButtonUI() {
            if (isPixelPerfect) {
                tvPixelPerfectLabel?.text = "1:1"
                tvPixelPerfectLabel?.setTextColor("#00FF00".toColorInt())
            } else {
                tvPixelPerfectLabel?.text = "FULL"
                tvPixelPerfectLabel?.setTextColor("#CFFF04".toColorInt())
            }
        }
        updatePixelPerfectButtonUI()

        btnPixelPerfect?.setOnClickListener {
            isPixelPerfect = !isPixelPerfect
            prefs.edit { putBoolean("pixel_perfect_enabled", isPixelPerfect) }
            updatePixelPerfectButtonUI()
            if (isPixelPerfect) {
                showFeedback("[ Display: 1:1 PIXEL PERFECT — Native unscaled retro resolution with 100% sharp pixel precision ]", durationMs = 2500, color = "#00FF00".toColorInt(), scrollAfterFreeze = true)
            } else {
                showFeedback("[ Display: FULL SCREEN — Scaled to fit screen with smooth graphics for maximum visibility ]", durationMs = 2500, color = "#CFFF04".toColorInt(), scrollAfterFreeze = true)
            }
        }

        // Onscreen Controls Toggle Button Initialization (None, Arrow Keys, D-Pad Left, D-Pad Right)
        val btnOnscreenToggle = findViewById<View>(R.id.btn_onscreen_toggle)
        val tvOnscreenLabel = findViewById<TextView>(R.id.tv_onscreen_label)

        val onscreenLabels = mapOf(
            0 to "NONE",
            1 to "ARROWS",
            2 to "DPAD L",
            3 to "DPAD R",
        )

        var savedStyle = prefs.getInt("onscreen_controls_style", -1)
        if (savedStyle == -1) {
            savedStyle = if (prefs.getBoolean("controls_enabled", false)) 1 else 0
        }
        var onscreenMode = savedStyle.coerceIn(0, 3)

        fun updateOnscreenButtonUI() {
            tvOnscreenLabel?.text = onscreenLabels[onscreenMode] ?: "NONE"
            if (onscreenMode == 0) {
                tvOnscreenLabel?.setTextColor("#888888".toColorInt())
            } else {
                tvOnscreenLabel?.setTextColor("#00FF00".toColorInt())
            }
        }
        updateOnscreenButtonUI()

        btnOnscreenToggle?.setOnClickListener {
            onscreenMode = (onscreenMode + 1) % 4
            prefs.edit { putInt("onscreen_controls_style", onscreenMode) }
            updateOnscreenButtonUI()

            val onscreenToasts = mapOf(
                0 to "[ Onscreen Controls: Disabled ]",
                1 to "[ Onscreen Controls: Arrow Keys ]",
                2 to "[ Onscreen Controls: D-Pad Left ]",
                3 to "[ Onscreen Controls: D-Pad Right ]",
            )
            showStaticVolumeFeedback(onscreenToasts[onscreenMode] ?: "", 1500)
        }

        // Onscreen Controls Size Dropdown Initialization
        val onscreenSizeSpinner = findViewById<Spinner>(R.id.spinner_onscreen_size)
        val onscreenSizeOptions = listOf("75% S", "100% M", "125% L", "150% XL")
        val onscreenSizeAdapter = createCustomFontAdapter(onscreenSizeOptions)
        onscreenSizeSpinner?.adapter = onscreenSizeAdapter

        val savedScaleIdx = prefs.getInt("onscreen_controls_scale_idx", 1)
        onscreenSizeSpinner?.setSelection(savedScaleIdx.coerceIn(0, 3))

        onscreenSizeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                prefs.edit { putInt("onscreen_controls_scale_idx", pos) }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }


        val touchLabels = mapOf(
            0 to "NONE",
            1 to "NAV.",
            2 to "CLASSIC",
            3 to "PRECISE",
            4 to "FLUID",
        )

        val isTouchNav = prefs.getBoolean("touch_nav_enabled", false)
        val lastSwipeStyle = prefs.getInt("swipe_style", 2)

        var touchControlMode = if (isTouchNav && !isLynx) {
            1
        } else {
            when (lastSwipeStyle) {
                -1 -> 0
                2 -> 2
                1 -> 3
                0 -> 4
                else -> 2
            }
        }

        fun updateTouchButtonUI() {
            tvTouchLabel.text = touchLabels[touchControlMode] ?: "NONE"
            if (touchControlMode == 0) {
                tvTouchLabel.setTextColor("#888888".toColorInt())
            } else {
                tvTouchLabel.setTextColor("#00FF00".toColorInt())
            }
        }
        updateTouchButtonUI()

        btnTouchToggle.setOnClickListener {
            var nextMode = (touchControlMode + 1) % 5
            if (isLynx && (nextMode == 1)) {
                nextMode = 2
            }
            touchControlMode = nextMode

            val (isNav, swipeStyle) = when (touchControlMode) {
                0 -> Pair(false, -1)
                1 -> Pair(true, 2)
                2 -> Pair(false, 2)
                3 -> Pair(false, 1)
                4 -> Pair(false, 0)
                else -> Pair(false, -1)
            }
            prefs.edit {
                putBoolean("touch_nav_enabled", isNav)
                putInt("swipe_style", swipeStyle)
            }
            updateTouchButtonUI()

            val modeToast = mapOf(
                0 to "[ Touch Controls: Disabled — No touch or swipe navigation enabled. ]",
                1 to "[ Touch Controls: Navi Enabled — Tap on screen relative to Chip to move. ]",
                2 to "[ Swipe Controls: Classic Enabled — Standard continuous swipe controls. ]",
                3 to "[ Swipe Controls: Precise Enabled — Single discrete step per swipe flick. ]",
                4 to "[ Swipe Controls: Fluid Enabled — Smooth continuous turns without lifting finger. ]",
            )
            showFeedback(modeToast[touchControlMode] ?: "", durationMs = 2500, scrollAfterFreeze = true)
        }

        // Game Speed Dropdown Initialization (Secret 10s Hold on START GAME to toggle)
        val gameSpeedContainer = findViewById<LinearLayout>(R.id.container_game_speed)
        val gameSpeedSpinner = findViewById<Spinner>(R.id.spinner_game_speed)
        val speedOptions = listOf(
            "25% (Crawl)",
            "50% (Sluggish)",
            "75% (Moderate)",
            "100% (Baseline)",
            "125% (Swift)",
            "150% (Turbo)",
            "175% (Overclock)",
            "200% (Hyper)",
            "225% (Supersonic)",
            "250% (Speed of light)",
            "275% (Warp speed)",
            "300% (Ludicrous speed)",
        )
        val speedAdapter = createCustomFontAdapter(speedOptions)
        gameSpeedSpinner.adapter = speedAdapter

        val isSpeedVisible = prefs.getBoolean("game_speed_visible", false)
        gameSpeedContainer.visibility = if (isSpeedVisible) View.VISIBLE else View.GONE

        val savedSpeedIdx = prefs.getInt("game_speed_index", 3) // Default to 100% (index 3)
        gameSpeedSpinner.setSelection(savedSpeedIdx.coerceIn(0, speedOptions.size - 1))

        gameSpeedSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                prefs.edit { putInt("game_speed_index", pos) }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // Secret Section: Unlimited Time Toggle Button Initialization
        val btnTimerToggle = findViewById<View>(R.id.btn_timer_toggle)
        val tvTimerLabel = findViewById<TextView>(R.id.tv_timer_label)
        var isUnlimitedTime = prefs.getBoolean("unlimited_time_enabled", false)

        fun updateTimerButtonUI() {
            if (isUnlimitedTime) {
                tvTimerLabel?.text = "UNLIMITED"
                tvTimerLabel?.setTextColor("#00FF00".toColorInt())
            } else {
                tvTimerLabel?.text = "NORMAL"
                tvTimerLabel?.setTextColor("#CFFF04".toColorInt())
            }
            GameEngine.nativeSetUnlimitedTime(isUnlimitedTime)
        }
        updateTimerButtonUI()

        btnTimerToggle?.setOnClickListener {
            isUnlimitedTime = !isUnlimitedTime
            prefs.edit { putBoolean("unlimited_time_enabled", isUnlimitedTime) }
            updateTimerButtonUI()
            if (isUnlimitedTime) {
                showStaticVolumeFeedback("[ Unlimited Level Time Enabled ]", 2500, "#00FF00".toColorInt())
            } else {
                showStaticVolumeFeedback("[ Normal Level Timer Enabled ]", 2500)
            }
        }

        // Secret 10-second hold on START GAME button to toggle/reset Game Speed
        var isLongHoldTriggered = false
        val holdHandler = Handler(Looper.getMainLooper())
        val holdRunnable = Runnable {
            isLongHoldTriggered = true
            val currentlyVisible = gameSpeedContainer.isVisible
            if (currentlyVisible) {
                gameSpeedContainer.visibility = View.GONE
                gameSpeedSpinner.setSelection(3) // Reset to 100%
                prefs.edit { putInt("game_speed_index", 3).putBoolean("game_speed_visible", false) }
                Toast.makeText(this, "Game Speed hidden & reset to 100%", Toast.LENGTH_SHORT).show()
            } else {
                gameSpeedContainer.visibility = View.VISIBLE
                prefs.edit { putBoolean("game_speed_visible", true) }
                Toast.makeText(this, "Game Speed options unlocked!", Toast.LENGTH_SHORT).show()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        startButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    isLongHoldTriggered = false
                    holdHandler.postDelayed(holdRunnable, 10000L) // 10 seconds hold
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    holdHandler.removeCallbacks(holdRunnable)
                    if (!isLongHoldTriggered) {
                        v.performClick()
                    }
                    isLongHoldTriggered = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    holdHandler.removeCallbacks(holdRunnable)
                    isLongHoldTriggered = false
                }
            }
            true
        }

        val msTiles = getTilesets("mstiles")
        val lynxTiles = getTilesets("lynxtiles")

        val msTilesAdapter = createCustomFontAdapter(msTiles)
        msTilesSpinner.adapter = msTilesAdapter

        val lynxTilesAdapter = createCustomFontAdapter(lynxTiles)
        lynxTilesSpinner.adapter = lynxTilesAdapter

        // Restore tileset selections
        msTilesSpinner.setSelection(prefs.getInt("last_tileset_ms", 0).coerceAtMost(msTiles.size - 1))
        lynxTilesSpinner.setSelection(prefs.getInt("last_tileset_lynx", 0).coerceAtMost(lynxTiles.size - 1))

        msTilesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                prefs.edit { putInt("last_tileset_ms", pos) }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        lynxTilesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                prefs.edit { putInt("last_tileset_lynx", pos) }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        fun updateSets() {
            val currentMap = getCurrentMap()
            val friendlyNames = currentMap.keys.toList()
            val setFiles = currentMap.values.toList()

            // Update tileset spinner visibility
            msTilesSpinner.visibility = if (isLynx) View.GONE else View.VISIBLE
            lynxTilesSpinner.visibility = if (isLynx) View.VISIBLE else View.GONE

            val adapter = createCustomFontAdapter(friendlyNames)
            setsSpinner.adapter = adapter

            val lastSet = prefs.getString(if (isLynx) "last_set_lynx" else "last_set_ms", setFiles[0])
            val lastSetIdx = setFiles.indexOf(lastSet)
            if (lastSetIdx >= 0) setsSpinner.setSelection(lastSetIdx)

            setsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val fileName = setFiles[pos]
                    Log.d(TAG, "Selected set: $fileName")
                    prefs.edit {
                        putString(if (isLynx) "last_set_lynx" else "last_set_ms", fileName)
                        putString("last_set", fileName)
                    }
                    updateLevelList(fileName, data, sets, save)
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }

        val btnRulesetToggle = findViewById<View>(R.id.btn_ruleset_toggle)
        val ivRulesetIcon = findViewById<ImageView>(R.id.iv_ruleset_icon)
        val tvRulesetLabel = findViewById<TextView>(R.id.tv_ruleset_label)
        isLynx = prefs.getBoolean("lynx_filter", false)

        fun updateRulesetButtonUI() {
            if (isLynx) {
                ivRulesetIcon?.setImageDrawable(null)
                ivRulesetIcon?.setImageResource(R.drawable.ic_ruleset_lynx_png)
                ivRulesetIcon?.clearColorFilter()
                tvRulesetLabel?.text = "LYNX"
                tvRulesetLabel?.setTextColor("#FF5F1F".toColorInt())
            } else {
                ivRulesetIcon?.setImageDrawable(null)
                ivRulesetIcon?.setImageResource(R.drawable.ic_ruleset_ms_png)
                ivRulesetIcon?.clearColorFilter()
                tvRulesetLabel?.text = "MS"
                tvRulesetLabel?.setTextColor("#00FFFF".toColorInt())
            }
        }
        updateRulesetButtonUI()

        btnRulesetToggle?.setOnClickListener {
            isLynx = !isLynx
            prefs.edit { putBoolean("lynx_filter", isLynx) }
            updateRulesetButtonUI()
            if (isLynx) {
                if (touchControlMode == 1) {
                    touchControlMode = 0
                    prefs.edit {
                        putBoolean("touch_nav_enabled", false)
                        putInt("swipe_style", -1)
                    }
                    updateTouchButtonUI()
                    showStaticVolumeFeedback("[ Ruleset: LYNX Activated — Touch navigation disabled ]", 2500, "#FF5F1F".toColorInt())
                } else {
                    showStaticVolumeFeedback("[ Ruleset: LYNX Activated ]", 2500, "#FF5F1F".toColorInt())
                }
            } else {
                updateTouchButtonUI()
                showStaticVolumeFeedback("[ Ruleset: MS Activated ]", 2500, "#00FFFF".toColorInt())
            }
            updateSets()
        }

        updateSets()

        startButton.isEnabled = true

        startButton.setOnClickListener {
            val currentMap = getCurrentMap()
            val setFiles = currentMap.values.toList()
            val selectedIdx = setsSpinner.selectedItemPosition
            if ((selectedIdx >= 0) && (selectedIdx < setFiles.size)) {
                val selectedFile = setFiles[selectedIdx]
                
                // Get selected tileset
                val spinnerItem = if (isLynx) lynxTilesSpinner.selectedItem else msTilesSpinner.selectedItem
                val selectedTilesetName = (spinnerItem as? String) ?: "Tile World"

                val selectedTileset = when (selectedTilesetName) {
                    "Tile World" -> if (isLynx) "atiles.bmp" else "tiles.bmp"
                    else -> if (isLynx) "lynxtiles/$selectedTilesetName.bmp" else "mstiles/$selectedTilesetName.bmp"
                }
                
                val selectedLevelIdx = levelsSpinner.selectedItemPosition
                val levelNum = currentSetProgress?.levels?.getOrNull(selectedLevelIdx)?.levelNumber ?: 1
                
                val bgmVol = bgmSeekBar.progress
                val sfxVol = sfxSeekBar.progress
                val bgmIsOn = bgmVol >= 5
                val sfxIsOn = sfxVol >= 5

                val isTouchNavSelected = (touchControlMode == 1) && !isLynx
                val selectedSwipeStyle = when (touchControlMode) {
                    0 -> -1
                    1 -> 2
                    2 -> 2
                    3 -> 1
                    4 -> 0
                    else -> -1
                }

                // Save settings
                prefs.edit {
                    putBoolean("bgm_enabled", bgmIsOn)
                    putInt("bgm_volume", bgmVol)
                    putBoolean("sfx_enabled", sfxIsOn)
                    putInt("sfx_volume", sfxVol)
                    putBoolean("touch_nav_enabled", isTouchNavSelected)
                    putInt("swipe_style", selectedSwipeStyle)
                }

                Log.d(TAG, "Starting game: set=$selectedFile level=$levelNum tileset=$selectedTileset")
                
                // Apply audio settings
                MusicManager.setEnabled(bgmIsOn)
                MusicManager.setVolume(if (bgmIsOn) bgmVol / 100.0f else 0.0f)
                if (bgmIsOn) {
                    MusicManager.playMusicForLevel(this, levelNum)
                }
                GameEngine.nativeSetSfxEnabled(sfxIsOn)
                GameEngine.nativeSetSfxVolume(if (sfxIsOn) (sfxVol / 10).coerceIn(0, 10) else 0)

                startGame(data, res, sets, save, selectedFile, levelNum, selectedTileset)
            }
        }

        val seeScoresButton = findViewById<View>(R.id.btn_see_scores)
        seeScoresButton?.setOnClickListener {
            showScoresDialog()
        }

        applyCustomFont(findViewById(R.id.root_launcher))
    }

    private fun applyCustomFont(root: View) {
        try {
            val typeface = Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
            applyTypefaceRecursive(root, typeface)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom font", e)
        }
    }

    private fun applyTypefaceRecursive(view: View, typeface: Typeface) {
        when (view) {
            is TextView -> {
                if (view.paint.isFakeBoldText || (view.typeface?.isBold == true)) {
                    view.setTypeface(typeface, Typeface.BOLD)
                } else {
                    view.typeface = typeface
                }
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    applyTypefaceRecursive(view.getChildAt(i), typeface)
                }
            }
        }
    }



    private fun <T> createCustomFontAdapter(items: List<T>): ArrayAdapter<T> {
        val typeface = try {
            Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
        } catch (_: Exception) { null }

        return object : ArrayAdapter<T>(this, R.layout.spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                typeface?.let { view.typeface = it }
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                typeface?.let { view.typeface = it }
                return view
            }
        }.apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
    }

    private fun startGame(data: String, res: String, sets: String, save: String, setName: String, levelNum: Int, tileset: String) {
        isGameRunning = true
        isEnginePaused = false
        activeSetName = setName
        activeTileset = tileset
        val prefs = getSharedPreferences("tworld_prefs", MODE_PRIVATE)
        val isTouchNavEnabled = prefs.getBoolean("touch_nav_enabled", false)
        val swipeStyle = prefs.getInt("swipe_style", 0)
        val isPixelPerfect = prefs.getBoolean("pixel_perfect_enabled", false)
        
        val speedValues = listOf(25, 50, 75, 100, 125, 150, 175, 200, 225, 250, 275, 300)
        val savedSpeedIdx = prefs.getInt("game_speed_index", 3).coerceIn(0, speedValues.size - 1)
        val speedPercent = speedValues[savedSpeedIdx]
        val isUnlimitedTime = prefs.getBoolean("unlimited_time_enabled", false)

        GameEngine.nativeSetGameSpeed(speedPercent)
        GameEngine.nativeSetUnlimitedTime(isUnlimitedTime)

        // Start native engine thread BEFORE creating surface view so nativeIsRunning() is true
        GameEngine.nativeAudioResume()
        GameEngine.nativeInit(data, res, sets, save)
        GameEngine.nativeStart(setName, levelNum, tileset)

        gameView = GameSurfaceView(this, isTouchNavEnabled, swipeStyle, speedPercent, isPixelPerfect) {
            runOnUiThread { showLauncher() }
        }

        val root = LinearLayout(this)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        // 1. Add Native Buttons at Top
        val buttonRow = LinearLayout(this)
        val buttonRowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        buttonRow.layoutParams = buttonRowParams
        buttonRow.orientation = LinearLayout.HORIZONTAL
        buttonRow.gravity = Gravity.CENTER_HORIZONTAL
        buttonRow.setPadding(0, 0, 0, 0) // No padding at the top

        fun createWinBtn(iconRes: Int, alignLeft: Boolean = false, onClick: () -> Unit): View {
            val btnFrame = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.tw_button)
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (50 * resources.displayMetrics.density).toInt(),
                    1.0f,
                )
                setOnClickListener { onClick() }
            }

            val iconView = ImageView(this).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (10 * resources.displayMetrics.density).toInt()

                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                if (alignLeft) {
                    lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(pad, pad, pad / 2, pad)
                } else {
                    lp.gravity = Gravity.CENTER
                    setPadding(pad, pad, pad, pad)
                }
                layoutParams = lp
            }

            btnFrame.addView(iconView)
            return btnFrame
        }

        val hasCameraCutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val rootInsets = window?.decorView?.rootWindowInsets
            val cutout = rootInsets?.displayCutout
            if (cutout != null) {
                val screenWidth = resources.displayMetrics.widthPixels
                val centerX = screenWidth / 2f
                val marginPx = 60 * resources.displayMetrics.density
                cutout.boundingRects.any { rect ->
                    (rect.left <= (centerX + marginPx)) && (rect.right >= (centerX - marginPx))
                }
            } else false
        } else {
            false
        }

        buttonRow.addView(createWinBtn(R.drawable.ic_game_menu) { GameEngine.nativeTypeChar(GameEngine.TWK_ESCAPE) })
        buttonRow.addView(
            createWinBtn(R.drawable.ic_game_pause, alignLeft = hasCameraCutout) {
                GameEngine.nativeTypeChar(GameEngine.TWC_PAUSEGAME)
            },
        )
        buttonRow.addView(createWinBtn(R.drawable.ic_game_reset) { GameEngine.nativeTypeChar(GameEngine.TWC_SAMELEVEL) })
        root.addView(buttonRow)

        // 2. Window Frame (Title Bar + Game)
        val pad3dp = (3 * resources.displayMetrics.density).toInt()
        val windowFrame = LinearLayout(this)
        windowFrame.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f,
        )
        windowFrame.orientation = LinearLayout.VERTICAL
        windowFrame.setBackgroundColor(Color.BLACK)
        windowFrame.setPadding(0, 0, 0, 0)

        // Title Bar Container with tw_title_window background (top, left, right 3dp border, open bottom)
        val titleBarContainer = LinearLayout(this)
        titleBarContainer.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (27 * resources.displayMetrics.density).toInt(),
        )
        titleBarContainer.setBackgroundResource(R.drawable.tw_title_window)
        titleBarContainer.setPadding(pad3dp, pad3dp, pad3dp, 0)

        // Blue Title Bar inside the frame
        val titleBar = LinearLayout(this)
        val titleBarParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        titleBar.layoutParams = titleBarParams
        titleBar.setBackgroundResource(R.drawable.tw_title_bg)
        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.setPadding((4 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt(), 0)

        val iconView = ImageView(this)
        val iconParams = LinearLayout.LayoutParams(
            (16 * resources.displayMetrics.density).toInt(),
            (16 * resources.displayMetrics.density).toInt(),
        )
        iconParams.marginEnd = (6 * resources.displayMetrics.density).toInt()
        iconView.layoutParams = iconParams
        iconView.setImageResource(R.drawable.chipnotrace)
        titleBar.addView(iconView)

        val titleTextLocal = TextView(this)
        this.gameTitleText = titleTextLocal
        val levelInfo = currentSetProgress?.levels?.find { it.levelNumber == levelNum }
        val name = levelInfo?.name ?: ""
        val author = levelInfo?.author ?: ""
        
        // Show the actual name if we found one, otherwise fall back to "Level N"
        var fullTitle = if (name.isNotEmpty() && !name.equals("Level $levelNum", ignoreCase = true)) {
            name
        } else {
            "Level $levelNum"
        }
        
        if (author.isNotEmpty()) fullTitle += " by $author"
        
        titleTextLocal.text = fullTitle
        titleTextLocal.setTextColor(Color.WHITE)
        titleTextLocal.textSize = 14f
        try {
            val customTypeface = Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
            titleTextLocal.setTypeface(customTypeface, Typeface.BOLD)
        } catch (_: Exception) {
            titleTextLocal.typeface = Typeface.MONOSPACE
            titleTextLocal.paint.isFakeBoldText = true
        }
        titleBar.addView(titleTextLocal)
        titleBarContainer.addView(titleBar)

        windowFrame.addView(titleBarContainer)

        // Game Area directly below Title Bar
        val gameContainer = FrameLayout(this)
        val gameContainerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f,
        )
        gameContainer.layoutParams = gameContainerParams
        gameContainer.setBackgroundColor(Color.BLACK)
        gameContainer.setPadding(0, 0, 0, 0)
        
        gameContainer.addView(gameView)

        val controlsStyle = prefs.getInt("onscreen_controls_style", 0)
        if (controlsStyle > 0) {
            val inflater = LayoutInflater.from(this)
            val overlay = inflater.inflate(R.layout.dpad_overlay, gameContainer, false)

            val arrowContainer = overlay.findViewById<View>(R.id.container_arrow_keys)
            val dpadContainer = overlay.findViewById<View>(R.id.container_dpad_cross)

            // Option 1: Automatic Ergonomic Thumbzone calculation
            val metrics = resources.displayMetrics
            val density = metrics.density
            val isTabletOrFoldable = resources.configuration.smallestScreenWidthDp >= 600

            val windowInsets = ViewCompat.getRootWindowInsets(windowFrame)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val safeBottomPx = windowInsets?.bottom ?: 0
            val safeSidePx = windowInsets?.left ?: 0

            val autoBottomMarginPx = if (isTabletOrFoldable) {
                (metrics.heightPixels * 0.18f).toInt()
            } else {
                (48 * density).toInt() + safeBottomPx
            }

            when (controlsStyle) {
                1 -> { // Arrow Keys (Always Centered Horizontally)
                    arrowContainer?.visibility = View.VISIBLE
                    dpadContainer?.visibility = View.GONE
                    (arrowContainer?.layoutParams as? RelativeLayout.LayoutParams)?.let { lp ->
                        lp.addRule(RelativeLayout.CENTER_HORIZONTAL)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                        lp.bottomMargin = autoBottomMarginPx
                        arrowContainer.layoutParams = lp
                    }
                }
                2 -> { // D-Pad Left
                    arrowContainer?.visibility = View.GONE
                    dpadContainer?.visibility = View.VISIBLE
                    (dpadContainer?.layoutParams as? RelativeLayout.LayoutParams)?.let { lp ->
                        lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                        lp.marginStart = (16 * density).toInt() + safeSidePx
                        lp.marginEnd = 0
                        lp.bottomMargin = autoBottomMarginPx
                        dpadContainer.layoutParams = lp
                    }
                }
                3 -> { // D-Pad Right
                    arrowContainer?.visibility = View.GONE
                    dpadContainer?.visibility = View.VISIBLE
                    (dpadContainer?.layoutParams as? RelativeLayout.LayoutParams)?.let { lp ->
                        lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_END)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                        lp.marginStart = 0
                        lp.marginEnd = (16 * density).toInt() + safeSidePx
                        lp.bottomMargin = autoBottomMarginPx
                        dpadContainer.layoutParams = lp
                    }
                }
            }

            gameContainer.addView(overlay)
            setupDpad(overlay)
            applyCustomFont(overlay)

            val scaleFactor = when (prefs.getInt("onscreen_controls_scale_idx", 1)) {
                0 -> 0.75f
                1 -> 1.00f
                2 -> 1.25f
                3 -> 1.50f
                else -> 1.00f
            }

            if (scaleFactor != 1.00f) {
                val targetView = if (controlsStyle == 1) arrowContainer else dpadContainer
                targetView?.post {
                    targetView.pivotX = when (controlsStyle) {
                        3 -> targetView.width.toFloat()
                        1 -> targetView.width.toFloat() / 2f
                        else -> 0f
                    }
                    targetView.pivotY = targetView.height.toFloat()
                    targetView.scaleX = scaleFactor
                    targetView.scaleY = scaleFactor
                }
            }
        }

        windowFrame.addView(gameContainer)
        root.addView(windowFrame)

        setContentView(root)

        hideSystemUI()
    }

    private fun setupDpad(view: View) {
        val setupBtn = { btn: View, twk: Int ->
            btn.isFocusable = false
            btn.isFocusableInTouchMode = false
            btn.isHapticFeedbackEnabled = true
            val defaultAlpha = 0.35f
            btn.alpha = defaultAlpha
            btn.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val endState = GameEngine.nativeGetLevelEndState()
                        if (endState > 0) {
                            GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = true)
                        } else if (endState < 0) {
                            GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = true)
                        } else {
                            GameEngine.nativeSendKey(twk, down = true)
                        }
                        v.alpha = 0.8f
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        val endState = GameEngine.nativeGetLevelEndState()
                        if (endState > 0) {
                            GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = false)
                        } else if (endState < 0) {
                            GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = false)
                        } else {
                            GameEngine.nativeSendKey(twk, down = false)
                        }
                        v.alpha = defaultAlpha
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        val endState = GameEngine.nativeGetLevelEndState()
                        if (endState > 0) {
                            GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = false)
                        } else if (endState < 0) {
                            GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = false)
                        } else {
                            GameEngine.nativeSendKey(twk, down = false)
                        }
                        v.alpha = defaultAlpha
                    }
                }
                true
            }
        }

        // Setup container fields so tapping anywhere in the control areas after win/death works
        val setupContainer = { container: View? ->
            container?.setOnTouchListener { v, event ->
                val endState = GameEngine.nativeGetLevelEndState()
                if (endState != 0) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            if (endState > 0) GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = true)
                            else GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = true)
                        }
                        MotionEvent.ACTION_UP -> {
                            if (endState > 0) GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = false)
                            else GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = false)
                            v.performClick()
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (endState > 0) GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = false)
                            else GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = false)
                        }
                    }
                    true
                } else {
                    false
                }
            }
        }

        setupContainer(view)
        setupContainer(view.findViewById(R.id.container_arrow_keys))

        // Advanced Drag & Slide Unified D-Pad Handling
        val dpadContainer = view.findViewById<View>(R.id.container_dpad_cross)
        val dpadBtns = listOf(
            R.id.dpad_btn_up to GameEngine.TWK_UP,
            R.id.dpad_btn_down to GameEngine.TWK_DOWN,
            R.id.dpad_btn_left to GameEngine.TWK_LEFT,
            R.id.dpad_btn_right to GameEngine.TWK_RIGHT,
        )
        
        // Pass touches through the visual buttons to the container
        dpadBtns.forEach { (id, _) ->
            view.findViewById<View>(id)?.apply {
                isClickable = false
                isFocusable = false
                alpha = 0.35f
            }
        }

        var currentDpadKey = -1
        var currentDpadBtn: View? = null
        
        dpadContainer?.setOnTouchListener { v, event ->
            val endState = GameEngine.nativeGetLevelEndState()
            if (endState != 0) {
                // Use default win/death retry behavior
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (endState > 0) GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = true)
                        else GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = true)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (endState > 0) GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = false)
                        else GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = false)
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (endState > 0) GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = false)
                        else GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = false)
                    }
                }
                return@setOnTouchListener true
            }

            val width = v.width.toFloat()
            val height = v.height.toFloat()
            val x = event.x - (width / 2)
            val y = event.y - (height / 2)

            var newKey = -1
            var newBtnId = -1

            // 15% center deadzone
            val deadzoneSq = (width * 0.15f) * (width * 0.15f)
            if (((x * x) + (y * y)) > deadzoneSq) {
                if (abs(x) > abs(y)) {
                    if (x > 0) {
                        newKey = GameEngine.TWK_RIGHT
                        newBtnId = R.id.dpad_btn_right
                    } else {
                        newKey = GameEngine.TWK_LEFT
                        newBtnId = R.id.dpad_btn_left
                    }
                } else {
                    if (y > 0) {
                        newKey = GameEngine.TWK_DOWN
                        newBtnId = R.id.dpad_btn_down
                    } else {
                        newKey = GameEngine.TWK_UP
                        newBtnId = R.id.dpad_btn_up
                    }
                }
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        v.alpha = 0.8f
                    }
                    if (newKey != currentDpadKey) {
                        if (currentDpadKey != -1) {
                            GameEngine.nativeSendKey(currentDpadKey, down = false)
                            currentDpadBtn?.alpha = 0.35f
                        }
                        if (newKey != -1) {
                            GameEngine.nativeSendKey(newKey, down = true)
                            currentDpadBtn = view.findViewById(newBtnId)
                            currentDpadBtn?.alpha = 0.8f
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        } else {
                            currentDpadBtn = null
                        }
                        currentDpadKey = newKey
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.35f
                    if (currentDpadKey != -1) {
                        GameEngine.nativeSendKey(currentDpadKey, down = false)
                        currentDpadBtn?.alpha = 0.35f
                    }
                    currentDpadKey = -1
                    currentDpadBtn = null
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }

        // Arrow Keys (4 Buttons)
        view.findViewById<View>(R.id.btn_up)?.let { setupBtn(it, GameEngine.TWK_UP) }
        view.findViewById<View>(R.id.btn_down)?.let { setupBtn(it, GameEngine.TWK_DOWN) }
        view.findViewById<View>(R.id.btn_left)?.let { setupBtn(it, GameEngine.TWK_LEFT) }
        view.findViewById<View>(R.id.btn_right)?.let { setupBtn(it, GameEngine.TWK_RIGHT) }
    }



    private fun updateLevelList(setName: String, dataDir: String, setsDir: String, saveDir: String) {
        try {
            val progress = SaveGameParser.getSetProgress(setName, dataDir, setsDir, saveDir)
            currentSetProgress = progress
            
            setProgressBar.max = progress.totalLevels
            setProgressBar.progress = progress.solvedCount
            progressOverlayText.text = "Progress: ${progress.solvedCount}/${progress.totalLevels}"
            
            // Level locking logic: solved levels + next level, OR password-unlocked limit
            val prefs = getSharedPreferences("tworld_prefs", MODE_PRIVATE)
            val unlockedLimit = prefs.getInt("unlocked_limit_$setName", 1)

            val highestSolvedIdx = progress.levels.indexOfLast { it.isSolved }
            val lastSolvedUnlockedIdx = (highestSolvedIdx + 1).coerceAtMost(progress.totalLevels - 1)
            val passwordUnlockedIdx = (unlockedLimit - 1).coerceIn(0, progress.totalLevels - 1)

            val lastAvailableIdx = maxOf(lastSolvedUnlockedIdx, passwordUnlockedIdx)
            Log.d(TAG, "Level list for $setName: highestSolvedIdx=$highestSolvedIdx, unlockedLimit=$unlockedLimit, total=${progress.totalLevels}")
            
            val visibleLevels = progress.levels.subList(0, lastAvailableIdx + 1)
            
            val levelNames = visibleLevels.map { 
                "${it.levelNumber}. ${it.name}"
            }
            
            val typeface = try {
                Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
            } catch (_: Exception) { null }

            val adapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, levelNames) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    typeface?.let { view.typeface = it }
                    setupIcon(view, position)
                    return view
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent) as TextView
                    typeface?.let { view.typeface = it }
                    setupIcon(view, position)
                    return view
                }
                private fun setupIcon(textView: TextView, position: Int) {
                    val level = visibleLevels.getOrNull(position)
                    val iconRes = if (level?.isSolved == true) R.drawable.ic_level_solved else R.drawable.ic_level_locked
                    textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, iconRes, 0)
                    textView.compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
                }
            }
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            levelsSpinner.adapter = adapter
            
            // Default selection: last unlocked level
            levelsSpinner.setSelection(lastAvailableIdx)
            
            Log.d(TAG, "Updated level list for $setName: ${visibleLevels.size}/${progress.totalLevels} levels visible, selected: $lastAvailableIdx")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating level list", e)
        }
    }

    private fun showPasswordDialog(dataDir: String, setsDir: String) {
        val input = EditText(this).apply {
            hint = "ENTER PASSWORD"
            filters = arrayOf(InputFilter.LengthFilter(10))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.tw_field)
            setPadding(30, 20, 30, 20)
        }

        val container = FrameLayout(this).apply {
            setPadding(40, 20, 40, 10)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter Level Password")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val pwd = input.text.toString().trim()
                if (pwd.isNotEmpty()) {
                    processPassword(pwd, dataDir, setsDir)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun showScoresDialog() {
        val (data, _, sets) = GameEngine.extractAssets(this)
        val save = (getExternalFilesDir(null)?.parent ?: filesDir.absolutePath) + "/save"

        val levelSetsSpinner = findViewById<Spinner>(R.id.spinner_sets)
        val displaySetName = levelSetsSpinner?.selectedItem?.toString() ?: "Level Set"

        val currentMap = getCurrentMap()
        val selectedIdx = levelSetsSpinner?.selectedItemPosition ?: 0
        val selectedFile = currentMap.values.toList().getOrNull(selectedIdx) ?: "CC-MS.dat"

        val progress = currentSetProgress ?: SaveGameParser.getSetProgress(selectedFile, data, sets, save)
        val density = resources.displayMetrics.density

        val customTypeface = try {
            Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
        } catch (_: Exception) {
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        // Single Root container with NO border lines
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.tw_popup_window)
            setPadding(0, 0, 0, 0)
        }

        // 1. Header Title Bar with Icon (Directly flush at top edge)
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.tw_title_bg)
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (26 * density).toInt()).apply {
                topMargin = 0
                bottomMargin = 0
            }
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.chipnotrace)
            layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt()).apply {
                marginEnd = (6 * density).toInt()
            }
        }
        titleBar.addView(iconView)

        val titleText = TextView(this).apply {
            text = "[SCORES] - $displaySetName"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(customTypeface, Typeface.BOLD)
        }
        titleBar.addView(titleText)
        rootLayout.addView(titleBar)

        // 2. Inner Body (Transparent background so window has 1 single uniform color)
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }

        // Summary Line (Clean text on unified window background)
        val formattedTotalScore = String.format(java.util.Locale.getDefault(), "%,d", progress.totalScore)
        val summaryText = TextView(this).apply {
            text = "SOLVED: ${progress.solvedCount} / ${progress.totalLevels}        TOTAL SCORE: $formattedTotalScore"
            setTextColor(Color.YELLOW)
            textSize = 12f
            setTypeface(customTypeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (2 * density).toInt(), 0, (8 * density).toInt())
        }
        contentLayout.addView(summaryText)

        // Column Header Row
        val colHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor("#1a1a1a".toColorInt())
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
        }
        val createHeaderTv = { txt: String, weight: Float, alignEnd: Boolean ->
            TextView(this).apply {
                text = txt
                setTextColor("#FFCC00".toColorInt()) // Gold color
                textSize = 10f
                setTypeface(customTypeface, Typeface.BOLD)
                if (alignEnd) gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            }
        }
        colHeader.addView(createHeaderTv("LVL", 0.10f, false))
        colHeader.addView(createHeaderTv("LEVEL NAME", 0.35f, false))
        colHeader.addView(createHeaderTv("TIME", 0.13f, true))
        colHeader.addView(createHeaderTv("BASE", 0.14f, true))
        colHeader.addView(createHeaderTv("BONUS", 0.14f, true))
        colHeader.addView(createHeaderTv("SCORE", 0.14f, true))
        contentLayout.addView(colHeader)

        // Scrollable ListView (Directly inside contentLayout)
        val listView = ListView(this).apply {
            divider = "#333333".toColorInt().toDrawable()
            dividerHeight = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }

        val adapter = object : ArrayAdapter<LevelProgress>(this, 0, progress.levels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                }
                row.setBackgroundColor(if ((position % 2) == 0) "#1a1a1a".toColorInt() else "#222222".toColorInt())
                row.removeAllViews()

                val item = getItem(position) ?: return row

                val baseScore = if (item.isSolved) item.levelNumber * 500 else 0
                val bonusScore = if (item.isSolved) (item.bestTimeTicks / 10) * 10 else 0
                val totalLevelScore = baseScore + bonusScore
                val timeSeconds = if (item.isSolved) item.bestTimeTicks / 20 else 0 // Tile World logic: 20 ticks = 1 sec

                val createCellTv = { txt: String, color: Int, weight: Float, alignEnd: Boolean ->
                    TextView(context).apply {
                        text = txt
                        setTextColor(color)
                        textSize = 10f
                        setTypeface(customTypeface, Typeface.BOLD)
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                        if (alignEnd) gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                    }
                }

                val numColor = if (item.isSolved) "#00FF00".toColorInt() else "#666666".toColorInt()
                val nameColor = if (item.isSolved) Color.WHITE else "#666666".toColorInt()
                val subColor = if (item.isSolved) "#B0C4DE".toColorInt() else "#555555".toColorInt()
                val timeColor = if (item.isSolved) "#00FFFF".toColorInt() else "#555555".toColorInt()
                val scoreColor = if (item.isSolved) Color.YELLOW else "#555555".toColorInt()

                row.addView(createCellTv(String.format(java.util.Locale.getDefault(), "%03d", item.levelNumber), numColor, 0.10f, false))
                row.addView(createCellTv(if (item.isSolved) item.name.ifEmpty { "Level ${item.levelNumber}" } else "-", nameColor, 0.35f, false))
                row.addView(createCellTv(if (item.isSolved) "${timeSeconds}s" else "-", timeColor, 0.13f, true))
                row.addView(createCellTv(if (item.isSolved) String.format(java.util.Locale.getDefault(), "%,d", baseScore) else "-", subColor, 0.14f, true))
                row.addView(createCellTv(if (item.isSolved) String.format(java.util.Locale.getDefault(), "%,d", bonusScore) else "-", subColor, 0.14f, true))
                row.addView(createCellTv(if (item.isSolved) String.format(java.util.Locale.getDefault(), "%,d", totalLevelScore) else "-", scoreColor, 0.14f, true))

                return row
            }
        }
        listView.adapter = adapter
        contentLayout.addView(listView)

        // Close Button (Matching START GAME size: 150dp x 42dp, 18sp text, centered)
        val closeBtn = Button(this).apply {
            text = "CLOSE"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(customTypeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            isAllCaps = false
            setBackgroundResource(R.drawable.tw_button)
            layoutParams = LinearLayout.LayoutParams((150 * density).toInt(), (42 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (8 * density).toInt()
            }
        }
        contentLayout.addView(closeBtn)

        rootLayout.addView(contentLayout)

        val dialog = AlertDialog.Builder(this).create()
        dialog.setView(rootLayout, 0, 0, 0, 0)

        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            decorView.setPadding(0, 0, 0, 0)
        }
        closeBtn.setOnClickListener { dialog.dismiss() }

        dialog.show()

        // Set wider window bounds (94% width, 82% height)
        val displayMetrics = resources.displayMetrics
        val dialogWidth = (displayMetrics.widthPixels * 0.94).toInt()
        val dialogHeight = (displayMetrics.heightPixels * 0.82).toInt()
        dialog.window?.setLayout(dialogWidth, dialogHeight)
    }

    private fun processPassword(pwd: String, dataDir: String, setsDir: String) {
        val currentMap = getCurrentMap()
        val setFiles = currentMap.values.toList()
        val selectedIdx = setsSpinner.selectedItemPosition
        if ((selectedIdx < 0) || (selectedIdx >= setFiles.size)) return

        val selectedFile = setFiles[selectedIdx]
        val levelNum = SaveGameParser.findLevelByPassword(selectedFile, pwd, dataDir, setsDir)

        if (levelNum != null) {
            val prefs = getSharedPreferences("tworld_prefs", MODE_PRIVATE)
            val currentUnlocked = prefs.getInt("unlocked_limit_$selectedFile", 1)
            val newUnlocked = maxOf(currentUnlocked, levelNum)
            prefs.edit { putInt("unlocked_limit_$selectedFile", newUnlocked) }

            val save = "${filesDir.absolutePath}/save"
            updateLevelList(selectedFile, dataDir, setsDir, save)

            val progress = currentSetProgress
            if (progress != null) {
                val targetIdx = (levelNum - 1).coerceIn(0, progress.levels.size - 1)
                levelsSpinner.setSelection(targetIdx)
            }

            val levelName = currentSetProgress?.levels?.getOrNull(levelNum - 1)?.name ?: "Level $levelNum"
            Toast.makeText(this, "Unlocked Level $levelNum: $levelName", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Invalid Password", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getTilesets(dirName: String): List<String> {
        val base = getExternalFilesDir(null)?.parent ?: filesDir.absolutePath
        val res = File(base, "res")
        val dir = File(res, dirName)
        val list = mutableSetOf("Tile World")
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles { _, name -> name.lowercase().endsWith(".bmp") }?.forEach {
                list.add(it.nameWithoutExtension)
            }
        }
        val sortedSub = list.asSequence().filter { it != "Tile World" }.sortedWith(String.CASE_INSENSITIVE_ORDER).toList()
        return listOf("Tile World") + sortedSub
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isGameRunning && ::gameView.isInitialized) {
            hideSystemUI()
            if (!GameEngine.nativeIsGamePaused()) {
                GameEngine.nativeTypeChar(GameEngine.TWC_PAUSEGAME)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isGameRunning && ::gameView.isInitialized) {
            hideSystemUI()
            GameEngine.nativeAudioResume()
            gameView.resume()
        } else {
            hideSystemUI()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isGameRunning && ::gameView.isInitialized) {
            if (!GameEngine.nativeIsGamePaused()) {
                GameEngine.nativeTypeChar(GameEngine.TWC_PAUSEGAME)
            }
            gameView.pause()
            GameEngine.nativeAudioPause()
            MusicManager.pauseMusic()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        GameEngine.nativeStop()
    }

    // ── Input forwarding ──────────────────────────────────────────────────

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) ||
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) ||
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)) {
            return super.dispatchKeyEvent(event)
        }

        if (isGameRunning && ::gameView.isInitialized) {
            if (gameView.handleKey(event)) {
                return true
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isGameRunning && ::gameView.isInitialized) {
            return gameView.handleMotion(event) || super.dispatchGenericMotionEvent(event)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // ── Full-screen immersive mode ────────────────────────────────────────

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }


    fun updateGameTitle(levelNum: Int, name: String, author: String) {
        val titleTextLocal = gameTitleText ?: return
        runOnUiThread {
            var fullTitle = if (name.isNotEmpty() && !name.equals("Level $levelNum", ignoreCase = true)) {
                name
            } else {
                "Level $levelNum"
            }
            val finalAuthor = author.ifEmpty { "Chuck Sommerville" }
            fullTitle += " by $finalAuthor"
            titleTextLocal.text = fullTitle
            titleTextLocal.textSize = 14f
            try {
                val customTypeface = Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
                titleTextLocal.setTypeface(customTypeface, Typeface.BOLD)
            } catch (_: Exception) {
                titleTextLocal.typeface = Typeface.MONOSPACE
                titleTextLocal.paint.isFakeBoldText = true
            }
        }
    }

    private fun getVolumeColor(progress: Int): Int {
        val p = progress.coerceIn(0, 100) / 100.0f
        val green = "#29CE10".toColorInt()  // Green (#29CE10)
        val orange = "#FF9900".toColorInt() // Orange midpoint
        val red = "#EF3110".toColorInt()    // Red (#EF3110)

        val eval = android.animation.ArgbEvaluator()
        return if (p <= 0.5f) {
            eval.evaluate(p / 0.5f, green, orange) as Int
        } else {
            eval.evaluate((p - 0.5f) / 0.5f, orange, red) as Int
        }
    }

    private fun updateSeekBarTrackColor(seekBar: SeekBar?, progress: Int) {
        if (seekBar == null) return
        val color = getVolumeColor(progress)
        val drawable = (seekBar.progressDrawable?.mutate() as? LayerDrawable) ?: return
        val backgroundItem = drawable.findDrawableByLayerId(android.R.id.background)
        backgroundItem?.setTint(color)
        val progressItem = drawable.findDrawableByLayerId(android.R.id.progress)
        progressItem?.setTint(color)
    }
}
