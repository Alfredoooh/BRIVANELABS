// app/src/main/java/com/brivanelabs/notes/MainActivity.kt
package com.brivanelabs.notes

import android.annotation.SuppressLint
import android.content.Intent
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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.brivanelabs.notes.bridge.NotesBridge

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        reloadBtn = findViewById(R.id.reloadBtn)

        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            view.setPadding(0, 0, 0, 0)
            applyKeyboardInsetToWebView(bottom)
            WindowInsetsCompat.CONSUMED
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

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params.createIntent().apply {
                    if (type == null) type = "image/*"
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
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
        val bottomDp = (bottomPx / density)
        val js = "document.documentElement.style.setProperty('--android-kb', '${bottomDp}px'); " +
            "if (typeof updateKeyboard === 'function') updateKeyboard();"
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
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}