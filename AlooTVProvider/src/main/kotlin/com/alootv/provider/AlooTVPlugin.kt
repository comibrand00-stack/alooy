package com.alootv.provider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class AlooTVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AlooTVProvider())
    }
}