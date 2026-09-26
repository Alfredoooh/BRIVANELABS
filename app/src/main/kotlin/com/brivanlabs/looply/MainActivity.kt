package com.brivanlabs.looply

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
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
 * Single-Activity native Looply WebView host.
 *
 * The application intentionally contains one Activity and one WebView.
 * There is no Compose, Material Components, Fragment, Navigation Component,
 * XML layout or external UI toolkit.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var cameraOutputFile: File? = null

    private var lastLoadHadError = false

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingPermissionFlow = PermissionFlow.NONE

    private val preferences by lazy {
        getSharedPreferences(
            Config.PREFS,
            Context.MODE_PRIVATE
        )
    }

    private enum class PermissionFlow {
        NONE,
        WEB_RESOURCE,
        GEOLOCATION,
        NATIVE_REQUEST
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {
            if (
                intent?.action == ConnectivityManager.CONNECTIVITY_ACTION &&
                intent.getBooleanExtra(
                    ConnectivityManager.EXTRA_NO_CONNECTIVITY,
                    false
                ).not() &&
                lastLoadHadError &&
                ::webView.isInitialized
            ) {
                lastLoadHadError = false
                webView.post {
                    if (!isFinishing && !isDestroyed) {
                        webView.reload()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyStoredThemeBeforeWebView()
        registerFileChooser()
        registerPermissionLauncher()
        registerBackCallback()
        configureWebView()
        registerConnectivityReceiver()

        setContentView(webView)
        webView.loadUrl(Config.TARGET_URL)
    }

    /**
     * Applies persisted colors before WebView creation so a returning user sees
     * the same native surface while the website starts rendering.
     */
    private fun applyStoredThemeBeforeWebView() {
        val statusColor = readColor(
            Config.PREF_STATUS_BAR,
            Config.DEFAULT_STATUS_BAR_COLOR
        )

        val navigationColor = readColor(
            Config.PREF_NAVIGATION_BAR,
            Config.DEFAULT_NAVIGATION_BAR_COLOR
        )

        val splashColor = readColor(
            Config.PREF_SPLASH,
            defaultSplashForSystemTheme()
        )

        window.statusBarColor = statusColor
        window.navigationBarColor = navigationColor
        window.setBackgroundDrawable(
            ColorDrawable(splashColor)
        )

        updateStatusBarLight(
            preferences.getBoolean(
                Config.PREF_STATUS_LIGHT,
                false
            )
        )

        updateNavigationBarLight(
            preferences.getBoolean(
                Config.PREF_NAVIGATION_LIGHT,
                false
            )
        )
    }

    private fun defaultSplashForSystemTheme(): String {
        val isNight =
            (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        return if (isNight) {
            "#0D0F12"
        } else {
            "#FFFFFF"
        }
    }

    private fun registerFileChooser() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result: ActivityResult ->
            deliverFileChooserResult(result)
        }
    }

    private fun registerPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            handlePermissionResult(result)
        }
    }

    private fun registerBackCallback() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    private fun configureWebView() {
        webView = WebView(this)

        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

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
        settings.setGeolocationEnabled(true)

        CookieManager.getInstance().setAcceptCookie(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance()
                .setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(
            WebAppInterface(this),
            "Android"
        )

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType
            )
        }
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return routeUrl(uri)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    lastLoadHadError = true
                }
            }
        }
    }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {

            override fun onShowFileChooser(
                view: WebView?,
                filePath: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePath == null || fileChooserParams == null) {
                    return false
                }

                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePath

                return try {
                    fileChooserLauncher.launch(
                        createFileChooserIntent(fileChooserParams)
                    )
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(
                request: PermissionRequest?
            ) {
                if (request == null) return

                runOnUiThread {
                    handleWebPermissionRequest(request)
                }
            }

            override fun onPermissionRequestCanceled(
                request: PermissionRequest?
            ) {
                if (request != null && pendingWebPermissionRequest === request) {
                    pendingWebPermissionRequest = null
                    pendingPermissionFlow = PermissionFlow.NONE
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin == null || callback == null) return

                runOnUiThread {
                    handleGeolocationRequest(
                        origin,
                        callback
                    )
                }
            }
        }
    }

    private fun routeUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US)

        if (scheme == "http" || scheme == "https") {
            val targetHost = Uri.parse(Config.TARGET_URL).host
            val requestedHost = uri.host

            val sameHost =
                targetHost != null &&
                    requestedHost != null &&
                    targetHost.equals(requestedHost, true)

            if (sameHost) {
                return false
            }
        }

        if (scheme == "about" || scheme == "file") {
            return false
        }

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

    private fun createFileChooserIntent(
        params: WebChromeClient.FileChooserParams
    ): Intent {
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
            when (val mime = mimeTypes[0].lowercase(Locale.US)) {
                "image/*",
                "image/jpeg",
                "image/png",
                "image/webp" -> {
                    return createCaptureIntent(
                        MediaStore.ACTION_IMAGE_CAPTURE,
                        "image",
                        ".jpg"
                    )
                }

                "video/*",
                "video/mp4",
                "video/webm" -> {
                    return createCaptureIntent(
                        MediaStore.ACTION_VIDEO_CAPTURE,
                        "video",
                        ".mp4"
                    )
                }

                "audio/*",
                "audio/mp4",
                "audio/mpeg",
                "audio/webm" -> {
                    return Intent(
                        MediaStore.Audio.Media.RECORD_SOUND_ACTION
                    )
                }
            }
        }

        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)

            type = if (mimeTypes.size == 1) {
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
                    WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            )

            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    private fun createCaptureIntent(
        action: String,
        prefix: String,
        extension: String
    ): Intent {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS",
            Locale.US
        ).format(Date())

        val file = File(
            cacheDir,
            "${prefix}_${timestamp}${extension}"
        )

        val uri = FileProvider.getUriForFile(
            this,
            "com.brivanlabs.looply.fileprovider",
            file
        )

        cameraOutputFile = file
        cameraOutputUri = uri

        return Intent(action).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            clipData = ClipData.newRawUri(
                "output",
                uri
            )
        }
    }

    private fun deliverFileChooserResult(
        result: ActivityResult
    ) {
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

        callback.onReceiveValue(
            extractUris(result.data)
        )
    }

    private fun extractUris(data: Intent?): Array<Uri>? {
        if (data == null) {
            return null
        }

        val clipData = data.clipData

        if (clipData != null && clipData.itemCount > 0) {
            return Array(clipData.itemCount) { index ->
                clipData.getItemAt(index).uri
            }
        }

        return data.data?.let { arrayOf(it) }
    }

    private fun handleWebPermissionRequest(
        request: PermissionRequest
    ) {
        if (isFinishing || isDestroyed) {
            request.deny()
            return
        }

        val targetHost = Uri.parse(Config.TARGET_URL).host
        val requestedHost = request.origin?.host

        if (
            targetHost == null ||
            requestedHost == null ||
            !targetHost.equals(requestedHost, true)
        ) {
            request.deny()
            return
        }

        val requested = request.resources.toSet()
        val permissions = mutableListOf<String>()

        if (
            PermissionRequest.RESOURCE_VIDEO_CAPTURE in requested &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.CAMERA
        }

        if (
            PermissionRequest.RESOURCE_AUDIO_CAPTURE in requested &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.RECORD_AUDIO
        }

        if (permissions.isEmpty()) {
            grantWebRequest(request)
            return
        }

        pendingWebPermissionRequest = request
        pendingPermissionFlow = PermissionFlow.WEB_RESOURCE

        permissionLauncher.launch(
            permissions.distinct().toTypedArray()
        )
    }

    private fun grantWebRequest(
        request: PermissionRequest
    ) {
        val grantedResources = request.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                else -> false
            }
        }.toTypedArray()

        if (grantedResources.isNotEmpty()) {
            request.grant(grantedResources)
        } else {
            request.deny()
        }
    }

    private fun handleGeolocationRequest(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        if (isFinishing || isDestroyed) {
            callback.invoke(origin, false, false)
            return
        }

        val targetHost = Uri.parse(Config.TARGET_URL).host
        val requestedHost = Uri.parse(origin).host

        if (
            targetHost == null ||
            requestedHost == null ||
            !targetHost.equals(requestedHost, true)
        ) {
            callback.invoke(origin, false, false)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            callback.invoke(origin, true, false)
            return
        }

        if (hasLocationPermission()) {
            callback.invoke(origin, true, false)
            return
        }

        pendingGeoOrigin = origin
        pendingGeoCallback = callback
        pendingPermissionFlow = PermissionFlow.GEOLOCATION

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun handlePermissionResult(
        result: Map<String, Boolean>
    ) {
        when (pendingPermissionFlow) {
            PermissionFlow.WEB_RESOURCE -> {
                pendingWebPermissionRequest?.let { request ->
                    if (!isFinishing && !isDestroyed) {
                        grantWebRequest(request)
                    } else {
                        request.deny()
                    }
                }

                pendingWebPermissionRequest = null
            }

            PermissionFlow.GEOLOCATION -> {
                val origin = pendingGeoOrigin
                val callback = pendingGeoCallback

                pendingGeoOrigin = null
                pendingGeoCallback = null

                if (origin != null && callback != null) {
                    val granted =
                        result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                            hasLocationPermission()

                    callback.invoke(
                        origin,
                        granted,
                        false
                    )
                }
            }

            PermissionFlow.NATIVE_REQUEST,
            PermissionFlow.NONE -> Unit
        }

        pendingPermissionFlow = PermissionFlow.NONE
    }

    private fun hasLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        return checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestNativePermissions(
        kind: String
    ) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }

        val requested = when (kind.lowercase(Locale.US)) {
            "camera" -> arrayOf(
                Manifest.permission.CAMERA
            )

            "microphone",
            "mic",
            "audio" -> arrayOf(
                Manifest.permission.RECORD_AUDIO
            )

            "location",
            "gps" -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            "all",
            "media" -> arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            else -> emptyArray()
        }

        val missing = requested.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            pendingPermissionFlow = PermissionFlow.NATIVE_REQUEST
            permissionLauncher.launch(
                missing.toTypedArray()
            )
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val fileName = android.webkit.URLUtil.guessFileName(
                url,
                contentDisposition,
                mimeType
            )

            val request = DownloadManager.Request(
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
                    mimeType ?: "application/octet-stream"
                )
                .setTitle(fileName)
                .setDescription("Download Looply")

            if (!userAgent.isNullOrBlank()) {
                request.addRequestHeader(
                    "User-Agent",
                    userAgent
                )
            }

            CookieManager.getInstance()
                .getCookie(url)
                ?.let { cookie ->
                    request.addRequestHeader(
                        "Cookie",
                        cookie
                    )
                }

            getSystemService(
                DownloadManager::class.java
            ).enqueue(request)

        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Não foi possível iniciar o download.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyStatusBarLight(
        isLight: Boolean
    ) {
        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).isAppearanceLightStatusBars = isLight
    }

    private fun applyNavigationBarLight(
        isLight: Boolean
    ) {
        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).isAppearanceLightNavigationBars = isLight
    }

    fun updateStatusBarColor(
        color: Int
    ) {
        preferences.edit()
            .putInt(
                Config.PREF_STATUS_BAR,
                color
            )
            .apply()

        window.statusBarColor = color
    }

    fun updateNavigationBarColor(
        color: Int
    ) {
        preferences.edit()
            .putInt(
                Config.PREF_NAVIGATION_BAR,
                color
            )
            .apply()

        window.navigationBarColor = color
    }

    fun updateSplashColor(
        color: Int
    ) {
        preferences.edit()
            .putInt(
                Config.PREF_SPLASH,
                color
            )
            .apply()

        window.setBackgroundDrawable(
            ColorDrawable(color)
        )
    }

    fun updateStatusBarDimmed(
        enabled: Boolean,
        amount: Float
    ) {
        val base = preferences.getInt(
            Config.PREF_STATUS_BAR,
            Color.parseColor(
                Config.DEFAULT_STATUS_BAR_COLOR
            )
        )

        window.statusBarColor = if (enabled) {
            dimColor(base, amount)
        } else {
            base
        }
    }

    fun updateNavigationBarDimmed(
        enabled: Boolean,
        amount: Float
    ) {
        val base = preferences.getInt(
            Config.PREF_NAVIGATION_BAR,
            Color.parseColor(
                Config.DEFAULT_NAVIGATION_BAR_COLOR
            )
        )

        window.navigationBarColor = if (enabled) {
            dimColor(base, amount)
        } else {
            base
        }
    }

    fun updateThemeMode(
        mode: String
    ) {
        val normalized = mode.lowercase(Locale.US)

        preferences.edit()
            .putString(
                Config.PREF_THEME_MODE,
                normalized
            )
            .apply()

        when (normalized) {
            "light" -> applyThemePreset(true)
            "dark" -> applyThemePreset(false)

            "system" -> {
                val isNight =
                    (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES

                applyThemePreset(!isNight)
            }
        }
    }

    private fun applyThemePreset(
        light: Boolean
    ) {
        if (light) {
            updateSplashColor(Color.WHITE)
            updateStatusBarColor(
                Color.rgb(242, 242, 247)
            )
            updateNavigationBarColor(Color.WHITE)
            updateStatusBarLight(true)
            updateNavigationBarLight(true)
        } else {
            updateSplashColor(
                Color.rgb(13, 15, 18)
            )
            updateStatusBarColor(
                Color.rgb(18, 18, 18)
            )
            updateNavigationBarColor(
                Color.rgb(18, 18, 18)
            )
            updateStatusBarLight(false)
            updateNavigationBarLight(false)
        }
    }

    fun updateStatusBarLight(
        isLight: Boolean
    ) {
        preferences.edit()
            .putBoolean(
                Config.PREF_STATUS_LIGHT,
                isLight
            )
            .apply()

        applyStatusBarLight(isLight)
    }

    fun updateNavigationBarLight(
        isLight: Boolean
    ) {
        preferences.edit()
            .putBoolean(
                Config.PREF_NAVIGATION_LIGHT,
                isLight
            )
            .apply()

        applyNavigationBarLight(isLight)
    }

    fun requestKeepScreenOn(
        keepOn: Boolean
    ) {
        if (::webView.isInitialized) {
            webView.keepScreenOn = keepOn
        }
    }

    fun copyToClipboard(
        text: String
    ) {
        val clipboard = getSystemService(
            ClipboardManager::class.java
        )

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Looply",
                text
            )
        )
    }

    fun shareText(
        text: String,
        title: String
    ) {
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            text
                        )
                    },
                    title.ifBlank {
                        "Partilhar"
                    }
                )
            )
        } catch (_: Exception) {
            // No share handler is available.
        }
    }

    fun vibrate(
        milliseconds: Long
    ) {
        val safeDuration = milliseconds.coerceIn(
            1L,
            1000L
        )

        val vibrator = getSystemService(
            Vibrator::class.java
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(
                    safeDuration,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(safeDuration)
        }
    }

    fun openExternalUrl(
        url: String
    ) {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                )
            )
        } catch (_: Exception) {
            // Ignore invalid/no-handler URLs.
        }
    }

    fun appVersion(): String = "1.0.0"

    private fun dimColor(
        color: Int,
        amount: Float
    ): Int {
        val factor = 1f - amount.coerceIn(
            0f,
            0.65f
        )

        return Color.rgb(
            (Color.red(color) * factor).toInt(),
            (Color.green(color) * factor).toInt(),
            (Color.blue(color) * factor).toInt()
        )
    }

    private fun readColor(
        key: String,
        fallback: String
    ): Int {
        return if (
            preferences.contains(key)
        ) {
            preferences.getInt(
                key,
                Color.parseColor(fallback)
            )
        } else {
            Color.parseColor(fallback)
        }
    }

    private fun cleanupCaptureFile() {
        cameraOutputUri = null
        cameraOutputFile?.delete()
        cameraOutputFile = null
    }

    private fun unregisterConnectivityReceiverSafely() {
        try {
            unregisterReceiver(
                connectivityReceiver
            )
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun registerConnectivityReceiver() {
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(connectivityReceiver, filter)
    }

    override fun onDestroy() {
        unregisterConnectivityReceiverSafely()

        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null

        pendingGeoCallback = null
        pendingGeoOrigin = null
        pendingPermissionFlow = PermissionFlow.NONE

        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        cleanupCaptureFile()

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.removeAllViews()
            webView.destroy()
        }

        super.onDestroy()
    }
}