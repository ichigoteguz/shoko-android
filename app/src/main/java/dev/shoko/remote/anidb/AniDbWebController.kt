package dev.shoko.remote.anidb

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The official page owns authentication, hidden fields, validation, and submission.
 * Only the allowlisted, current form can be read or populated by the native editor.
 * This class never clicks Next or Add Files, and never creates a raw form POST.
 */
@SuppressLint("SetJavaScriptEnabled")
class AniDbWebController(context: Context) {
    private val applicationContext = context.applicationContext
    private val hostContext = MutableContextWrapper(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val handler = Handler(Looper.getMainLooper())
    private val script = context.assets.open("anidb-adapter.js").bufferedReader().use { it.readText() }
    private var disposed = false
    private val mutableSnapshot = MutableStateFlow(AniDbFormSnapshot())
    private val mutableLoading = MutableStateFlow(false)
    private val mutablePageError = MutableStateFlow<String?>(null)
    private val mutableJsDialog = MutableStateFlow<AniDbBrowserDialog?>(null)
    private var pendingJsResult: JsResult? = null

    val snapshot: StateFlow<AniDbFormSnapshot> = mutableSnapshot.asStateFlow()
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()
    val pageError: StateFlow<String?> = mutablePageError.asStateFlow()
    val jsDialog: StateFlow<AniDbBrowserDialog?> = mutableJsDialog.asStateFlow()

    val webView: WebView = WebView(hostContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
                showBrowserDialog(url, message, "alert", "", result)
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
                showBrowserDialog(url, message, "confirm", "", result)
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean =
                showBrowserDialog(url, message, "prompt", defaultValue, result)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (isAniDbOrigin(request.url.toString())) return false
                mutablePageError.value = "This link leaves AniDB. The native editor only operates on the official HTTPS AniDB site."
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // This is defense in depth, not a claim that a navigation callback intercepts every POST.
                val unsafeMainFrame = request.isForMainFrame && !isAniDbOrigin(request.url.toString())
                if (unsafeMainFrame || request.url.scheme !in setOf("https", "data", "blob")) {
                    return WebResourceResponse("text/plain", "UTF-8", 403, "HTTPS required", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mutableLoading.value = true
                mutablePageError.value = null
                mutableSnapshot.value = AniDbFormSnapshot(url = url.orEmpty(), message = "Loading the official AniDB page…")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                mutableLoading.value = false
                CookieManager.getInstance().flush()
                refresh()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mutableLoading.value = false
                    mutablePageError.value = "AniDB could not load: ${error.description}"
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    mutablePageError.value = "AniDB returned HTTP ${errorResponse.statusCode}. Read the official page before continuing."
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                mutableLoading.value = false
                mutablePageError.value = "AniDB's secure connection could not be verified."
            }
        }
    }

    fun loadAnime(animeId: Int) {
        require(animeId > 0) { "Enter a positive AniDB anime ID." }
        onMain { webView.loadUrl("https://anidb.net/anime/$animeId/release/add") }
    }

    fun attachToHost(context: Context) { onMain { hostContext.baseContext = context } }

    fun detachFromHost() { onMain { hostContext.baseContext = applicationContext } }

    fun refresh(onComplete: (AniDbFormSnapshot) -> Unit = {}) {
        onMain {
            if (!requireOrigin()) return@onMain
            webView.evaluateJavascript(script + "\nJSON.stringify(window.__shokoAniDbV1.snapshot());") { raw ->
                runCatching { json.decodeFromString<AniDbFormSnapshot>(unwrap(raw)) }
                    .onSuccess {
                        mutableSnapshot.value = it
                        if (it.status == "unsupported") Log.w("ShokoAniDb", "Form mapping blocked: " + it.unsupported.joinToString("; "))
                        onComplete(it)
                    }
                    .onFailure { mutablePageError.value = "The AniDB form could not be read. Use its official page. ${it.message.orEmpty()}" }
            }
        }
    }

    fun apply(edits: AniDbEdits, onComplete: (Boolean) -> Unit = {}) =
        execute("apply", json.encodeToString(edits), onComplete)

    fun selectTemplate(value: String) = apply(AniDbEdits(values = mapOf("addfilem_template" to value)))

    fun searchGroups(query: String) {
        execute("searchGroups", json.encodeToString(query)) { ok ->
            if (ok) {
                // Group search updates the same document asynchronously. Native controls refresh
                // after the response; there is deliberately no general-purpose JavaScript bridge.
                handler.postDelayed({ if (!disposed) refresh() }, 700)
                handler.postDelayed({ if (!disposed) refresh() }, 2_000)
            }
        }
    }

    fun applyReview(edits: List<AniDbReviewEdit>, onComplete: (Boolean) -> Unit = {}) =
        execute("applyReview", json.encodeToString(edits), onComplete)

    fun clearError() { mutablePageError.value = null }

    fun resolveBrowserDialog(accepted: Boolean, input: String = "") {
        onMain {
            val result = pendingJsResult
            pendingJsResult = null
            mutableJsDialog.value = null
            if (accepted) {
                if (result is JsPromptResult) result.confirm(input) else result?.confirm()
            } else result?.cancel()
        }
    }

    private fun showBrowserDialog(url: String, message: String, kind: String, defaultValue: String, result: JsResult): Boolean {
        if (!isAniDbOrigin(url)) { result.cancel(); return true }
        pendingJsResult?.cancel()
        pendingJsResult = result
        mutableJsDialog.value = AniDbBrowserDialog(message, kind, defaultValue)
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pendingJsResult?.cancel()
        pendingJsResult = null
        mutableJsDialog.value = null
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.destroy()
    }

    private fun execute(method: String, argument: String, onComplete: (Boolean) -> Unit) {
        check(method in setOf("apply", "applyReview", "searchGroups"))
        onMain {
            if (!requireOrigin()) { onComplete(false); return@onMain }
            mutablePageError.value = null
            webView.evaluateJavascript(script + "\nJSON.stringify(window.__shokoAniDbV1.$method($argument));") { raw ->
                val result = runCatching { json.decodeFromString<AniDbAdapterResult>(unwrap(raw)) }.getOrNull()
                result?.snapshot?.let { mutableSnapshot.value = it }
                if (result?.ok != true) mutablePageError.value = result?.error?.takeIf { it.isNotBlank() }
                    ?: "AniDB's form could not be updated. Your draft remains saved."
                onComplete(result?.ok == true)
            }
        }
    }

    private fun requireOrigin(): Boolean {
        if (isAniDbOrigin(webView.url)) return true
        mutablePageError.value = "Open the official AniDB Mass Add page before using the native editor."
        return false
    }

    private fun unwrap(raw: String?): String {
        require(!raw.isNullOrBlank() && raw != "null") { "No form response" }
        return json.decodeFromString<String>(raw)
    }

    private fun onMain(block: () -> Unit) {
        if (disposed) return
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { if (!disposed) block() }
    }

    companion object {
        fun isAniDbOrigin(url: String?): Boolean = runCatching {
            val uri = Uri.parse(url)
            uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("anidb.net", ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443) && uri.userInfo == null
        }.getOrDefault(false)
    }
}
