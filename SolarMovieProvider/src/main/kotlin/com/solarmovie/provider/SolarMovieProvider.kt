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
    private val dataApi = "https://data.vidsrc.sh/api.php"
    private val cloudReferer = "https://cloudorchestranova.com/"
    override var name = "SolarMovie2"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/movies/" to "Movies - أفلام",
        "/series/" to "TV-Series - مسلسلات",
        "/top-imdb/" to "Top IMDb - الأعلى تقييماً",
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

    private fun buildPageUrl(base: String, page: Int): String? {
        if (page <= 1) return "$mainUrl$base"
        // /movies/ -> /movies/2/  (note: /movies/1/ is a redirect stub, must NOT be used)
        if (base == "/movies/" || base == "/series/" || base == "/top-imdb/") {
            return "$mainUrl$base$page/"
        }
        // genre pages have no URL pagination
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
            ?: return newHomePageResponse(request.name, emptyList())
        val items = try {
            val document = app.get(url).document
            document.select("div.col div.card a.poster").mapNotNull { it.toSearchResponse() }
                .ifEmpty { document.select("a.poster").mapNotNull { it.toSearchResponse() } }
        } catch (_: Exception) {
            emptyList()
        }
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
        val posterRaw = img?.attr("data-src")?.ifBlank { img?.attr("src") ?: "" } ?: ""
        val poster = fixUrlNull(posterRaw.replace("w_40/h_60", "w_156/h_234"))?.ifBlank { null }
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
        val out = mutableListOf<SearchResponse>()
        val objRegex = Regex("""\{"t":"(.*?)","s":"(.*?)".*?"d":"(.*?)".*?"q":"(.*?)".*?"y":(\d+)""")
        for (m in objRegex.findAll(res)) {
            try {
                val title = m.groupValues[1]
                val slug = m.groupValues[2]
                val kind = m.groupValues[3] // m = movie, s = series
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
                ?.ifBlank { document.selectFirst("img.lazy")?.attr("src") ?: "" }
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
                this.actors = actors?.split(",")?.map { ActorData(Actor(it.trim())) }
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
            this.actors = actors?.split(",")?.map { ActorData(Actor(it.trim())) }
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

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun decryptInfo(password: String, token: String): String? {
        return try {
            val parts = token.split("-")
            if (parts.size != 3) return null
            val salt = hexToBytes(parts[0])
            val iv = hexToBytes(parts[1])
            val ctTag = hexToBytes(parts[2])
            if (salt.size != 8 || iv.size != 12 || ctTag.size < 17) return null
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, 1000, 256)
            val keyBytes = factory.generateSecret(spec).encoded
            val tag = ctTag.copyOfRange(ctTag.size - 16, ctTag.size)
            val ct = ctTag.copyOfRange(0, ctTag.size - 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct + tag), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun extractJsonField(json: String, field: String): String? {
        return Regex(""""$field"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\/", "/")?.replace("\\u0026", "&")
    }

    // re("player", "mid-ep"): XOR each char with (xor of password bytes), hex-encoded.
    // Used by ployan player to build /sub/{hash}/index.json subtitle URLs.
    private fun reHash(password: String, text: String): String {
        var k = 0
        for (b in password.toByteArray(Charsets.UTF_8)) k = k xor (b.toInt() and 0xFF)
        return text.toByteArray(Charsets.UTF_8).joinToString("") {
            "%02x".format((it.toInt() and 0xFF) xor k)
        }
    }

    // ---------- Server 1: direct HLS via ployan ----------
    private suspend fun resolveServer1(
        mid: String, ep: Int, label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val token = sealToken("$mid+$ep+1+${System.currentTimeMillis() / 1000}")
            val json = app.get(
                "$playerApi/get/$token",
                headers = mapOf("Referer" to "$playerApi/", "Accept" to "application/json")
            ).text
            if (extractJsonField(json, "mode") != "direct") return false
            val info = extractJsonField(json, "info") ?: return false
            if (info.isBlank()) return false
            callback(
                newExtractorLink(
                    source = "SolarMovie $label",
                    name = "$label - HLS",
                    url = "$playerApi/hls/$info/master.m3u8",
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "$playerApi/"
                    quality = Qualities.P1080.value
                }
            )
            true
        } catch (_: Exception) { false }
    }

    // ---------- Server 2/3: embed via ployan -> vidsrc data API -> WASM decrypt ----------
    private suspend fun resolveEmbedServer(
        mid: String, ep: Int, srv: String, label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // 1. ployan token -> embed info -> tmdb id
            val token = sealToken("$mid+$ep+$srv+${System.currentTimeMillis() / 1000}")
            val json = app.get(
                "$playerApi/get/$token",
                headers = mapOf("Referer" to "$playerApi/", "Accept" to "application/json")
            ).text
            val mode = extractJsonField(json, "mode")
            val info = extractJsonField(json, "info") ?: return false
            if (info.isBlank()) return false
            if (mode == "direct") {
                // some titles serve direct on every server
                callback(
                    newExtractorLink(
                        source = "SolarMovie $label",
                        name = "$label - HLS",
                        url = "$playerApi/hls/$info/master.m3u8",
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = "$playerApi/"
                        quality = Qualities.P1080.value
                    }
                )
                return true
            }
            if (mode != "embed") return false
            val inner = decryptInfo("player", info) ?: return false
            // movie/452557-ts  |  tv/103516/2-1-ts
            val movieMatch = Regex("""^movie/(\d+)-""").find(inner)
            val tvMatch = Regex("""^tv/(\d+)/(\d+)-(\d+)-""").find(inner)
            val apiUrl = when {
                movieMatch != null ->
                    "$dataApi?type=movie&tmdb=${movieMatch.groupValues[1]}&stream_urls"
                tvMatch != null ->
                    "$dataApi?type=tv&tmdb=${tvMatch.groupValues[1]}&season=${tvMatch.groupValues[2]}&episode=${tvMatch.groupValues[3]}&stream_urls"
                else -> return false
            }
            // 2. stream data: encrypted urls + wasm decryptor url
            val apiJson = app.get(
                apiUrl, headers = mapOf("Referer" to cloudReferer, "Accept" to "application/json")
            ).text
            val blob = extractJsonField(apiJson, "stream_urls") ?: return false
            val wasmUrl = extractJsonField(apiJson, "wasm_url")
                ?: Regex(""""wasm_url"\s*:\s*"([^"]+)"""").find(apiJson)?.groupValues?.get(1)
                    ?.replace("\\/", "/")?.replace("\\u0026", "&")
                ?: return false
            if (blob.isBlank()) return false
            // 3. run rotating decryptor, exactly like vsdec.js
            val wasmBytes = app.get(
                wasmUrl, headers = mapOf("Referer" to cloudReferer)
            ).body.byteStream().readBytes()
            if (wasmBytes.size < 100) return false
            val masters = VsWasm().decryptStreamUrls(wasmBytes, blob)
            if (masters.isEmpty()) return false
            // 4. per-host IP-bound token (mirrors player.js loadStream)
            var emitted = 0
            val tokenCache = mutableMapOf<String, String>()
            masters.take(3).forEachIndexed { index, master ->
                try {
                    val host = Regex("""^(https://[^/]+)""").find(master)?.groupValues?.get(1)
                        ?: return@forEachIndexed
                    var tk = tokenCache[host]
                    if (tk == null) {
                        tk = try { fetchHostToken(host, master) } catch (_: Exception) { "" }
                        tokenCache[host] = tk
                    }
                    val finalUrl = when {
                        tk.isBlank() -> master
                        master.contains("__TOKEN__") -> master.replace("__TOKEN__", tk)
                        master.contains("?") -> "$master&token=$tk"
                        else -> "$master?token=$tk"
                    }
                    val name = if (masters.size > 1) "$label - Mirror ${index + 1}" else "$label - HLS"
                    callback(
                        newExtractorLink(
                            source = "SolarMovie $label",
                            name = name,
                            url = finalUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = cloudReferer
                            quality = Qualities.Unknown.value
                        }
                    )
                    emitted++
                } catch (_: Exception) { }
            }
            return emitted > 0
        } catch (_: Exception) { return false }
    }

    private suspend fun fetchHostToken(host: String, referer: String): String {
        val raw = app.get(
            "$host/generate.php", headers = mapOf("Referer" to referer, "Accept" to "*/*")
        ).text.trim()
        if (raw.isBlank()) return ""
        // mirror player.js parseToken: plain text or {"token"|"data"|"string"|"result":...}
        if (!raw.startsWith("{") && !raw.startsWith("[")) return raw
        return extractJsonField(raw, "token")
            ?: extractJsonField(raw, "data")
            ?: extractJsonField(raw, "string")
            ?: extractJsonField(raw, "result")
            ?: ""
    }

    // ---------- Subtitles: ployan /sub/{re(mid-ep)}/index.json, Arabic first ----------
    private suspend fun loadPloyanSubs(
        mid: String, ep: Int,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val h = reHash("player", "$mid-$ep")
            val json = app.get(
                "$playerApi/sub/$h/index.json",
                headers = mapOf("Referer" to "$playerApi/", "Accept" to "application/json")
            ).text
            data class T(val file: String, val label: String, val lang: String?)
            val subs = Regex("""\{[^}]*\}""").findAll(json).mapNotNull { m ->
                val f = Regex(""""file"\s*:\s*"([^"]+)"""").find(m.value)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val l = Regex(""""label"\s*:\s*"([^"]+)"""").find(m.value)?.groupValues?.get(1)
                    ?: "English"
                val lg = Regex(""""lang"\s*:\s*"([^"]+)"""").find(m.value)?.groupValues?.get(1)
                T(f, l, lg)
            }.toList()
            if (subs.isEmpty()) return
            val rank = { t: T ->
                when {
                    t.lang == "ar" || t.label.contains("arab", true) || t.label.contains("العربية") -> 0
                    t.lang == "en" || t.label.startsWith("English", true) -> 1
                    else -> 2
                }
            }
            subs.sortedBy(rank).take(8).forEach { (file, label, _) ->
                val abs = if (file.startsWith("http")) file else playerApi + file
                subtitleCallback(SubtitleFile(label, abs))
            }
        } catch (_: Exception) { }
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
        if (mid.isBlank()) return false

        var found = false
        // Server 1: direct HLS
        if (resolveServer1(mid, ep, "Server 1", callback)) found = true
        // Server 2 + Server 3: embed chain (vidsrc)
        if (resolveEmbedServer(mid, ep, "2", "Server 2", callback)) found = true
        if (resolveEmbedServer(mid, ep, "5", "Server 3", callback)) found = true
        // Subtitles (host-independent, same mid/episode)
        loadPloyanSubs(mid, ep, subtitleCallback)
        return found
    }
}
