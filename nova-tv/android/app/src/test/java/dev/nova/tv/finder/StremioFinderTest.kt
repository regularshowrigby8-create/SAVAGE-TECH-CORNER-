package dev.nova.tv.finder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioFinderTest {

    private fun entry(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `addon catalog endpoints come from declared addonCatalogs`() {
        val e = entry(
            """{"transportUrl":"https://cat.example/manifest.json","manifest":{"resources":["addon_catalog"],
               "addonCatalogs":[{"type":"all","id":"community"},{"type":"movie","id":"top"}]}}"""
        )
        assertEquals(
            listOf("https://cat.example/addon_catalog/all/community.json", "https://cat.example/addon_catalog/movie/top.json"),
            StremioFinder.addonCatalogEndpoints(e)
        )
    }

    @Test
    fun `entries without addon_catalog or with odd ids give nothing`() {
        assertTrue(StremioFinder.addonCatalogEndpoints(entry("""{"transportUrl":"https://a.example/manifest.json","manifest":{"resources":["stream"]}}""")).isEmpty())
        val bad = entry(
            """{"transportUrl":"https://a.example/manifest.json","manifest":{"resources":[{"name":"addon_catalog"}],
               "addonCatalogs":[{"type":"../x","id":"y"}]}}"""
        )
        assertTrue(StremioFinder.addonCatalogEndpoints(bad).isEmpty())
    }
}
