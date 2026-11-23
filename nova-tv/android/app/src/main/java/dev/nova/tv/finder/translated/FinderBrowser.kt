package dev.nova.tv.finder.translated

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The JS-rendering half of both Python finders, on Android.
 *
 * `servers.py` and `addons.py` use Playwright: `Browser.render` navigates to a
 * URL, lets the DOM settle, and returns `(html, network_log)`. That is how the
 * Python website finder got anything at all. Its seeds are anime sites whose
 * home pages are JS shells, so it rendered each one, read the player hosts out
 * of the finished DOM, and pushed those through the probe.
 *
 * This class used to be a deliberate stub whose `render` always returned empty
 * HTML, on the theory that Android has no Playwright. The theory was right but
 * the conclusion was wrong: Android ships a full Chromium as `WebView`, so the
 * rendering engine the Python depended on is present, it simply is not
 * Playwright. Returning `""` sent every seed page down the plain-HTTP branch,
 * the extractor found nothing in the unrendered shell, and the website pool
 * stayed at 0 on device while the addon finder -- whose seeds are JSON APIs
 * that need no rendering -- filled up normally.
 *
 * The translation stays faithful: same inputs, same outputs, and the same
 * "empty on failure" contract, with `WebView` standing in for Chromium.
 *
 * Two hard rules make this safe inside a harvest loop:
 *  - a `WebView` must be created and driven on the main looper, so every
 *    method hops to `Dispatchers.Main` while callers may sit on IO;
 *  - a page that never finishes loading must not hang the harvest, so every
 *    wait is bounded by [LOAD_TIMEOUT_MS] and degrades to `""`.
 */
class FinderBrowser(private val context: Context? = null) {

    /** One reusable view; building one per page is slow and leaks. */
    private var web: WebView? = null

    /**
     * A single `WebView` shows exactly one document, and the harvesters call
     * `render` from 64 concurrent coroutines. Without this lock two navigations
     * interleave and one caller reads the other's page -- which would put the
     * wrong hosts in the pool. Rendering is slower serialised, but it is
     * correct, and the per-load timeout below keeps it from stalling.
     */
    private val gate = Mutex()

