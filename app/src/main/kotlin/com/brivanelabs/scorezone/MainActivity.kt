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
import android.view.Gravity
import android.view.ViewGroup
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
import android.widget.Button
import android.widget.FrameLayout
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
 * Single-Activity native Android WebView wrapper for ScoreZone.
 *
 * The Activity creates exactly one WebView programmatically.
 * No XML layout, Compose, Material Components, Fragments or Navigation Component.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private lateinit var reloadButton: Button
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var cameraOutputFile: File? = null
    private var lastLoadHadError = false

    /**
     * Lightweight receiver used only to retry the WebView after connectivity
     * returns following a main-frame loading error.
     */
    private val connectivityReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            if (
                intent.action == ConnectivityManager.CONNECTIVITY_ACTION &&
                !intent.getBooleanExtra(
                    ConnectivityManager.EXTRA_NO_CONNECTIVITY,
                    false
                ) &&
                lastLoadHadError
            ) {
                lastLoadHadError = false

                if (::webView.isInitialized) {
                    webView.post {
                        webView.reload()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureSystemBars()
        registerFileChooser()
        registerBackCallback()
        configureWebView()
        configureReloadButton()
        configureRootLayout()
        registerConnectivityReceiver()

        setContentView(rootLayout)

        /*
         * The site is intentionally loaded immediately.
         * There is no artificial splash delay, Handler, Thread.sleep or
         * simulated loading period.
         */
        webView.loadUrl(Config.TARGET_URL)
    }

    /**
     * Native defaults before the website calls window.Android.
     */
    private fun configureSystemBars() {
        window.statusBarColor =
            Color.parseColor(
                Config.DEFAULT_STATUS_BAR_COLOR
            )

        window.navigationBarColor =
            Color.parseColor(
                Config.DEFAULT_NAVIGATION_BAR_COLOR
            )

        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).isAppearanceLightStatusBars = false
    }

    /**
     * Modern Activity Result API.
     *
     * No deprecated onActivityResult implementation is used.
     */
    private fun registerFileChooser() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result: ActivityResult ->
            deliverFileChooserResult(result)
        }
    }

    /**
     * Physical/gesture Android back behavior:
     *
     * 1. Navigate WebView history when possible.
     * 2. Otherwise finish the Activity.
     */
    private fun registerBackCallback() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    /**
     * Creates the only visual surface of the application: WebView.
     */
    private fun configureWebView() {
        webView = WebView(this)

        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        /*
         * Transparent WebView background avoids introducing another solid
         * loading surface between the transparent launch window and the site.
         */
        webView.setBackgroundColor(Color.TRANSPARENT)

        webView.overScrollMode =
            WebView.OVER_SCROLL_NEVER

        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        /*
         * Normal WebView caching behavior. Web resources can be reused when
         * appropriate while still allowing fresh network validation.
         */
        settings.cacheMode =
            WebSettings.LOAD_DEFAULT

        settings.allowContentAccess = true
        settings.allowFileAccess = true

        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true

        CookieManager
            .getInstance()
            .setAcceptCookie(true)

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.LOLLIPOP
        ) {
            CookieManager
                .getInstance()
                .setAcceptThirdPartyCookies(
                    webView,
                    true
                )
        }

        /*
         * JavaScript bridge:
         *
         * window.Android.setStatusBarColor(...)
         * window.Android.setStatusBarLight(...)
         * window.Android.setNavigationBarColor(...)
         */
        webView.addJavascriptInterface(
            WebAppInterface(this),
            "Android"
        )

        webView.webViewClient =
            createWebViewClient()

        webView.webChromeClient =
            createWebChromeClient()

        webView.setDownloadListener(
            DownloadListener {
                    url,
                    userAgent,
                    contentDisposition,
                    mimeType,
                    _ ->

                enqueueDownload(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType
                )
            }
        )
    }

    /**
     * Creates the gray/white "Recarregar" button shown only on load failure,
     * styled like old-fashioned system-default buttons (no icon, plain text).
     */
    private fun configureReloadButton() {
        reloadButton = Button(this)
        reloadButton.text = "Recarregar"
        reloadButton.setTextColor(Color.WHITE)
        reloadButton.setBackgroundColor(Color.parseColor("#5A5A5A"))
        reloadButton.isAllCaps = false

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.CENTER

        reloadButton.layoutParams = params
        reloadButton.visibility = android.view.View.GONE

        reloadButton.setOnClickListener {
            reloadButton.visibility = android.view.View.GONE
            webView.loadUrl(Config.TARGET_URL)
        }
    }

    /**
     * Root container stacking WebView and the reload button.
     */
    private fun configureRootLayout() {
        rootLayout = FrameLayout(this)
        rootLayout.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootLayout.setBackgroundColor(
            Color.parseColor(Config.DEFAULT_STATUS_BAR_COLOR)
        )

        rootLayout.addView(webView)
        rootLayout.addView(reloadButton)
    }

    /**
     * Dedicated WebViewClient.
     */
    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return routeUrl(
                    request.url
                )
            }

            override fun onPageStarted(
                view: WebView,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                reloadButton.visibility = android.view.View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    lastLoadHadError = true
                    reloadButton.visibility = android.view.View.VISIBLE
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                /*
                 * Prevent a dead Chromium renderer from propagating the crash
                 * to the Activity.
                 */
                lastLoadHadError = true
                reloadButton.visibility = android.view.View.VISIBLE
                return true
            }
        }
    }

    /**
     * Dedicated WebChromeClient.
     */
    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView,
                filePath: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {

                /*
                 * Cancel any previous callback so JavaScript never receives
                 * two competing file-selection callbacks.
                 */
                this@MainActivity.filePathCallback
                    ?.onReceiveValue(null)

                this@MainActivity.filePathCallback =
                    filePath

                return try {

                    val chooserIntent =
                        createFileChooserIntent(
                            fileChooserParams
                        )

                    fileChooserLauncher.launch(
                        chooserIntent
                    )

                    true

                } catch (_: Exception) {

                    this@MainActivity.filePathCallback
                        ?.onReceiveValue(null)

                    this@MainActivity.filePathCallback =
                        null

                    false
                }
            }
        }
    }

    /**
     * Keeps same-host HTTPS/HTTP navigation inside WebView.
     *
     * Any different host is delegated to the system browser.
     */
    private fun routeUrl(
        uri: Uri
    ): Boolean {

        val targetHost =
            Uri.parse(
                Config.TARGET_URL
            ).host

        val requestedHost =
            uri.host

        val isSameHost =
            targetHost != null &&
                requestedHost != null &&
                targetHost.equals(
                    requestedHost,
                    ignoreCase = true
                )

        val isWebScheme =
            uri.scheme.equals(
                "https",
                ignoreCase = true
            ) ||
                uri.scheme.equals(
                    "http",
                    ignoreCase = true
                )

        if (
            isSameHost &&
            isWebScheme
        ) {
            /*
             * false = WebView handles this navigation.
             */
            return false
        }

        /*
         * Different hosts are opened externally.
         */
        return try {

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    uri
                )
            )

            true

        } catch (_: Exception) {

            Toast.makeText(
                this,
                "Nenhuma aplicação disponível para abrir este link.",
                Toast.LENGTH_SHORT
            ).show()

            true
        }
    }

    /**
     * Builds the Android picker intent according to the website's
     * <input type="file"> accept/capture parameters.
     *
     * Supports:
     * - ordinary document/image/audio/video selection
     * - multiple selection
     * - camera capture
     * - video capture
     * - audio recording apps
     */
    private fun createFileChooserIntent(
        params: WebChromeClient.FileChooserParams
    ): Intent {

        val acceptTypes =
            params.acceptTypes
                .flatMap { value ->
                    value.split(',')
                }
                .map { value ->
                    value.trim()
                }
                .filter { value ->
                    value.isNotEmpty()
                }
                .distinct()

        val mimeTypes =
            if (acceptTypes.isEmpty()) {
                arrayOf("*/*")
            } else {
                acceptTypes.toTypedArray()
            }

        /*
         * HTML capture="..." support.
         */
        if (
            params.isCaptureEnabled &&
            mimeTypes.size == 1
        ) {

            val mime =
                mimeTypes[0]
                    .lowercase(
                        Locale.US
                    )

            when {

                mime.startsWith("image/") -> {
                    return createCaptureIntent(
                        action =
                            MediaStore.ACTION_IMAGE_CAPTURE,
                        prefix = "image",
                        extension = ".jpg"
                    )
                }

                mime.startsWith("video/") -> {
                    return createCaptureIntent(
                        action =
                            MediaStore.ACTION_VIDEO_CAPTURE,
                        prefix = "video",
                        extension = ".mp4"
                    )
                }

                mime.startsWith("audio/") -> {
                    return createCaptureIntent(
                        action =
                            MediaStore.Audio.Media.RECORD_SOUND_ACTION,
                        prefix = "audio",
                        extension = ".m4a"
                    )
                }
            }
        }

        /*
         * Normal document provider.
         */
        return Intent(
            Intent.ACTION_OPEN_DOCUMENT
        ).apply {

            addCategory(
                Intent.CATEGORY_OPENABLE
            )

            type =
                if (mimeTypes.size == 1) {
                    mimeTypes[0]
                } else {
                    "*/*"
                }

            if (mimeTypes.size > 1) {
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    mimeTypes
                )
            }

            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.mode ==
                    WebChromeClient.FileChooserParams
                        .MODE_OPEN_MULTIPLE
            )

            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    /**
     * Creates the temporary output used by camera/video capture.
     */
    private fun createCaptureIntent(
        action: String,
        prefix: String,
        extension: String
    ): Intent {

        val timestamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
            ).format(
                Date()
            )

        val file =
            File(
                cacheDir,
                "${prefix}_${timestamp}${extension}"
            )

        val uri =
            FileProvider.getUriForFile(
                this,
                "com.brivanelabs.scorezone.fileprovider",
                file
            )

        cameraOutputFile = file
        cameraOutputUri = uri

        return Intent(action).apply {

            putExtra(
                MediaStore.EXTRA_OUTPUT,
                uri
            )

            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            clipData =
                ClipData.newRawUri(
                    "output",
                    uri
                )
        }
    }

    /**
     * Completes the pending WebView file chooser callback.
     */
    private fun deliverFileChooserResult(
        result: ActivityResult
    ) {

        val callback =
            filePathCallback
                ?: return

        filePathCallback = null

        /*
         * User cancelled picker/camera.
         */
        if (
            result.resultCode !=
            RESULT_OK
        ) {

            cleanupCaptureFile()

            callback.onReceiveValue(
                null
            )

            return
        }

        /*
         * Camera capture.
         */
        val captureUri =
            cameraOutputUri

        if (captureUri != null) {

            cameraOutputUri = null
            cameraOutputFile = null

            callback.onReceiveValue(
                arrayOf(
                    captureUri
                )
            )

            return
        }

        /*
         * Normal document provider result.
         */
        callback.onReceiveValue(
            extractUris(
                result.data
            )
        )
    }

    /**
     * Extracts one or multiple selected documents.
     */
    private fun extractUris(
        data: Intent?
    ): Array<Uri>? {

        if (data == null) {
            return null
        }

        val clipData =
            data.clipData

        if (
            clipData != null &&
            clipData.itemCount > 0
        ) {

            return Array(
                clipData.itemCount
            ) { index ->
                clipData
                    .getItemAt(index)
                    .uri
            }
        }

        return data.data?.let {
            arrayOf(it)
        }
    }

    /**
     * Cleans temporary capture files.
     */
    private fun cleanupCaptureFile() {

        cameraOutputUri =
            null

        cameraOutputFile
            ?.delete()

        cameraOutputFile =
            null
    }

    /**
     * Sends a download to Android DownloadManager.
     *
     * The user gets the normal system download notification and the file is
     * placed in the public Downloads directory.
     */
    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {

        try {

            val fileName =
                URLUtil.guessFileName(
                    url,
                    contentDisposition,
                    mimeType
                )

            val request =
                DownloadManager.Request(
                    Uri.parse(url)
                )
                    .setNotificationVisibility(
                        DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                    .setMimeType(
                        mimeType
                            ?: "application/octet-stream"
                    )
                    .setTitle(
                        fileName
                    )
                    .setDescription(
                        "Download ScoreZone"
                    )

            if (
                !userAgent.isNullOrBlank()
            ) {

                request.addRequestHeader(
                    "User-Agent",
                    userAgent
                )
            }

            /*
             * Preserve WebView cookies when the website requires an authenticated
             * download endpoint.
             */
            CookieManager
                .getInstance()
                .getCookie(url)
                ?.let { cookie ->

                    request.addRequestHeader(
                        "Cookie",
                        cookie
                    )
                }

            val downloadManager =
                getSystemService(
                    DownloadManager::class.java
                )

            downloadManager.enqueue(
                request
            )

        } catch (_: Exception) {

            Toast.makeText(
                this,
                "Não foi possível iniciar o download.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Registers the legacy connectivity broadcast receiver because the
     * application deliberately avoids adding a networking library.
     */
    @Suppress("DEPRECATION")
    private fun registerConnectivityReceiver() {

        val filter =
            IntentFilter(
                ConnectivityManager.CONNECTIVITY_ACTION
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            registerReceiver(
                connectivityReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            registerReceiver(
                connectivityReceiver,
                filter
            )
        }
    }

    override fun onDestroy() {

        /*
         * Stop receiving connectivity events.
         */
        try {

            unregisterReceiver(
                connectivityReceiver
            )

        } catch (_: IllegalArgumentException) {
            /*
             * Already unregistered or Activity teardown interrupted registration.
             */
        }

        /*
         * Resolve any pending HTML file input callback.
         */
        filePathCallback
            ?.onReceiveValue(
                null
            )

        filePathCallback =
            null

        cleanupCaptureFile()

        webView.stopLoading()
        webView.webChromeClient = null
        webView.removeAllViews()
        webView.destroy()

        super.onDestroy()
    }
}