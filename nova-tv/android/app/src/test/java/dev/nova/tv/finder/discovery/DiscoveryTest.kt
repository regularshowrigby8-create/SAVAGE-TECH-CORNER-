package dev.nova.tv.finder.discovery

import dev.nova.tv.finder.translated.FinderHelpers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seedless discovery: query rotation, host hygiene and every index parser.
 *
 * The finders no longer start from a list of sites, so these parsers are the
 * only thing standing between a raw index response and the crawl frontier.
 */
class DiscoveryTest {

    // -- host hygiene --------------------------------------------------------

    @Test
    fun `usable_host rejects search engines social sites and metadata`() {
        assertFalse(Discovery.usableHost("www.google.com"))
        assertFalse(Discovery.usableHost("html.duckduckgo.com"))
        assertFalse(Discovery.usableHost("www.reddit.com"))
        assertFalse(Discovery.usableHost("raw.githubusercontent.com"))
        assertFalse(Discovery.usableHost("crt.sh"))
        assertFalse(Discovery.usableHost("imdb.com"))
        assertFalse(Discovery.usableHost("localhost"))
        assertFalse(Discovery.usableHost(""))
    }

    @Test
    fun `usable_host keeps a plausible streaming or addon host`() {
        assertTrue(Discovery.usableHost("anime.example"))
        assertTrue(Discovery.usableHost("watch-movies.example.co"))
        assertTrue(Discovery.usableHost("stremio-addon.example"))
    }

    @Test
    fun `is_search_host recognises the front ends whose pages are read`() {
        assertTrue(Discovery.isSearchHost("html.duckduckgo.com"))
        assertTrue(Discovery.isSearchHost("duckduckgo.com"))
        assertTrue(Discovery.isSearchHost("www.mojeek.com"))
        assertTrue(Discovery.isSearchHost("www.bing.com"))
        assertFalse(Discovery.isSearchHost("anime.example"))
        assertFalse(Discovery.isSearchHost(""))
    }

    @Test
    fun `looks_like_streaming reads the host and the path`() {
        assertTrue(Discovery.looksLikeStreaming("anime.example"))
        assertTrue(Discovery.looksLikeStreaming("cdn.example", "https://cdn.example/watch/123"))
        assertFalse(Discovery.looksLikeStreaming("bank.example", "https://bank.example/login"))
    }

    @Test
    fun `watch_like matches episode and catalogue paths`() {
        assertTrue(Discovery.watchLike("https://a.example/watch/one-piece-1"))
        assertTrue(Discovery.watchLike("https://a.example/series/123"))
        assertTrue(Discovery.watchLike("https://a.example/browse?page=4"))
        assertFalse(Discovery.watchLike("https://a.example/about"))
    }

    // -- query rotation ------------------------------------------------------

    @Test
    fun `web queries rotate so a later round asks something new`() {
        val q0 = Discovery.webQueries(0)
        val q1 = Discovery.webQueries(1)
        assertTrue(q0.isNotEmpty())
        assertEquals(q0.size, q1.size)
        assertTrue(q0 != q1)
        // The queries are plain viewer intents, never a site name.
        assertTrue(q0.none { it.contains("http") })
    }

    @Test
    fun `web keywords rotate through the domain fragments`() {
        val k0 = Discovery.webKeywords(0)
        val k1 = Discovery.webKeywords(1)
        assertEquals(3, k0.size)
        assertTrue(k0 != k1)
        assertTrue(k0.all { it.isNotBlank() })
    }

    @Test
    fun `addon queries rotate and never name a fixed directory`() {
        val q0 = Discovery.addonQueries(0)
        val q1 = Discovery.addonQueries(1)
        assertEquals(5, q0.size)
        assertTrue(q0 != q1)
        assertTrue(q0.none { it.contains("stremio-addons.net") })
    }

