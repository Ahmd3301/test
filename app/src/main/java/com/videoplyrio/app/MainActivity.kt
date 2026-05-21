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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

    // حالة الاستخراج — لمنع التنفيذ المتداخل
    @Volatile private var isExtracting = false

    // رابط آخر محاولة — لإعادة المحاولة عند الفشل
    private var lastExtractionUrl: String? = null

    companion object {
        private const val EXTRACTION_TIMEOUT_MS = 15_000L   // 15 ثانية
        private const val POLL_INTERVAL_MS      = 100L      // polling كل 100ms بدل 150

        // User Agent يحاكي Chrome Desktop لتجاوز حجب الموبايل
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

        // امتدادات الموارد غير المطلوبة — يُحجب تحميلها لتسريع الاستخراج
        private val BLOCKED_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
            ".css", ".woff", ".woff2", ".ttf", ".eot",
            ".ico", ".pdf"
        )

        // نطاقات يجب حجبها (إعلانات وتتبع)
        private val BLOCKED_DOMAINS = listOf(
            "google-analytics", "doubleclick", "googlesyndication",
            "facebook.net", "connect.facebook", "twitter.com/i/jot",
            "analytics", "clickmagick", "popunder", "popads",
            "trafficjunky", "exoclick", "adnxs", "onclick",
            "mgid.com", "taboola", "outbrain"
        )
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyFullscreenFlags()

        mainWebView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled   = false
        }
        setContentView(mainWebView)

        setupMainWebView()
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishAffinity() }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onUserLeaveHint() {
        // الدخول التلقائي لـ PiP عند الضغط على زر الهوم أثناء التشغيل
        super.onUserLeaveHint()
        enterAndroidPipMode()
    }

    override fun onDestroy() {
        // تنظيف كامل — منع Memory Leaks
        cleanupHandler()
        stopExtraction()
        mainWebView.apply {
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    // ============================================================
    // Fullscreen Setup
    // ============================================================

    private fun applyFullscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ============================================================
    // Main WebView Setup
    // ============================================================

    private fun setupMainWebView() {
        mainWebView.settings.apply {
            javaScriptEnabled          = true
            domStorageEnabled          = true
            allowFileAccess            = true
            allowContentAccess         = true
            mixedContentMode           = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            @Suppress("SetJavaScriptEnabled")
            allowFileAccessFromFileURLs    = true
            allowUniversalAccessFromFileURLs = true
            // تسريع الرسم
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            cacheMode = WebSettings.LOAD_DEFAULT
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

        val hasDeepLink = intent?.data != null
        val targetUrl = if (hasDeepLink)
            "file:///android_asset/player.html?deeplink=true"
        else
            "file:///android_asset/player.html"

        mainWebView.loadUrl(targetUrl)
    }

    // ============================================================
    // Intent / Deep Link
    // ============================================================

    private fun handleIntent(intent: Intent?) {
        val dataUri = intent?.data ?: return
        if (Intent.ACTION_VIEW != intent.action) return

        if (dataUri.scheme == "videoplyrio" && dataUri.host == "open") {
            val base64Data = dataUri.getQueryParameter("data")
            if (!base64Data.isNullOrEmpty()) {
                if (isPageLoaded) executePlaylistLoad(base64Data)
                else pendingPlaylistData = base64Data
            }
        }
    }

    private fun executePlaylistLoad(base64Data: String) {
        val clean = base64Data.replace("\\s".toRegex(), "")
        mainWebView.evaluateJavascript("window.loadBase64Playlist('$clean')", null)
    }

    // ============================================================
    // Extraction Engine (Faselhd + sites with iframes)
    // ============================================================

    fun startBackgroundExtraction(mainUrl: String) {
        runOnUiThread {
            if (isExtracting) stopExtraction()   // إلغاء استخراج سابق إذا وُجد
            isExtracting = true
            lastExtractionUrl = mainUrl

            mainWebView.evaluateJavascript("window.showLoadingLoop()", null)

            extractorWebView = buildExtractorWebView()

            val headers = HashMap<String, String>()
            headers["Referer"] = deriveSiteReferer(mainUrl)

            extractorWebView?.webViewClient = buildStage1Client()
            extractorWebView?.loadUrl(mainUrl, headers)

            // Global timeout — يُلغي الاستخراج إذا تجاوز الوقت
            handler.postDelayed({
                if (isExtracting) {
                    runOnUiThread {
                        stopExtractionWithError("انتهت مهلة استخراج الرابط")
                    }
                }
            }, EXTRACTION_TIMEOUT_MS)
        }
    }

    /**
     * بناء WebView المستخرج بإعدادات مُحسَّنة للسرعة:
     * - حجب الصور والإعلانات والتتبع → أقل بيانات + أسرع تحميل
     * - User Agent ديسكتوب
     */
    private fun buildExtractorWebView(): WebView {
        return WebView(this@MainActivity).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString   = DESKTOP_UA
                mixedContentMode  = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadsImagesAutomatically = false   // حجب الصور تماماً
                blockNetworkImage        = true     // لا صور من الشبكة
                setRenderPriority(WebSettings.RenderPriority.HIGH)
            }
        }
    }

    /** إنشاء Referer من الـ URL الأصلي */
    private fun deriveSiteReferer(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            "${uri.scheme}://${uri.host}/"
        } catch (e: Exception) {
            "https://faselhd.center/"
        }
    }

    /** فلترة الموارد غير الضرورية أثناء الاستخراج */
    private fun shouldBlockResource(urlStr: String): Boolean {
        val lower = urlStr.lowercase()
        return BLOCKED_EXTENSIONS.any { lower.contains(it) } ||
               BLOCKED_DOMAINS.any    { lower.contains(it) }
    }

    private fun makeEmptyResponse() =
        WebResourceResponse("text/plain", "UTF-8",
            java.io.ByteArrayInputStream(ByteArray(0)))

    // ============================================================
    // Stage 1: الصفحة الرئيسية → البحث عن iframe أو m3u8 مباشرة
    // ============================================================

    private fun buildStage1Client(): WebViewClient {
        return object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: ""

                // إذا كان m3u8 ظهر مباشرة في الطلبات → اغتنمه فوراً!
                if (urlStr.contains(".m3u8", ignoreCase = true) && isExtracting) {
                    val clean = urlStr.split("?")[0].let { base ->
                        if (base.contains(".m3u8", ignoreCase = true)) urlStr else base
                    }
                    // التحقق من أنه ليس رابط مجزأ segment
                    if (!urlStr.contains("seg") && !urlStr.contains("/seg") &&
                        !urlStr.contains("chunk") && !urlStr.contains("/chunk")) {
                        handler.post { onM3u8Found(urlStr) }
                    }
                }

                if (shouldBlockResource(urlStr)) return makeEmptyResponse()
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                startPollingForIframe()
            }
        }
    }

    // ============================================================
    // Stage 1 Polling: البحث عن رابط الـ iframe في الصفحة
    // ============================================================

    private fun startPollingForIframe() {
        stopIframePolling()

        // JS يبحث عن عدة أنماط شائعة لروابط الـ iframe في مواقع البث
        val js = """
            (function() {
                // نمط faselhd الأصلي
                var li = document.querySelector('li[onclick*="player_iframe"]');
                if (li) {
                    var m = li.getAttribute('onclick').match(/'([^']+)'/);
                    if (m) return m[1];
                }
                // نمط iframe مباشر
                var iframe = document.querySelector('iframe[src*="embed"], iframe[src*="player"], iframe[src*="video"]');
                if (iframe && iframe.src && iframe.src.length > 10) return iframe.src;
                // نمط data-src
                var ds = document.querySelector('[data-src*="embed"], [data-src*="player"]');
                if (ds) return ds.getAttribute('data-src');
                return null;
            })()
        """.trimIndent()

        iframePollingRunnable = object : Runnable {
            override fun run() {
                if (!isExtracting) return
                runOnUiThread {
                    extractorWebView?.evaluateJavascript(js) { result ->
                        val iframeUrl = result?.trim()?.removeSurrounding("\"")
                        if (!iframeUrl.isNullOrEmpty() && iframeUrl != "null") {
                            stopIframePolling()
                            loadIframeAndExtractM3u8(iframeUrl)
                        } else {
                            handler.postDelayed(this, POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }
        handler.post(iframePollingRunnable!!)
    }

    private fun stopIframePolling() {
        iframePollingRunnable?.let { handler.removeCallbacks(it) }
        iframePollingRunnable = null
    }

    // ============================================================
    // Stage 2: تحميل الـ iframe والبحث عن m3u8
    // ============================================================

    private fun loadIframeAndExtractM3u8(iframeUrl: String) {
        runOnUiThread {
            val headers = HashMap<String, String>()
            headers["Referer"] = lastExtractionUrl ?: "https://faselhd.center/"

            extractorWebView?.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val urlStr = request?.url?.toString() ?: ""

                    // اعتراض m3u8 مباشرة من الشبكة — أسرع من polling
                    if (urlStr.contains(".m3u8", ignoreCase = true) && isExtracting) {
                        if (!urlStr.contains("seg") && !urlStr.contains("chunk")) {
                            handler.post { onM3u8Found(urlStr) }
                        }
                    }

                    if (shouldBlockResource(urlStr)) return makeEmptyResponse()
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    startPollingForM3u8()
                }
            }

            extractorWebView?.loadUrl(iframeUrl, headers)
        }
    }

    // ============================================================
    // Stage 2 Polling: البحث عن m3u8 في DOM
    // ============================================================

    private fun startPollingForM3u8() {
        pollingRunnable?.let { handler.removeCallbacks(it) }

        // JS يبحث عن m3u8 في عدة أنماط DOM شائعة
        val js = """
            (function() {
                // نمط faselhd: أزرار الجودة
                var btns = document.querySelectorAll('button[data-url*=".m3u8"], button.hd_btn');
                for (var i = 0; i < btns.length; i++) {
                    var u = btns[i].getAttribute('data-url') || btns[i].getAttribute('data-src');
                    if (u && u.indexOf('.m3u8') !== -1) return u;
                }
                // نمط source داخل video
                var src = document.querySelector('video source[src*=".m3u8"]');
                if (src) return src.getAttribute('src');
                // نمط video src مباشر
                var vid = document.querySelector('video[src*=".m3u8"]');
                if (vid) return vid.getAttribute('src');
                // نمط script يحتوي على m3u8 (بعض المشغلات تضع الرابط في script)
                var scripts = document.querySelectorAll('script:not([src])');
                for (var j = 0; j < scripts.length; j++) {
                    var txt = scripts[j].textContent;
                    var match = txt.match(/["'](https?:\/\/[^"']+\.m3u8[^"']*?)["']/);
                    if (match) return match[1];
                }
                return null;
            })()
        """.trimIndent()

        pollingRunnable = object : Runnable {
            override fun run() {
                if (!isExtracting) return
                runOnUiThread {
                    extractorWebView?.evaluateJavascript(js) { result ->
                        val m3u8 = result?.trim()?.removeSurrounding("\"")
                        if (!m3u8.isNullOrEmpty() && m3u8 != "null") {
                            onM3u8Found(m3u8)
                        } else {
                            handler.postDelayed(this, POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }
        handler.post(pollingRunnable!!)
    }

    // ============================================================
    // نجاح الاستخراج
    // ============================================================

    private fun onM3u8Found(url: String) {
        if (!isExtracting) return
        runOnUiThread {
            stopExtraction()
            mainWebView.evaluateJavascript("window.hideLoadingLoop()", null)
            mainWebView.evaluateJavascript("window.playExtractedUrl('${url.replace("'", "\\'")}')", null)
        }
    }

    // ============================================================
    // فشل الاستخراج — إخبار المستخدم
    // ============================================================

    private fun stopExtractionWithError(msg: String) {
        stopExtraction()
        val safeMsg = msg.replace("'", "\\'")
        mainWebView.evaluateJavascript("window.hideLoadingLoop()", null)
        mainWebView.evaluateJavascript("window.showExtractionError('$safeMsg')", null)
    }

    // ============================================================
    // Cleanup
    // ============================================================

    private fun stopExtraction() {
        isExtracting = false
        stopIframePolling()
        pollingRunnable?.let { handler.removeCallbacks(it) }
        pollingRunnable = null
        extractorWebView?.apply {
            stopLoading()
            destroy()
        }
        extractorWebView = null
    }

    private fun cleanupHandler() {
        handler.removeCallbacksAndMessages(null)
    }

    // ============================================================
    // PiP
    // ============================================================

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        runOnUiThread {
            val js = if (isInPictureInPictureMode)
                "document.querySelector('.plyr')?.classList.add('plyr--pip-active')"
            else
                "document.querySelector('.plyr')?.classList.remove('plyr--pip-active')"
            mainWebView.evaluateJavascript(js, null)
        }
    }

    fun enterAndroidPipMode() {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build()
                try { enterPictureInPictureMode(params) } catch (e: Exception) { /* غير مدعوم */ }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                try { enterPictureInPictureMode() } catch (e: Exception) { /* غير مدعوم */ }
            }
        }
    }
}

// ============================================================
// JavaScript Bridge
// ============================================================

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
