package dev.nova.tv.finder.pipeline

import dev.nova.tv.finder.addons.AddonFinderStore
import dev.nova.tv.finder.addons.AddonRecord
import dev.nova.tv.finder.servers.ServerFinderStore
import dev.nova.tv.finder.servers.ServerRecord
import dev.nova.tv.finder.translated.FinderHelpers

/** Bridges the verified pipeline into the existing frontier of a finder. */
fun interface PipelineSink {
    fun push(site: CandidateSite): Boolean
}

class ServerPipelineSink(private val store: ServerFinderStore) : PipelineSink {
    override fun push(site: CandidateSite): Boolean = store.push(site.url)
}

class AddonPipelineSink(private val store: AddonFinderStore) : PipelineSink {
    override fun push(site: CandidateSite): Boolean =
        store.push("${site.url.trimEnd('/')}/manifest.json")
}

/** Writes verified websites into the same JSON pool the UI already reads. */
class ServerPipelineStore(private val store: ServerFinderStore) : PipelineStore {
    override fun put(site: StoredSite): Boolean {
        if (site.kind != PipelineKind.WEBSITE) return false
        if (store.knownHost(site.host)) return false
        val now = FinderHelpers.now()
        val record = ServerRecord(
            id = FinderHelpers.sha1_16(site.url),
            name = site.host,
            provider = site.host,
            host = site.host,
            baseUrl = site.url,
            kind = "site",
            sampleUrl = site.sourceUrl,
            referer = site.url,
            quality = "unknown",
            qualityTier = 0,
            catalogue = site.catalogueItemCount,
            ttfbMs = 0,
            kbps = 0,
            stalls = 0,
            bufferScore = 50,
            signature = "pipeline-v3",
            status = "alive",
            score = site.score.toDouble(),
            failures = 0,
            reason = "verified catalogue and server",
            firstSeen = now,
            lastChecked = now
        )
        store.upsert(record, "alive")
        return true
    }

    override fun size(): Int = store.count("alive")

    override fun snapshot(): List<StoredSite> = store.alive().map {
        StoredSite(
            url = it.toSiteUrl(),
            host = it.host,
            engineId = "servers-pool",
            kind = PipelineKind.WEBSITE,
            catalogueItemCount = it.catalogue,
            sourceUrl = it.sampleUrl,
            score = it.score.toInt(),
            provenance = "servers_pool.json"
        )
    }
}

/** Writes verified add-ons into the same JSON pool the UI already reads. */
class AddonPipelineStore(private val store: AddonFinderStore) : PipelineStore {
    override fun put(site: StoredSite): Boolean {
        if (site.kind != PipelineKind.ADDON) return false
        val base = site.url.trimEnd('/')
        if (store.knownBase(base) || store.knownHost(site.host)) return false
        val now = FinderHelpers.now()
        val record = AddonRecord(
            id = FinderHelpers.sha1_16(base),
            name = site.host,
            host = site.host,
            baseUrl = base,
            manifestUrl = "$base/manifest.json",
            types = listOf("movie", "series", "anime"),
            resources = listOf("catalog", "stream"),
            catalogs = listOf("nova-pipeline"),
            catalogue = site.catalogueItemCount,
            streamsProbed = 1,
            nonTorrent = true,
            quality = "unknown",
            qualityTier = 0,
            ttfbMs = 0,
            kbps = 0,
            bufferScore = 50,
            status = "alive",
            score = site.score.toDouble(),
            failures = 0,
            reason = "verified catalogue and stream endpoint",
            firstSeen = now,
            lastChecked = now
        )
        store.upsert(record, "alive")
        return true
    }

    override fun size(): Int = store.count("alive")

    override fun snapshot(): List<StoredSite> = store.alive().map {
        StoredSite(
            url = it.baseUrl,
            host = it.host,
            engineId = "addons-pool",
            kind = PipelineKind.ADDON,
            catalogueItemCount = it.catalogue,
            sourceUrl = it.manifestUrl,
            score = it.score.toInt(),
            provenance = "addons_pool.json"
        )
    }
}
