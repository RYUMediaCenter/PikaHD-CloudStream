package com.ryumediacenter

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType

/**
 * Safe starter provider.
 *
 * This intentionally contains no PikaHD scraping, stream extraction, DRM bypass,
 * or links to unlicensed media. Replace the placeholder with a catalog you own
 * or have permission to access and distribute.
 */
class AuthorizedCatalogProvider : MainAPI() {
    override var mainUrl = "https://example.org"
    override var name = "Authorized Media Catalog"
    override val lang = "en"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO: Implement search against an authorized catalog API/site.
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // TODO: Map authorized catalog metadata into a MovieLoadResponse or
        // TvSeriesLoadResponse. Do not resolve protected or unauthorized streams.
        return null
    }
}
