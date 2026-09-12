@file:Suppress("SetTextI18n")

package dev.mcfry64.tworld

import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.Gravity
import android.graphics.Color

import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.app.AlertDialog
import android.graphics.drawable.LayerDrawable
import java.io.File

class GameActivity : ComponentActivity() {

    private lateinit var gameView: GameSurfaceView
    private lateinit var setsSpinner: Spinner
    private lateinit var msTilesSpinner: Spinner
    private lateinit var lynxTilesSpinner: Spinner
    private lateinit var levelsSpinner: Spinner
    private lateinit var setProgressBar: ProgressBar
    private lateinit var progressOverlayText: TextView
    private lateinit var startButton: Button
    private lateinit var swipeStyleSpinner: Spinner
    private lateinit var swipeDescText: TextView
    private var isLynx = false
    private var gameTitleText: TextView? = null

    private var currentSetProgress: SetProgress? = null
    private var isGameRunning = false
    private var isEnginePaused = false

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
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
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
        hideSystemUI()
        MusicManager.stopMusic()
        setContentView(R.layout.activity_main)

        val rootLauncher = findViewById<View>(R.id.root_launcher)
        try {
            val bgFile = File(filesDir, "res/background.bmp")
            val bmp = if (bgFile.exists()) {
                android.graphics.BitmapFactory.decodeFile(bgFile.absolutePath)
            } else {
                android.graphics.BitmapFactory.decodeStream(assets.open("res/background.bmp"))
            }
            if (bmp != null) {
                // Match the exact scaling factor used by C++ GameSurfaceView (screenWidth / 288f)
                val gameScale = resources.displayMetrics.widthPixels.toFloat() / (9 * 32f)
                val scaledW = maxOf(1, (bmp.width * gameScale).toInt())
                val scaledH = maxOf(1, (bmp.height * gameScale).toInt())
                val scaledBmp = bmp.scale(scaledW, scaledH)

                val tiledDrawable = scaledBmp.toDrawable(resources).apply {
                    setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
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
        swipeStyleSpinner = findViewById(R.id.spinner_swipe_style)
        swipeDescText = findViewById(R.id.text_swipe_desc)
        val save = "${filesDir.absolutePath}/save"
        
        val prefs = getSharedPreferences("tworld_prefs", MODE_PRIVATE)

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
        audioMarqueeText?.isSelected = true

        val bgmThemeSpinner = findViewById<Spinner>(R.id.spinner_bgm_theme)
        val sfxThemeSpinner = findViewById<Spinner>(R.id.spinner_sfx_theme)

        val marqueeHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var revertMarqueeRunnable: Runnable? = null

        fun getBgmThemeDescription(themeName: String): String {
            return when (themeName.trim().uppercase()) {
                "AKI" -> "+++ [♪] AKI — Demoscene legend since 1994. Discover her music: YT▶ @aki_128 ☕ ko-fi.com/aki128 +++"
                "MS" -> "[♪] MS — Chip's Challenge Original Microsoft MIDI Soundtrack"
                else -> "+++ [★] TILE WORLD MENU: [♪] Pick your soundtrack (AKI / MS / Custom) | [>] Set controls (D-Pad / Arrows / Touch) | [#] Select level sets | [*] Toggle SFX Assist (Haptics & Subs) | Press START GAME to begin! +++"
            }
        }

        fun updateAudioMarquee() {
            revertMarqueeRunnable?.let { marqueeHandler.removeCallbacks(it) }
            val bgmThemeName = bgmThemeSpinner.selectedItem?.toString() ?: "AKI"
            val desc = getBgmThemeDescription(bgmThemeName)

            audioMarqueeText?.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            audioMarqueeText?.gravity = Gravity.CENTER_VERTICAL
            audioMarqueeText?.text = desc
            audioMarqueeText?.isSelected = true
        }

        fun showStaticVolumeFeedback(text: String, durationMs: Long = 500) {
            revertMarqueeRunnable?.let { marqueeHandler.removeCallbacks(it) }
            audioMarqueeText?.ellipsize = null
            audioMarqueeText?.isSelected = false
            audioMarqueeText?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            audioMarqueeText?.text = text

            val runnable = Runnable { updateAudioMarquee() }
            revertMarqueeRunnable = runnable
            marqueeHandler.postDelayed(runnable, durationMs)
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

        bgmThemeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val selectedTheme = availableBgmThemes[pos.coerceIn(0, availableBgmThemes.size - 1)]
                prefs.edit { putString("bgm_theme", selectedTheme) }
                MusicManager.setActiveBgmTheme(selectedTheme)
                updateAudioMarquee()
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
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

        sfxThemeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val selectedTheme = availableThemes[pos.coerceIn(0, availableThemes.size - 1)]
                prefs.edit { putString("sfx_theme", selectedTheme) }
                GameEngine.nativeSetSfxTheme(selectedTheme)
                updateAudioMarquee()
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
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
                tvPixelPerfectLabel?.setTextColor("#00FF00".toColorInt())
            } else {
                tvPixelPerfectLabel?.setTextColor("#888888".toColorInt())
            }
        }
        updatePixelPerfectButtonUI()

        btnPixelPerfect?.setOnClickListener {
            isPixelPerfect = !isPixelPerfect
            prefs.edit { putBoolean("pixel_perfect_enabled", isPixelPerfect) }
            updatePixelPerfectButtonUI()
            if (isPixelPerfect) {
                showStaticVolumeFeedback("[ Pixel-Perfect Integer Scaling Enabled ]", 1500)
            } else {
                showStaticVolumeFeedback("[ Pixel-Perfect Scaling Disabled ]", 1500)
            }
        }

        // Onscreen Controls Dropdown Initialization (None, Arrow Keys, D-Pad Left, D-Pad Right)
        val onscreenControlsSpinner = findViewById<Spinner>(R.id.spinner_onscreen_controls)
        val onscreenOptions = listOf("None", "Arrow Keys", "D-Pad Left", "D-Pad Right")
        val onscreenAdapter = createCustomFontAdapter(onscreenOptions)
        onscreenControlsSpinner.adapter = onscreenAdapter


        var savedStyle = prefs.getInt("onscreen_controls_style", -1)
        if (savedStyle == -1) {
            savedStyle = if (prefs.getBoolean("controls_enabled", false)) 1 else 0
        }
        onscreenControlsSpinner.setSelection(savedStyle.coerceIn(0, onscreenOptions.size - 1))

        onscreenControlsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                prefs.edit { putInt("onscreen_controls_style", pos) }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }

        val touchOptions = listOf("None", "Touch Nav", "Swipe Classic", "Swipe Precise", "Swipe Fluid")
        val touchDescs = mapOf(
            0 to "No touch or swipe navigation enabled.",
            1 to "Tap on screen relative to Chip to move.",
            2 to "Standard continuous swipe controls.",
            3 to "Single discrete step per swipe flick.",
            4 to "Smooth continuous turns without lifting finger.",
        )
        val touchAdapter = createTouchControlAdapter(touchOptions) { isLynx }
        swipeStyleSpinner.adapter = touchAdapter

        val isTouchNav = prefs.getBoolean("touch_nav_enabled", false)
        val lastSwipeStyle = prefs.getInt("swipe_style", 2)

        val initialSelection = if (isTouchNav && !isLynx) {
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

        var autoJumpedFromTouchNavToNone = false

        swipeStyleSpinner.setSelection(initialSelection)
        swipeDescText.text = touchDescs[initialSelection] ?: ""

        swipeStyleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                if (isLynx && (pos == 1)) {
                    autoJumpedFromTouchNavToNone = true
                    swipeStyleSpinner.setSelection(0)
                    @Suppress("SetTextI18n")
                    swipeDescText.text = "Touch navigation is not available on Lynx ruleset."
                    return
                }

                if (autoJumpedFromTouchNavToNone) {
                    autoJumpedFromTouchNavToNone = false
                    @Suppress("SetTextI18n")
                    swipeDescText.text = "Touch navigation is not available on Lynx ruleset."
                } else {
                    swipeDescText.text = touchDescs[pos] ?: ""
                }

                when (pos) {
                    0 -> prefs.edit { putBoolean("touch_nav_enabled", false).putInt("swipe_style", -1) }
                    1 -> prefs.edit { putBoolean("touch_nav_enabled", true).putInt("swipe_style", 2) }
                    2 -> prefs.edit { putBoolean("touch_nav_enabled", false).putInt("swipe_style", 2) }
                    3 -> prefs.edit { putBoolean("touch_nav_enabled", false).putInt("swipe_style", 1) }
                    4 -> prefs.edit { putBoolean("touch_nav_enabled", false).putInt("swipe_style", 0) }
                }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
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

        gameSpeedSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                prefs.edit { putInt("game_speed_index", pos) }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }

        // Secret 10-second hold on START GAME button to toggle/reset Game Speed
        var isLongHoldTriggered = false
        val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val holdRunnable = Runnable {
            isLongHoldTriggered = true
            val currentlyVisible = gameSpeedContainer.isVisible
            if (currentlyVisible) {
                gameSpeedContainer.visibility = View.GONE
                gameSpeedSpinner.setSelection(3) // Reset to 100%
                prefs.edit { putInt("game_speed_index", 3).putBoolean("game_speed_visible", false) }
                android.widget.Toast.makeText(this, "Game Speed hidden & reset to 100%", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                gameSpeedContainer.visibility = View.VISIBLE
                prefs.edit { putBoolean("game_speed_visible", true) }
                android.widget.Toast.makeText(this, "Game Speed options unlocked!", android.widget.Toast.LENGTH_SHORT).show()
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

        msTilesSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                prefs.edit { putInt("last_tileset_ms", pos) }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }
        lynxTilesSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                prefs.edit { putInt("last_tileset_lynx", pos) }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
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

            setsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val fileName = setFiles[pos]
                    Log.d(TAG, "Selected set: $fileName")
                    prefs.edit {
                        putString(if (isLynx) "last_set_lynx" else "last_set_ms", fileName)
                        putString("last_set", fileName)
                    }
                    updateLevelList(fileName, data, sets, save)
                }
                override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
            }
        }