    @Test
    fun `web surfaces use only the engines that accept a bare brand`() {
        val s = Discovery.webSurfaces(0)
        // Bing and RapidDNS are the only passive engines measured to answer a
        // brand query with real hosts. Mojeek 403s automated queries, DuckDuckGo
        // html returns a 202 anomaly page, and crt.sh/urlscan/Wayback/Cert
        // Spotter reject a bare brand, so none of them belong in this tier.
        assertTrue(s.any { "bing.com" in it })
        assertTrue(s.any { "rapiddns.io" in it })
        assertTrue(s.any { "collinfo.json" in it })
        assertTrue(s.none { "crt.sh" in it })
        assertTrue(s.none { "urlscan.io" in it })
        assertTrue(s.none { "duckduckgo" in it })
        assertTrue(s.none { "mojeek" in it })
    }

    @Test
    fun `addon surfaces mix github npm reddit and rapiddns`() {
        val s = Discovery.addonSurfaces(0)
        assertTrue(s.any { "api.github.com" in it })
        assertTrue(s.any { "registry.npmjs.org" in it })
        assertTrue(s.any { "reddit.com" in it })
        assertTrue(s.any { "rapiddns.io" in it })
        assertTrue(s.none { "crt.sh" in it })
    }

    @Test
    fun `domain surfaces need a real domain and rotate the index by round`() {
        // A bare brand is refused by every domain-tier engine.
        assertTrue(Discovery.domainSurfaces("anime", 0).isEmpty())
        assertTrue(Discovery.domainSurfaces("", 0).isEmpty())
        val d0 = Discovery.domainSurfaces("aniwave.to", 0)
        val d1 = Discovery.domainSurfaces("aniwave.to", 1)
        assertTrue(d0.any { "crt.sh" in it })
        assertTrue(d0.any { "urlscan.io" in it })
        assertTrue(d1.any { "web.archive.org" in it })
        assertTrue(d1.any { "certspotter" in it })
        assertTrue(d0 != d1)
    }

    @Test
    fun `is_surface recognises an index host and nothing else`() {
        assertTrue(Discovery.isSurface("https://www.bing.com/search?q=x"))
        assertTrue(Discovery.isSurface("https://rapiddns.io/subdomain/anime?full=1"))
        assertTrue(Discovery.isSurface("https://crt.sh/?q=x&output=json"))
        assertFalse(Discovery.isSurface("https://aniwave.to/watch/1"))
    }

    // -- parsers -------------------------------------------------------------

    @Test
    fun `links_from_html resolves relative links and unwraps duckduckgo results`() {
        val html = """
            <a href="/watch/1">rel</a>
            <a href="https://direct.example/show/2">direct</a>
            <a href="//html.duckduckgo.com/l/?uddg=https%3A%2F%2Fwrapped.example%2Fwatch%2F3&amp;rut=1">w</a>
            <a href="javascript:void(0)">skip</a>
        """.trimIndent()
        val links = Discovery.linksFromHtml(html, "https://base.example/")
        assertTrue(links.any { it == "https://base.example/watch/1" })
        assertTrue(links.any { it == "https://direct.example/show/2" })
        assertTrue(links.any { "wrapped.example" in it })
        assertTrue(links.none { it.startsWith("javascript") })
    }

    @Test
    fun `hosts_from_ct flattens multi line names and strips wildcards`() {
        val text = """
            [
              {"name_value": "anime.example\nwww.anime.example"},
              {"name_value": "*.mirror.example"}
            ]
        """.trimIndent()
        val hosts = Discovery.hostsFromCt(text)
        assertTrue("anime.example" in hosts)
        assertTrue("www.anime.example" in hosts)
        assertTrue("mirror.example" in hosts)
        assertTrue(hosts.none { it.startsWith("*") })
    }

    @Test
    fun `hosts_from_urlscan reads the page domain`() {
        val text = """{"results":[{"page":{"domain":"Anime.Example"}},{"page":{}}]}"""
        assertEquals(listOf("anime.example"), Discovery.hostsFromUrlscan(text))
    }

    @Test
    fun `latest_common_crawl_index takes the first collection id`() {
        val text = """[{"id":"CC-MAIN-2025-08"},{"id":"CC-MAIN-2024-51"}]"""
        assertEquals("CC-MAIN-2025-08", Discovery.latestCommonCrawlIndex(text))
        assertEquals("", Discovery.latestCommonCrawlIndex("not json"))
    }

