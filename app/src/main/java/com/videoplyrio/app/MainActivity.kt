package com.videoplyrio.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingPlaylistData: String? = null
    private var isPageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        setContentView(webView)

        setupWebView()
        handleIntent(intent)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // إلغاء تفعيل قيود استهلاك الإيماءات لبدء الفيديو وملء الشاشة ذاتياً تلقائياً
            mediaPlaybackRequiresUserGesture = false
            
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        // تسجيل الجسر البرمجي مع جافا سكربت لإغلاق النشاط عند الطلب
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                pendingPlaylistData?.let {
                    executePlaylistLoad(it)
                    pendingPlaylistData = null
                }
            }
        }

        webView.loadUrl("file:///android_asset/player.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val dataUri = intent?.data
        if (Intent.ACTION_VIEW == action && dataUri != null) {
            if (dataUri.scheme == "videoplyrio" && dataUri.host == "open") {
                val base64Data = dataUri.getQueryParameter("data")
                if (!base64Data.isNullOrEmpty()) {
                    if (isPageLoaded) {
                        executePlaylistLoad(base64Data)
                    } else {
                        pendingPlaylistData = base64Data
                    }
                }
            }
        }
    }

    private fun executePlaylistLoad(base64Data: String) {
        val cleanData = base64Data.replace("\\s".toRegex(), "")
        webView.evaluateJavascript("window.loadBase64Playlist('$cleanData')", null)
    }
}

// واجهة الجسر البرمجي لاستدعاء أوامر أندرويد من متصفح WebView
class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun closeApp() {
        activity.finishAffinity() // إغلاق التطبيق كلياً وبشكل نظيف
    }
}
