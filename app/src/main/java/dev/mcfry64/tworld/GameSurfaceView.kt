package dev.mcfry64.tworld

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context.VIBRATOR_SERVICE
import android.content.Context.VIBRATOR_MANAGER_SERVICE
import android.graphics.Matrix
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withTranslation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlin.random.Random

@Suppress("ViewConstructor")
class GameSurfaceView(
    context: Context,
    private val isTouchNavEnabled: Boolean = false,
    private val swipeStyle: Int = 0,
    private val speedPercent: Int = 100,
    private val pixelPerfect: Boolean = false,
    private val onFinish: () -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {

    private var gameThread: GameThread? = null
    @Volatile private var isPaused = false
    private val keyBits = AtomicInteger(0)   // live D-pad/analog state for continuous keys
    private var gameBitmap: Bitmap? = null
    private var sharpBitmap: Bitmap? = null
    private var sharpCanvas: Canvas? = null
    private val nearestPaint = Paint().apply {
        isFilterBitmap = false
        isDither = false
    }
    private val sharpPaint = Paint().apply {
        isFilterBitmap = true
        isDither = false
    }
    private val overlayPaint = Paint().apply {
        color = Color.argb(50, 0, 0, 0)
    }
    private val pauseIconPaint = Paint().apply {
        color = Color.WHITE
        isFilterBitmap = false
    }
    private val pauseShadowPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        isFilterBitmap = false
    }

    private var rawBgBmp: Bitmap? = null
    private var lastBgScale = -1
    private var bgShader: BitmapShader? = null
    private val bgPaint = Paint().apply {
        try {
            val bmpStream = context.assets.open("res/background.bmp")
            rawBgBmp = BitmapFactory.decodeStream(bmpStream)
            isFilterBitmap = false
        } catch (_: Exception) {}
    }

    data class FloatingSfxText(
        val text: String,
        val startTime: Long = System.currentTimeMillis(),
        val durationMs: Long = 900L,
        val startYOffset: Float = (Random.nextFloat() * 24f) - 12f,
        val startXOffset: Float = (Random.nextFloat() * 40f) - 20f,
    )

    private val floatingSfxList = java.util.concurrent.CopyOnWriteArrayList<FloatingSfxText>()

    private val menuTypeface: android.graphics.Typeface by lazy {
        try {
            android.graphics.Typeface.createFromAsset(context.assets, "fonts/FSEX300.ttf")
        } catch (_: Exception) {
            android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
    }

    private val sfxTextPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 28f * resources.displayMetrics.density
        typeface = menuTypeface
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val sfxTextStrokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        textSize = 28f * resources.displayMetrics.density
        typeface = menuTypeface
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    @Volatile private var activeDstLeft = 0
    @Volatile private var activeDstTop = 0
    @Volatile private var activeDstWidth = 0
    @Volatile private var activeDstHeight = 0

    // Method to play specific haptic feedback based on the sound effect
    private fun playHapticFeedback(soundName: String) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return

        val effect = when (soundName.lowercase()) {
            "chip", "key", "door" -> {
                // Short, sharp vibration for pickups
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                } else {
                    VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }
            "teleport", "water", "bomb", "pop", "slide", "button" -> {
                // Double pulse for actions
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                } else {
                    val timings = longArrayOf(0, 30, 50, 30)
                    val amplitudes = intArrayOf(0, 255, 0, 255)
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                }
            }
            "oof", "die", "monster", "fire" -> {
                // Long, heavy vibration for danger/death
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                } else {
                    VibrationEffect.createOneShot(300, 255)
                }
            }
            "time", "timeup", "bell" -> {
                // Heartbeat pattern for time running out
                val timings = longArrayOf(0, 50, 100, 50)
                val amplitudes = intArrayOf(0, 255, 0, 128)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            else -> {
                // Default medium vibration
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } else {
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }
        }
        vibrator.vibrate(effect)
    }

    // Bits for the live key state (D-pad, held directional keys)
    companion object {
        private const val TAG = "TileWorld"
        private const val BIT_UP    = 1
        private const val BIT_DOWN  = 2
        private const val BIT_LEFT  = 4
        private const val BIT_RIGHT = 8
    }

    private val imm: InputMethodManager by lazy {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastSwipeDir = -1

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        GameEngine.onSfxTextListener = { text ->
            val prefs = context.getSharedPreferences("tworld_prefs", Context.MODE_PRIVATE)
            val sfxAssistMode = prefs.getInt("sfx_assist_mode", 0)
            
            if ((sfxAssistMode > 0) && text.isNotBlank()) {
                val cleanText = text.replace("\"", "").trim()
                
                if (cleanText.isNotEmpty()) {
                    // Trigger Haptic Vibration Pattern if AssistMode is 1 or 2
                    if ((sfxAssistMode == 1) || (sfxAssistMode == 2)) {
                        playHapticFeedback(cleanText)
                    }
                    
                    // Show Subtitle Visuals in bottom text window if AssistMode is 2 or 3
                    if ((sfxAssistMode == 2) || (sfxAssistMode == 3)) {
                        GameEngine.nativeShowMessage(cleanText)
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmpW = GameEngine.nativeGetScreenWidth()
        val bmpH = GameEngine.nativeGetScreenHeight()
        if ((bmpW <= 0) || (bmpH <= 0)) return super.onTouchEvent(event)

        if (isTouchNavEnabled) {
            val dWidth = if (activeDstWidth > 0) activeDstWidth else width
            val dHeight = if (activeDstHeight > 0) activeDstHeight else height
            val dLeft = activeDstLeft
            val dTop = activeDstTop

            val scaleX = dWidth.toFloat() / bmpW
            val scaleY = dHeight.toFloat() / bmpH

            val bmpX = ((event.x - dLeft) / scaleX).toInt().coerceIn(0, bmpW - 1)
            val bmpY = ((event.y - dTop) / scaleY).toInt().coerceIn(0, bmpH - 1)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    GameEngine.nativeSendTouch(bmpX, bmpY, down = true)
                }
                MotionEvent.ACTION_MOVE -> {
                    GameEngine.nativeSendTouch(bmpX, bmpY, down = true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        performClick()
                    }
                    GameEngine.nativeSendTouch(bmpX, bmpY, down = false)
                }
            }
            return true
        }

        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                lastSwipeDir = -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeStyle < 0) return true
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                val dist = sqrt(((dx * dx) + (dy * dy)).toDouble()).toFloat()

                when (swipeStyle) {
                    0 -> { // Fluid (Pivot)
                        val threshold = 30f * density
                        if ((dist > threshold) && !isPaused) {
                            val dir = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                if (dx > 0) GameEngine.TWK_RIGHT else GameEngine.TWK_LEFT
                            } else {
                                if (dy > 0) GameEngine.TWK_DOWN else GameEngine.TWK_UP
                            }
                            if (dir != lastSwipeDir) {
                                if (lastSwipeDir != -1) GameEngine.nativeSendKey(lastSwipeDir, down = false)
                                GameEngine.nativeSendKey(dir, down = true)
                                lastSwipeDir = dir
                            }
                            // Reset origin to current point for instant fluid turns
                            touchStartX = event.x
                            touchStartY = event.y
                        }
                    }
                    1 -> { // Precise (Flick)
                        val threshold = 30f * density
                        if ((dist > threshold) && (lastSwipeDir == -1) && !isPaused) {
                            val dir = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                if (dx > 0) GameEngine.TWK_RIGHT else GameEngine.TWK_LEFT
                            } else {
                                if (dy > 0) GameEngine.TWK_DOWN else GameEngine.TWK_UP
                            }
                            GameEngine.nativeTypeChar(dir)
                            lastSwipeDir = dir
                        }
                    }
                    else -> { // Classic
                        val threshold = 40f * density
                        if ((dist > threshold) && !isPaused) {
                            val dir = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                if (dx > 0) GameEngine.TWK_RIGHT else GameEngine.TWK_LEFT
                            } else {
                                if (dy > 0) GameEngine.TWK_DOWN else GameEngine.TWK_UP
                            }
                            if (dir != lastSwipeDir) {
                                if (lastSwipeDir != -1) GameEngine.nativeSendKey(lastSwipeDir, down = false)
                                GameEngine.nativeSendKey(dir, down = true)
                                lastSwipeDir = dir
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    val dist = sqrt(((dx * dx) + (dy * dy)).toDouble()).toFloat()
                    val tapThreshold = 20f * resources.displayMetrics.density
                    if ((lastSwipeDir == -1) && (dist < tapThreshold)) {
                        Log.d(TAG, "General tap detected - sending RETURN to continue")
                        GameEngine.nativeTypeChar(GameEngine.TWK_RETURN)
                    }
                }
                if ((lastSwipeDir != -1) && (swipeStyle != 1)) {
                    GameEngine.nativeSendKey(lastSwipeDir, down = false)
                }
                lastSwipeDir = -1
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────────────

    override fun surfaceCreated(h: SurfaceHolder) {
        val t = gameThread
        if ((t == null) || !t.isAlive) {
            gameThread = GameThread(h).also {
                it.isPausedByLifecycle = false
                it.start()
            }
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, h2: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        gameThread?.isPausedByLifecycle = true
        gameThread?.quit()
        gameThread = null
        recycleBitmaps()
    }

    fun pause() {
        gameThread?.isPausedByLifecycle = true
        gameThread?.quit()
        recycleBitmaps()
    }

    private fun recycleBitmaps() {
        gameBitmap?.recycle()
        gameBitmap = null
        sharpBitmap?.recycle()
        sharpBitmap = null
        sharpCanvas = null
    }

    fun resume() {
        val t = gameThread
        if (((t == null) || !t.isAlive) && holder.surface.isValid) {
            gameThread = GameThread(holder).also {
                it.isPausedByLifecycle = false
                it.start()
            }
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────

    /** Called from Activity.dispatchKeyEvent. Returns true if consumed. */
    fun handleKey(event: KeyEvent): Boolean {
        val twk = keyCodeToTwk(event.keyCode) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        GameEngine.nativeSendKey(twk, down)
        // Track directional keys in live bits too (for analog-style repeat)
        updateDpadBit(twk, down)
        return true
    }

    /** Called from Activity.dispatchGenericMotionEvent (gamepad axes). */
    fun handleMotion(event: MotionEvent): Boolean {
        if (((event.source and InputDevice.SOURCE_JOYSTICK) == 0) &&
            ((event.source and InputDevice.SOURCE_GAMEPAD) == 0)) return false

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val lx   = event.getAxisValue(MotionEvent.AXIS_X)
        val ly   = event.getAxisValue(MotionEvent.AXIS_Y)

        val prevBits = keyBits.get()
        var newBits  = prevBits and (BIT_UP or BIT_DOWN or BIT_LEFT or BIT_RIGHT).inv()

        val x = if (hatX != 0f) hatX else lx
        val y = if (hatY != 0f) hatY else ly
        if (x < -0.5f) newBits = newBits or BIT_LEFT
        if (x >  0.5f) newBits = newBits or BIT_RIGHT
        if (y < -0.5f) newBits = newBits or BIT_UP
        if (y >  0.5f) newBits = newBits or BIT_DOWN

        val changed = prevBits xor newBits
        keyBits.set(newBits)

        if ((changed and BIT_UP) != 0) GameEngine.nativeSendKey(GameEngine.TWK_UP, (newBits and BIT_UP) != 0)
        if ((changed and BIT_DOWN) != 0) GameEngine.nativeSendKey(GameEngine.TWK_DOWN, (newBits and BIT_DOWN) != 0)
        if ((changed and BIT_LEFT) != 0) GameEngine.nativeSendKey(GameEngine.TWK_LEFT, (newBits and BIT_LEFT) != 0)
        if ((changed and BIT_RIGHT) != 0) GameEngine.nativeSendKey(GameEngine.TWK_RIGHT, (newBits and BIT_RIGHT) != 0)

        return changed != 0
    }

    private fun keyCodeToTwk(keyCode: Int): Int? = when (keyCode) {
        // Directional: Arrow keys, WASD, Numpad 8/2/4/6
        KeyEvent.KEYCODE_DPAD_UP,    KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_NUMPAD_8 -> GameEngine.TWK_UP
        KeyEvent.KEYCODE_DPAD_DOWN,  KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_NUMPAD_2 -> GameEngine.TWK_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT,  KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_NUMPAD_4 -> GameEngine.TWK_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_NUMPAD_6 -> GameEngine.TWK_RIGHT

        // System & Menu navigation
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_A -> GameEngine.TWK_ESCAPE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_B -> GameEngine.TWK_RETURN

        // Quick Controls
        KeyEvent.KEYCODE_R -> GameEngine.TWC_SAMELEVEL     // R = Restart Level
        KeyEvent.KEYCODE_P -> GameEngine.TWC_PAUSEGAME     // P = Pause Game

        // Gamepad / Extra
        KeyEvent.KEYCODE_BUTTON_START -> GameEngine.TWC_TILESET
        KeyEvent.KEYCODE_BUTTON_X    -> GameEngine.TWC_SEESCORES
        KeyEvent.KEYCODE_BUTTON_Y    -> GameEngine.TWC_GOTOLEVEL
        KeyEvent.KEYCODE_BUTTON_SELECT -> GameEngine.TWC_KEYS
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP   -> GameEngine.TWK_PAGEUP
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> GameEngine.TWK_PAGEDOWN
        else -> null
    }

    private fun updateDpadBit(twk: Int, down: Boolean) {
        val bit = when (twk) {
            GameEngine.TWK_UP    -> BIT_UP
            GameEngine.TWK_DOWN  -> BIT_DOWN
            GameEngine.TWK_LEFT  -> BIT_LEFT
            GameEngine.TWK_RIGHT -> BIT_RIGHT
            else -> return
        }
        keyBits.getAndUpdate { if (down) it or bit else it and bit.inv() }
    }

    // ── Soft keyboard (for password entry) ───────────────────────────────

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.forEach { ch ->
                    when {
                        ch.isLetter() -> GameEngine.nativeTypeChar(ch.lowercaseChar().code)
                    }
                }
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) GameEngine.nativeTypeChar(GameEngine.TWK_BACKSPACE)
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = handleKey(event)
            override fun performEditorAction(actionCode: Int): Boolean {
                GameEngine.nativeTypeChar(GameEngine.TWK_RETURN)
                return true
            }
        }
    }

    // ── Game render thread ────────────────────────────────────────────────

    inner class GameThread(private val holder: SurfaceHolder) : Thread("GameRender") {
        @Volatile var running = true
        @Volatile var isPausedByLifecycle = false
        private val dstRect = Rect()
        var dstLeft = 0
        var dstTop = 0
        var dstWidth = 0
        var dstHeight = 0
        private var lastLevelNum = -1

        override fun run() {
            // Wait up to 1 second for native engine thread to start
            var startupWait = 0
            while (running && (!GameEngine.nativeIsRunning()) && (startupWait < 50)) {
                try { sleep(20) } catch (_: Exception) {}
                startupWait++
            }

            while (running) {
                if (!GameEngine.nativeIsRunning()) {
                    // Engine stopped, exit thread and return to launcher
                    break
                }

                val isPausedInC = GameEngine.nativeIsGamePaused()
                if (isPausedInC) {
                    if (!MusicManager.isPaused()) {
                        MusicManager.pauseMusic()
                    }
                } else {
                    if (MusicManager.isPaused()) {
                        MusicManager.resumeMusic()
                    }

                    // Re-trigger active directional keys continuously (matches touchscreen behavior)
                    val liveBits = keyBits.get()
                    if (liveBits != 0) {
                        if (liveBits and BIT_UP != 0) GameEngine.nativeSendKey(GameEngine.TWK_UP, down = true)
                        if (liveBits and BIT_DOWN != 0) GameEngine.nativeSendKey(GameEngine.TWK_DOWN, down = true)
                        if (liveBits and BIT_LEFT != 0) GameEngine.nativeSendKey(GameEngine.TWK_LEFT, down = true)
                        if (liveBits and BIT_RIGHT != 0) GameEngine.nativeSendKey(GameEngine.TWK_RIGHT, down = true)
                    }
                }

                val currentLevel = GameEngine.nativeGetCurrentLevelNumber()
                if (currentLevel != lastLevelNum && currentLevel > 0) {
                    val name = GameEngine.nativeGetCurrentLevelName()
                    val author = GameEngine.nativeGetCurrentLevelAuthor()
                    (context as? GameActivity)?.let { activity ->
                        activity.updateGameTitle(currentLevel, name, author)
                        MusicManager.playMusicForLevel(activity, currentLevel)
                    }
                    lastLevelNum = currentLevel
                }

                val w = GameEngine.nativeGetScreenWidth()
                val h = GameEngine.nativeGetScreenHeight()
                if (w <= 0 || h <= 0) { sleep(16); continue }

                // Ensure bitmap matches game resolution
                if (gameBitmap == null || gameBitmap!!.width != w || gameBitmap!!.height != h) {
                    gameBitmap?.recycle()
                    gameBitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                val bmp = gameBitmap ?: continue

                GameEngine.nativeCopyPixels(bmp)

                val kbReq = GameEngine.nativeGetKeyboardRequest()
                if (kbReq > 0) post {
                    requestFocus()
                    imm.showSoftInput(this@GameSurfaceView, InputMethodManager.SHOW_IMPLICIT)
                } else if (kbReq < 0) post {
                    imm.hideSoftInputFromWindow(windowToken, 0)
                }

                val canvas: Canvas = try {
                    holder.lockCanvas() ?: continue
                } catch (_: Exception) { continue }

                try {
                    val viewW = canvas.width
                    val viewH = canvas.height
                    val bmpW = bmp.width
                    val bmpH = bmp.height

                    val dstW: Int
                    val dstH: Int
                    val dstLeft: Int
                    val dstTop: Int
                    val scaleFactor: Int

                    if (pixelPerfect) {
                        scaleFactor = minOf(viewW / bmpW, viewH / bmpH).coerceAtLeast(1)
                        dstW = bmpW * scaleFactor
                        dstH = bmpH * scaleFactor
                        dstLeft = (viewW - dstW) / 2
                        dstTop = (viewH - dstH) / 2
                    } else {
                        scaleFactor = maxOf(1, (viewW.toFloat() / bmpW).toInt())
                        dstW = viewW
                        dstH = (bmpH * (viewW.toFloat() / bmpW)).toInt()
                        dstLeft = 0
                        dstTop = 0
                    }

                    val totalScale = if (pixelPerfect) scaleFactor else 1

                    if (rawBgBmp != null && totalScale != lastBgScale) {
                        lastBgScale = totalScale
                        val scaledBmp = if (totalScale > 1) {
                            rawBgBmp!!.scale(rawBgBmp!!.width * totalScale, rawBgBmp!!.height * totalScale, filter = false)
                        } else {
                            rawBgBmp!!
                        }
                        bgShader = BitmapShader(scaledBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                        bgPaint.shader = bgShader
                    }

                    if (bgShader != null) {
                        val shiftX = 0f // Zijwaards
                        val shiftY = 40f * scaleFactor  // Opwaards / neerwaards geschaald met zoomfactor

                        val bgMatrix = Matrix()
                        bgMatrix.setTranslate(dstLeft.toFloat() + shiftX, dstTop.toFloat() + shiftY)
                        bgShader?.setLocalMatrix(bgMatrix)

                        if (pixelPerfect) {
                            if (dstLeft > 0) {
                                canvas.drawRect(0f, 0f, dstLeft.toFloat(), viewH.toFloat(), bgPaint)
                            }
                            if (viewW > dstLeft + dstW) {
                                canvas.drawRect((dstLeft + dstW).toFloat(), 0f, viewW.toFloat(), viewH.toFloat(), bgPaint)
                            }
                            if (dstTop > 0) {
                                canvas.drawRect(0f, 0f, viewW.toFloat(), dstTop.toFloat(), bgPaint)
                            }
                            if (dstTop + dstH < viewH) {
                                canvas.drawRect(0f, (dstTop + dstH).toFloat(), viewW.toFloat(), viewH.toFloat(), bgPaint)
                            }
                        } else {
                            canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), bgPaint)
                        }
                    } else {
                        canvas.drawColor(Color.BLACK)
                    }

                    dstWidth = dstW
                    dstHeight = dstH
                    this.dstLeft = dstLeft
                    this.dstTop = dstTop

                    activeDstLeft = dstLeft
                    activeDstTop = dstTop
                    activeDstWidth = dstWidth
                    activeDstHeight = dstHeight

                    dstRect.set(dstLeft, dstTop, dstLeft + dstWidth, dstTop + dstHeight)

                    if (pixelPerfect) {
                        canvas.drawBitmap(bmp, null, dstRect, nearestPaint)
                    } else {
                        // Sharp Bilinear Sub-Pixel Scaling Pass for fluid scaling:
                        val k = maxOf(1, (dstW.toFloat() / bmpW).toInt() + 1)
                        val interW = bmpW * k
                        val interH = bmpH * k

                        if (sharpBitmap == null || sharpBitmap!!.width != interW || sharpBitmap!!.height != interH) {
                            sharpBitmap?.recycle()
                            sharpBitmap = createBitmap(interW, interH, Bitmap.Config.ARGB_8888)
                            sharpCanvas = Canvas(sharpBitmap!!)
                        }

                        val sBmp = sharpBitmap
                        val sCanvas = sharpCanvas
                        if (sBmp != null && sCanvas != null) {
                            val srcRect = Rect(0, 0, bmp.width, bmp.height)
                            val interRect = Rect(0, 0, interW, interH)
                            sCanvas.drawBitmap(bmp, srcRect, interRect, nearestPaint)
                            canvas.drawBitmap(sBmp, interRect, dstRect, sharpPaint)
                        } else {
                            canvas.drawBitmap(bmp, null, dstRect, nearestPaint)
                        }
                    }

                    // Render Retro Blocky Pause Overlay over playfield when game is paused
                    if (isPausedInC) {
                        canvas.withTranslation(dstLeft.toFloat(), dstTop.toFloat()) {
                            val mapSize = dstWidth.toFloat()
                            drawRect(0f, 0f, mapSize, mapSize, overlayPaint)

                            val centerX = mapSize / 2f
                            val centerY = mapSize / 2f
                            val iconW = mapSize * 0.18f
                            val iconH = mapSize * 0.24f
                            val barW = iconW * 0.35f
                            val gap = iconW * 0.30f

                            val leftBar = RectF(centerX - barW - gap / 2f, centerY - iconH / 2f, centerX - gap / 2f, centerY + iconH / 2f)
                            val rightBar = RectF(centerX + gap / 2f, centerY - iconH / 2f, centerX + gap / 2f + barW, centerY + iconH / 2f)

                            drawRect(leftBar, pauseIconPaint)
                            drawRect(rightBar, pauseIconPaint)

                            drawRect(leftBar, pauseShadowPaint)
                            drawRect(rightBar, pauseShadowPaint)
                        }
                    }

                    // Render Floating Retro SFX Text Overlay over playfield
                    if (floatingSfxList.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        val mapSize = dstWidth.toFloat()
                        val centerX = mapSize / 2f
                        val centerY = mapSize * 0.42f

                        val iterator = floatingSfxList.iterator()
                        while (iterator.hasNext()) {
                            val item = iterator.next()
                            val elapsed = now - item.startTime
                            if (elapsed >= item.durationMs) {
                                floatingSfxList.remove(item)
                                continue
                            }

                            val p = elapsed.toFloat() / item.durationMs.toFloat()
                            val alpha = if (p < 0.6f) 255 else ((1f - p) / 0.4f * 255f).toInt().coerceIn(0, 255)
                            val floatY = centerY + item.startYOffset - (p * 50f * resources.displayMetrics.density)
                            val floatX = centerX + item.startXOffset

                            sfxTextStrokePaint.alpha = alpha
                            sfxTextPaint.alpha = alpha

                            canvas.drawText(item.text, floatX, floatY, sfxTextStrokePaint)
                            canvas.drawText(item.text, floatX, floatY, sfxTextPaint)
                        }
                    }
                } finally {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }

                val sleepMs = ((16.666f / (speedPercent.coerceIn(10, 300) / 100.0f)).toLong()).coerceIn(4L, 100L)
                sleep(sleepMs)
            }
            if (!isPausedByLifecycle && !GameEngine.nativeIsRunning()) {
                post { onFinish() }
            }
        }

        fun quit() { 
            running = false 
        }
    }
}
