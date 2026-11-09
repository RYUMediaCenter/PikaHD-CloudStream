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

/**
 * AniList-backed anime catalogue.
 * This provider supplies metadata and discovery only; it does not claim to
 * provide episode video streams.
 */
class PikaHDAnimeProvider : MainAPI() {
    override var mainUrl = "https://graphql.anilist.co"
    override var name = "PikaHD Anime"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val mapper by lazy { jacksonObjectMapper() }

    private val mediaFields = """
        id
        title { romaji english native }
        coverImage { large extraLarge }
        description(asHtml: false)
        episodes
        seasonYear
        format
        genres
        averageScore
        siteUrl
    """.trimIndent()

    private suspend fun requestMedia(query: String, variables: Map<String, Any>): List<JsonNode> {
        val response = app.post(
            mainUrl,
            data = mapOf("query" to query, "variables" to variables),
            headers = mapOf("Accept" to "application/json")
        ).text
        val root = mapper.readTree(response)
        val errors = root.path("errors")
        if (errors.isArray && errors.size() > 0) {
            throw IllegalStateException(errors[0].path("message").asText("AniList request failed"))
        }
        val media = root.path("data").path("Page").path("media")
        if (!media.isArray) return emptyList()
        return media.toList()
    }

    private fun title(node: JsonNode): String {
        val titles = node.path("title")
        return titles.path("english").asText().takeIf { it.isNotBlank() }
            ?: titles.path("romaji").asText().takeIf { it.isNotBlank() }
            ?: titles.path("native").asText().takeIf { it.isNotBlank() }
            ?: "Untitled Anime"
    }

    private fun toSearch(node: JsonNode): SearchResponse {
        val id = node.path("id").asInt()
        val format = node.path("format").asText()
        val type = if (format == "MOVIE") TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(
            title(node),
            "https://anilist.co/anime/$id",
            type
        ) {
            posterUrl = node.path("coverImage").path("large").asText().takeIf { it.isNotBlank() }
        }
    }

    private suspend fun popular(page: Int): List<JsonNode> {
        val query = """
            query ($page: Int) {
              Page(page: $page, perPage: 20) {
                media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
                  $mediaFields
                }
              }
            }
        """.trimIndent()
        return requestMedia(query, mapOf("page" to page))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val popularItems = popular(page).map { it.toSearch(this) }
            val trendingQuery = """
                query ($page: Int) {
                  Page(page: $page, perPage: 20) {
                    media(type: ANIME, sort: TRENDING_DESC, isAdult: false) {
                      $mediaFields
                    }
                  }
                }
            """.trimIndent()
            val trendingItems = requestMedia(trendingQuery, mapOf("page" to page)).map { it.toSearch(this) }
            newHomePageResponse(
                listOf(
                    HomePageList("Popular Anime", popularItems, true),
                    HomePageList("Trending Anime", trendingItems, true)
                )
            )
        } catch (e: Exception) {
            com.lagradost.cloudstream3.mvvm.logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return null
        return try {
            val gql = """
                query ($page: Int, $search: String) {
                  Page(page: $page, perPage: 20) {
                    media(type: ANIME, search: $search, sort: SEARCH_MATCH, isAdult: false) {
                      $mediaFields
                    }
                  }
                }
            """.trimIndent()
            requestMedia(gql, mapOf("page" to page, "search" to query))
                .map { it.toSearch(this) }
                .toNewSearchResponseList()
        } catch (e: Exception) {
            com.lagradost.cloudstream3.mvvm.logError(e)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast('/').substringBefore('?').toIntOrNull()
            ?: url.substringAfter("/anime/").substringBefore('/').toIntOrNull()
            ?: throw IllegalArgumentException("Invalid AniList anime URL")
        val gql = """
            query ($id: Int) {
              Media(id: $id, type: ANIME) {
                $mediaFields
              }
            }
        """.trimIndent()
        val response = app.post(
            mainUrl,
            data = mapOf("query" to gql, "variables" to mapOf("id" to id)),
            headers = mapOf("Accept" to "application/json")
        ).text
        val media = mapper.readTree(response).path("data").path("Media")
        if (media.isMissingNode || media.isNull) throw IllegalStateException("Anime not found")
        val format = media.path("format").asText()
        val type = if (format == "MOVIE") TvType.Movie else TvType.TvSeries
        val description = media.path("description").asText().takeIf { it.isNotBlank() }
        val year = media.path("seasonYear").takeIf { it.canConvertToInt() }?.asInt()
        val genres = media.path("genres").takeIf { it.isArray }?.map { it.asText() }
        val poster = media.path("coverImage").path("large").asText().takeIf { it.isNotBlank() }
        return newMovieLoadResponse(
            title(media),
            media.path("siteUrl").asText("https://anilist.co/anime/$id"),
            type,
            media.path("siteUrl").asText("https://anilist.co/anime/$id")
        ) {
            plot = description
            this.year = year
            tags = genres
            posterUrl = poster
        }
    }
}
