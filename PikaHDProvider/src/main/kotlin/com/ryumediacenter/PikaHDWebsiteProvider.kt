package com.ryumediacenter

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Generic HTML catalogue parser for the user-authorized PikaHD website.
 * Selectors are intentionally defensive because the live page was unavailable
 * to inspect in this build environment. No DRM, login, paywall or anti-bot bypass.
 */
class PikaHDWebsiteProvider : MainAPI() {
    override var mainUrl = "https://new.pikahd.co"
    override var name = "PikaHD Website"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override val hasMainPage = true

        private suspend fun fetchDocument(url: String): Document {
        val response = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            "Accept-Language" to "hi-IN,hi;q=0.9,en;q=0.8"
        ))
        return Jsoup.parse(response.text, url)
    }

    private fun title(doc: Document): String =
        doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" - ").ifBlank { "PikaHD Title" }

    private fun poster(doc: Document): String? =
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("article img, .poster img, .entry-content img, img")?.let {
                it.absUrl("src").takeIf(String::isNotBlank)
            }

    private fun description(doc: Document): String? =
        doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".entry-content, .description, .synopsis")?.text()?.takeIf { it.isNotBlank() }

    private fun isContentLink(href: String): Boolean {
        if (!href.startsWith(mainUrl)) return false
        val path = URI(href).path.orEmpty()
        return path.isNotBlank() && path != "/" &&
            !path.startsWith("/wp-") && !path.startsWith("/tag/") &&
            !path.startsWith("/category/") && !path.startsWith("/page/")
    }

    private fun cards(doc: Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        return doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href").substringBefore("#")
            val text = a.text().trim()
            if (!isContentLink(href) || text.length < 2 || !seen.add(href)) return@mapNotNull null
            val img = a.selectFirst("img")
            val poster = img?.let {
                it.absUrl("data-src").ifBlank { it.absUrl("src") }
            }?.takeIf { it.isNotBlank() }
            val label = listOf(text, img?.attr("alt").orEmpty()).firstOrNull { it.length > 2 }
                ?: return@mapNotNull null
            newMovieSearchResponse(label, href, TvType.TvSeries) { posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val doc = fetchDocument(if (page <= 1) mainUrl else "$mainUrl/page/$page/")
            val results = cards(doc)
            newHomePageResponse(listOf(HomePageList("PikaHD Latest", results, true)), results.isNotEmpty())
        } catch (e: Exception) {
            com.lagradost.cloudstream3.mvvm.logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return null
        return try {
            val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                if (page > 1) "&paged=$page" else ""
            cards(fetchDocument(url)).toNewSearchResponseList()
        } catch (e: Exception) {
            com.lagradost.cloudstream3.mvvm.logError(e)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDocument(url)
        val name = title(doc)
        val episodeLinks = doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            val label = a.text().trim().ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
            val episodeMatch = Regex("(?:episode|ep|e)\\s*[-#:]?\\s*(\\d+)", RegexOption.IGNORE_CASE).find(label)
                ?: Regex("s(\\d+)\\s*e(\\d+)", RegexOption.IGNORE_CASE).find(label)
            if (href.startsWith(mainUrl) && href != url && episodeMatch != null) {
                Triple(href, label.ifBlank { "Episode ${episodeMatch.groupValues.last()}" }, episodeMatch)
            } else null
        }.distinctBy { it.first }

        val poster = poster(doc)
        val plot = description(doc)
        val year = Regex("\\b(19|20)\\d{2}\\b").find(doc.text())?.value?.toIntOrNull()
        if (episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.map { (href, label, match) ->
                val numbers = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
                newEpisode(href) {
                    name = label
                    if (numbers.size == 2) { season = numbers[0]; episode = numbers[1] }
                    else episode = numbers.lastOrNull()
                    posterUrl = poster
                }
            }
            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(name, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetchDocument(data)
        var found = false
        doc.select("video source[src], video[src], source[src]").forEach { element ->
            val src = element.absUrl("src")
            if (src.startsWith("http") && (src.contains(".m3u8") || src.contains(".mp4") || src.contains(".mpd"))) {
                callback(newExtractorLink(name, name, src) { referer = data })
                found = true
            }
        }
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src")
            if (src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }
}
