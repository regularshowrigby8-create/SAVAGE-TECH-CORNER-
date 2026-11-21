package dev.nova.tv.data.repo

import dev.nova.tv.data.model.CatalogDef
import dev.nova.tv.data.model.ExtraDef
import dev.nova.tv.data.model.Manifest
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.ResourceDef

/**
 * Keyless public Stremio catalogue services. These are separate addon services,
 * not twelve independent underlying databases: some aggregate Cinemeta/TMDB or
 * other upstreams. Seeds are last-known protocol definitions, not evidence that
 * a service is currently reachable. Live manifests refresh in the background.
 * All entries here are catalogue-only except Cinemeta and Kitsu metadata and
 * OpenSubtitles; their stream resources are deliberately not auto-enabled.
 */
internal object CatalogDefaults {
    const val CINEMETA = "https://v3-cinemeta.strem.io"
    const val IMDB_POPULAR = "https://imdb-popular-stremio.vercel.app"
    const val IMDB_CATALOGS = "https://1fe84bc728af-imdb-catalogs.baby-beamup.club"
    const val ROTTEN = "https://7a82163c306e-rottentomatoes.baby-beamup.club"
    const val WATCH_ATLAS = "https://watch-atlas.pages.dev"
    const val HALLOWEEN = "https://halloween-after-dark.pages.dev"
    const val FESTIVALS = "https://stremio-film-festivals.elfhosted.com"
    const val ANIME_SEASONS = "https://victorgveloso.github.io/animes-season-addon"
    const val KITSU = "https://anime-kitsu.strem.fun"
    const val STREAMING = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club"
    const val INDIA = "https://indiastreams.rdata.in"
    const val DOMAIN_MOVIES = "https://caching.stremio.net/publicdomainmovies.now.sh"
    const val STREMSHARE = "https://stremshare.com"
    const val SUBTITLES = "https://opensubtitles-v3.strem.io"

