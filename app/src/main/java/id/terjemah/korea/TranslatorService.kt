package id.terjemah.korea

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Penerjemah LIVE.
 *
 * Alur:
 *  1. Layar terus direkam (MediaProjection). Tiap frame diringkas jadi "sidik jari" kecil.
 *  2. Kalau sidik jari berubah banyak (kamu scroll) -> kotak terjemahan disembunyikan.
 *  3. Kalau layar diam selama SETTLE_MS -> frame terakhir di-OCR & diterjemahkan -> kotak ditampilkan.
 *  4. Kotak terjemahan tembus sentuhan, jadi scroll/tap tetap jalan seperti biasa.
 *
 * Semua status "live" hanya disentuh di thread capture ([ch]); urusan UI di main thread.
 */
class TranslatorService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "id.terjemah.korea.STOP"
        private const val CHANNEL_ID = "translator"
        private const val NOTIF_ID = 1

        /** Layar dianggap "bergerak" kalau >3% grid berubah dibanding saat terakhir diterjemahkan. */
        private const val MOVE_THRESHOLD = 0.03f
        /** Saat bergerak, perubahan >1,5% dari frame acuan mereset hitungan diam. */
        private const val SETTLE_THRESHOLD = 0.015f
        /** Berapa lama layar harus diam sebelum diterjemahkan. */
        private const val SETTLE_MS = 350L

        /**
         * Android 12+ memblokir sentuhan yang menembus jendela overlay aplikasi lain
         * jika jendela itu lebih pekat dari 80%. Jadi kotak dibuat 80% pekat.
         */
        private const val OVERLAY_ALPHA = 0.8f

        /** Dua layar dianggap sama kalau <1% grid berbeda. */
        private const val CACHE_MATCH = 0.01f
        private const val CACHE_SIZE = 40
        /** Setelah kuota Gemini habis (HTTP 429), pakai offline selama ini sebelum mencoba lagi. */
        private const val QUOTA_COOLDOWN_MS = 10 * 60 * 1000L

        @Volatile
        var isRunning = false
            private set
    }

    private enum class State { MOVING, PROCESSING, SHOWING, PAUSED }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: OfflineEngine? = null

    // Mode online (main thread)
    private var gemini: GeminiEngine? = null
    private var geminiConfig: String? = null
    private var geminiBlockedUntil = 0L
    private var lastQuotaToast = 0L

    /** Hasil terjemahan terakhir per "sidik jari" layar — layar yang sama tidak dikirim ulang. */
    private class Cached(val sig: IntArray, val mode: String, val boxes: List<TextBox>)
    private val resultCache = ArrayDeque<Cached>() // hanya di thread capture

    // Rekam layar
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private lateinit var ch: Handler
    private val frameLock = Any()
    private var latestImage: Image? = null
    private var screenW = 0
    private var screenH = 0

    // Status live (hanya di thread capture)
    private var state = State.MOVING
    private var lastSig: IntArray? = null
    private var anchorSig: IntArray? = null
    private var baselineSig: IntArray? = null
    private var mask: BooleanArray? = null
    private val stableCheck = Runnable { onStable() }

    // UI (hanya di main thread)
    private var bubble: TextView? = null
    private var overlay: TranslationOverlayView? = null
    private var paused = false
    private var lastErrorToast = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Wajib dipanggil SEBELUM getMediaProjection() (Android 14+).
        startInForeground()

        if (projection != null) return START_NOT_STICKY // sudah berjalan

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            engine = OfflineEngine()
            computeScreenSize()
            addOverlayWindow()   // ditambahkan dulu supaya tombol 번역 selalu di atasnya
            showBubble()
            setupCapture(code, data)
            isRunning = true
        } catch (e: Exception) {
            toast("Gagal memulai: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ================= Notifikasi latar depan =================

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Penerjemah aktif", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Terjemah Korea (live) aktif")
            .setContentText("Berhenti scroll sebentar, terjemahan muncul otomatis. Ketuk 번역 untuk jeda.")
            .setContentIntent(openIntent)
            .addAction(0, "Berhenti", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ================= Rekam layar =================

    @SuppressLint("WrongConstant")
    private fun setupCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data)
            ?: throw IllegalStateException("Izin rekam layar tidak diberikan")
        projection = mp

        // Android 14+: callback wajib didaftarkan sebelum createVirtualDisplay.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        val thread = HandlerThread("capture").also { it.start() }
        captureThread = thread
        ch = Handler(thread.looper)

        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            } ?: return@setOnImageAvailableListener
            onFrame(img)
        }, ch)
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "terjemah-korea",
            screenW, screenH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        // Terjemahkan layar pertama begitu ada frame.
        ch.post {
            state = State.MOVING
            ch.postDelayed(stableCheck, SETTLE_MS)
        }
    }

    private fun computeScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
    }

    private fun grabFrame(): Bitmap? = synchronized(frameLock) {
        val img = latestImage ?: return null
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val padded = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) padded
        else Bitmap.createBitmap(padded, 0, 0, img.width, img.height).also { padded.recycle() }
    }

    // ================= Logika live (thread capture) =================

    private fun onFrame(img: Image) {
        val sig = FrameSignature.compute(img)
        synchronized(frameLock) {
            latestImage?.close()
            latestImage = img
        }
        lastSig = sig

        when (state) {
            State.PAUSED, State.PROCESSING -> Unit
            State.SHOWING -> {
                val base = baselineSig
                if (base == null || FrameSignature.diff(sig, base, mask) > MOVE_THRESHOLD) {
                    startMoving(sig)
                }
            }
            State.MOVING -> {
                val a = anchorSig
                if (a == null || FrameSignature.diff(sig, a, mask) > SETTLE_THRESHOLD) {
                    anchorSig = sig
                    ch.removeCallbacks(stableCheck)
                    ch.postDelayed(stableCheck, SETTLE_MS)
                }
            }
        }
    }

    private fun startMoving(sig: IntArray?) {
        state = State.MOVING
        anchorSig = sig
        main.post { setOverlayVisible(false) }
        ch.removeCallbacks(stableCheck)
        ch.postDelayed(stableCheck, SETTLE_MS)
    }

    private fun onStable() {
        if (state != State.MOVING) return
        val sigNow = lastSig
        val mode = Prefs.mode(this)
        val cacheKey = mode

        // Layar ini sudah pernah diterjemahkan? Pakai hasil lama (hemat kuota & instan).
        if (sigNow != null) {
            val hit = resultCache.firstOrNull {
                it.mode == cacheKey && FrameSignature.diff(it.sig, sigNow, null) < CACHE_MATCH
            }
            if (hit != null) {
                state = State.PROCESSING
                onResult(hit.boxes, sigNow, cacheKey)
                return
            }
        }

        val bmp = grabFrame()
        if (bmp == null) {
            ch.postDelayed(stableCheck, SETTLE_MS) // belum ada frame
            return
        }
        state = State.PROCESSING
        val sigAtCapture = lastSig

        scope.launch {
            val boxes = try {
                translateFrame(bmp, mode)
            } catch (e: Exception) {
                errorToast(e)
                emptyList()
            } finally {
                bmp.recycle()
            }
            ch.post { onResult(boxes, sigAtCapture, cacheKey) }
        }
    }

    /** Main thread. Mode Gemini dulu; kalau gagal/kuota habis, otomatis pakai offline. */
    private suspend fun translateFrame(bmp: Bitmap, mode: String): List<TextBox> {
        val exclude = bubbleRect()
        val now = SystemClock.uptimeMillis()
        if (mode == Prefs.MODE_GEMINI && now >= geminiBlockedUntil) {
            val g = geminiEngine()
            if (g != null) {
                try {
                    return g.translate(bmp, exclude)
                } catch (e: GeminiEngine.QuotaException) {
                    geminiBlockedUntil = now + QUOTA_COOLDOWN_MS
                    if (now - lastQuotaToast > QUOTA_COOLDOWN_MS) {
                        lastQuotaToast = now
                        toast("Kuota Gemini sedang habis/terbatas. Sementara pakai mode offline, dicoba lagi 10 menit lagi.")
                    }
                } catch (e: Exception) {
                    errorToast(e) // jaringan bermasalah dll -> pakai offline untuk layar ini
                }
            }
        }
        return engine?.translate(bmp, exclude) ?: emptyList()
    }

    private fun geminiEngine(): GeminiEngine? {
        val key = Prefs.geminiKey(this)
        if (key.isBlank()) return null
        val model = Prefs.geminiModel(this)
        val cfg = "$model|${key.hashCode()}"
        if (gemini == null || geminiConfig != cfg) {
            gemini = GeminiEngine(key, model)
            geminiConfig = cfg
        }
        return gemini
    }

    private fun onResult(boxes: List<TextBox>, sigAtCapture: IntArray?, mode: String) {
        if (state != State.PROCESSING) return
        val cur = lastSig
        // Layar sudah berubah selama proses -> hasil basi, ulangi.
        if (cur != null && sigAtCapture != null &&
            FrameSignature.diff(cur, sigAtCapture, null) > MOVE_THRESHOLD
        ) {
            startMoving(cur)
            return
        }
        main.post {
            val rects = overlay?.setBoxes(boxes, screenW) ?: emptyList()
            if (!paused) setOverlayVisible(boxes.isNotEmpty())
            val m = FrameSignature.mask(rects, screenW, screenH)
            ch.post {
                if (state != State.PROCESSING) return@post
                baselineSig = sigAtCapture
                mask = m
                state = State.SHOWING
                if (sigAtCapture != null &&
                    resultCache.none { it.mode == mode && FrameSignature.diff(it.sig, sigAtCapture, null) < CACHE_MATCH }
                ) {
                    resultCache.addFirst(Cached(sigAtCapture, mode, boxes))
                    while (resultCache.size > CACHE_SIZE) resultCache.removeLast()
                }
                // Kalau ada scroll di sela-sela tadi, langsung tangani.
                val now = lastSig
                if (now != null && sigAtCapture != null &&
                    FrameSignature.diff(now, sigAtCapture, m) > MOVE_THRESHOLD
                ) startMoving(now)
            }
        }
    }

    // ================= Jendela overlay (main thread) =================

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun addOverlayWindow() {
        val v = TranslationOverlayView(this).apply { visibility = View.GONE }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = OVERLAY_ALPHA
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(v, lp)
        overlay = v
    }

    private fun setOverlayVisible(visible: Boolean) {
        overlay?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = dp(52)
        val tv = TextView(this).apply {
            text = "번역"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E8453C"))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size - dp(8)
            y = screenH / 3
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(tv, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePause()
            }
            true
        }

        wm.addView(tv, lp)
        bubble = tv
    }

    /** Ketuk 번역: jeda / lanjutkan terjemahan live. */
    private fun togglePause() {
        paused = !paused
        (bubble?.background as? GradientDrawable)
            ?.setColor(Color.parseColor(if (paused) "#888888" else "#E8453C"))
        bubble?.alpha = if (paused) 0.6f else 1f
        if (paused) {
            setOverlayVisible(false)
            ch.post {
                state = State.PAUSED
                ch.removeCallbacks(stableCheck)
            }
            Toast.makeText(this, "Terjemahan live dijeda", Toast.LENGTH_SHORT).show()
        } else {
            ch.post { startMoving(lastSig) }
            Toast.makeText(this, "Terjemahan live aktif", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bubbleRect(): Rect? {
        val b = bubble ?: return null
        if (b.width == 0) return null
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
    }

    // ================= Bersih-bersih =================

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        virtualDisplay?.release()
        virtualDisplay = null
        captureThread?.let { t ->
            ch.removeCallbacksAndMessages(null)
            t.quitSafely()
        }
        captureThread = null
        imageReader?.setOnImageAvailableListener(null, null)
        synchronized(frameLock) {
            latestImage?.close()
            latestImage = null
        }
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        engine?.close()
        engine = null
        super.onDestroy()
    }

    private fun errorToast(e: Exception) {
        val now = SystemClock.uptimeMillis()
        if (now - lastErrorToast < 10_000) return
        lastErrorToast = now
        val msg = e.message ?: e.javaClass.simpleName
        toast(
            if (msg.contains("model", ignoreCase = true)) "Model terjemahan belum diunduh. Buka aplikasi & unduh dulu."
            else "Gagal menerjemahkan: $msg"
        )
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
            .toInt()
}
