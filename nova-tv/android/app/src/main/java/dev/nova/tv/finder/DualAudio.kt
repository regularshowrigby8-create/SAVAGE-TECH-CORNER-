package dev.nova.tv.finder

import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.Stream

/**
 * Dual audio: titles made in another language that also carry English audio.
 *
 * Two halves, both pure so they are unit-tested:
 *  - catalogue side: [isInternational] keeps titles whose first production
 *    country is not English-speaking (Cinemeta previews carry `country`);
 *  - stream side: [languagesOf] reads the release name the way addons write
 *    it ("Dual Audio", "MULTI", "ENG-JPN", "Hindi + English", flag emoji), and
 *    [hasEnglishPlusOther] is what the ranker boosts.
 *
 * The player then asks for English first (`setPreferredAudioLanguage("en")`),
 * and ARVIO's audio panel switches to the original track at any time.
 */
object DualAudio {

    private val ENGLISH_COUNTRIES = setOf(
        "united states", "usa", "united kingdom", "uk", "canada", "australia",
        "new zealand", "ireland"
    )

    /** First listed country, which Cinemeta orders by production weight. */
    fun primaryCountry(meta: MetaPreview): String =
        meta.country.orEmpty().split(',').firstOrNull()?.trim()?.lowercase().orEmpty()

    fun isInternational(meta: MetaPreview): Boolean {
        val primary = primaryCountry(meta)
        if (primary.isNotEmpty()) return primary !in ENGLISH_COUNTRIES
        // Full metadata has `language`; the first one is the original.
        val firstLanguage = meta.language.orEmpty().split(',').firstOrNull()?.trim()?.lowercase().orEmpty()
        return firstLanguage.isNotEmpty() && firstLanguage != "english"
    }

    /** ISO-639-1 code → the words and tags releases use for it. */
    private val LANGUAGE_TOKENS: Map<String, List<String>> = mapOf(
        "en" to listOf("english", "eng"),
        "ja" to listOf("japanese", "jpn", "jap"),
        "ko" to listOf("korean", "kor"),
        "hi" to listOf("hindi", "hin"),
        "es" to listOf("spanish", "spa", "esp", "castellano", "latino", "lat"),
        "fr" to listOf("french", "fre", "fra", "vff", "truefrench"),
        "de" to listOf("german", "ger", "deu"),
        "it" to listOf("italian", "ita"),
        "pt" to listOf("portuguese", "por", "dublado"),
        "ru" to listOf("russian", "rus"),
        "zh" to listOf("chinese", "chi", "mandarin", "cantonese"),
        "ta" to listOf("tamil", "tam"),
        "te" to listOf("telugu", "tel"),
        "tr" to listOf("turkish", "tur"),
        "ar" to listOf("arabic", "ara"),
        "th" to listOf("thai"),
        "pl" to listOf("polish", "pol"),
        "nl" to listOf("dutch", "nld")
    )

    private val FLAGS: Map<String, String> = mapOf(
        "🇬🇧" to "en", "🇺🇸" to "en", "🇯🇵" to "ja", "🇰🇷" to "ko", "🇮🇳" to "hi",
        "🇪🇸" to "es", "🇲🇽" to "es", "🇫🇷" to "fr", "🇩🇪" to "de", "🇮🇹" to "it",
        "🇵🇹" to "pt", "🇧🇷" to "pt", "🇷🇺" to "ru", "🇨🇳" to "zh", "🇹🇷" to "tr"
    )

    private val EN_TAG = Regex("""(^|[\[(|/+,\s])EN([\])|/+,\s]|$)""")

    private val DUAL = Regex("""\b(dual[ ._-]?audio|dual|multi[ ._-]?audio|multi)\b""", RegexOption.IGNORE_CASE)

    private fun text(stream: Stream) =
        listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ")

    /** Languages named in the release, as ISO codes. */
    fun languagesOf(stream: Stream): Set<String> {
        val raw = text(stream)
        val words = raw.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotEmpty() }.toSet()
        val found = LANGUAGE_TOKENS.filter { (_, tokens) -> tokens.any { it.length >= 3 && it in words } }.keys.toMutableSet()
        // "en" alone is too common a fragment; only trust it as a tag ("[EN]", "EN/JA").
        if (EN_TAG.containsMatchIn(raw)) found += "en"
        FLAGS.forEach { (flag, code) -> if (flag in raw) found += code }
        return found
    }

    fun isDual(stream: Stream): Boolean = DUAL.containsMatchIn(text(stream)) || languagesOf(stream).size >= 2

    /** English plus at least one other language, or a release marked dual/multi. */
    fun hasEnglishPlusOther(stream: Stream): Boolean {
        val langs = languagesOf(stream)
        if ("en" in langs && langs.size >= 2) return true
        return DUAL.containsMatchIn(text(stream)) && (langs.isEmpty() || "en" in langs)
    }
}
