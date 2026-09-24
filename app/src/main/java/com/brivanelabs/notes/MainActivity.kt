// app/src/main/java/com/brivanelabs/notes/MainActivity.kt
package com.brivanelabs.notes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.brivanelabs.notes.bridge.NotesBridge
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var reloadBtn: Button
    private val appIndexUrl = "file:///android_asset/index.html"
    private var loadFailed = false
    private var pendingOpenNoteId: String? = null
    private var pendingBiometricNoteId: String? = null

    // Guarda o último estado de tema aplicado pelo próprio WebView (via bridge),
    // para não deixar o onResume "reverter" para o tema do sistema quando o
    // utilizador escolheu manualmente claro/escuro dentro da app.
    private var lastKnownLightIcons: Boolean? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    // ---- Splash em Kotlin ----
    private lateinit var splashOverlay: FrameLayout
    private var splashStartedAt = 0L
    private var splashHidden = false
    private var pageReady = false
    private val splashMinMs = 2000L
    private val splashHandler = Handler(Looper.getMainLooper())

    // ---- Permissões (câmera / microfone) ----
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingCameraCaptureUri: Uri? = null
    private lateinit var webPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var cameraPermissionForChooserLauncher: ActivityResultLauncher<String>
    private var pendingChooserParams: WebChromeClient.FileChooserParams? = null
    private var pendingChooserCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            val captureUri = pendingCameraCaptureUri
            pendingCameraCaptureUri = null
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                captureUri != null && data?.data == null && data?.clipData == null -> arrayOf(captureUri)
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.itemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                captureUri != null -> arrayOf(captureUri)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

        // Permissões pedidas pelo WebView (getUserMedia: câmera / microfone)
        webPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingWebPermissionRequest
            pendingWebPermissionRequest = null
            if (request == null) return@registerForActivityResult
            val toGrant = ArrayList<String>()
            request.resources.forEach { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        if (grants[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)) toGrant.add(res)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        if (grants[Manifest.permission.RECORD_AUDIO] == true || hasPermission(Manifest.permission.RECORD_AUDIO)) toGrant.add(res)
                }
            }
            if (toGrant.isEmpty()) request.deny() else request.grant(toGrant.toTypedArray())
        }

        // Permissão de CÂMERA pedida antes de abrir a câmera via <input capture>
        cameraPermissionForChooserLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val params = pendingChooserParams
            val callback = pendingChooserCallback
            pendingChooserParams = null
            pendingChooserCallback = null
            if (callback == null) return@registerForActivityResult
            if (granted && params != null) {
                launchFileChooser(callback, params, allowCamera = true)
            } else if (params != null) {
                launchFileChooser(callback, params, allowCamera = false)
            } else {
                callback.onReceiveValue(null)
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        reloadBtn = findViewById(R.id.reloadBtn)
        splashOverlay = findViewById(R.id.splashOverlay)
        splashStartedAt = SystemClock.elapsedRealtime()
        splashHandler.postDelayed({ hideSplash(force = true) }, 12000L)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.rootFrame)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val keyboardPx = if (imeVisible) maxOf(0, ime.bottom - bars.bottom) else 0
            applyKeyboardInsetToWebView(keyboardPx)
            insets
        }

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

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needed = ArrayList<String>()
                    request.resources.forEach { res ->
                        when (res) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                if (!hasPermission(Manifest.permission.CAMERA)) needed.add(Manifest.permission.CAMERA)
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                if (!hasPermission(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    if (needed.isEmpty()) {
                        val ok = request.resources.filter {
                            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        }
                        if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
                    } else {
                        pendingWebPermissionRequest?.deny()
                        pendingWebPermissionRequest = request
                        webPermissionLauncher.launch(needed.toTypedArray())
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPermissionRequest == request) pendingWebPermissionRequest = null
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
                val wantsCamera = params.isCaptureEnabled
                if (wantsCamera && !hasPermission(Manifest.permission.CAMERA)) {
                    pendingChooserParams = params
                    pendingChooserCallback = callback
                    cameraPermissionForChooserLauncher.launch(Manifest.permission.CAMERA)
                    return true
                }
                return launchFileChooser(callback, params, allowCamera = wantsCamera)
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
                    hideSplash(force = true)
                }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!loadFailed) {
                    view.visibility = View.VISIBLE
                    reloadBtn.visibility = View.GONE
                    pageReady = true
                    view.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            hideSplash(force = false)
                        }
                    })
                    // Só aplica o tema do SISTEMA se o WebView ainda não tiver
                    // comunicado um tema explícito (evita "piscar" para o tema
                    // errado sempre que a Activity é retomada).
                    if (lastKnownLightIcons == null) applyStatusBarForSystemTheme()
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

    private fun applyKeyboardInsetToWebView(bottomPx: Int) {
        if (!::webView.isInitialized) return
        val density = resources.displayMetrics.density
        val bottomDp = Math.round(bottomPx / density)
        val js = "(function(){try{" +
            "window.__nativeKb=$bottomDp;" +
            "document.documentElement.style.setProperty('--kb','${bottomDp}px');" +
            "if(typeof scrollCaretIntoView==='function'&&$bottomDp>40){scrollCaretIntoView();}" +
            "}catch(e){}})();"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /**
     * lightIcons = true  -> fundo é CLARO, então os ícones da status/nav bar
     *                       precisam de ser ESCUROS para ficarem visíveis.
     * lightIcons = false -> fundo é ESCURO, então os ícones ficam CLAROS.
     *
     * Esta é a correção do bug relatado: antes o valor passado pelo bridge
     * não estava a ser respeitado de forma consistente e a Activity também
     * reescrevia o tema no onResume usando a configuração do SISTEMA em vez
     * do tema real escolhido dentro da app (system/light/dark), fazendo com
     * que no tema claro os ícones ficassem sempre claros (errados).
     */
    fun setStatusBarLight(lightIcons: Boolean) {
        lastKnownLightIcons = lightIcons
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
    }

    fun isBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(this)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun requestBiometricUnlock(noteId: String) {
        if (!isBiometricAvailable()) {
            NotesBridge.notifyBiometricResult(webView, noteId, false)
            return
        }
        pendingBiometricNoteId = noteId
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                NotesBridge.notifyBiometricResult(webView, noteId, true)
                pendingBiometricNoteId = null
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                NotesBridge.notifyBiometricResult(webView, noteId, false)
                pendingBiometricNoteId = null
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_note_title))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(promptInfo)
    }

    /**
     * Gera um PDF A4 paginado a partir de um bitmap alto (o "print" completo
     * da nota). Corta o bitmap em fatias do tamanho de uma página A4 mantendo
     * a largura total e repetindo margens consistentes em cada página, em vez
     * de espremer a nota inteira numa única página gigante.
     */
    fun createPaginatedA4Pdf(bitmap: Bitmap, outputPath: String): Boolean {
        return try {
            // A4 a 150dpi para boa nitidez sem ficheiros enormes.
            val dpi = 150
            val pageWidthPx = (8.27 * dpi).toInt()   // 1240
            val pageHeightPx = (11.69 * dpi).toInt() // 1754
            val marginPx = (0.5 * dpi).toInt()       // 75
            val contentWidthPx = pageWidthPx - marginPx * 2

            val scale = contentWidthPx.toFloat() / bitmap.width.toFloat()
            val scaledHeight = (bitmap.height * scale).toInt()
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, contentWidthPx, scaledHeight, true)

            val contentHeightPerPage = pageHeightPx - marginPx * 2
            val totalPages = maxOf(1, Math.ceil(scaledHeight.toDouble() / contentHeightPerPage.toDouble()).toInt())

            val document = PdfDocument()
            for (pageIndex in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPx, pageHeightPx, pageIndex + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                val srcTop = pageIndex * contentHeightPerPage
                val srcBottom = minOf(scaledHeight, srcTop + contentHeightPerPage)
                if (srcTop < srcBottom) {
                    val sliceHeight = srcBottom - srcTop
                    val slice = Bitmap.createBitmap(scaledBitmap, 0, srcTop, contentWidthPx, sliceHeight)
                    canvas.drawBitmap(slice, marginPx.toFloat(), marginPx.toFloat(), null)
                    slice.recycle()
                }
                document.finishPage(page)
            }

            java.io.File(outputPath).outputStream().use { document.writeTo(it) }
            document.close()
            if (scaledBitmap != bitmap) scaledBitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e("BrivaneNotes", "Falha ao gerar PDF paginado", e)
            false
        }
    }

    private fun applyStatusBarForSystemTheme() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setStatusBarLight(!isDark)
    }

    override fun onPause() { super.onPause(); webView.onPause(); webView.pauseTimers() }
    override fun onResume() {
        super.onResume(); webView.onResume(); webView.resumeTimers()
        // Não força mais o tema do sistema aqui — isso é o que causava o bug
        // de o tema claro escolhido dentro da app ser "esquecido". O WebView
        // reafirma o tema correto via bridge (setStatusBarTheme) sempre que
        // a página aplica o tema (ver applyTheme() no JS).
        webView.evaluateJavascript("(function(){try{if(typeof applyTheme==='function')applyTheme(true);}catch(e){}})()", null)
    }
    override fun onSaveInstanceState(outState: Bundle) { webView.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        splashHandler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    private fun hideSplash(force: Boolean) {
        if (splashHidden) return
        if (!force && !pageReady) return
        val elapsed = SystemClock.elapsedRealtime() - splashStartedAt
        val remaining = if (force) 0L else (splashMinMs - elapsed).coerceAtLeast(0L)
        splashHandler.postDelayed({
            if (splashHidden) return@postDelayed
            splashHidden = true
            splashOverlay.animate()
                .alpha(0f)
                .setDuration(280L)
                .withEndAction { splashOverlay.visibility = View.GONE }
                .start()
        }, remaining)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun launchFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
        allowCamera: Boolean
    ): Boolean {
        filePathCallback = callback
        pendingCameraCaptureUri = null

        val intent: Intent
        if (allowCamera && params.isCaptureEnabled) {
            val accepts = params.acceptTypes?.joinToString(",").orEmpty()
            val wantsVideo = accepts.contains("video")
            val captureIntent = Intent(
                if (wantsVideo) MediaStore.ACTION_VIDEO_CAPTURE
                else MediaStore.ACTION_IMAGE_CAPTURE
            )

            if (!wantsVideo) {
                val dir = File(cacheDir, "camera_capture").apply { mkdirs() }
                val photo = File(dir, "foto_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    photo
                )
                pendingCameraCaptureUri = uri
                captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                captureIntent.addFlags(
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            intent = captureIntent
        } else {
            intent = params.createIntent().apply {
                if (type == null) type = "image/*"
            }
        }

        return try {
            fileChooserLauncher.launch(intent)
            true
        } catch (e: Exception) {
            filePathCallback = null
            pendingCameraCaptureUri = null
            callback.onReceiveValue(null)
            false
        }
    }
}