    private val genres = listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary",
        "Drama", "Family", "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi",
        "Sport", "Thriller", "War", "Western"
    )
    private fun def(
        type: String, id: String, name: String, skip: Boolean = false,
        search: Boolean = false, genre: Boolean = false, genreRequired: Boolean = false
    ): CatalogDef = CatalogDef(type = type, id = id, name = name, extra = buildList {
        if (genre) add(ExtraDef(name = "genre", isRequired = genreRequired, options = if (genreRequired) emptyList() else genres))
        if (search) add(ExtraDef(name = "search"))
        if (skip) add(ExtraDef(name = "skip"))
    })
    private fun both(id: String, name: String, skip: Boolean = false, search: Boolean = false, genre: Boolean = false) =
        listOf("movie", "series").map { def(it, id, name, skip, search, genre) }

    private fun source(
        base: String, id: String, name: String, catalogs: List<CatalogDef>,
        meta: Boolean = false, subtitles: Boolean = false, prefixes: List<String>? = listOf("tt")
    ) = NovaAddon(
        baseUrl = base, isBuiltIn = true,
        manifest = Manifest(
            id = id, name = name, version = "seed", catalogs = catalogs,
            resources = listOf(ResourceDef("catalog")) +
                (if (meta) listOf(ResourceDef("meta")) else emptyList()) +
                (if (subtitles) listOf(ResourceDef("subtitles")) else emptyList()),
            types = catalogs.map { it.type }.distinct(), idPrefixes = prefixes
        )
    )

    /** A complete small bootstrap, available immediately without manifest I/O. */
    val addons: List<NovaAddon> = listOf(
        source(CINEMETA, "com.linvo.cinemeta", "Cinemeta",
            both("top", "Popular", skip = true, search = true, genre = true) +
                both("imdbRating", "Top Rated", skip = true, genre = true) +
                listOf("movie", "series").map { def(it, "year", "New", skip = true, genre = true, genreRequired = true) },
            meta = true),
        source(IMDB_POPULAR, "community.imdb-popular", "IMDb Popular",
            listOf("movie", "series").flatMap { type ->
                val plural = if (type == "movie") "movies" else "series"
                listOf(def(type, "imdb-popular-$plural", "IMDb Popular", true, true, true),
                    def(type, "imdb-top-rated-$plural", "IMDb Top Rated", true, true, true))
            }),
        source(IMDB_CATALOGS, "org.imdbcatalogs", "IMDb Catalogs",
            listOf(def("movie", "imdb-movie-catalog", "IMDb Movies", genre = true),
                def("series", "imdb-series-catalog", "IMDb Series", genre = true))),
        source(ROTTEN, "pw.ers.rottentomatoes", "Rotten Tomatoes",
            listOf(def("movie", "rtfresh_movie", "Certified Fresh", genre = true),
                def("series", "rtfresh_series", "Fresh TV Shows", genre = true))),
        source(WATCH_ATLAS, "community.watchatlas", "Watch Atlas",
            listOf(def("movie", "wa-new-releases", "New Movie Releases", skip = true),
                def("series", "wa-new-shows", "New Shows", skip = true),
                def("movie", "wa-tonight", "Tonight", skip = true),
                def("series", "wa-tonight", "Tonight", skip = true))),
        source(HALLOWEEN, "community.halloweenafterdark", "Halloween After Dark",
            listOf(def("movie", "31-nights", "31 Nights", skip = true, search = true),
                def("series", "spooky-tv", "Spooky TV", skip = true, search = true))),
        source(FESTIVALS, "tv.deflix.stremio-film-festivals", "Film Festivals",
            listOf(def("movie", "cannes-palme-dor", "Cannes — Palme d'Or"))),
        source(ANIME_SEASONS, "animes-season-addon", "Animes' season",
            both("latest_anime_seasons", "Anime Seasons")),
        source(KITSU, "community.anime.kitsu", "Anime Kitsu",
            listOf(def("anime", "kitsu-anime-trending", "Kitsu Trending"),
                def("anime", "kitsu-anime-popular", "Kitsu Most Popular", skip = true, genre = true)),
            meta = true, subtitles = true, prefixes = listOf("kitsu", "mal", "anilist", "anidb")),
        source(STREAMING, "pw.ers.netflix-catalog", "Streaming Catalogs", both("nfx", "Netflix")),
        source(INDIA, "in.rdata.indiastreams", "IndiaStreams",
            listOf(def("movie", "trendingmovies", "Trending movies in India"),
                def("series", "trendingtv", "Trending TV shows in India"))),
        source(DOMAIN_MOVIES, "org.stremio.pubdomainmovies", "Public Domain Movies",
            listOf(def("movie", "publicdomainmovies", "Public Domain Movies", skip = true, search = true))),
        source(STREMSHARE, "com.stremshare.addon", "StremShare",
            listOf(def("movie", "group-155581da-movies", "Netflix — Trending", skip = true, genre = true),
                def("series", "group-155581da-series", "Netflix — Trending", skip = true, genre = true))),
        NovaAddon(SUBTITLES, Manifest(id = "org.stremio.opensubtitlesv3", name = "OpenSubtitles v3",
            resources = listOf(ResourceDef("subtitles"))), isBuiltIn = true)
    )

    /** Only equivalent advertised roles are allowed as general shelf fallbacks. */
    fun alias(base: String, type: String, role: String): String? = when (base.trimEnd('/').lowercase()) {
        IMDB_POPULAR -> when (role) {
            "top" -> if (type == "movie") "imdb-popular-movies" else "imdb-popular-series"
            "imdbRating" -> if (type == "movie") "imdb-top-rated-movies" else "imdb-top-rated-series"
            else -> null
        }
        IMDB_CATALOGS -> if (role == "top") "imdb-$type-catalog" else null
        WATCH_ATLAS -> when (role) {
            "top" -> "wa-tonight"
            "year" -> if (type == "movie") "wa-new-releases" else "wa-new-shows"
            else -> null
        }
        STREAMING -> if (role == "top") "nfx" else null
        INDIA -> if (role == "top") if (type == "movie") "trendingmovies" else "trendingtv" else null
        STREMSHARE -> if (role == "top") if (type == "movie") "group-155581da-movies" else "group-155581da-series" else null
        else -> null
    }
}
