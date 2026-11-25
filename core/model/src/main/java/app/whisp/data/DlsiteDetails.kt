// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DlsiteCredit(val role: String, val name: String, val id: String? = null) {
    val label
        get() =
            when (role) {
                "voice_by" -> "声优"
                "scenario_by" -> "剧本"
                "illust_by" -> "插画"
                "music_by" -> "音乐"
                "directed_by" -> "导演"
                "created_by" -> "作者"
                "others_by" -> "其他创作"
                else -> "创作"
            }
}

@Serializable data class DlsiteGenre(val id: String, val name: String)

@Serializable
data class DlsiteRelatedWork(
    val rj: String,
    val title: String = "",
    val languages: List<String> = emptyList(),
)

@Serializable
data class DlsiteBlock(val kind: String, val text: String = "", val url: String? = null)

@Serializable
data class DlsiteStatistics(
    val rating: Double? = null,
    val ratings: Long? = null,
    val reviews: Long? = null,
    val downloads: Long? = null,
    val priceYen: Long? = null,
    val fetchedAt: Long = 0,
)

@Serializable
data class DlsiteDetails(
    val rj: String,
    val circleId: String? = null,
    val circle: String? = null,
    val released: String? = null,
    val ageRating: String? = null,
    val workType: String? = null,
    val formats: List<String> = emptyList(),
    val packageBytes: Long? = null,
    val credits: List<DlsiteCredit> = emptyList(),
    val genres: List<DlsiteGenre> = emptyList(),
    val seriesId: String? = null,
    val series: String? = null,
    val seriesWorks: List<DlsiteRelatedWork> = emptyList(),
    val relatedEditions: List<DlsiteRelatedWork> = emptyList(),
    val originalRj: String? = null,
    val translationParentRj: String? = null,
    val translated: Boolean = false,
    val translationLanguage: String? = null,
    val languageEditions: List<DlsiteRelatedWork> = emptyList(),
    val summary: String? = null,
    val images: List<String> = emptyList(),
    val description: List<DlsiteBlock> = emptyList(),
    val descriptionFetchedAt: Long = 0,
    val statistics: DlsiteStatistics? = null,
    val productFetchedAt: Long = 0,
    val completeProduct: Boolean = false,
    val availableFields: Set<String> = emptySet(),
) {
    /** Keyed fallback data enriches a complete snapshot without dropping its structured fields. */
    fun preserving(previous: DlsiteDetails?): DlsiteDetails {
        if (previous == null || previous.rj != rj) return this
        val base =
            if (completeProduct && productFetchedAt >= previous.productFetchedAt)
                copy(
                    circleId = circleId ?: previous.circleId,
                    circle = circle ?: previous.circle,
                    released = released ?: previous.released,
                    ageRating = ageRating ?: previous.ageRating,
                    workType = workType ?: previous.workType,
                    packageBytes = packageBytes ?: previous.packageBytes,
                    credits = if ("creaters" in availableFields) credits else previous.credits,
                    genres = if ("genres" in availableFields) genres else previous.genres,
                    images = if ("image_samples" in availableFields) images else previous.images,
                    seriesWorks =
                        if ("editions" in availableFields) seriesWorks else previous.seriesWorks,
                    relatedEditions =
                        if ("editions" in availableFields) relatedEditions
                        else previous.relatedEditions,
                    languageEditions =
                        if (
                            "language_editions" in availableFields ||
                                "translation_info" in availableFields
                        )
                            languageEditions
                        else previous.languageEditions,
                    summary = summary ?: previous.summary,
                )
            else
                previous.copy(
                    circle = circle ?: previous.circle,
                    series = series ?: previous.series,
                    originalRj = originalRj ?: previous.originalRj,
                    translationParentRj = translationParentRj ?: previous.translationParentRj,
                )
        val latestStats =
            if ((statistics?.fetchedAt ?: 0) >= (previous.statistics?.fetchedAt ?: 0))
                statistics ?: previous.statistics
            else previous.statistics
        val mergedStats =
            latestStats?.copy(
                rating = latestStats.rating ?: previous.statistics?.rating,
                ratings = latestStats.ratings ?: previous.statistics?.ratings,
                reviews = latestStats.reviews ?: previous.statistics?.reviews,
                downloads = latestStats.downloads ?: previous.statistics?.downloads,
                priceYen = latestStats.priceYen ?: previous.statistics?.priceYen,
            )
        return base.copy(
            description =
                if (
                    descriptionFetchedAt > 0 &&
                        descriptionFetchedAt >= previous.descriptionFetchedAt
                )
                    description
                else previous.description,
            descriptionFetchedAt = maxOf(descriptionFetchedAt, previous.descriptionFetchedAt),
            statistics = mergedStats,
        )
    }
}

private val metadataJson = Json { ignoreUnknownKeys = true }

fun DlsiteDetails.encode(): String = metadataJson.encodeToString(this)

fun decodeDlsiteDetails(value: String?): DlsiteDetails? = value?.let {
    runCatching { metadataJson.decodeFromString<DlsiteDetails>(it) }.getOrNull()
}

fun dlsiteLanguage(code: String): String =
    when (code.uppercase()) {
        "JPN",
        "JA_JP",
        "JA" -> "日语"
        "ENG",
        "EN_US",
        "EN" -> "英语"
        "CHI_HANS",
        "ZH_CN" -> "简体中文"
        "CHI_HANT",
        "ZH_TW" -> "繁体中文"
        "KO_KR",
        "KOR" -> "韩语"
        else -> code
    }
