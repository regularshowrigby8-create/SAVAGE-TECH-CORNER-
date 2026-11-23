package dev.nova.tv.finder.website

import org.junit.Assert.assertEquals
import org.junit.Test

/** The website finder's link rules: only real sites, one per host. */
class WebsiteSpiderTest {

    @Test
    fun `keeps streaming sites and drops hubs, social and documents`() {
        val markdown = """
            # Streaming
            * [One](https://watch.example.org/) - a site
            * [Two](https://www.movies.example.net/browse?page=2)
            * [Github](https://github.com/someone/streaming-list)
            * [Reddit](https://www.reddit.com/r/example)
            * [File](https://cdn.example.net/movie.mp4)
            * [Duplicate](https://watch.example.org/watch/123)
            * [Social](https://twitter.com/example)
        """.trimIndent()

        assertEquals(
            listOf("https://watch.example.org/", "https://movies.example.net/"),
            WebsiteSpider.siteCandidates(markdown)
        )
    }

    @Test
    fun `ignores http, bare hosts and archive links`() {
        val text = "http://plain.example.com/ https://archive.org/details/x https://example.com"
        assertEquals(listOf("https://example.com/"), WebsiteSpider.siteCandidates(text))
    }
}
