// StreamTest | Jung1330

package com.jung1330

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Webdramaturkey : MainAPI() {
    override var mainUrl = "https://webdramaturkey2.com"
    private val initialMainUrl = mainUrl
    override var name = "WebDramaTurkey"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Son Eklenen Diziler",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%221%22%7D" to "Kore Dizileri",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%222%22%7D" to "Çin Dizileri",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%223%22%7D" to "Japon Dizileri",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%224%22%7D" to "Tayland Dizileri",
        "$mainUrl/filmler" to "Asya Filmleri",
        "$mainUrl/animeler" to "Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DomainConfig.sync(this, "Webdramaturkey")
        val request = request.copy(data = DomainConfig.rewrite(request.data, initialMainUrl, mainUrl))
        val separator = if (request.data.contains("?")) "&" else "?"
        val targetUrl = if (page <= 1) request.data else "${request.data}${separator}page=$page"
        val document = app.get(targetUrl).document
        val items = parseCatalogItems(document)
        val hasNext = document.selectFirst("ul.pagination a.page-link:contains(Sonraki), ul.pagination a[href*='page=${page + 1}']") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun parseCatalogItems(document: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<SearchResponse>()

        document.select("div.list-movie").forEach { card ->
            val item = card.toSearchResult() ?: return@forEach
            if (seen.add(item.url)) {
                results.add(item)
            }
        }
        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a.list-title") ?: selectFirst("a.list-media") ?: selectFirst("a[href]") ?: return null
        val rawHref = linkEl.attr("href").trim()
        if (rawHref.isBlank()) return null

        // Convert episode URLs (/dizi/slug/1-sezon/1-bolum) to series root URLs (/dizi/slug)
        val normalizedHref = rawHref.replace(Regex("""/(dizi|anime)/([^/?#]+)/\d+-sezon/\d+-bolum.*"""), "/$1/$2")
        val url = fixUrlNull(normalizedHref) ?: return null
        if (!url.contains("/dizi/") && !url.contains("/film/") && !url.contains("/anime/")) return null

        val title = (selectFirst("a.list-title, .list-title")?.text() ?: linkEl.attr("title")).trim()
        if (title.isBlank()) return null

        val mediaEl = selectFirst(".media-cover, .media-episode, .media")
        val posterUrl = fixUrlNull(
            mediaEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: mediaEl?.selectFirst("img")?.attr("src")
        )

        return when {
            url.contains("/film/") -> newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
            }
            url.contains("/anime/") -> newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = posterUrl
            }
            else -> newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        DomainConfig.sync(this, "Webdramaturkey")
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val document = app.get("$mainUrl/arama/$encoded").document
        return parseCatalogItems(document)
    }

    override suspend fun load(url: String): LoadResponse? {
        DomainConfig.sync(this, "Webdramaturkey")
        val url = DomainConfig.rewrite(url, initialMainUrl, mainUrl)
        val cleanUrl = url.replace(Regex("""/(dizi|anime)/([^/?#]+)/\d+-sezon/\d+-bolum.*"""), "/$1/$2")
        val document = app.get(cleanUrl).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" -")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(".app-detail .media-cover")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst(".detail-attr .text-content")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = document.select(".featured-attr").firstOrNull {
            it.selectFirst(".attr")?.text()?.contains("Yayın yılı", ignoreCase = true) == true
        }?.selectFirst(".text")?.text()?.trim()?.toIntOrNull()

        val tags = document.select("a[href*='/tur/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }.distinct()

        val actors = document.select("#actorsList a.list-trailer").mapNotNull { actorEl ->
            val actorName = actorEl.selectFirst(".list-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val roleName = actorEl.selectFirst(".list-description")?.text()?.trim()
            val actorImg = fixUrlNull(actorEl.selectFirst(".media")?.attr("data-src"))
            Actor(actorName, actorImg) to roleName
        }

        val recommendations = parseCatalogItems(document).filter { it.url != cleanUrl }

        if (cleanUrl.contains("/film/")) {
            return newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        val episodes = mutableListOf<Episode>()
        val episodeRegex = Regex("""/(\d+)-sezon/(\d+)-bolum""")

        document.select(".season-list .episodes a[href]").forEach { epLink ->
            val epUrl = fixUrlNull(epLink.attr("href")) ?: return@forEach
            val match = episodeRegex.find(epUrl)
            val seasonNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val epNum = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: epLink.selectFirst(".episode")?.text()?.let { Regex("""(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

            val epTitle = epLink.selectFirst(".name")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: epLink.selectFirst(".episode")?.text()?.trim()
                ?: "${epNum ?: 1}. Bölüm"

            episodes.add(
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        }

        val tvType = if (cleanUrl.contains("/anime/")) TvType.Anime else TvType.AsianDrama

        return newTvSeriesLoadResponse(title, cleanUrl, tvType, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        DomainConfig.sync(this, "Webdramaturkey")
        val data = DomainConfig.rewrite(data, initialMainUrl, mainUrl)
        val document = app.get(data).document
        val embedButtons = document.select("button.btn-service[data-embed], a.btn-service[data-embed]")
        val seenEmbedIds = LinkedHashSet<String>()
        var foundAny = false

        for (btn in embedButtons) {
            val embedId = btn.attr("data-embed").trim()
            if (embedId.isBlank() || !seenEmbedIds.add(embedId)) continue
            val sourceName = btn.selectFirst(".name")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: "WDT"

            try {
                val ajaxHtml = app.post(
                    "$mainUrl/ajax/embed",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data
                    ),
                    data = mapOf("id" to embedId)
                ).text

                val videoPhpUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(ajaxHtml)?.groupValues?.getOrNull(1)
                    ?.let { fixUrlNull(it) } ?: continue

                if (videoPhpUrl.contains("/video.php")) {
                    val wrapperHtml = app.get(videoPhpUrl, referer = data).text
                    val mainIframeUrl = Regex("""<iframe[^>]+id=["']main-iframe["'][^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(wrapperHtml)?.groupValues?.getOrNull(1)
                        ?: Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(wrapperHtml)?.groupValues?.getOrNull(1)
                        ?: continue

                    val resolvedIframe = fixUrl(mainIframeUrl)
                    if (extractResolvedEmbed(resolvedIframe, sourceName, subtitleCallback, callback)) {
                        foundAny = true
                    }
                } else {
                    if (extractResolvedEmbed(videoPhpUrl, sourceName, subtitleCallback, callback)) {
                        foundAny = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        return foundAny
    }

    private suspend fun extractResolvedEmbed(
        iframeUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // 1. FirePlayer hosts (dtpasn.asia, webdrama.io, etc.) with /video/{32-hex-hash}
        val fireMatch = Regex("""^(https?://[^/]+)/video/([a-f0-9]{32})""", RegexOption.IGNORE_CASE).find(iframeUrl)
        if (fireMatch != null) {
            val hostOrigin = fireMatch.groupValues[1]
            val videoHash = fireMatch.groupValues[2]
            try {
                val jsonText = app.post(
                    "$hostOrigin/player/index.php?data=$videoHash&do=getVideo",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to iframeUrl,
                        "Origin" to hostOrigin
                    ),
                    data = mapOf(
                        "hash" to videoHash,
                        "r" to "$mainUrl/"
                    )
                ).text

                val parsed = runCatching { JSONObject(jsonText) }.getOrNull()
                val streamUrl = parsed?.optString("securedLink")?.takeIf { it.isNotBlank() }
                    ?: parsed?.optString("videoSource")?.takeIf { it.isNotBlank() }
                if (!streamUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name ($sourceName)",
                            name = "$name ($sourceName)",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$hostOrigin/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            } catch (_: Exception) {
            }
        }

        // 2. bulgu.net OK.ru wrapper or standard CloudStream extractors (Odnoklassniki, VK, Vidmoly, Filemoon, etc.)
        val normalizedUrl = when {
            iframeUrl.contains("vidmoly.biz") -> iframeUrl.replace("vidmoly.biz", "vidmoly.to")
            else -> iframeUrl
        }

        try {
            if (loadExtractor(normalizedUrl, "$mainUrl/", subtitleCallback, callback)) {
                found = true
            }
        } catch (_: Exception) {
        }

        return found
    }
}
