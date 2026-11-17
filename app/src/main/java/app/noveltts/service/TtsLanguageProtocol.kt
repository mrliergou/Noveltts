package app.noveltts.service

import java.util.Locale

/** CHECK_TTS_DATA uses ISO language identifiers, never Android Voice names. */
object TtsLanguageProtocol {
    data class Language(val tag: String, val usable: Boolean)
    data class CheckResult(val available: List<String>, val unavailable: List<String>)

    fun locale(tag: String): Locale? = runCatching {
        val locale = Locale.forLanguageTag(tag.trim().replace('_', '-'))
        require(locale.language.isNotBlank() && locale.isO3Language.isNotBlank())
        if (locale.country.isNotBlank()) require(locale.isO3Country.isNotBlank())
        require(locale.variant.isEmpty() || locale.variant.matches(Regex("[A-Za-z0-9]+")))
        locale
    }.getOrNull()

    fun identifier(tag: String): String? = locale(tag)?.let { locale ->
        buildList {
            add(locale.isO3Language)
            if (locale.country.isNotEmpty()) add(locale.isO3Country)
            if (locale.variant.isNotEmpty()) add(locale.variant)
        }.joinToString("-")
    }

    fun check(languages: List<Language>): CheckResult {
        val available = languages.filter { it.usable }.mapNotNull { identifier(it.tag) }.distinct().sorted()
        val unavailable = languages.filterNot { it.usable }.mapNotNull { identifier(it.tag) }
            .distinct().filterNot { it in available }.sorted()
        return CheckResult(available, unavailable)
    }

    fun matches(locale: Locale, language: String?, country: String?, variant: String?): Boolean =
        runCatching {
            !language.isNullOrBlank() &&
                (locale.language.equals(language, true) || locale.isO3Language.equals(language, true)) &&
                (country.isNullOrBlank() || locale.country.equals(country, true) || locale.isO3Country.equals(country, true)) &&
                (variant.isNullOrBlank() || locale.variant.equals(variant, true))
        }.getOrDefault(false)
}
