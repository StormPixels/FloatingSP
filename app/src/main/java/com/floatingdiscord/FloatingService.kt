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
import kotlin.math.max

class FloatingService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.floatingdiscord.TOGGLE"
        const val ACTION_CLOSE = "com.floatingdiscord.CLOSE"
        const val CHANNEL = "floating"
        const val URL = "https://discord.com/app"
        const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: OverlayFrame? = null
    private var web: WebView? = null

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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWindow() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics

        lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.9).toInt(),
            (dm.heightPixels * 0.55).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.05).toInt()
            y = dp(80)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        val frame = OverlayFrame(this) { setFocusable(false) }
        frame.setBackgroundColor(Color.parseColor("#1E1F22"))

        // --- WebView ---
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = UA
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient = WebViewClient()
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

        // --- Title bar ---
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111214"))
        }
        val title = TextView(this).apply {
            text = "  Discord"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))

        fun btn(label: String, onClick: () -> Unit) {
            val t = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            }
            bar.addView(t, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
        btn("◀") { if (wv.canGoBack()) wv.goBack() }
        btn("⟳") { wv.reload() }
        btn("—") { frame.visibility = View.GONE; setFocusable(false) }
        btn("✕") { stopSelf() }

        // drag
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

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(40)))
        col.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        // --- Resize handle ---
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
                    lp.width = max(dp(220), sw + (e.rawX - rx).toInt())
                    lp.height = max(dp(200), sh + (e.rawY - ry).toInt())
                    wm.updateViewLayout(frame, lp)
                }
            }
            true
        }
        frame.addView(handle, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END))

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
