package com.ryumediacenter

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PikaHDPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AuthorizedCatalogProvider())
    }
}
