package com.brivanelabs.notes

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.brivanelabs.notes.bridge.NotesBridge

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var reloadBtn: Button
    private val appIndexUrl = "file:///android_asset/index.html"
    private var loadFailed = false
    private var pendingOpenNoteId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        reloadBtn = findViewById(R.id.reloadBtn)
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets -> insets }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowContentAccess = true
            allowFileAccess = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.addJavascriptInterface(NotesBridge(this, webView), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("BrivaneWeb", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                loadFailed = false
                reloadBtn.visibility = View.GONE
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    loadFailed = true
                    view.stopLoading()
                    view.visibility = View.INVISIBLE
                    reloadBtn.visibility = View.VISIBLE
                }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!loadFailed) {
                    view.visibility = View.VISIBLE
                    reloadBtn.visibility = View.GONE
                    applyStatusBarForTheme()
                    pendingOpenNoteId?.let { id ->
                        NotesBridge.openNoteInWebView(webView, id)
                        pendingOpenNoteId = null
                    }
                }
            }
        }

        reloadBtn.setOnClickListener {
            reloadBtn.visibility = View.GONE
            webView.visibility = View.VISIBLE
            loadFailed = false
            webView.loadUrl(appIndexUrl)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("(function(){try{return window.onNativeBack ? window.onNativeBack() : false;}catch(e){return false;}})()") { result ->
                    if (result != "true") moveTaskToBack(true)
                }
            }
        })

        handleNoteIntent(intent)
        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else webView.loadUrl(appIndexUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNoteIntent(intent)
    }

    private fun handleNoteIntent(intent: Intent?) {
        val noteId = intent?.getStringExtra("open_note_id") ?: return
        if (::webView.isInitialized && webView.progress == 100) NotesBridge.openNoteInWebView(webView, noteId) else pendingOpenNoteId = noteId
    }

    private fun applyStatusBarForTheme() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }

    override fun onPause() { super.onPause(); webView.onPause(); webView.pauseTimers() }
    override fun onResume() { super.onResume(); webView.onResume(); webView.resumeTimers(); applyStatusBarForTheme() }
    override fun onSaveInstanceState(outState: Bundle) { webView.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
