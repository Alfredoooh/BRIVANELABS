package com.brivanelabs.scorezone

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The only Activity in ScoreZone. The entire visible UI is one WebView created
 * in Kotlin, without an XML layout, Compose, Material, Fragments or navigation
 * libraries.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var cameraOutputFile: File? = null
    private var lastLoadHadError = false

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION == intent.action && !intent.getBooleanExtra(
                    ConnectivityManager.EXTRA_NO_CONNECTIVITY,
                    false
                ) && lastLoadHadError
            ) {
                lastLoadHadError = false
                webView.post { webView.reload() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureSystemBars()
        registerFileChooser()
        registerBackCallback()
        configureWebView()
        registerConnectivityReceiver()

        setContentView(webView)
        webView.loadUrl(Config.TARGET_URL)
    }

    private fun configureSystemBars() {
        window.statusBarColor = Color.parseColor(Config.DEFAULT_STATUS_BAR_COLOR)
        window.navigationBarColor = Color.parseColor(Config.DEFAULT_NAVIGATION_BAR_COLOR)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
    }

    private fun registerFileChooser() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result: ActivityResult ->
            deliverFileChooserResult(result)
        }
    }

    private fun registerBackCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun configureWebView() {
        webView = WebView(this)
        webView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Transparent while the page is still painting avoids introducing a native
        // white/colored loading surface between the system launch window and site.
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return routeUrl(request.url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    lastLoadHadError = true
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                // Returning true prevents the dead WebView renderer from taking down
                // the Activity. The OS has already terminated that renderer process.
                lastLoadHadError = true
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePath: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePath

                return try {
                    val intent = createFileChooserIntent(fileChooserParams)
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            }
        )
    }

    private fun routeUrl(uri: Uri): Boolean {
        val targetHost = Uri.parse(Config.TARGET_URL).host
        val requestedHost = uri.host

        if (targetHost != null && requestedHost != null &&
            targetHost.equals(requestedHost, ignoreCase = true) &&
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
        ) {
            return false
        }

        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            Toast.makeText(this, "Nenhuma aplicação disponível para abrir este link.", Toast.LENGTH_SHORT)
                .show()
            true
        }
    }

    private fun createFileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        val acceptTypes = params.acceptTypes
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val mimeTypes = if (acceptTypes.isEmpty()) {
            arrayOf("*/*")
        } else {
            acceptTypes.toTypedArray()
        }

        if (params.isCaptureEnabled && mimeTypes.size == 1) {
            val mime = mimeTypes[0].lowercase(Locale.US)
            when {
                mime.startsWith("image/") -> {
                    return createCaptureIntent(
                        MediaStore.ACTION_IMAGE_CAPTURE,
                        "image",
                        ".jpg"
                    )
                }

                mime.startsWith("video/") -> {
                    return createCaptureIntent(
                        MediaStore.ACTION_VIDEO_CAPTURE,
                        "video",
                        ".mp4"
                    )
                }

                mime.startsWith("audio/") -> {
                    return createCaptureIntent(
                        MediaStore.Audio.Media.RECORD_SOUND_ACTION,
                        "audio",
                        ".m4a"
                    )
                }
            }
        }

        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    private fun createCaptureIntent(
        action: String,
        prefix: String,
        extension: String
    ): Intent {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(cacheDir, "${prefix}_${stamp}$extension")
        val uri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )

        cameraOutputFile = file
        cameraOutputUri = uri

        return Intent(action).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("output", uri)
        }
    }

    private fun deliverFileChooserResult(result: ActivityResult) {
        val callback = filePathCallback ?: return
        filePathCallback = null

        if (result.resultCode != RESULT_OK) {
            cleanupCaptureFile()
            callback.onReceiveValue(null)
            return
        }

        val captureUri = cameraOutputUri
        if (captureUri != null) {
            cameraOutputUri = null
            cameraOutputFile = null
            callback.onReceiveValue(arrayOf(captureUri))
            return
        }

        val data = result.data
        val uris = extractUris(data)
        callback.onReceiveValue(uris)
    }

    private fun extractUris(data: Intent?): Array<Uri>? {
        if (data == null) return null

        val clipData = data.clipData
        if (clipData != null && clipData.itemCount > 0) {
            return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }

        return data.data?.let { arrayOf(it) }
    }

    private fun cleanupCaptureFile() {
        cameraOutputUri = null
        cameraOutputFile?.delete()
        cameraOutputFile = null
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                )
                .setMimeType(mimeType ?: "application/octet-stream")
                .setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                .setDescription("Download ScoreZone")

            if (!userAgent.isNullOrBlank()) {
                request.addRequestHeader("User-Agent", userAgent)
            }

            CookieManager.getInstance().getCookie(url)?.let { cookie ->
                request.addRequestHeader("Cookie", cookie)
            }

            val downloadManager = getSystemService(DownloadManager::class.java)
            downloadManager.enqueue(request)
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível iniciar o download.", Toast.LENGTH_SHORT)
                .show()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerConnectivityReceiver() {
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectivityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(connectivityReceiver, filter)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(connectivityReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered or Activity teardown interrupted it.
        }

        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        cleanupCaptureFile()

        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = null
        webView.removeAllViews()
        webView.destroy()

        super.onDestroy()
    }
}
