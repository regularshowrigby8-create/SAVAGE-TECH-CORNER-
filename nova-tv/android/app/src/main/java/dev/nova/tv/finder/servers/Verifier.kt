package dev.nova.tv.finder.servers

import org.json.JSONObject

/**
 * Two natures, one verdict. A website and a Stremio addon are different
 * objects and must not be judged by the same addon-shaped test.
 */
class Verifier(private val blocklist: Set<String>) {

    private val embedHosts = listOf(
        "vidstream", "vidcloud", "filemoon", "mp4upload", "streamtape", "dood", "dooood",
        "vidsrc", "embedsito", "rabbitstream", "mixdrop", "upcloud", "streamwish", "streamhub",
        "luluvdo", "abyss", "filelions", "vidhide", "vidguard", "megaup", "uqload", "veev",
        "voe", "wolfstream", "streamlare", "asianembed", "platinumvip", "mcloud", "streamsb",
        "gdriveplayer", "vidsrc.to", "vidsrc.xyz", "superembed", "2embed", "multiembed"
    )
    private val embedMark = Regex("""<iframe|<video|\.m3u8|data-embed|class="[^"]*player""")
    private val slug = Regex("""href="[^"]*/(?:watch|movie|movies|series|serie|tv|anime|film|filme|episode|ep|show|assistir|ver|pelicula|dorama)/([a-z0-9-]{3,})""")
    private val searchForm = Regex("""<input[^>]+(type="search"|name="[qs]"|name="query")""")
    private val searchLink = Regex("""href="[^"]*(/search|\?s=|\?q=|/buscar|/recherche)""")
    private val parked = listOf(
        "sedoparking", "parkingcrew", "bodis.com", "dan.com", "hugedomains",
        "afternic", "domain is for sale", "buy this domain", "this domain is parked",
        "namecheap parking", "godaddy.com/domainsearch"
    )
    private val resellers = listOf("sedo.com", "dan.com", "afternic.com", "hugedomains.com", "bodis.com", "namecheap.com")

    suspend fun verify(c: Candidate): Verdict {
        val start = hostOf(c.url) ?: return Verdict.Reject(Outcome.NOT_A_SITE)
        if (start in blocklist) return Verdict.Reject(Outcome.BLOCKED)

        if (!tcpAlive(start)) return Verdict.Reject(Outcome.DEAD_TCP)

        // Root fetch, redirects walked by hand so a move is detectable.
        var host = start
        var page: Fetch? = null
        for (hop in 0 until 3) {
            val r = Net.get("https://$host/", cap = 220_000)
                ?: return Verdict.Reject(Outcome.DEAD_HTTP, "unreachable")

            if (r.status in 300..399) {
                val raw = r.location ?: return Verdict.Reject(Outcome.DEAD_HTTP, "redirect:${r.status}")
                val to = hostOf(absolute(raw, host)) ?: return Verdict.Reject(Outcome.DEAD_HTTP, "redirect-host")
                val reg = registrable(to)
                if (reg != null && resellers.any { reg == it || reg.endsWith(".$it") }) {
                    return Verdict.Reject(Outcome.RESELLER, reg)
                }
                if (registrable(to) != registrable(host)) return Verdict.Move("https://$to/")
                host = to
                continue
            }
            if (r.status == 403 || r.status == 429) return Verdict.Reject(Outcome.BLOCKED, "http-${r.status}")
            if (r.status >= 400) return Verdict.Reject(Outcome.DEAD_HTTP, "http-${r.status}")
            page = r
            break
        }

        val fetch = page ?: return Verdict.Reject(Outcome.DEAD_HTTP, "hops")
        val body = fetch.body
        val low = body.lowercase()
        if (parked.any { low.contains(it) }) return Verdict.Reject(Outcome.PARKED)

        // A root that serves a manifest is an addon, not a website.
        if (low.trimStart().startsWith("{") && low.contains("\"resources\"")) {
            return addon(host, "https://$host/manifest.json", c.source)
        }
        if (low.contains("\"resources\"") && low.contains("manifest.json")) {
            val m = addon(host, "https://$host/manifest.json", c.source)
            if (m is Verdict.PassAddon) return m
        }

        if ("json" in fetch.type) return Verdict.Reject(Outcome.NOT_JSON, "root")

        site(host, body, c.source)?.let { return it }
        return Verdict.Reject(Outcome.NO_EMBEDS)
    }

