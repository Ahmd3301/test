package com.videoplyrio.app

import android.content.Intent
import android.content.res.Configuration
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var mainWebView: WebView
    private var extractorWebView: WebView? = null
    private var pendingPlaylistData: String? = null
    private var isPageLoaded = false
    private val handler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private var iframePollingRunnable: Runnable? = null

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

        mainWebView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        setContentView(mainWebView)

        setupWebView()
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })
    }

    private fun setupWebView() {
        mainWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        mainWebView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        mainWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                pendingPlaylistData?.let {
                    executePlaylistLoad(it)
                    pendingPlaylistData = null
                }
            }
        }

        val hasPendingData = intent?.data != null
        val targetUrl = if (hasPendingData) "file:///android_asset/player.html?deeplink=true" else "file:///android_asset/player.html"
        mainWebView.loadUrl(targetUrl)
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
        mainWebView.evaluateJavascript("window.loadBase64Playlist('$cleanData')", null)
    }

    fun startBackgroundExtraction(mainUrl: String) {
        runOnUiThread {
            mainWebView.evaluateJavascript("window.showLoadingLoop()", null)

            stopExtraction()

            extractorWebView = WebView(this@MainActivity).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }

            val extraHeaders = HashMap<String, String>()
            extraHeaders["Referer"] = "https://faselhd.center/"

            extractorWebView?.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }

                override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val urlStr = request?.url?.toString() ?: ""
                    if (urlStr.contains(".jpg") || urlStr.contains(".png") || urlStr.contains(".gif") || 
                        urlStr.contains(".css") || urlStr.contains("google") || urlStr.contains("analytics") || 
                        urlStr.contains("doubleclick") || urlStr.contains("onclick") || urlStr.contains("popunder")) {
                        return android.webkit.WebResourceResponse("text/plain", "UTF-8", null)
                    }
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    startPollingForIframe()
                }
            }

            extractorWebView?.loadUrl(mainUrl, extraHeaders)
        }
    }

    private fun startPollingForIframe() {
        val jsGetIframe = """
            (function() {
                var firstLi = document.querySelector('li[onclick*="player_iframe.location.href"]');
                if (firstLi) {
                    var onclick = firstLi.getAttribute('onclick');
                    var match = onclick.match(/'([^']+)'/);
                    return match ? match[1] : null;
                }
                return null;
            })()
        """.trimIndent()

        iframePollingRunnable = object : Runnable {
            override fun run() {
                runOnUiThread {
                    extractorWebView?.evaluateJavascript(jsGetIframe) { iframeUrl ->
                        val cleanIframeUrl = iframeUrl?.replace("\"", "")?.trim()
                        if (!cleanIframeUrl.isNullOrEmpty() && cleanIframeUrl != "null") {
                            stopIframePolling()
                            loadIframeAndExtractM3u8(cleanIframeUrl)
                        } else {
                            handler.postDelayed(this, 150)
                        }
                    }
                }
            }
        }
        iframePollingRunnable?.let { handler.post(it) }
    }

    private fun stopIframePolling() {
        iframePollingRunnable?.let { handler.removeCallbacks(it) }
        iframePollingRunnable = null
    }

    private fun loadIframeAndExtractM3u8(iframeUrl: String) {
        runOnUiThread {
            val extraHeaders = HashMap<String, String>()
            extraHeaders["Referer"] = "https://faselhd.center/"

            extractorWebView?.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }

                override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val urlStr = request?.url?.toString() ?: ""
                    if (urlStr.contains(".jpg") || urlStr.contains(".png") || urlStr.contains(".gif") || 
                        urlStr.contains(".css") || urlStr.contains("google") || urlStr.contains("analytics")) {
                        return android.webkit.WebResourceResponse("text/plain", "UTF-8", null)
                    }
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    startPollingForM3u8()
                }
            }
            extractorWebView?.loadUrl(iframeUrl, extraHeaders)
        }
    }

    private fun startPollingForM3u8() {
        val jsPoll = """
            (function() {
                var buttons = document.querySelectorAll('button.hd_btn');
                for (var i = 0; i < buttons.length; i++) {
                    var dataUrl = buttons[i].getAttribute('data-url');
                    if (dataUrl && dataUrl.indexOf('.m3u8') !== -1) {
                        return dataUrl;
                    }
                }
                return null;
            })()
        """.trimIndent()

        pollingRunnable = object : Runnable {
            override fun run() {
                runOnUiThread {
                    extractorWebView?.evaluateJavascript(jsPoll) { m3u8Url ->
                        val cleanM3u8 = m3u8Url?.replace("\"", "")?.trim()
                        if (!cleanM3u8.isNullOrEmpty() && cleanM3u8 != "null") {
                            mainWebView.evaluateJavascript("window.hideLoadingLoop()", null)
                            mainWebView.evaluateJavascript("window.playExtractedUrl('$cleanM3u8')", null)
                            stopExtraction()
                        } else {
                            handler.postDelayed(this, 150)
                        }
                    }
                }
            }
        }
        pollingRunnable?.let { handler.post(it) }

        handler.postDelayed({
            runOnUiThread {
                stopExtraction()
                cancelLoadingLoop()
            }
        }, 12000)
    }

    private fun cancelLoadingLoop() {
        runOnUiThread {
            mainWebView.evaluateJavascript("window.hideLoadingLoop()", null)
        }
    }

    private fun stopExtraction() {
        runOnUiThread {
            stopIframePolling()
            pollingRunnable?.let { handler.removeCallbacks(it) }
            pollingRunnable = null
            extractorWebView?.stopLoading()
            extractorWebView = null
        }
    }

    // تم تصحيح نوع المدخلات لـ Configuration ليتوافق تماماً مع توقيع الدالة الرئيسي بالنظام [1]
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        runOnUiThread {
            if (isInPictureInPictureMode) {
                mainWebView.evaluateJavascript("document.querySelector('.plyr').classList.add('plyr--pip-active')", null)
            } else {
                mainWebView.evaluateJavascript("document.querySelector('.plyr').classList.remove('plyr--pip-active')", null)
            }
        }
    }

    fun enterAndroidPipMode() {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val aspectRatio = android.util.Rational(16, 9)
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(params)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                enterPictureInPictureMode()
            }
        }
    }
}

class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun closeApp() {
        activity.finishAffinity()
    }

    @JavascriptInterface
    fun triggerExtraction(url: String) {
        activity.startBackgroundExtraction(url)
    }

    @JavascriptInterface
    fun enterPip() {
        activity.enterAndroidPipMode()
    }
}
