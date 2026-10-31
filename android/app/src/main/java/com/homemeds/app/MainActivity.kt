package com.homemeds.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PATH = "com.homemeds.app.PATH"
        private val cream = 0xFFF4F5EF.toInt()
    }

    private lateinit var webView: WebView
    private lateinit var offline: View
    private var bridge: Bridge? = null
    private var failed = false
    private var cameraRequest: PermissionRequest? = null
    private var permissionWaiter: CompletableDeferred<Unit>? = null
    private var voiceWaiter: CompletableDeferred<String?>? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraRequest?.let { if (granted) it.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else it.deny() }
        cameraRequest = null
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionWaiter?.complete(Unit)
    }
    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        voiceWaiter?.complete(if (r.resultCode == RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(cream) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            root.addView(notice(getString(R.string.webview_outdated), null))
            return
        }
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        offline = notice(getString(R.string.offline_title) + "\n\n" + getString(R.string.offline_body)) { webView.reload() }
        offline.isVisible = false
        root.addView(offline)
        configure(webView)
        bridge = Bridge(this).also { WebViewCompat.addWebMessageListener(webView, "HomeMedsNative", setOf(Config.origin), it) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) open(intent)
    }

    private fun notice(text: String, retry: (() -> Unit)?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(cream)
        setPadding(64, 64, 64, 64)
        addView(TextView(context).apply {
            this.text = text
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF203E35.toInt())
        })
        if (retry != null) addView(
            Button(context).apply {
                setText(R.string.retry)
                setOnClickListener { retry() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 48 },
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString HomeMedsAndroid/${BuildConfig.VERSION_NAME}"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) WebSettingsCompat.setSafeBrowsingEnabled(view.settings, true)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (Config.isSite(request.url)) return false
                if (request.isForMainFrame || request.hasGesture()) openExternal(request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                failed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                offline.isVisible = failed
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    failed = true
                    offline.isVisible = true
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                recreate()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                if (!Config.isSite(request.origin) || PermissionRequest.RESOURCE_VIDEO_CAPTURE !in request.resources) {
                    request.deny()
                    return
                }
                if (granted(Manifest.permission.CAMERA)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    cameraRequest?.deny()
                    cameraRequest = request
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (cameraRequest == request) cameraRequest = null
            }
        }
    }

    private fun openExternal(uri: Uri) {
        if (uri.scheme !in setOf("http", "https", "mailto", "tel")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun open(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_PATH)?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
        webView.loadUrl(Config.baseUrl + path)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::webView.isInitialized && intent.hasExtra(EXTRA_PATH)) open(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs(this).session != null) SyncWorker.enqueue(this)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onDestroy() {
        bridge?.dispose()
        bridge = null
        cameraRequest?.deny()
        cameraRequest = null
        permissionWaiter?.cancel()
        voiceWaiter?.cancel()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun requestPermissions(permissions: Array<String>) {
        val missing = permissions.filterNot(::granted)
        if (missing.isEmpty()) return
        val waiter = CompletableDeferred<Unit>()
        permissionWaiter?.cancel()
        permissionWaiter = waiter
        permissionLauncher.launch(missing.toTypedArray())
        waiter.await()
        // Android stops showing the dialog after repeated refusals; send the user to settings instead.
        if (missing.any { !granted(it) && !shouldShowRequestPermissionRationale(it) }) openAppSettings()
    }

    fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null)))
        } catch (_: ActivityNotFoundException) {
            openAppSettings()
        }
    }

    private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))

    fun voiceAvailable() = speechIntent().resolveActivity(packageManager) != null

    suspend fun listen(): String? {
        val waiter = CompletableDeferred<String?>()
        voiceWaiter?.cancel()
        voiceWaiter = waiter
        try {
            voiceLauncher.launch(speechIntent())
        } catch (_: ActivityNotFoundException) {
            throw UserError("手机没有可用的语音输入")
        }
        return waiter.await()
    }
}
