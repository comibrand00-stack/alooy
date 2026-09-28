package com.solarmovie.provider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class SolarMoviePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SolarMovieProvider())
    }
}
