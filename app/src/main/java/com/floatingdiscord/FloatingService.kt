package com.floatingdiscord

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class FloatingService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.floatingdiscord.TOGGLE"
        const val ACTION_CLOSE = "com.floatingdiscord.CLOSE"
        const val CHANNEL = "floating"
        const val URL = "https://discord.com/app"
        const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 1.0f
        const val START_SCALE = 0.65f
    }

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: OverlayFrame? = null
    private var web: WebView? = null

    // Ukuran "layar virtual" tempat halaman dirender. Jendela hanya menampilkannya
    // dalam skala tertentu, jadi isi ikut mengecil/membesar (bukan terpotong).
    private var baseW = 0
    private var baseH = 0
    private var scale = START_SCALE

    class OverlayFrame(c: Context, private val onOutside: () -> Unit) : FrameLayout(c) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_OUTSIDE) onOutside()
            return super.onTouchEvent(e)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLOSE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> root?.let {
                it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (it.visibility == View.GONE) setFocusable(false)
            }
            else -> {
                goForeground()
                if (root == null) createWindow()
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jendela melayang", NotificationManager.IMPORTANCE_LOW)
        )
        fun pi(action: String) = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, FloatingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Floating Discord")
            .setContentText("Ketuk untuk tampilkan / sembunyikan")
            .setContentIntent(pi(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_menu_view, "Tampil/Sembunyi", pi(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tutup", pi(ACTION_CLOSE))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setFocusable(on: Boolean) {
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(r, lp)
        if (!on) {
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(r.windowToken, 0)
        }
    }

    private fun sendCtrlF(v: View) {
        val t = android.os.SystemClock.uptimeMillis()
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        fun key(action: Int, code: Int, meta: Int) = v.dispatchKeyEvent(
            KeyEvent(t, t, action, code, 0, meta)
        )
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl)
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, ctrl)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, ctrl)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWindow() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics

        baseW = dm.widthPixels
        baseH = dm.heightPixels
        scale = START_SCALE

        lp = WindowManager.LayoutParams(
            (baseW * scale).toInt(),
            (baseH * scale).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24)
            y = dp(48)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        val frame = OverlayFrame(this) { setFocusable(false) }
        frame.setBackgroundColor(Color.parseColor("#1E1F22"))
        frame.clipChildren = true

        // --- WebView ---
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
        }
        // UA mobile: hapus penanda WebView supaya Discord menyajikan layout HP
        val mobileUA = wv.settings.userAgentString
            .replace("; wv", "").replace("Version/4.0 ", "")
        wv.settings.userAgentString = mobileUA
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
            }
        }
        wv.setBackgroundColor(Color.parseColor("#313338"))
        wv.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                setFocusable(true)
                v.requestFocus()
            }
            false
        }
        wv.loadUrl(URL)
        web = wv

        // --- Title bar (ukuran logis; ikut diskalakan bersama isi) ---
        val barH = dp(48)
        val btnW = dp(46)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111214"))
        }
        val title = TextView(this).apply {
            text = "  Discord"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(title, LinearLayout.LayoutParams(0, barH, 1f))

        fun btn(label: String, onClick: (TextView) -> Unit) {
            val t = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                setOnClickListener { onClick(this) }
            }
            bar.addView(t, LinearLayout.LayoutParams(btnW, barH))
        }
        var desktop = false
        btn("🖥") { t ->
            desktop = !desktop
            wv.settings.userAgentString = if (desktop) UA else mobileUA
            t.text = if (desktop) "📱" else "🖥"
            wv.reload()
        }
        btn("🔍") {
            // Kirim Ctrl+F ke Discord. Jendela harus fokus dulu agar key event diterima.
            setFocusable(true)
            wv.requestFocus()
            wv.postDelayed({ sendCtrlF(wv) }, 120)
        }
        btn("◀") { if (wv.canGoBack()) wv.goBack() }
        btn("⟳") { wv.reload() }
        btn("—") { frame.visibility = View.GONE; setFocusable(false) }
        btn("✕") { stopSelf() }

        // --- Konten berukuran tetap baseW x baseH, lalu diskalakan ---
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(bar, LinearLayout.LayoutParams(-1, barH))
        content.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale
        val contentLp = FrameLayout.LayoutParams(baseW, baseH)
        frame.addView(content, contentLp)

        // Lebar diatur lewat skala; tinggi diatur lewat tinggi logis halaman
        // (WebView benar-benar lebih tinggi/pendek, isi tidak terpotong).
        fun applyResize(s: Float, heightPx: Float) {
            scale = s.coerceIn(MIN_SCALE, MAX_SCALE)
            val hPx = heightPx.coerceIn(dp(160).toFloat(), dm.heightPixels.toFloat())
            baseH = (hPx / scale).toInt()
            contentLp.height = baseH
            content.layoutParams = contentLp
            content.scaleX = scale
            content.scaleY = scale
            lp.width = (baseW * scale).toInt()
            lp.height = hPx.toInt()
            wm.updateViewLayout(frame, lp)
        }

        // --- Geser jendela lewat title bar ---
        var sx = 0; var sy = 0; var ox = 0f; var oy = 0f
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; ox = e.rawX; oy = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - ox).toInt()
                    lp.y = sy + (e.rawY - oy).toInt()
                    wm.updateViewLayout(frame, lp)
                }
            }
            true
        }

        // --- Handle resize: geser kiri-kanan = lebar (skala), atas-bawah = tinggi ---
        val handle = TextView(this).apply {
            text = "◢"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            gravity = Gravity.CENTER
            textSize = 16f
        }
        var sw = 0; var sh = 0; var rx = 0f; var ry = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sw = lp.width; sh = lp.height; rx = e.rawX; ry = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    applyResize((sw + (e.rawX - rx)) / baseW, sh + (e.rawY - ry))
                }
            }
            true
        }
        frame.addView(handle, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.END))

        root = frame
        wm.addView(frame, lp)
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        root?.let { runCatching { wm.removeView(it) } }
        web?.destroy()
        root = null
        web = null
        super.onDestroy()
    }
}
