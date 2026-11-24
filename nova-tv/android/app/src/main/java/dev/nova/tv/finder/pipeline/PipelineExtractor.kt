package dev.nova.tv.finder.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Stage 10. Extract only URLs that were actually observed in the rendered DOM,
 * a search response, a manifest, a catalogue response or the browser network
 * log. No URL shape is guessed and no seed list is consulted.
 */
object PipelineExtractor {
    private val URL = Regex("""https?://[^\s"'<>)]+""", RegexOption.IGNORE_CASE)
    private val ATTR_URL = Regex(
        """(?:href|src|action|data-url|data-src|url)\s*[:=]\s*["']([^"']{1,500})["']""",
        RegexOption.IGNORE_CASE
    )
    private val HREF = Regex("""href\s*=\s*["']([^"']{1,500})["']""", RegexOption.IGNORE_CASE)
    private val cataloguePath = Regex(
        "/(?:movie|movies|series|anime|watch|title|episode|catalog|catalogue|meta|stream|manifest)(?:[/.?#]|$)",
        RegexOption.IGNORE_CASE
    )
    private val apiPath = Regex(
        "/(?:ajax|api|sources|servers|embed|player|stream|watch)(?:[/.?#]|$)",
        RegexOption.IGNORE_CASE
    )
    private val junkPath = Regex(
        "/(?:wp-admin|wp-json|wp-includes|wp-login|xmlrpc\\.php|admin-ajax\\.php|" +
            "ads?|adserver|adframe|analytics|track(?:ing|er)?|pixel|beacon|prebid|" +
            "gpt|pagead|telemetry|cdn-cgi)(?:[/?#]|$)",
        RegexOption.IGNORE_CASE
    )
    private val assetPath = Regex(
        """\.(?:js|css|woff2?|ttf|eot|png|jpe?g|gif|svg|ico|map)(?:$|\?)""",
        RegexOption.IGNORE_CASE
    )
    private val torrent = Regex("""magnet:|xt=urn:btih|torrentio|jackettio|\.torrent""", RegexOption.IGNORE_CASE)

    fun kindOf(url: String, contentType: String): String {
        val path = PipelineText.pathOf(url).lowercase()
        val type = contentType.lowercase()
        return when {
            path.endsWith("manifest.json") || path.endsWith("/manifest") -> "manifest"
            path.contains("/stream/") || path.contains("/stream.json") -> "stream-json"
            path.contains("/catalog/") || path.contains("/catalog.json") -> "catalog-json"
            path.endsWith(".m3u8") || "mpegurl" in type -> "hls"
            path.endsWith(".mpd") || "dash+xml" in type -> "dash"
            path.endsWith(".mp4") || "video/mp4" in type || "video/" in type -> "mp4"
            "application/json" in type || path.endsWith(".json") -> "json"
            "text/html" in type -> "page"
            else -> "unknown"
        }
    }

    /** Count real JSON catalogue/stream items. Stremio add-ons return
     * `{"metas":[...]}` or `{"streams":[...]}`, not HTML anchors. */
    fun jsonItemCount(body: String): Int {
        val text = body.trim()
        if (text.isEmpty() || (text[0] != '{' && text[0] != '[')) return 0
        val root: JsonElement = try {
            Json.parseToJsonElement(text)
        } catch (error: Exception) {
            // Invalid JSON is not catalogue evidence; the caller records it.
            return 0
        }
        return largestArray(root)
    }

    private fun largestArray(element: JsonElement): Int {
        var best = 0
        when (element) {
            is JsonArray -> {
                best = maxOf(best, element.size)
                for (child in element) best = maxOf(best, largestArray(child))
            }
            is JsonObject -> {
                for (child in element.values) best = maxOf(best, largestArray(child))
            }
            else -> Unit
        }
        return best
    }

    fun catalogueItemCount(html: String): Int {
        val items = LinkedHashSet<String>()
        for (match in HREF.findAll(html)) {
            val raw = clean(match.groupValues[1])
            val path = when {
                raw.startsWith("http://") || raw.startsWith("https://") -> PipelineText.pathOf(raw)
                raw.startsWith("//") -> PipelineText.pathOf("https:$raw")
                raw.startsWith("/") -> raw.substringBefore('?').substringBefore('#')
                else -> "/$raw"
            }
            if (cataloguePath.containsMatchIn(path) && !junkPath.containsMatchIn(path)) {
                items += path.lowercase()
            }
        }
        return maxOf(items.size, jsonItemCount(html))
    }

    fun extractSources(
        kind: PipelineKind,
        html: String,
        network: List<String>,
        referer: String
    ): List<ExtractedSource> {
        val observed = LinkedHashSet<String>()
        for (match in ATTR_URL.findAll(html)) observed += absolute(clean(match.groupValues[1]), referer)
        for (match in URL.findAll(html)) observed += absolute(clean(match.value), referer)
        for (raw in network) observed += absolute(clean(raw), referer)

        val out = ArrayList<ExtractedSource>()
        for (url in observed) {
            val garbage = sourceGarbageReason(url, kind)
            if (garbage.isNotEmpty()) continue
            val mediaKind = mediaKindOf(kind, url)
            if (mediaKind != "unknown") out += ExtractedSource(url, referer, mediaKind)
        }
        return out.distinctBy { it.url }.toList()
    }

    private fun mediaKindOf(kind: PipelineKind, url: String): String {
        val path = PipelineText.pathOf(url).lowercase()
        if (kind == PipelineKind.ADDON) {
            return when {
                path.endsWith("manifest.json") || path.endsWith("/manifest") -> "manifest"
                path.contains("/stream/") || path.contains("/stream.json") -> "stream"
                path.contains("/catalog/") || path.contains("/catalog.json") -> "catalog"
                else -> "unknown"
            }
        }
        val observed = kindOf(url, "")
        return when {
            observed in setOf("hls", "dash", "mp4") -> observed
            apiPath.containsMatchIn(path) -> "embed-api"
            else -> "unknown"
        }
    }

    private fun sourceGarbageReason(url: String, kind: PipelineKind): String {
        val lower = url.lowercase()
        val host = PipelineText.hostOf(url)
        val path = PipelineText.pathOf(url)
        val dummy = CandidateSite(url, host, "extractor", kind, "", "observed")
        return when {
            torrent.containsMatchIn(lower) -> "torrent-or-magnet"
            junkPath.containsMatchIn(path) -> "cms-ad-or-tracker-path"
            assetPath.containsMatchIn(path) -> "static-asset"
            PipelineSorter.garbageReason(dummy).isNotEmpty() -> PipelineSorter.garbageReason(dummy)
            else -> ""
        }
    }

    private fun absolute(raw: String, referer: String): String {
        val value = clean(raw)
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "${PipelineText.originOf(referer)}$value"
            else -> "${PipelineText.originOf(referer)}/$value"
        }
    }

    private fun clean(raw: String): String =
        raw.trim().trimEnd(',', ';', ')', ']', '}', '"', '\'').replace("\\/", "/")
}
