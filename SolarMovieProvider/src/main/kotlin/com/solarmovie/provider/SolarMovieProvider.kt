package com.solarmovie.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SolarMovieProvider : MainAPI() {
    override var mainUrl = "https://ww1.solarmovie2.com"
    private val playerApi = "https://ployan.live"
    private val imgBase = "https://img.icdn.my.id"
    override var name = "SolarMovie2"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/movies/1/" to "Movies - أفلام",
        "/series/1/" to "TV-Series - مسلسلات",
        "/top-imdb/1/" to "Top IMDb - الأعلى تقييماً",
        "/genre/action.html" to "Action - أكشن",
        "/genre/adventure.html" to "Adventure - مغامرات",
        "/genre/animation.html" to "Animation - أنيميشن",
        "/genre/comedy.html" to "Comedy - كوميدي",
        "/genre/crime.html" to "Crime - جريمة",
        "/genre/drama.html" to "Drama - دراما",
        "/genre/horror.html" to "Horror - رعب",
        "/genre/romance.html" to "Romance - رومانسي",
        "/genre/sci-fi.html" to "Sci-Fi - خيال علمي",
        "/genre/thriller.html" to "Thriller - إثارة"
    )

    private fun buildPageUrl(base: String, page: Int): String {
        if (page <= 1) return "$mainUrl$base"
        // /movies/1/ -> /movies/2/
        if (base.matches(Regex("""/(movies|series|top-imdb)/\d+/"""))) {
            return "$mainUrl${base.replace(Regex("""/\d+/"""), "/$page/")}"
        }
        if (base.matches(Regex("""/(movies|series|top-imdb)/"""))) {
            return "$mainUrl$base$page/"
        }
        // /genre/action.html -> /genre/action.html2/  (site appends page number)
        if (base.startsWith("/genre/") && base.endsWith(".html")) {
            return "$mainUrl$base$page/"
        }
        return "$mainUrl$base"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url).document
        val items = document.select("div.col div.card a.poster").mapNotNull { it.toSearchResponse() }
            .ifEmpty { document.select("a.poster").mapNotNull { it.toSearchResponse() } }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank() || !href.contains("/movie/")) return null
        val fullUrl = if (href.startsWith("http")) href else fixUrl(href)
        val img = selectFirst("img")
        val title = img?.attr("alt")?.ifBlank { null }
            ?: selectFirst("h2")?.text()?.trim()
            ?: return null
        if (title.isBlank()) return null
        val posterRaw = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
        val poster = fixUrlNull(posterRaw?.replace("w_40/h_60", "w_156/h_234"))?.ifBlank { null }
        // badge: HD = movie, Eps = series
        val badge = selectFirst("span.mlbq, span.mlbe")?.text() ?: ""
        val isSeries = badge.contains("Eps", ignoreCase = true) ||
                title.contains("Season", ignoreCase = true) ||
                fullUrl.contains("season", ignoreCase = true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) { addPoster(poster) }
        } else {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) { addPoster(poster) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        // /searching?q=..&limit=30&offset=0  -> {"data":[{"t","s","d","q","y"}]}
        val res = app.get(
            "$mainUrl/searching",
            params = mapOf("q" to query, "limit" to "30", "offset" to "0"),
            headers = mapOf("Accept" to "application/json", "Referer" to "$mainUrl/")
        ).text
        // minimal manual JSON parse to avoid extra deps
        // find all {...} with "t","s"
        val out = mutableListOf<SearchResponse>()
        val objRegex = Regex("""\{"t":"(.*?)","s":"(.*?)".*?"d":"(.*?)".*?"q":"(.*?)".*?"y":(\d+)""")
        for (m in objRegex.findAll(res)) {
            try {
                val title = m.groupValues[1]
                val slug = m.groupValues[2]
                val kind = m.groupValues[3] // m = movie, s = series
                val quality = m.groupValues[4]
                val year = m.groupValues[5].toIntOrNull()
                val url = "$mainUrl/movie/$slug.html"
                val poster = "$imgBase/thumb/w_156/h_234/$slug.jpg"
                val displayTitle = if (year != null) "$title ($year)" else title
                if (kind == "s") {
                    out.add(newTvSeriesSearchResponse(displayTitle, url, TvType.TvSeries) {
                        addPoster(poster)
                    })
                } else {
                    out.add(newMovieSearchResponse(displayTitle, url, TvType.Movie) {
                        addPoster(poster)
                    })
                }
            } catch (_: Exception) { }
        }
        // fallback: /search.html?q= (server rendered for some queries)
        if (out.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/search.html", params = mapOf("q" to query)).document
                return doc.select("a.poster").mapNotNull { it.toSearchResponse() }
            } catch (_: Exception) { }
        }
        return out.ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val mid = document.selectFirst("#mid")?.attr("data-mid")?.trim()
            ?: Regex("""/movie/.*?-(\d+)\.html""").find(url)?.groupValues?.get(1)
            ?: return null

        val title = document.selectFirst("h1.fs-2")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img.lazy")?.attr("data-src")
                ?.ifBlank { document.selectFirst("img.lazy")?.attr("src") }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )?.replace("w_40/h_60", "w_200/h_300")

        val plot = document.selectFirst(".fst-italic p")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = document.selectFirst("a[href*=/release/]")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""(19|20)\d{2}""").find(document.selectFirst("h1.fs-2")?.text() ?: "")?.value?.toIntOrNull()

        val genres = document.select("a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.selectFirst("p:contains(Actor)")?.text()?.substringAfter(":")?.trim()
        val tags = document.select("a[href*=/tags/]").map { it.text().trim() }

        val episodesRaw = document.select("#eps-list .episode")
        val serversCount = document.select("#srv-list .server").size

        // sanitize title for data payload (| is separator)
        val safeTitle = title.replace("|", "-").replace("\n", " ").trim().take(120)
        val safeYear = (year ?: 0).toString()

        if (episodesRaw.size <= 1) {
            // Movie
            val data = "$mid|1|$safeTitle|$safeYear"
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                addPoster(poster)
                this.plot = plot
                this.year = year
                this.tags = if (tags.isNotEmpty()) tags else genres.ifEmpty { null }
                this.actors = actors?.split(",")?.map { Actor(it.trim()) }
            }
        }

        // TV Series: one entry per episode button
        val episodes = episodesRaw.mapIndexed { index, btn ->
            val epId = btn.attr("id").substringAfter("ep-").toIntOrNull() ?: (index + 1)
            val epName = btn.text().trim().ifBlank { "Episode $epId" }
            val epData = "$mid|$epId|$safeTitle|$safeYear"
            newEpisode(epData) {
                name = epName
                episode = epId
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            addPoster(poster)
            this.plot = plot
            this.year = year
            this.tags = if (tags.isNotEmpty()) tags else genres.ifEmpty { null }
            this.actors = actors?.split(",")?.map { Actor(it.trim()) }
        }
    }

    // ---------- Ployan player crypto (Server 1/2/3) ----------
    // seal: PBKDF2-HMAC-SHA256("player", salt 8B, 1000, 256bit) + AES-256-GCM (iv 12B)
    // wire: saltHex-ivHex-cipherHex+tagHex
    private fun sealToken(plaintext: String): String {
        val random = SecureRandom()
        val salt = ByteArray(8).also { random.nextBytes(it) }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec("player".toCharArray(), salt, 1000, 256)
        val keyBytes = factory.generateSecret(spec).encoded
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val ctTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "${salt.toHex()}-${iv.toHex()}-${ctTag.toHex()}"
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun decryptInfo(password: String, token: String): String? {
        return try {
            val parts = token.split("-")
            if (parts.size != 3) return null
            val salt = parts[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val iv = parts[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val ctTag = parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (salt.size != 8 || iv.size != 12 || ctTag.size < 17) return null
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, 1000, 256)
            val keyBytes = factory.generateSecret(spec).encoded
            val tag = ctTag.copyOfRange(ctTag.size - 16, ctTag.size)
            val ct = ctTag.copyOfRange(0, ctTag.size - 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            // JCE expects ct+tag combined
            val combined = ct + tag
            String(cipher.doFinal(combined), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun extractJsonField(json: String, field: String): String? {
        return Regex(""""$field"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false
        val mid = parts[0].trim()
        val ep = parts[1].trim().toIntOrNull() ?: 1
        val title = parts.getOrNull(2)?.trim().takeIf { !it.isNullOrBlank() } ?: mid
        val year = parts.getOrNull(3)?.trim()?.toIntOrNull()

        var found = false
        // Servers on site: srv-1 (Server 1), srv-2 (Server 2), srv-5 (Server 3)
        val servers = listOf("1" to "Server 1", "2" to "Server 2", "5" to "Server 3")
        val ts = System.currentTimeMillis() / 1000

        for ((srv, label) in servers) {
            try {
                val plain = "$mid+$ep+$srv+$ts"
                val token = sealToken(plain)
                val json = app.get(
                    "$playerApi/get/$token",
                    headers = mapOf("Referer" to "$playerApi/", "Accept" to "application/json"),
                ).text
                val mode = extractJsonField(json, "mode")
                val info = extractJsonField(json, "info")
                if (info.isNullOrBlank()) continue
                if (mode == "direct") {
                    val hls = "$playerApi/hls/$info/master.m3u8"
                    callback(
                        newExtractorLink(
                            source = "SolarMovie $label",
                            name = "$label - HLS",
                            url = hls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = "$playerApi/"
                            quality = Qualities.P1080.value
                        }
                    )
                    found = true
                    // keep trying other servers for more qualities/backups
                } else if (mode == "embed") {
                    // embed hosts (Server 2/3) need extra JS decrypt; try to log decoded path
                    // decrypted looks like "movie/452557-<ts>" – not directly playable,
                    // so we skip but keep Server 1 link. Future: resolve via external extractors.
                    decryptInfo("player", info)
                    continue
                }
            } catch (_: Exception) { continue }
        }

        // ---------- Arabic + English subtitles ----------
        // 1) OpenSubtitles (old free REST, no key) – Arabic first
        try {
            val q = title.replace(Regex("""\s*\(\d{4}\)\s*"""), "").trim()
                .replace(" ", "+").take(80)
            if (q.length >= 2) {
                // Arabic
                try {
                    val arJson = app.get(
                        "https://rest.opensubtitles.org/search/query-$q/sublanguageid-ara",
                        headers = mapOf("User-Agent" to "TemporaryUserAgent", "Accept" to "application/json")
                    ).text
                    // pick top by downloads
                    val linkRegex = Regex(""""SubDownloadLink"\s*:\s*"([^"]+)"""")
                    val links = linkRegex.findAll(arJson).map { it.groupValues[1] }.toList()
                    // prefer first 2
                    links.take(2).forEachIndexed { i, link ->
                        val fixed = link.replace("\\/", "/")
                        subtitleCallback(SubtitleFile(if (i == 0) "Arabic" else "Arabic $i", fixed))
                    }
                } catch (_: Exception) { }
                // English fallback
                try {
                    val enJson = app.get(
                        "https://rest.opensubtitles.org/search/query-$q/sublanguageid-eng",
                        headers = mapOf("User-Agent" to "TemporaryUserAgent", "Accept" to "application/json")
                    ).text
                    val linkRegex = Regex(""""SubDownloadLink"\s*:\s*"([^"]+)"""")
                    val link = linkRegex.find(enJson)?.groupValues?.get(1)?.replace("\\/", "/")
                    if (!link.isNullOrBlank()) subtitleCallback(SubtitleFile("English", link))
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        return found
    }
}
