package dev.nova.tv.finder.pipeline

import dev.nova.tv.finder.translated.FinderBrowser
import dev.nova.tv.finder.translated.FinderHelpers
import dev.nova.tv.finder.translated.FinderHttp
import okio.Buffer

/**
 * One explicit transport contract for every pipeline stage. A failed fetch,
 * render or probe is a value with a reason; no stage receives `null` and silently
 * treats it as an empty catalogue.
 */
interface PipelineTransport {
    suspend fun fetch(url: String, referer: String? = null, read: Int = MAX_DOCUMENT_BYTES): TransportResult
    suspend fun render(url: String, referer: String? = null): TransportResult
    suspend fun probe(url: String, referer: String? = null): PipelineProbe
}

/** Production adapter over the existing shared HTTP session and WebView renderer. */
class FinderPipelineTransport(
    private val http: FinderHttp,
    private val browser: FinderBrowser? = null
) : PipelineTransport {

    override suspend fun fetch(url: String, referer: String?, read: Int): TransportResult {
        val host = FinderHelpers.hostOf(url)
        if (host.isEmpty()) {
            return TransportResult(url, 0, "", "", "not-an-absolute-http-url")
        }
        val response = http.get(url, referer = referer, read = read)
            ?: return TransportResult(url, 0, "", "", "no-response-or-host-tripped")
        val contentType = header(response.headers, "content-type")
        val body = response.text
        val error = when {
            response.status >= 400 -> "http-${response.status}"
            response.status == 0 -> "network-error"
            body.isEmpty() -> "empty-body"
            else -> ""
        }
        return TransportResult(
            url = response.url,
            status = response.status,
            body = body,
            contentType = contentType,
            error = error
        )
    }

    override suspend fun render(url: String, referer: String?): TransportResult {
        val renderer = browser
            ?: return TransportResult(url, 503, "", "text/html", "renderer-not-wired")
        val rendered = renderer.render(url, referer = referer)
        val html = rendered.first
        val network = rendered.second.map { it.second }
        return TransportResult(
            url = url,
            status = if (html.isEmpty()) 503 else 200,
            body = html,
            contentType = "text/html",
            error = if (html.isEmpty()) "renderer-returned-empty-dom" else "",
            network = network
        )
    }

    override suspend fun probe(url: String, referer: String?): PipelineProbe {
        val host = FinderHelpers.hostOf(url)
        if (host.isEmpty()) {
            return PipelineProbe(url, 0, "unknown", "", "", false, "not-an-absolute-http-url")
        }
        val handle = http.openStream(url, referer)
            ?: return PipelineProbe(url, 0, "unknown", "", "", false, "stream-unavailable-or-host-tripped")
        var buffer = Buffer()
        var sample = ""
        var readError = ""
        try {
            val source = handle.source
            if (source == null) {
                readError = "response-has-no-body"
            } else {
                source.read(buffer, 8_192L)
                sample = String(buffer.readByteArray(), Charsets.UTF_8)
            }
        } catch (error: Exception) {
            readError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        } finally {
            val close = handle.close()
            val closeError = close.exceptionOrNull()
            if (closeError != null && readError.isEmpty()) {
                readError = "close-failed: ${closeError.message.orEmpty()}"
            }
        }
        val contentType = header(handle.headers, "content-type")
        val kind = PipelineExtractor.kindOf(handle.url, contentType)
        val statusError = when {
            handle.status >= 400 -> "http-${handle.status}"
            handle.status == 0 -> "network-error"
            else -> ""
        }
        val error = listOf(readError, statusError).filter { it.isNotEmpty() }.joinToString("; ")
        val alive = PipelineText.okStatus(handle.status) && error.isEmpty() &&
            kind !in setOf("unknown", "page")
        return PipelineProbe(
            url = handle.url,
            status = handle.status,
            kind = kind,
            contentType = contentType,
            sample = sample,
            alive = alive,
            error = error
        )
    }

    private fun header(headers: Map<String, String>, name: String): String =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
}
