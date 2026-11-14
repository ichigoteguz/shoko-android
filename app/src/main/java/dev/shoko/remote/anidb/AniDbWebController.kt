package dev.shoko.remote.anidb

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

/** The official page owns authentication, validation and submission. Native actions only
 * activate its allowlisted controls after an explicit user tap; no raw POST is created. */
@SuppressLint("SetJavaScriptEnabled")
class AniDbWebController(context: Context) {
    private val applicationContext = context.applicationContext
    private val hostContext = MutableContextWrapper(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val handler = Handler(Looper.getMainLooper())
    private val script = context.assets.open("anidb-adapter.js").bufferedReader().use { it.readText() }
    private var disposed = false
    private var foreground = false
    private var groupGeneration = 0L
    private var documentGeneration = 0L
    private var requestRevision = 0L
    private var expectedAnimeId = 0
    private var associatedDocument = false
    private var reading = false
    private var pendingAction: String? = null
    private var actionStartedAt = 0L
    private var actionErrorBaseline = ""
    private var actionErrorRevision = 0
    private var actionErrorDocument = 0L
    private var submissionRejected = false
    private var submitLocked = false
    private var unknownSubmission = false
    private val mutableSnapshot = MutableStateFlow(AniDbFormSnapshot())
    private val mutableLoading = MutableStateFlow(false)
    private val mutableActionBusy = MutableStateFlow(false)
    private val mutablePageError = MutableStateFlow<String?>(null)
    private val mutableJsDialog = MutableStateFlow<AniDbBrowserDialog?>(null)
    private var pendingJsResult: JsResult? = null

    val snapshot: StateFlow<AniDbFormSnapshot> = mutableSnapshot.asStateFlow()
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()
    val actionBusy: StateFlow<Boolean> = mutableActionBusy.asStateFlow()
    val pageError: StateFlow<String?> = mutablePageError.asStateFlow()
    val jsDialog: StateFlow<AniDbBrowserDialog?> = mutableJsDialog.asStateFlow()

    // Poll the small allowlisted snapshot rather than exposing a general JavaScript bridge.
    // This sees the same-document replacement performed by AniDB's Next handler.
    private val observe = object : Runnable {
        override fun run() {
            if (disposed || !foreground) return
            checkActionTimeout()
            if (!mutableLoading.value && !reading && isAniDbOrigin(webView.url)) refresh()
            handler.postDelayed(this, 750)
        }
    }

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
                if (!request.isForMainFrame || isAniDbOrigin(request.url.toString())) return false
                pageFailure("This link leaves AniDB. The native editor only operates on the official HTTPS AniDB site.")
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val unsafeMainFrame = request.isForMainFrame && !isAniDbOrigin(request.url.toString())
                if (unsafeMainFrame || request.url.scheme !in setOf("https", "data", "blob")) {
                    return WebResourceResponse("text/plain", "UTF-8", 403, "HTTPS required", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                documentGeneration++
                reading = false
                val navigatedAnime = animeId(url)
                if (expectedAnimeId == 0) expectedAnimeId = navigatedAnime
                if (navigatedAnime > 0) associatedDocument = navigatedAnime == expectedAnimeId
                else if (!isAniDbOrigin(url)) associatedDocument = false
                mutableLoading.value = true
                mutablePageError.value = null
                mutableSnapshot.value = AniDbFormSnapshot(url = url.orEmpty(), animeId = if (associatedDocument) expectedAnimeId else navigatedAnime, message = "Loading the official AniDB page…")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != view.url) return
                mutableLoading.value = false
                CookieManager.getInstance().flush()
                if (isAniDbOrigin(url)) refresh()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) pageFailure("AniDB could not load: ${error.description}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) pageFailure("AniDB returned HTTP ${errorResponse.statusCode}. Read the official page before continuing.")
            }

            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                pageFailure("AniDB's secure connection could not be verified.")
            }
        }
    }

    fun setForeground(active: Boolean) = onMain {
        foreground = active
        handler.removeCallbacks(observe)
        if (active) handler.post(observe)
    }

    /** Invalidate callbacks and remove the previous group's document, retaining cookies. */
    fun reset() = onMain {
        invalidate()
        webView.stopLoading()
        webView.loadUrl("about:blank")
    }

    private fun invalidate() {
        groupGeneration++
        documentGeneration++
        expectedAnimeId = 0
        associatedDocument = false
        reading = false
        pendingAction = null
        submitLocked = false
        unknownSubmission = false
        submissionRejected = false
        mutableActionBusy.value = false
        mutableLoading.value = false
        mutablePageError.value = null
        pendingJsResult?.cancel()
        pendingJsResult = null
        mutableJsDialog.value = null
        mutableSnapshot.value = AniDbFormSnapshot()
    }

    fun loadAnime(animeId: Int) {
        require(animeId > 0) { "Enter a positive AniDB anime ID." }
        onMain {
            invalidate()
            expectedAnimeId = animeId
            webView.stopLoading()
            webView.loadUrl("https://anidb.net/anime/$animeId/release/add")
        }
    }

    fun attachToHost(context: Context) { onMain { hostContext.baseContext = context } }
    fun detachFromHost() { onMain { hostContext.baseContext = applicationContext } }

    fun refresh(onComplete: (AniDbFormSnapshot) -> Unit = {}) {
        onMain {
            if (!requireOrigin()) return@onMain
            val group = groupGeneration
            val document = documentGeneration
            val revision = ++requestRevision
            reading = true
            webView.evaluateJavascript(script + "\nJSON.stringify(window.__shokoAniDbV1.snapshot());") { raw ->
                if (disposed || group != groupGeneration || document != documentGeneration || revision != requestRevision) return@evaluateJavascript
                reading = false
                runCatching { json.decodeFromString<AniDbFormSnapshot>(unwrap(raw)) }
                    .onSuccess { onComplete(accept(it)) }
                    .onFailure { pageFailure("The AniDB form could not be read. Use its official page. ${it.message.orEmpty()}") }
            }
        }
    }

    private fun accept(incoming: AniDbFormSnapshot): AniDbFormSnapshot {
        val newError = incoming.errorMessage.isNotBlank() && (incoming.errorMessage != actionErrorBaseline || incoming.errorRevision != actionErrorRevision || documentGeneration != actionErrorDocument)
        if (associatedDocument && newError && (pendingAction == "submitFiles" || submitLocked)) submissionRejected = true
        val page = incoming.copy(animeId = if (associatedDocument) expectedAnimeId else animeId(incoming.url), submissionRejected = associatedDocument && submissionRejected)
        if (associatedDocument && (page.status in setOf("success", "error") || newError || pendingAction == "next" && page.status == "review")) {
            pendingAction = null
            mutableActionBusy.value = false
            if (page.status == "error" || newError) { submitLocked = false; unknownSubmission = false }
            if (page.status == "success") { submitLocked = true; unknownSubmission = false }
        }
        if (page.errorMessage.isNotBlank()) mutablePageError.value = page.errorMessage
        val result = if (unknownSubmission && page.status !in setOf("success", "error", "login", "challenge", "unsupported")) page.copy(status = "unknown", message = UNKNOWN_SUBMISSION) else page
        mutableSnapshot.value = result
        return result
    }

    fun apply(edits: AniDbEdits, onComplete: (Boolean) -> Unit = {}) = execute("apply", json.encodeToString(edits), onComplete)
    fun selectTemplate(value: String) = apply(AniDbEdits(values = mapOf("addfilem_template" to value)))
    fun searchGroups(query: String) { execute("searchGroups", json.encodeToString(query)) {} }
    fun applyReview(edits: List<AniDbReviewEdit>, onComplete: (Boolean) -> Unit = {}) = execute("applyReview", json.encodeToString(edits), onComplete)
    fun next(onComplete: (Boolean) -> Unit = {}) = activate("next", onComplete)
    fun submitFiles(onComplete: (Boolean) -> Unit = {}) = activate("submitFiles", onComplete)

    private fun activate(method: String, onComplete: (Boolean) -> Unit) = onMain {
        if (mutableActionBusy.value || method == "submitFiles" && submitLocked) { onComplete(false); return@onMain }
        if (!requireOrigin() || !associatedDocument || animeId(webView.url) != expectedAnimeId || expectedAnimeId <= 0) { onComplete(false); return@onMain }
        pendingAction = method
        actionErrorBaseline = mutableSnapshot.value.errorMessage
        actionErrorRevision = mutableSnapshot.value.errorRevision
        actionErrorDocument = documentGeneration
        actionStartedAt = SystemClock.elapsedRealtime()
        mutableActionBusy.value = true
        if (method == "submitFiles") { submitLocked = true; submissionRejected = false }
        execute(method, "") { ok ->
            if (!ok) {
                pendingAction = null
                mutableActionBusy.value = false
                if (method == "submitFiles") submitLocked = false
            }
            onComplete(ok)
        }
    }

    private fun checkActionTimeout() {
        if (pendingAction != null && pendingJsResult == null && SystemClock.elapsedRealtime() - actionStartedAt >= 30_000) {
            if (pendingAction == "submitFiles") markUnknown()
            else {
                pendingAction = null
                mutableActionBusy.value = false
                mutablePageError.value = "AniDB did not return the file review. Inspect its official page before continuing."
            }
        }
    }

    private fun markUnknown() {
        pendingAction = null
        mutableActionBusy.value = false
        submitLocked = true
        unknownSubmission = true
        mutablePageError.value = UNKNOWN_SUBMISSION
        mutableSnapshot.value = mutableSnapshot.value.copy(status = "unknown", animeId = expectedAnimeId, message = UNKNOWN_SUBMISSION)
    }

    private fun pageFailure(message: String) {
        mutableLoading.value = false
        if (pendingAction == "submitFiles") markUnknown()
        else { pendingAction = null; mutableActionBusy.value = false }
        mutablePageError.value = if (unknownSubmission) "$message $UNKNOWN_SUBMISSION" else message
    }

    fun clearError() { mutablePageError.value = null }
    fun resolveBrowserDialog(accepted: Boolean, input: String = "") = onMain {
        val result = pendingJsResult
        pendingJsResult = null
        mutableJsDialog.value = null
        if (accepted) {
            if (result is JsPromptResult) result.confirm(input) else result?.confirm()
        } else result?.cancel()
        actionStartedAt = SystemClock.elapsedRealtime()
        refresh()
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
        check(method in setOf("apply", "applyReview", "searchGroups", "next", "submitFiles"))
        onMain {
            if (!requireOrigin()) { onComplete(false); return@onMain }
            val group = groupGeneration
            val document = documentGeneration
            ++requestRevision
            reading = false
            mutablePageError.value = null
            webView.evaluateJavascript(script + "\nJSON.stringify(window.__shokoAniDbV1.$method($argument));") { raw ->
                if (disposed || group != groupGeneration) return@evaluateJavascript
                // The official action may synchronously start navigation before the callback.
                if (document != documentGeneration) { onComplete(method in setOf("next", "submitFiles")); return@evaluateJavascript }
                val result = runCatching { json.decodeFromString<AniDbAdapterResult>(unwrap(raw)) }.getOrNull()
                if (result == null && method in setOf("next", "submitFiles")) {
                    // The click may already have reached AniDB even when JS cannot return.
                    if (method == "submitFiles") markUnknown()
                    else mutablePageError.value = "AniDB’s Next response could not be read. Inspect the official page."
                    onComplete(true)
                    return@evaluateJavascript
                }
                result?.snapshot?.let { accept(it) }
                if (result?.ok == false && result.attempted && method in setOf("next", "submitFiles")) {
                    if (method == "submitFiles") markUnknown()
                    else mutablePageError.value = "AniDB’s Next outcome could not be read. Inspect the official page."
                    onComplete(true)
                    return@evaluateJavascript
                }
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
        private const val UNKNOWN_SUBMISSION = "AniDB’s submission outcome is unknown. Inspect the official result or check Shoko before submitting again. The app will not repeat Add Files."
        private fun animeId(url: String?): Int = runCatching { Regex("^/anime/(\\d+)/release/add/?$").matchEntire(Uri.parse(url).path.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0 }.getOrDefault(0)
        fun isAniDbOrigin(url: String?): Boolean = runCatching {
            val uri = Uri.parse(url)
            uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("anidb.net", ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443) && uri.userInfo == null
        }.getOrDefault(false)
    }
}
