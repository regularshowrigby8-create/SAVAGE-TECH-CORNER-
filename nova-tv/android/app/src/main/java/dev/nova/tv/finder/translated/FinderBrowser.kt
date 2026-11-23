package dev.nova.tv.finder.translated

/**
 * Headless Chromium for JS-challenged pages and clearance minting.
 *
 * Translation of `Browser` in both Python scripts, with one deliberate
 * platform adaptation: there is no Playwright / headless Chromium on
 * Android, so this class always takes the branch the Python scripts take
 * when Playwright is not installed:
 *
 *  - `start()` returns false, and the caller prints
 *    "[warn] Playwright missing - plain HTTP only".
 *  - `render()` returns empty HTML, so the harvester falls back to a plain
 *    HTTP GET (`if not html: r = await self.http.get(url)`).
 *  - `mint()` returns no cookies, so the 403 ladder simply retries with a
 *    rotated fingerprint.
 *
 * The logic is unchanged; only the rendering engine is absent on this
 * platform. Keeping the class (instead of deleting the calls) means the
 * harvester code below is a faithful translation, not a rewrite.
 */
class FinderBrowser {

    suspend fun start(): Boolean = false

    suspend fun stop() = Unit

    /**
     * servers.py `Browser.render`: returns (html, [(resource_type, url)]).
     * Always empty on Android; see class KDoc.
     */
    suspend fun render(
        url: String,
        referer: String? = null,
        waitMs: Int = 1600
    ): Pair<String, List<Pair<String, String>>> = "" to emptyList()

    /**
     * addons.py `Browser.render`: returns the rendered DOM, or '' on failure.
     * Always empty on Android; see class KDoc.
     */
    suspend fun renderText(url: String, referer: String? = null, waitMs: Int = 1500): String = ""

    /** Mint cookies for a Cloudflare wall. Always empty on Android. */
    suspend fun mint(url: String, referer: String?): Map<String, String> = emptyMap()
}