    companion object {
        /**
         * Ceiling for one navigation. Kept well under the harvesters' 45 s
         * budget: a serialised queue of slow pages must still leave the
         * harvest time to probe what it found.
         */
        private const val LOAD_TIMEOUT_MS = 8_000L

        /** Longest DOM handed back, matching the Python's read budget. */
        private const val MAX_DOM = 1_500_000

        /** Desktop Chrome identity, matching what Playwright presents. */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    /** True when a context was supplied, i.e. rendering will actually happen. */
    val available: Boolean get() = context != null

    /**
     * servers.py `Browser.start`.
     *
     * Nothing to launch: the view is created lazily. The return value is the
     * "is Playwright installed?" flag, so callers log the same warning the
     * Python prints when it is not.
     */
    suspend fun start(): Boolean = available

    suspend fun stop() {
        withContext(Dispatchers.Main) {
            runCatching {
                web?.stopLoading()
                web?.destroy()
            }
            web = null
        }
    }

    /**
     * servers.py `Browser.render`: navigate, settle, return
     * `(html, [(resource_type, url)])`.
     *
     * The network log is the page's sub-resource list, which is what the Python
     * replayed for `/api/` XHRs and how `discover()` finds JSON endpoints
     * without parsing JavaScript. On any failure the contract is
     * `"" to emptyList()`, so the caller falls back to plain HTTP as before.
     */
    suspend fun render(
        url: String,
        referer: String? = null,
        waitMs: Int = 1600
    ): Pair<String, List<Pair<String, String>>> {
        if (!available) return "" to emptyList()
        val net = mutableListOf<Pair<String, String>>()
        val html = load(url, referer, waitMs.toLong(), net)
        return html to net.toList()
    }

    /** addons.py `Browser.render`: the rendered DOM, or `""` on failure. */
    suspend fun renderText(url: String, referer: String? = null, waitMs: Int = 1500): String {
        if (!available) return ""
        return load(url, referer, waitMs.toLong(), null)
    }

    /**
     * Clearance cookies for a wall, read back out of the view's own jar.
     *
     * The Python called Playwright's `mint()` after a real navigation. `WebView`
     * shares no jar with the finders' OkHttp client, so the caller copies these
     * across. An empty map is a normal outcome, and means "no wall here".
     */
    suspend fun mint(url: String, referer: String?): Map<String, String> {
        if (!available) return emptyMap()
        load(url, referer, 1200L, null)
        return withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().getCookie(url).orEmpty()
                    .split(';')
                    .mapNotNull { part ->
                        val name = part.substringBefore('=', "").trim()
                        val value = part.substringAfter('=', "").trim()
                        if (name.isEmpty() || value.isEmpty()) null else name to value
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
        }
    }

    /**
     * Navigate and return the finished DOM, or `""`.
     *
     * Resumes on `onPageFinished` rather than a fixed sleep, then waits
     * [waitMs] for late XHRs to land -- the same "settle" the Python
     * approximated with a timeout. The whole thing is bounded by
     * [LOAD_TIMEOUT_MS], so a page that never fires the callback still returns.
     */
    private suspend fun load(
        url: String,
        referer: String?,
        waitMs: Long,
        net: MutableList<Pair<String, String>>?
    ): String = gate.withLock {
        withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                net?.clear()
                val view = view() ?: return@withContext ""
                val finished = awaitFinish(view, url, referer, net)
                // Late XHRs still count: they are often where /api/ JSON lives.
                // Capped so settling cannot eat the whole load budget.
                if (finished) delay(waitMs.coerceAtMost(1_200L))
                readDom(view).take(MAX_DOM)
            }
        } ?: ""
    }

    /** Install the client, start the load, and wait (bounded) for completion. */
    private suspend fun awaitFinish(
        view: WebView,
        url: String,
        referer: String?,
        net: MutableList<Pair<String, String>>?
    ): Boolean = withTimeoutOrNull(LOAD_TIMEOUT_MS - 500L) {
        suspendCancellableCoroutine<Boolean> { cont ->
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onReceivedError(
                    v: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    // Only a main-frame failure aborts; a broken ad script must not.
                    if (request?.isForMainFrame == true && cont.isActive) cont.resume(false)
                }

                override fun shouldInterceptRequest(
                    v: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val sub = request?.url?.toString()
                    if (sub != null && net != null && net.size < 400) {
                        net += "xhr" to sub
                    }
                    return null
                }
            }
            val headers = referer?.let { mapOf("Referer" to it) } ?: emptyMap()
            runCatching { view.loadUrl(url, headers) }
                .onFailure { if (cont.isActive) cont.resume(false) }
        }
    } ?: false

    /** Read the live DOM. `evaluateJavascript` is callback-based. */
    private suspend fun readDom(view: WebView): String =
        withTimeoutOrNull(2_000L) {
            suspendCancellableCoroutine<String> { cont ->
                runCatching {
                    view.evaluateJavascript("document.documentElement.outerHTML") { value ->
                        if (cont.isActive) cont.resume(unquoteJs(value))
                    }
                }.onFailure { if (cont.isActive) cont.resume("") }
            }
        } ?: ""

    /** `evaluateJavascript` hands back a JSON string literal. */
    private fun unquoteJs(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        var s = raw
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1)
        return s
            .replace("\\u003C", "<").replace("\\u003E", ">").replace("\\u0026", "&")
            .replace("\\\"", "\"").replace("\\n", "\n")
            .replace("\\/", "/").replace("\\\\", "\\")
    }

    /** Create the shared view on first use. Main thread only. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun view(): WebView? {
        val ctx = context ?: return null
        web?.let { return it }
        return runCatching {
            val v = WebView(ctx)
            v.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                // Playwright renders as desktop Chromium, so the Python saw the
                // full desktop page. A phone UA gets a cut-down layout whose
                // catalogue links the extractor would miss, so the identity is
                // pinned to desktop Chrome to keep the two equivalent.
                userAgentString = DESKTOP_UA
                // Images are never read by the extractor; skipping them makes a
                // render several times cheaper on a phone connection.
                loadsImagesAutomatically = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            web = v
            v
        }.getOrNull()
    }
}
