package com.ryumediacenter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri

/**
 * AniList-backed anime discovery. AniList supplies metadata only, not video streams.
 * Playback is deliberately not fabricated or linked to unlicensed copies.
 */
class PikaHDAnimeProvider : MainAPI() {
    override var mainUrl = "https://graphql.anilist.co"
    override var name = "PikaHD Anime"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val mapper by lazy { jacksonObjectMapper() }
    private val fields = """
        id
        title { romaji english native }
        coverImage { large }
        description(asHtml: false)
        seasonYear
        format
        genres
        siteUrl
    """.trimIndent()

    private suspend fun request(query: String, variables: Map<String, Any?>): JsonNode {
        val variablesJson = mapper.writeValueAsString(variables)
        val response = app.get(
            "$mainUrl?query=${query.encodeUri()}&variables=${variablesJson.encodeUri()}",
            headers = mapOf("Accept" to "application/json")
        ).text
        val root = mapper.readTree(response)
        val errors = root.path("errors")
        if (errors.isArray && errors.size() > 0) {
            throw IllegalStateException(errors[0].path("message").asText("AniList request failed"))
        }
        return root
    }

    private fun title(node: JsonNode): String {
        val t = node.path("title")
        return t.path("english").asText().takeIf { it.isNotBlank() }
            ?: t.path("romaji").asText().takeIf { it.isNotBlank() }
            ?: t.path("native").asText().takeIf { it.isNotBlank() }
            ?: "Untitled Anime"
    }

    private fun toSearch(node: JsonNode): SearchResponse {
        val id = node.path("id").asInt()
        val type = if (node.path("format").asText() == "MOVIE") TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(title(node), "https://anilist.co/anime/$id", type) {
            posterUrl = node.path("coverImage").path("large").asText().takeIf { it.isNotBlank() }
        }
    }

    private suspend fun mediaPage(
        page: Int,
        sort: String,
        search: String? = null,
        format: String? = null
    ): List<JsonNode> {
        val query = """
            query ($page: Int, $search: String, $sort: [MediaSort], $format: MediaFormat) {
              Page(page: $page, perPage: 20) {
                media(type: ANIME, search: $search, sort: $sort, format: $format, isAdult: false) {
                  $fields
                }
              }
            }
        """.trimIndent()
        val variables = mapOf(
            "page" to page,
            "search" to search,
            "sort" to listOf(sort),
            "format" to format
        )
        val media = request(query, variables).path("data").path("Page").path("media")
        return if (media.isArray) media.toList() else emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val sections = listOf(
                HomePageList("Popular Anime", mediaPage(page, "POPULARITY_DESC").map { toSearch(it) }, true),
                HomePageList("Trending Anime", mediaPage(page, "TRENDING_DESC").map { toSearch(it) }, true),
                HomePageList("Top Rated Anime", mediaPage(page, "SCORE_DESC").map { toSearch(it) }, true),
                HomePageList("Anime Movies", mediaPage(page, "POPULARITY_DESC", format = "MOVIE").map { toSearch(it) }, true),
                HomePageList("Upcoming Anime", mediaPage(page, "POPULARITY_DESC", format = "TV").map { toSearch(it) }, true)
            )
            newHomePageResponse(sections)
        } catch (e: Exception) {
            com.lagradost.cloudstream3.mvvm.logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return null
        return try {
            mediaPage(page, "SEARCH_MATCH", query).map { toSearch(it) }.toNewSearchResponseList()
        } catch (e: Exception) {
            com.lagradost.cloudstream3.mvvm.logError(e)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast('/').substringBefore('?').toIntOrNull()
            ?: throw IllegalArgumentException("Invalid AniList anime URL")
        val query = """
            query ($id: Int) {
              Media(id: $id, type: ANIME) {
                $fields
              }
            }
        """.trimIndent()
        val media = request(query, mapOf("id" to id)).path("data").path("Media")
        if (media.isMissingNode || media.isNull) throw IllegalStateException("Anime not found")
        val type = if (media.path("format").asText() == "MOVIE") TvType.Movie else TvType.TvSeries
        val siteUrl = media.path("siteUrl").asText("https://anilist.co/anime/$id")
        return newMovieLoadResponse(title(media), siteUrl, type, siteUrl) {
            plot = media.path("description").asText().takeIf { it.isNotBlank() }
            this.year = media.path("seasonYear").takeIf { it.canConvertToInt() }?.asInt()
            tags = media.path("genres").takeIf { it.isArray }?.map { it.asText() }
            posterUrl = media.path("coverImage").path("large").asText().takeIf { it.isNotBlank() }
        }
    }
}
