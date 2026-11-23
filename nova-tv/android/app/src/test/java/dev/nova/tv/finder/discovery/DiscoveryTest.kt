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
    fun `web surfaces mix search certificate transparency and common crawl`() {
        val s = Discovery.webSurfaces(0)
        assertTrue(s.any { "html.duckduckgo.com" in it })
        assertTrue(s.any { "crt.sh" in it })
        assertTrue(s.any { "urlscan.io" in it })
        assertTrue(s.any { "collinfo.json" in it })
    }

    @Test
    fun `addon surfaces mix github npm reddit and transparency`() {
        val s = Discovery.addonSurfaces(0)
        assertTrue(s.any { "api.github.com" in it })
        assertTrue(s.any { "registry.npmjs.org" in it })
        assertTrue(s.any { "reddit.com" in it })
        assertTrue(s.any { "crt.sh" in it })
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
}