    @Test
    fun `urls_from_common_crawl reads newline delimited json`() {
        val text = """
            {"url":"https://a.example/watch/1","status":"200"}
            {"url":"https://b.example/show/2","status":"200"}
            not json at all
        """.trimIndent()
        val urls = Discovery.urlsFromCommonCrawl(text)
        assertEquals(listOf("https://a.example/watch/1", "https://b.example/show/2"), urls)
    }

    @Test
    fun `manifest_urls finds absolute manifest links in a page`() {
        val html = """
            <a href="https://one.example/manifest.json">one</a>
            <script src="https://two.example/stremio/manifest.json"></script>
        """.trimIndent()
        val found = Discovery.manifestUrls(html)
        assertTrue(found.any { "one.example" in it })
        assertTrue(found.any { "two.example" in it })
    }

    @Test
    fun `looks_like_addon_page needs stremio vocabulary or a manifest`() {
        assertTrue(Discovery.looksLikeAddonPage("""{"id":"x","manifest.json":true}"""))
        assertTrue(Discovery.looksLikeAddonPage("Stremio addon with catalogs and resources"))
        assertFalse(Discovery.looksLikeAddonPage("A blog about gardening"))
    }

    @Test
    fun `repos_from_github keeps full name homepage and branch`() {
        val text = """
            {"items":[
              {"full_name":"owner/repo","homepage":"https://addon.example","default_branch":"dev"},
              {"full_name":"other/one"}
            ]}
        """.trimIndent()
        val repos = Discovery.reposFromGithub(text)
        assertEquals(2, repos.size)
        assertEquals("owner/repo", repos[0].first)
        assertEquals("https://addon.example|dev", repos[0].second)
        assertEquals("other/one", repos[1].first)
        assertEquals("|main", repos[1].second)
    }

    @Test
    fun `links_from_npm reads homepage demo and repository`() {
        val text = """
            {"objects":[{"package":{
              "name":"stremio-x",
              "links":{"homepage":"https://home.example","repository":"https://github.com/o/r"}
            }}]}
        """.trimIndent()
        val links = Discovery.linksFromNpm(text)
        assertTrue("https://home.example" in links)
        assertTrue("https://github.com/o/r" in links)
    }

    @Test
    fun `surface urls are absolute and encoded`() {
        for (u in Discovery.webSurfaces(2) + Discovery.addonSurfaces(2)) {
            assertTrue("not absolute: $u", u.startsWith("http"))
            assertTrue("unencoded space: $u", !u.contains(" "))
        }
        // Sanity: the helpers are still reachable from the finders.
        assertEquals("example", FinderHelpers.providerOf("https://cdn.example.com/x"))
    }

    // -- redirect unwrapping (bug 1) ----------------------------------------

    @Test
    fun `unwrap decodes a duckduckgo uddg redirect`() {
        val href = "//duckduckgo.com/l/?uddg=https%3A%2F%2Fanime.example%2Fwatch%2F1&rut=abc"
        assertEquals("https://anime.example/watch/1", Discovery.Unwrap.of(href))
    }

    @Test
    fun `unwrap decodes a bing a1 base64 redirect`() {
        val target = "https://hianime.example/watch/9"
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(target.toByteArray())
        val href = "https://www.bing.com/ck/a?!&&p=abc&u=a1$encoded&ntb=1"
        assertEquals(target, Discovery.Unwrap.of(href))
    }

    @Test
    fun `unwrap drops the engine's own host but keeps a plain result`() {
        assertEquals(null, Discovery.Unwrap.of("https://www.bing.com/search?q=anime"))
        assertEquals("https://anime.example/", Discovery.Unwrap.of("https://anime.example/"))
    }

    @Test
    fun `links_from_html unwraps every redirect hop`() {
        val ddg = """<a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fone.example%2F">1</a>"""
        val bing = """<a href="https://www.bing.com/ck/a?u=a1${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://two.example/".toByteArray())}">2</a>"""
        val links = Discovery.linksFromHtml(ddg + bing, "https://www.bing.com/search?q=x")
        assertTrue(links.any { it.startsWith("https://one.example") })
        assertTrue(links.any { it.startsWith("https://two.example") })
        assertTrue(links.none { "duckduckgo.com" in it || "bing.com" in it })
    }

    // -- passive index parsers (bug 2) --------------------------------------

