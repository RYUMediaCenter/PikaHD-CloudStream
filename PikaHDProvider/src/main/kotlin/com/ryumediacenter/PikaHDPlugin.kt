package com.ryumediacenter

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PikaHDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AuthorizedCatalogProvider())
    }
}
