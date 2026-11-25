package dev.nova.tv.finder.servers

import java.util.concurrent.ConcurrentHashMap

/**
 * Gate 0. Offline, synchronous, no socket. This is where the asset
 * rejections in a trace die instead of burning a fetch each.
 */
object Admission {

    private val assetExt = Regex("""\.(png|jpe?g|gif|webp|svg|ico|bmp|css|js|mjs|woff2?|ttf|txt|xml|map|pdf|zip|apk)$""")
    private val assetSeg = Regex("""/(logo|icon|favicon|badge|sprite|static|assets|img|images|fonts?|css|js)(/|\.|$)""")
    private val staticHosts = setOf(
        "i.imgur.com", "imgur.com", "files.catbox.moe", "styles.redditmedia.com",
        "i.redd.it", "preview.redd.it", "i.postimg.cc", "raw.githubusercontent.com",
        "cdn.jsdelivr.net", "unpkg.com", "fonts.googleapis.com", "gravatar.com"
    )
    /** Multi-tenant sites: the first label is the site, so the key must keep it. */
    private val platforms = setOf(
        "vercel.app", "netlify.app", "fly.dev", "duckdns.org", "dpdns.org", "qzz.io",
        "herokuapp.com", "pages.dev", "onrender.com", "elfhosted.com",
        "midnightignite.me", "github.io", "workers.dev", "replit.app", "glitch.me"
    )

    private val perKey = ConcurrentHashMap<String, Int>()
    private const val MAX_PER_KEY = 3

    fun key(raw: String): String? {
        val host = hostOf(raw) ?: return null
        val labels = host.split('.')
        val platform = platforms.firstOrNull { host.endsWith(".$it") }
        return if (platform != null && labels.size >= 3) {
            labels[labels.size - platform.split('.').size - 1] + "." + platform
        } else {
            labels.takeLast(2).joinToString(".")
        }
    }

    /** null means "worth a socket". Anything else is a final outcome. */
    fun classify(raw: String): Outcome? {
        val host = hostOf(raw) ?: return Outcome.NOT_A_SITE
        if (host in staticHosts) return Outcome.STATIC_ASSET
        if (host.count { it == '.' } > 4) return Outcome.NOT_A_SITE
        val path = raw.substringAfter(host, "").substringBefore('?')
        if (assetExt.containsMatchIn(path.lowercase())) return Outcome.STATIC_ASSET
        if (assetSeg.containsMatchIn(path.lowercase())) return Outcome.STATIC_ASSET
        if (path.trim('/').split('/').size > 3) return Outcome.BAD_PATH
        return null
    }

    fun claim(url: String): Outcome? {
        val k = key(url) ?: return Outcome.NOT_A_SITE
        val n = perKey.merge(k, 1, Int::plus) ?: 1
        return if (n > MAX_PER_KEY) Outcome.DUPLICATE_HOST else null
    }

    /** Round-scoped counters: a new round starts with a clean slate. */
    fun reset() = perKey.clear()
}
