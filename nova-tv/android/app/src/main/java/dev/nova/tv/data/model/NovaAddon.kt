package dev.nova.tv.data.model

import java.net.URI

/** One installed addon: its manifest plus where it came from. */
data class NovaAddon(
    val baseUrl: String,
    val manifest: Manifest,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false
) {
    val id: String get() = manifest.id.ifBlank { baseUrl }
    val name: String get() = manifest.name.ifBlank { hostLabel() }
    val catalogDefs: List<CatalogDef> get() = manifest.catalogs

    fun supportsCatalog(type: String): Boolean = catalogDefs.any { it.type == type }

    /** Streams are only useful if the addon actually declares the resource. */
    fun supportsStreams(): Boolean =
        manifest.resources.any { it.name.equals("stream", ignoreCase = true) }

    private fun hostLabel(): String = runCatching {
        URI(baseUrl).host ?: baseUrl
    }.getOrDefault(baseUrl)

    companion object {
        /** Keyless public addon, so first launch has content with no setup. */
        const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
    }
}
