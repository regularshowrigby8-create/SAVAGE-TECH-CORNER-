package dev.nova.tv.data

import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.finder.FinderRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue was empty because Cinemeta's manifest (string `resources`)
 * could not be parsed, so no addon was ever installed. These are trimmed
 * copies of the real manifests of the built-in sources.
 */
class ManifestParseTest {

    @Test
    fun `string resources parse - Cinemeta`() {
        val m = AddonClient.parseManifest(
            """{"id":"com.linvo.cinemeta","name":"Cinemeta","resources":["catalog","meta","addon_catalog"],
               "types":["movie","series"],"idPrefixes":["tt"],
               "catalogs":[{"type":"movie","id":"top","name":"Popular","extra":[{"name":"genre","options":["Action"]},{"name":"skip"}]},
                           {"type":"movie","id":"year","extra":[{"name":"genre","isRequired":true,"options":["2026"]}]}]}"""
        )
        val addon = NovaAddon("https://v3-cinemeta.strem.io", m)
        assertEquals(listOf("catalog", "meta", "addon_catalog"), m.resources.map { it.name })
        assertTrue(addon.supportsMeta())
        assertTrue(addon.supportsCatalog("movie"))
        assertFalse(addon.supportsStreams())
        assertEquals(2, m.catalogs.size)
        assertTrue(m.catalogs[1].extra.single().isRequired)
    }

    @Test
    fun `object resources keep their id prefixes - TMDB Collections`() {
        val m = AddonClient.parseManifest(
            """{"id":"org.stremio.tmdbcollections","name":"TMDB Collections",
               "resources":["catalog",{"name":"meta","types":["movie"],"idPrefixes":["ctmdb."]}],
               "types":["movie","collections"],"catalogs":[]}"""
        )
        assertEquals(listOf("catalog", "meta"), m.resources.map { it.name })
        assertEquals(listOf("ctmdb."), m.resources[1].idPrefixes)
        assertTrue(NovaAddon("https://x.example", m).acceptsId("ctmdb.10"))
    }

    @Test
    fun `odd entries are skipped, not fatal`() {
        val m = AddonClient.parseManifest("""{"id":"x","resources":["catalog",null,7,{"types":[]},""],"catalogs":[]}""")
        assertEquals(listOf("catalog", "7"), m.resources.map { it.name })
    }

    @Test
    fun `missing resources is an empty list`() {
        assertTrue(AddonClient.parseManifest("""{"id":"x"}""").resources.isEmpty())
    }

    @Test
    fun `TMDB titles switch to their IMDb id`() {
        val tmdb = MetaPreview(id = "tmdb:1368337", name = "The Odyssey", imdbId = "tt33764258")
        assertEquals("tt33764258", AddonClient.preferImdbId(tmdb).id)
        // No IMDb id, or not a TMDB id: unchanged.
        assertEquals("tmdb:1", AddonClient.preferImdbId(MetaPreview(id = "tmdb:1", name = "a")).id)
        assertEquals("kitsu:7442", AddonClient.preferImdbId(MetaPreview(id = "kitsu:7442", name = "a", imdbId = "tt2560140")).id)
    }

    @Test
    fun `built-in sources - Cinemeta first, TMDB plus at least five more`() {
        val sources = FinderRules.DEFAULT_SOURCES
        assertEquals(NovaAddon.CINEMETA_URL, sources.first())
        assertTrue(FinderRules.TMDB_SOURCE in sources)
        val catalogues = sources.filterNot { it == FinderRules.TMDB_SOURCE || it.contains("opensubtitles") }
        assertTrue("need TMDB + 5 others, got $catalogues", catalogues.size >= 6)
        assertEquals(sources.size, sources.distinct().size)
        sources.forEach { assertTrue(it, FinderRules.publicHttpsUrl(it).isNotEmpty()) }
    }
}
