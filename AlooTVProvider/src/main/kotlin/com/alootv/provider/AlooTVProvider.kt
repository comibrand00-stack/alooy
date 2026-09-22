package com.alootv.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class AlooTVProvider : MainAPI() {
    override var mainUrl = "https://alooytv-proxy.nu2-proxy.workers.dev"
    override var name = "AlooTV (JoooTV)"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/" to "الرئيسية",
        "/movies.html" to "أفلام",
        "/tv-series.html" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = request.data
        val pageUrl = if (page > 1) "$mainUrl$path?page=$page" else "$mainUrl$path"
        val document = app.get(pageUrl).document
        val items = document.select("div.movie-img").mapNotNull { it.toSearchResponse(path) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/search", params = mapOf("q" to query)).document
        return document.select("div.movie-img").mapNotNull { it.toSearchResponse("/search") }
    }

    private fun Element.toSearchResponse(path: String): SearchResponse? {
        val a = selectFirst("a[href*='/watch/']") ?: return null
        val img = selectFirst("img") ?: return null
        val title = img.attr("alt").ifBlank { a.text().ifBlank { return null } }
        val poster = fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
        val href = fixUrl(a.attr("href"))

        val type = if (path.contains("tv-series")) TvType.TvSeries else TvType.Movie
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) { addPoster(poster) }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { addPoster(poster) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document ?: return null

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val links = document.select("a.btn-ep")
        if (links.isEmpty()) return null

        val episodes = links.mapIndexed { index, link ->
            val episodeNumber = link.text().replace("Ep#", "").replace("حلقة", "").trim().toIntOrNull()
                ?: (index + 1)
            newEpisode(
                fixUrl(link.attr("href")),
                fix = false
            ) {
                name = link.text().trim()
                episode = episodeNumber
            }
        }

        if (episodes.size > 1) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                addPoster(poster)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
            addPoster(poster)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document ?: return false

        document.select("video source[src]").forEach { source ->
            val src = source.attr("src").trim()
            if (src.isNotBlank() && src.startsWith("http") && !src.endsWith(".0")) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = src,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = mainUrl + "/"
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return true
    }
}