    private suspend fun site(host: String, index: String, source: Source): Verdict? {
        val low = index.lowercase()
        if (!embedMark.containsMatchIn(low)) return null

        val embeds = embedHosts.count { low.contains(it) }
        val page1 = slug.findAll(low).map { it.groupValues[1] }.toSet()
        val page2 = Net.get("https://$host/browse?page=2", 150_000)
            ?.body?.lowercase()
            ?.let { b -> slug.findAll(b).map { it.groupValues[1] }.toSet() }
            ?: emptySet()

        val catalogue = (page1 + page2).size
        if (catalogue < 40) return Verdict.Reject(Outcome.THIN_CATALOGUE, "n=$catalogue")
        if (page2.isNotEmpty() && catalogue < page1.size * 2) {
            return Verdict.Reject(Outcome.THIN_CATALOGUE, "shallow")
        }

        val searchable = searchForm.containsMatchIn(low) || searchLink.containsMatchIn(low)
        if (!searchable && catalogue < 150) return Verdict.Reject(Outcome.NOT_SEARCHABLE)

        val score = (catalogue / 100.0).coerceAtMost(12.0) + embeds * 1.5 + if (searchable) 4.0 else 0.0
        if (score < 6.0) return Verdict.Reject(Outcome.THIN_CATALOGUE, "score=$score")

        return Verdict.PassSite(SiteRecord(host, score, catalogue, embeds, searchable, source))
    }

    private suspend fun addon(host: String, manifestUrl: String, source: Source): Verdict {
        val r = Net.get(manifestUrl, 120_000) ?: return Verdict.Reject(Outcome.DEAD_HTTP, "manifest")
        if (r.status == 404) return Verdict.Reject(Outcome.MANIFEST_MISSING)
        if (r.status >= 400) return Verdict.Reject(Outcome.DEAD_HTTP, "manifest:${r.status}")
        if (r.body.trimStart().startsWith("<")) return Verdict.Reject(Outcome.MANIFEST_PARSE, "html")

        val json = try { JSONObject(r.body) } catch (e: Exception) {
            return Verdict.Reject(Outcome.MANIFEST_PARSE, r.body.take(1))
        }
        val res = json.optJSONArray("resources") ?: return Verdict.Reject(Outcome.MANIFEST_PARSE, "no-resources")
        val flat = res.toString()
        if (!flat.contains("catalog")) return Verdict.Reject(Outcome.MANIFEST_NO_CATALOG)
        if (!flat.contains("stream")) return Verdict.Reject(Outcome.MANIFEST_NO_STREAM)

        val catalogs = json.optJSONArray("catalogs") ?: return Verdict.Reject(Outcome.MANIFEST_NO_CATALOG)
        val first = catalogs.optJSONObject(0) ?: return Verdict.Reject(Outcome.MANIFEST_NO_CATALOG)
        val type = first.optString("type", "movie")
        val id = first.optString("id").ifBlank { return Verdict.Reject(Outcome.MANIFEST_NO_CATALOG) }

        // Catalogue must actually list things, and page.
        val base = "https://$host/catalog/$type/$id.json"
        val p1 = Net.get(base, 200_000) ?: return Verdict.Reject(Outcome.DEAD_HTTP, "catalog")
        if (p1.status >= 400) return Verdict.Reject(Outcome.ENDPOINT_EMPTY, "catalog:${p1.status}")
        val metas1 = ids(p1.body)
        if (metas1.isEmpty()) return Verdict.Reject(Outcome.ENDPOINT_EMPTY, "catalog-empty")
        val p2 = Net.get("$base?skip=${metas1.size}", 200_000)
        val listed = metas1.size + (p2?.let { ids(it.body).size } ?: 0)
        if (listed < 40) return Verdict.Reject(Outcome.THIN_CATALOGUE, "n=$listed")

        // And the first item must yield a stream.
        val stream = Net.get("https://$host/stream/$type/${metas1.first()}.json", 200_000)
            ?: return Verdict.Reject(Outcome.ENDPOINT_EMPTY, "stream")
        if (stream.status >= 400) return Verdict.Reject(Outcome.ENDPOINT_EMPTY, "stream:${stream.status}")
        val streams = Regex(""""url"\s*:""").findAll(stream.body).count()
        if (streams == 0) return Verdict.Reject(Outcome.ENDPOINT_EMPTY, "no-streams")

        val score = (listed / 100.0).coerceAtMost(20.0) + streams * 2.0 + 6.0
        return Verdict.PassAddon(AddonRecord(host, manifestUrl, score, listed, streams, source))
    }

    private fun ids(body: String) =
        Regex(""""id"\s*:\s*"([^"]{4,})"""").findAll(body).map { it.groupValues[1] }.toList()

    /** A Location header may be relative; the verifier needs an absolute host. */
    private fun absolute(location: String, host: String): String = when {
        location.startsWith("http://") || location.startsWith("https://") -> location
        location.startsWith("/") -> "https://$host$location"
        else -> "https://$host/$location"
    }
}