        val btnRulesetToggle = findViewById<View>(R.id.btn_ruleset_toggle)
        val tvRulesetLynx = findViewById<TextView>(R.id.tv_ruleset_lynx)
        val tvRulesetMs = findViewById<TextView>(R.id.tv_ruleset_ms)
        isLynx = prefs.getBoolean("lynx_filter", false)

        fun updateRulesetButtonUI() {
            if (isLynx) {
                tvRulesetLynx?.setTextColor("#00FF00".toColorInt())
                tvRulesetMs?.setTextColor("#888888".toColorInt())
            } else {
                tvRulesetLynx?.setTextColor("#888888".toColorInt())
                tvRulesetMs?.setTextColor("#00FF00".toColorInt())
            }
        }
        updateRulesetButtonUI()

        btnRulesetToggle?.setOnClickListener {
            isLynx = !isLynx
            prefs.edit { putBoolean("lynx_filter", isLynx) }
            updateRulesetButtonUI()
            touchAdapter.notifyDataSetChanged()
            if (isLynx) {
                val pos = swipeStyleSpinner.selectedItemPosition
                if (pos == 1) {
                    autoJumpedFromTouchNavToNone = true
                    swipeStyleSpinner.setSelection(0)
                    swipeDescText.text = "Touch navigation is not available on Lynx ruleset."
                }
                showStaticVolumeFeedback("[ Ruleset: LYNX ]", 1500)
            } else {
                autoJumpedFromTouchNavToNone = false
                val pos = swipeStyleSpinner.selectedItemPosition
                swipeDescText.text = touchDescs[pos] ?: ""
                showStaticVolumeFeedback("[ Ruleset: MS ]", 1500)
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

                val isTouchNavSelected = (swipeStyleSpinner.selectedItemPosition == 1) && !isLynx
                val selectedSwipeStyle = when (swipeStyleSpinner.selectedItemPosition) {
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
            val typeface = android.graphics.Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
            applyTypefaceRecursive(root, typeface)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom font", e)
        }
    }

    private fun applyTypefaceRecursive(view: View, typeface: android.graphics.Typeface) {
        when (view) {
            is TextView -> {
                if (view.paint.isFakeBoldText || (view.typeface?.isBold == true)) {
                    view.setTypeface(typeface, android.graphics.Typeface.BOLD)
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

    private fun createTouchControlAdapter(items: List<String>, isLynxProvider: () -> Boolean): ArrayAdapter<String> {
        val typeface = try {
            android.graphics.Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
        } catch (_: Exception) { null }

        return object : ArrayAdapter<String>(this, R.layout.spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                typeface?.let { view.typeface = it }
                val isLynx = isLynxProvider()
                val isTouchNav = position == 1
                if (isLynx && isTouchNav) {
                    view.paintFlags = view.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    view.setTextColor("#888888".toColorInt())
                } else {
                    view.paintFlags = view.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    view.setTextColor(Color.WHITE)
                }
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                typeface?.let { view.typeface = it }
                val isLynx = isLynxProvider()
                val isTouchNav = position == 1
                if (isLynx && isTouchNav) {
                    view.paintFlags = view.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    view.setTextColor("#888888".toColorInt())
                } else {
                    view.paintFlags = view.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    view.setTextColor(Color.WHITE)
                }
                return view
            }
        }.apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
    }

    private fun <T> createCustomFontAdapter(items: List<T>): ArrayAdapter<T> {
        val typeface = try {
            android.graphics.Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
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
        val prefs = getSharedPreferences("tworld_prefs", MODE_PRIVATE)
        val isTouchNavEnabled = prefs.getBoolean("touch_nav_enabled", false)
        val swipeStyle = prefs.getInt("swipe_style", 0)
        val isPixelPerfect = prefs.getBoolean("pixel_perfect_enabled", false)
        
        val speedValues = listOf(25, 50, 75, 100, 125, 150, 175, 200, 225, 250, 275, 300)
        val savedSpeedIdx = prefs.getInt("game_speed_index", 3).coerceIn(0, speedValues.size - 1)
        val speedPercent = speedValues[savedSpeedIdx]

        GameEngine.nativeSetGameSpeed(speedPercent)

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

        fun createWinBtn(iconRes: Int, onClick: () -> Unit): ImageButton {
            return ImageButton(this).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (10 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(R.drawable.tw_button)
                isFocusable = false
                isFocusableInTouchMode = false
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (50 * resources.displayMetrics.density).toInt(),
                    1.0f,
                )
                setOnClickListener { onClick() }
            }
        }

        buttonRow.addView(createWinBtn(R.drawable.ic_game_menu) { GameEngine.nativeTypeChar(GameEngine.TWK_ESCAPE) })
        buttonRow.addView(
            createWinBtn(R.drawable.ic_game_pause) {
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
            val customTypeface = android.graphics.Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
            titleTextLocal.setTypeface(customTypeface, android.graphics.Typeface.BOLD)
        } catch (_: Exception) {
            titleTextLocal.typeface = android.graphics.Typeface.MONOSPACE
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

            when (controlsStyle) {
                1 -> {
                    arrowContainer?.visibility = View.VISIBLE
                    dpadContainer?.visibility = View.GONE
                }
                2 -> { // D-Pad Left
                    arrowContainer?.visibility = View.GONE
                    dpadContainer?.visibility = View.VISIBLE
                    (dpadContainer?.layoutParams as? RelativeLayout.LayoutParams)?.let { lp ->
                        lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                        lp.marginStart = (16 * resources.displayMetrics.density).toInt()
                        lp.marginEnd = 0
                        lp.bottomMargin = (60 * resources.displayMetrics.density).toInt()
                        dpadContainer.layoutParams = lp
                    }
                }
                3 -> { // D-Pad Right
                    arrowContainer?.visibility = View.GONE
                    dpadContainer?.visibility = View.VISIBLE
                    (dpadContainer?.layoutParams as? RelativeLayout.LayoutParams)?.let { lp ->
                        lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_END)
                        lp.marginStart = 0
                        lp.marginEnd = (16 * resources.displayMetrics.density).toInt()
                        lp.bottomMargin = (60 * resources.displayMetrics.density).toInt()
                        dpadContainer.layoutParams = lp
                    }
                }
            }

            gameContainer.addView(overlay)
            setupDpad(overlay)
            applyCustomFont(overlay)
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
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        val endState = GameEngine.nativeGetLevelEndState()
                        if (endState > 0) {
                            // Level Completed -> Proceed to Next Level
                            GameEngine.nativeSendKey(GameEngine.TWK_RETURN, down = true)
                        } else if (endState < 0) {
                            // Level Failed / Died / Time Out -> Restart Level
                            GameEngine.nativeSendKey(GameEngine.TWC_SAMELEVEL, down = true)
                        } else {
                            // Game Active -> Send directional move key
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
        setupContainer(view.findViewById(R.id.container_dpad_cross))

        // Arrow Keys (4 Buttons)
        view.findViewById<View>(R.id.btn_up)?.let { setupBtn(it, GameEngine.TWK_UP) }
        view.findViewById<View>(R.id.btn_down)?.let { setupBtn(it, GameEngine.TWK_DOWN) }
        view.findViewById<View>(R.id.btn_left)?.let { setupBtn(it, GameEngine.TWK_LEFT) }
        view.findViewById<View>(R.id.btn_right)?.let { setupBtn(it, GameEngine.TWK_RIGHT) }

        // D-Pad Cross (Directional Cross)
        view.findViewById<View>(R.id.dpad_btn_up)?.let { setupBtn(it, GameEngine.TWK_UP) }
        view.findViewById<View>(R.id.dpad_btn_down)?.let { setupBtn(it, GameEngine.TWK_DOWN) }
        view.findViewById<View>(R.id.dpad_btn_left)?.let { setupBtn(it, GameEngine.TWK_LEFT) }
        view.findViewById<View>(R.id.dpad_btn_right)?.let { setupBtn(it, GameEngine.TWK_RIGHT) }
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
                android.graphics.Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
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
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            typeface = android.graphics.Typeface.MONOSPACE
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
            android.graphics.Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
        } catch (_: Exception) {
            android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
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
            setTypeface(customTypeface, android.graphics.Typeface.BOLD)
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
            setTypeface(customTypeface, android.graphics.Typeface.BOLD)
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
                setTypeface(customTypeface, android.graphics.Typeface.BOLD)
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
                        setTypeface(customTypeface, android.graphics.Typeface.BOLD)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
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
            setTypeface(customTypeface, android.graphics.Typeface.BOLD)
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
            android.widget.Toast.makeText(this, "Unlocked Level $levelNum: $levelName", android.widget.Toast.LENGTH_LONG).show()
        } else {
            android.widget.Toast.makeText(this, "Invalid Password", android.widget.Toast.LENGTH_SHORT).show()
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
                val customTypeface = android.graphics.Typeface.createFromAsset(assets, "fonts/FSEX300.ttf")
                titleTextLocal.setTypeface(customTypeface, android.graphics.Typeface.BOLD)
            } catch (_: Exception) {
                titleTextLocal.typeface = android.graphics.Typeface.MONOSPACE
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
