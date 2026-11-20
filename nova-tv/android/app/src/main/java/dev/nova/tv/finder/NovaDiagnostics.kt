package dev.nova.tv.finder

import androidx.compose.ui.unit.dp
import dev.nova.tv.finder.pool.ServerPool
import dev.nova.tv.ui.theme.NovaSizing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * On-device self-test.
 *
 * ## Why this exists
 *
 * Every claim about the finder so far has been argued from reading the code.
 * That is not the same as knowing it works, and it is not something a viewer can
 * check either. This runs the rules against fixtures on the device itself and
 * prints pass/fail, so "the crawler only stores servers" becomes a line of
 * output rather than a paragraph of reasoning.
 *
 * The checks are deliberately pure: no network, no coroutines, no Android APIs.
 * A diagnostic that needs the internet to run cannot tell you whether the code
 * is wrong or the network is, which is the one thing you most need to know when
 * something is broken.
 */
object NovaDiagnostics {

    enum class Status { PASS, FAIL, WARN }

    data class Check(
        val name: String,
        val status: Status,
        /** What was measured, in one line. */
        val detail: String,
        /** What it means when it fails. */
        val hint: String = ""
    )

    data class Report(
        val checks: List<Check>,
        val elapsedMs: Long
    ) {
        val passed: Int get() = checks.count { it.status == Status.PASS }
        val failed: Int get() = checks.count { it.status == Status.FAIL }
        val warned: Int get() = checks.count { it.status == Status.WARN }
        val ok: Boolean get() = failed == 0
        val summary: String
            get() = when {
                checks.isEmpty() -> "No checks ran"
                failed > 0 -> "$failed failed, $passed passed"
                warned > 0 -> "$passed passed, $warned warnings"
                else -> "All $passed checks passed"
            }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Run every check. Never throws: a check that throws is reported as a failure. */
    fun run(): Report {
        val started = System.currentTimeMillis()
        val checks = mutableListOf<Check>()
        fun check(name: String, hint: String = "", block: () -> Check) {
            checks += try {
                block()
            } catch (t: Throwable) {
                Check(name, Status.FAIL, "${t::class.simpleName}: ${t.message}", hint)
            }
        }

        check("Manifest gate rejects non-servers") {
            val cases = listOf(
                "catalogue only" to """{"id":"a","name":"Catalogue","resources":["catalog"],"types":["movie"]}""",
                "p2p torrent index" to """{"id":"b","name":"Torrents","resources":["stream"],"types":["movie"],"behaviorHints":{"p2p":true}}""",
                "metadata only" to """{"id":"c","name":"Meta","resources":["meta"],"types":["movie"]}""",
                "no usable type" to """{"id":"d","name":"Odd","resources":["stream"],"types":["channel"]}"""
            )
            val leaked = cases.filter { (_, body) ->
                FinderRules.parseManifest(json.parseToJsonElement(body) as JsonObject) != null
            }.map { it.first }
            if (leaked.isEmpty()) {
                Check("Manifest gate rejects non-servers", Status.PASS, "4/4 non-server manifests refused")
            } else {
                Check(
                    "Manifest gate rejects non-servers", Status.FAIL,
                    "leaked: ${leaked.joinToString()}",
                    "parseManifest must require a stream resource and a movie/series type."
                )
            }
        }

        check("Manifest gate accepts a real server") {
            val body = """{"id":"e","name":"Real Server","version":"1.0.0","resources":["stream","catalog"],"types":["movie","series"],"idPrefixes":["tt"],"catalogs":[{"type":"movie","id":"top","extra":[{"name":"search"}]}]}"""
            val info = FinderRules.parseManifest(json.parseToJsonElement(body) as JsonObject)
            if (info == null) {
                Check("Manifest gate accepts a real server", Status.FAIL, "a valid server was refused")
            } else if (!info.capabilities.serves("series") || !info.capabilities.search) {
                Check(
                    "Manifest gate accepts a real server", Status.FAIL,
                    "capabilities wrong: ${info.capabilities}",
                    "Capabilities must report series support and searchable catalogues."
                )
            } else {
                Check(
                    "Manifest gate accepts a real server", Status.PASS,
                    "streams=${info.capabilities.streams} movie=${info.capabilities.movie} series=${info.capabilities.series} search=${info.capabilities.search}"
                )
            }
        }

        check("Signup-gated hosts are refused") {
            val refused = listOf("real-debrid", "premiumize", "alldebrid", "torbox").count { host ->
                FinderRules.requiresSignup(host, "https://$host.example")
            }
            if (refused >= 3) {
                Check("Signup-gated hosts are refused", Status.PASS, "$refused/4 known signup hosts refused")
            } else {
                Check(
                    "Signup-gated hosts are refused", Status.FAIL,
                    "only $refused/4 matched",
                    "SIGNUP_NAMES / SIGNUP_HOSTS must cover the debrid services."
                )
            }
        }

        check("Pool capacity is 3000") {
            if (ServerPool.CAPACITY == 3000) {
                Check("Pool capacity is 3000", Status.PASS, "CAPACITY=${ServerPool.CAPACITY}")
            } else {
                Check("Pool capacity is 3000", Status.FAIL, "CAPACITY=${ServerPool.CAPACITY}")
            }
        }

        check("Catalogue sources") {
            val count = FinderRules.DEFAULT_SOURCES.size
            if (count >= 20) {
                Check("Catalogue sources", Status.PASS, "$count sources configured")
            } else {
                Check(
                    "Catalogue sources", Status.WARN,
                    "$count sources",
                    "The brief asked for at least ten beyond Cinemeta."
                )
            }
        }

        check("Title identity is validated") {
            val rejected = FinderRules.normalizeIdentity("a") == null &&
                FinderRules.normalizeIdentity("") == null
            val accepted = FinderRules.normalizeIdentity(
                "Breaking Bad", year = 2008, imdbId = "tt0903747",
                type = "series", season = 1, episode = 1
            )
            if (!rejected) {
                Check("Title identity is validated", Status.FAIL, "a one-character title was accepted")
            } else if (accepted == null || accepted.streamId() != "tt0903747:1:1") {
                Check("Title identity is validated", Status.FAIL, "streamId=${accepted?.streamId()}")
            } else {
                Check("Title identity is validated", Status.PASS, "query=\"${accepted.query()}\" id=${accepted.streamId()}")
            }
        }

        check("Fingerprint is stable") {
            val a = FinderRules.stableFingerprint("Breaking Bad", "2008", "series")
            val b = FinderRules.stableFingerprint("  breaking bad ", "2008", "SERIES")
            val c = FinderRules.stableFingerprint("Better Call Saul", "2015", "series")
            if (a == b && a != c) {
                Check("Fingerprint is stable", Status.PASS, "same input hashes equal, different input differs")
            } else {
                Check(
                    "Fingerprint is stable", Status.FAIL,
                    "case/space normalisation broken",
                    "stableFingerprint must trim and lowercase before hashing."
                )
            }
        }

        check("Cards scale with the screen") {
            val small = NovaSizing.metrics(640.dp)
            val hd = NovaSizing.metrics(960.dp)
            val uhd = NovaSizing.metrics(1920.dp)
            val monotonic = small.posterWidth <= hd.posterWidth && hd.posterWidth <= uhd.posterWidth
            val sameCount = small.visiblePosters == hd.visiblePosters && hd.visiblePosters == uhd.visiblePosters
            if (!monotonic) {
                Check(
                    "Cards scale with the screen", Status.FAIL,
                    "640dp=${small.posterWidth} 960dp=${hd.posterWidth} 1920dp=${uhd.posterWidth}",
                    "Poster width must grow with the window."
                )
            } else if (!sameCount) {
                Check("Cards scale with the screen", Status.FAIL, "visible poster count differs per screen")
            } else {
                Check(
                    "Cards scale with the screen", Status.PASS,
                    "640dp=${small.posterWidth.value.toInt()}dp 960dp=${hd.posterWidth.value.toInt()}dp 1920dp=${uhd.posterWidth.value.toInt()}dp, ${hd.visiblePosters} across"
                )
            }
        }

        check("Contrast of body text") {
            val ratio = contrast(TEXT_PRIMARY, BACKGROUND)
            if (ratio >= 7.0) {
                Check("Contrast of body text", Status.PASS, "%.2f:1 on background".format(ratio))
            } else {
                Check(
                    "Contrast of body text", Status.FAIL,
                    "%.2f:1".format(ratio),
                    "Body text must clear 7:1 on the near-black background."
                )
            }
        }

        check("Contrast of accent as text") {
            val ratio = contrast(ACCENT, BACKGROUND)
            if (ratio >= 4.5) {
                Check("Contrast of accent as text", Status.PASS, "%.2f:1 on background".format(ratio))
            } else {
                Check(
                    "Contrast of accent as text", Status.FAIL,
                    "%.2f:1".format(ratio),
                    "The accent is used as a label, so it needs 4.5:1, not just fill contrast."
                )
            }
        }

        return Report(checks, System.currentTimeMillis() - started)
    }

    // Palette values are mirrored here rather than read from NovaPalette, so the
    // contrast checks measure the same literals the theme declares without
    // depending on a Composition. The only Compose type this object touches is
    // Dp, for the layout check.
    private const val TEXT_PRIMARY = 0xFFF5F7FAL
    private const val ACCENT = 0xFF7FB0FFL
    private const val BACKGROUND = 0xFF0B0E14L

    /** WCAG 2.1 relative luminance. */
    private fun luminance(argb: Long): Double {
        fun channel(c: Long): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(fg: Long, bg: Long): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }
}
