package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginRequirement
import com.lagradost.cloudstream3.syncproviders.AuthLoginResponse
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI
import com.lagradost.cloudstream3.utils.SubtitleHelper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

class SubSourceApi : SubtitleAPI() {
    override val name = "SubSource"
    override val idPrefix = "subsource"

    // The SubSource API moved to api.subsource.net/api/v1 and now requires a
    // free API key (the old keyless endpoints are dead behind Cloudflare).
    // The key is created in the account dashboard on subsource.net and is
    // entered in the in-app login dialog, like SubDL.
    override val requiresLogin = true
    override val hasInApp = true
    override val inAppLoginRequirement = AuthLoginRequirement(email = true)
    override val createAccountUrl = "https://subsource.net/api-docs"

    companion object {
        const val APIURL = "https://api.subsource.net/api/v1"
    }

    override suspend fun login(form: AuthLoginResponse): AuthToken? {
        // The "email" field is used to paste the SubSource API key.
        val apiKey = form.email?.trim()?.takeUnless { it.isEmpty() } ?: return null

        // Validate the key with a cheap request before saving it.
        val test = app.get(
            url = "$APIURL/movies/search?searchType=text&q=test",
            headers = mapOf("X-API-Key" to apiKey)
        ).parsedSafe<SearchResponse>()

        return if (test?.success == true) AuthToken(accessToken = apiKey) else null
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        token?.accessToken ?: return null
        return AuthUser(id = idPrefix.hashCode(), name = name)
    }

    override suspend fun search(
        auth: AuthData?,
        query: AbstractSubtitleEntities.SubtitleSearch
    ): List<AbstractSubtitleEntities.SubtitleEntity>? {
        val apiKey = auth?.token?.accessToken ?: return null
        val queryLang = SubtitleHelper.fromTagToEnglishLanguageName(query.lang) ?: return null
        val isSeries = (query.seasonNumber ?: 0) > 0
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        // Resolve the movie/series id (imdb search, like before).
        val searchResponse = query.imdbId?.let { imdbId ->
            app.get(
                url = "$APIURL/movies/search?searchType=imdb&imdb=$imdbId",
                headers = mapOf("X-API-Key" to apiKey),
                cacheTime = 120,
                cacheUnit = TimeUnit.MINUTES,
            ).parsedSafe<SearchResponse>()
        } ?: return null

        val movieId = searchResponse.results.firstOrNull()?.movieId ?: return null

        // List subtitles with server side language and episode filtering.
        val seasonNum = query.seasonNumber ?: 0
        val epNum = query.epNumber ?: 0
        val languageQuery = "&language=${queryLang.lowercase()}"
        val seasonQuery = if (seasonNum > 0) "&seasonNumber=$seasonNum" else ""
        val episodeQuery = if (epNum > 0) "&episodeNumber=$epNum" else ""
        val subtitleResponse = app.get(
            url = "$APIURL/subtitles?movieId=$movieId&limit=100$languageQuery$seasonQuery$episodeQuery",
            headers = mapOf("X-API-Key" to apiKey),
            cacheTime = 120,
            cacheUnit = TimeUnit.MINUTES,
        ).parsedSafe<SubtitlesResponse>() ?: return null

        val withoutTrailers = subtitleResponse.results.filter {
            it.releaseType?.equals("trailer", true) != true
        }

        // Prefer the requested language, but if the API names it differently
        // show everything rather than an empty list.
        val languageMatched = withoutTrailers
            .filter { it.language.equals(queryLang, true) }
            .ifEmpty { withoutTrailers }

        // The API may return the whole series, so for episodes first prefer
        // subtitles that mention this episode, then ones with no episode
        // marker (season packs), and as a last resort everything.
        val subtitles = if (seasonNum > 0 && epNum > 0) {
            val thisEpisode = languageMatched.filter { sub ->
                val text = sub.toReleaseText()
                text.contains(
                    String.format(java.util.Locale.ROOT, "E%02d", epNum),
                    ignoreCase = true
                ) || text.contains(
                    Regex("\\b0?$seasonNum\\s?x0?$epNum\\b", RegexOption.IGNORE_CASE)
                )
            }
            val noEpisodeMarker = languageMatched.filter { sub ->
                !Regex(
                    "\\bS\\d{1,2}\\s?E\\d{1,3}\\b|\\b\\d{1,2}x\\d{1,3}\\b",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(sub.toReleaseText())
            }

            thisEpisode.ifEmpty { noEpisodeMarker }.ifEmpty { languageMatched }
        } else {
            languageMatched
        }

        return subtitles.mapNotNull { subtitle ->
            AbstractSubtitleEntities.SubtitleEntity(
                idPrefix = this.idPrefix,
                name = subtitle.toReleaseText().ifBlank { subtitle.releaseType ?: "SubSource subtitle" },
                lang = query.lang ?: "en",
                data = subtitle.subtitleId?.toString() ?: return@mapNotNull null,
                type = type,
                source = this.name,
                epNumber = query.epNumber,
                seasonNumber = query.seasonNumber,
                isHearingImpaired = subtitle.hearingImpaired == true,
            )
        }
    }

    override suspend fun SubtitleResource.getResources(
        auth: AuthData?,
        subtitle: AbstractSubtitleEntities.SubtitleEntity
    ) {
        val apiKey = auth?.token?.accessToken ?: return
        val id = subtitle.data
        if (id.isEmpty()) return

        // The download endpoint also accepts the key as a query parameter.
        this.addZipUrl(
            "$APIURL/subtitles/$id/download?api_key=$apiKey"
        ) { name, _ ->
            name
        }
    }

    private fun SubtitleItem.toReleaseText(): String = when (val info = releaseInfo) {
        is JsonArray -> info.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" ")
        is JsonPrimitive -> info.content
        else -> ""
    }

    @Serializable
    data class SearchResponse(
        @SerialName("success") var success: Boolean? = null,
        @SerialName("data") var results: List<SearchResult> = emptyList(),
    )

    @Serializable
    data class SearchResult(
        @SerialName("movieId") var movieId: Int? = null,
        @SerialName("title") var title: String? = null,
        @SerialName("releaseYear") var releaseYear: Int? = null,
    )

    @Serializable
    data class SubtitlesResponse(
        @SerialName("success") var success: Boolean? = null,
        @SerialName("data") var results: List<SubtitleItem> = emptyList(),
    )

    @Serializable
    data class SubtitleItem(
        @SerialName("subtitleId") var subtitleId: Int? = null,
        @SerialName("language") var language: String = "",
        @SerialName("releaseType") var releaseType: String? = null,
        // releaseInfo is normally an array of release tags, but keep it
        // flexible in case the API sends a plain string.
        @SerialName("releaseInfo") var releaseInfo: JsonElement? = null,
        @SerialName("hearingImpaired") var hearingImpaired: Boolean? = null,
        @SerialName("foreignParts") var foreignParts: Boolean? = null,
    )
}