    @Test
    fun `hosts_from_certspotter reads dns_names`() {
        val text = """[{"dns_names":["*.anime.example","cdn.anime.example"]}]"""
        val hosts = Discovery.hostsFromCertSpotter(text)
        assertTrue("anime.example" in hosts)
        assertTrue("cdn.anime.example" in hosts)
    }

    @Test
    fun `hosts_from_wayback reads the original column`() {
        val text = """[["urlkey","timestamp","original"],
            ["x","2020","https://old.example/watch/1"],
            ["y","2021","https://web.archive.org/web/1/https://nested.example/"]]"""
        val hosts = Discovery.hostsFromWayback(text)
        assertTrue("old.example" in hosts)
        assertTrue("nested.example" in hosts)
        assertTrue("web.archive.org" !in hosts)
    }

    @Test
    fun `hosts_from_rapiddns reads table cells`() {
        val text = """<table><tr><td>sub.anime.example</td><td>A</td></tr></table>"""
        assertTrue("sub.anime.example" in Discovery.hostsFromRapidDns(text))
    }

    @Test
    fun `hosts_from_otx reads passive dns rows`() {
        val text = """{"passive_dns":[{"hostname":"a.anime.example"},{"hostname":"b.anime.example"}]}"""
        val hosts = Discovery.hostsFromOtx(text)
        assertEquals(2, hosts.size)
        assertTrue("a.anime.example" in hosts)
    }

    // -- brand sweep and the JSON fast path (bugs 3 and 4) ------------------

    @Test
    fun `brand_of strips the tld and a numeric suffix`() {
        assertEquals("aniwaves", Discovery.brandOf("www.aniwaves.example"))
        assertEquals("hianime", Discovery.brandOf("hianime2.to"))
        assertEquals(null, Discovery.brandOf("localhost"))
    }

    /**
     * A category word is not a brand.
     *
     * Measured: `brandOf("anime.live")` returned "anime", so the sweep asked
     * RapidDNS for the literal keyword `anime` and got 95 hosts back -- 3
     * parked, 1 empty, 1 a 114-byte stub, the rest apparel shops and news
     * portals. Sweeping a category word burns three engines at once for zero
     * streaming sites, so it must expand nothing.
     */
    @Test
    fun `brand_of refuses a category word`() {
        for (host in listOf(
            "anime.live", "anime.com", "anime.us", "anime.club", "anime.plus",
            "movies.to", "watchseries.io", "streaming.hd", "free-tv.example",
            "manga.com", "cartoons.net", "player.one", "video.two",
            "freemovies.example", "watchanimeonline.example"
        )) {
            assertEquals("$host must not be swept", null, Discovery.brandOf(host))
        }
        // A distinctive label is still a brand, including a digit-prefixed one:
        // 9anime, 2embed, 4anime and 1movies are the norm in this space.
        assertEquals("aniwaves", Discovery.brandOf("aniwaves.org"))
        assertEquals("gotaku", Discovery.brandOf("gotaku.to"))
        assertEquals("miruro", Discovery.brandOf("www.miruro.tv"))
        assertEquals("9anime", Discovery.brandOf("9anime.cam"))
        assertEquals("2embed", Discovery.brandOf("2embed.to"))
    }

    @Test
    fun `tld_sweep rotates so a brand is asked for new tlds each round`() {
        val r0 = Discovery.tldSweep("anime", 0)
        val r1 = Discovery.tldSweep("anime", 1)
        assertEquals(12, r0.size)
        assertTrue(r0.all { it.startsWith("anime.") })
        assertTrue(r0.any { it !in r1 })
    }

    @Test
    fun `is_json_surface marks the apis that must never be rendered`() {
        assertTrue(Discovery.isJsonSurface("https://www.reddit.com/search.json?q=x"))
        assertTrue(Discovery.isJsonSurface("https://urlscan.io/api/v1/search/?q=x"))
        assertTrue(Discovery.isJsonSurface("https://api.certspotter.com/v1/issuances?domain=x"))
        assertTrue(Discovery.isJsonSurface("https://web.archive.org/cdx/search/cdx?url=x"))
        assertFalse(Discovery.isJsonSurface("https://anime.example/watch/1"))
    }
}
