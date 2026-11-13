// StreamTest | Jung1330

package com.jung1330

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Asyaanimeleri : MainAPI() {
    override var mainUrl = "https://asyaanimeleri.top"
    override var name = "AsyaAnimeleri"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/series/?status=&type=anime&order=update" to "Güncel Animeler",
        "$mainUrl/series/?status=&type=donghua&order=update" to "Güncel Donghualar",
        "$mainUrl/series/?status=&type=movie&order=update" to "Anime Filmleri",
        "$mainUrl/series/?status=&type=&order=popular" to "Popüler Seriler",
        "$mainUrl/series/?status=completed&type=&order=update" to "Tamamlanan Seriler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val targetUrl = if (page <= 1) request.data else "${request.data}${separator}page=$page"
        val document = app.get(targetUrl).document
        val items = parseBsxCards(document)
        val hasNext = document.selectFirst("div.hpage a.r, div.pagination a.next, a.page-numbers.next") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun parseBsxCards(document: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<SearchResponse>()

        document.select("article.bs div.bsx").forEach { card ->
            val item = card.toSearchResult() ?: return@forEach
            if (seen.add(item.url)) {
                results.add(item)
            }
        }
        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a[href]") ?: return null
        val url = fixUrlNull(linkEl.attr("href")) ?: return null
        val typeText = selectFirst(".typez")?.text()?.trim().orEmpty()
        if (typeText.equals("Comic", ignoreCase = true)) return null

        val title = (selectFirst("h2[itemprop=headline]")?.text()
            ?: linkEl.attr("title")
            ?: selectFirst(".tt")?.ownText()
            )?.trim().orEmpty()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: selectFirst("img")?.attr("data-src")
        )

        val tvType = if (typeText.equals("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, url, tvType) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return parseBsxCards(document)
    }

    override suspend fun load(url: String): LoadResponse? {
        var document = app.get(url).document

        // If user clicked an episode link directly, resolve parent series page if available
        if (!url.contains("/series/")) {
            val seriesLink = document.selectFirst("div.headlist a[href*='/series/'], div.ts-breadcrumb a[href*='/series/']")
                ?.attr("href")?.let { fixUrlNull(it) }
            if (seriesLink != null) {
                document = app.get(seriesLink).document
            }
        }

        val canonicalUrl = document.selectFirst("link[rel=canonical]")?.attr("href") ?: url
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" -")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.thumb img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst("div.entry-content[itemprop=description]")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = document.select("div.spe span").firstOrNull {
            it.text().contains("Yayın Yılı", ignoreCase = true)
        }?.text()?.let { Regex("""(\d{4})""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val tags = document.select("div.genxed a").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }.distinct()

        val recommendations = parseBsxCards(document).filter { it.url != canonicalUrl }

        val episodes = mutableListOf<Episode>()
        val epItems = document.select("div.eplister ul li a[href]").reversed()

        epItems.forEachIndexed { idx, epLink ->
            val epUrl = fixUrlNull(epLink.attr("href")) ?: return@forEachIndexed
            val numStr = epLink.selectFirst(".epl-num")?.text()?.trim().orEmpty()
            val epNum = Regex("""(\d+)""").find(numStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)
            val epTitle = epLink.selectFirst(".epl-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: if (numStr.isNotBlank()) "$numStr. Bölüm" else "$epNum. Bölüm"

            episodes.add(
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                }
            )
        }

        if (episodes.isEmpty() && document.selectFirst("select.mirror, #pembed") != null) {
            episodes.add(
                newEpisode(canonicalUrl) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, canonicalUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val seenUrls = LinkedHashSet<String>()
        var foundAny = false

        // 1. Default #pembed iframe
        document.selectFirst("#pembed iframe[src]")?.attr("src")?.let { rawSrc ->
            val fixed = fixUrlNull(rawSrc)
            if (fixed != null && seenUrls.add(fixed)) {
                if (extractMirrorUrl(fixed, "Varsayılan", subtitleCallback, callback)) {
                    foundAny = true
                }
            }
        }

        // 2. Base64-encoded <select class="mirror"> options
        document.select("select.mirror option[value]").forEach { opt ->
            val b64 = opt.attr("value").trim()
            if (b64.isBlank()) return@forEach
            val mirrorLabel = opt.text().trim().ifBlank { "Alternatif" }

            try {
                val decodedHtml = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                val rawSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(decodedHtml)?.groupValues?.getOrNull(1)
                    ?.let { if (it.startsWith("//")) "https:$it" else fixUrlNull(it) }
                    ?: return@forEach

                if (seenUrls.add(rawSrc)) {
                    if (extractMirrorUrl(rawSrc, mirrorLabel, subtitleCallback, callback)) {
                        foundAny = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        return foundAny
    }

    private suspend fun extractMirrorUrl(
        iframeUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. FirePlayer hosts (asyaanimeleri.pw, player04.xyz, etc.) with /video/{32-hex-hash}
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
                            source = "$name ($label)",
                            name = "$name ($label)",
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

        // 2. Standard CloudStream extractors (OK.ru, VK, Vidmoly, Voe, Dailymotion, GDrive)
        val normalizedUrl = when {
            iframeUrl.contains("vidmoly.biz") -> iframeUrl.replace("vidmoly.biz", "vidmoly.to")
            else -> iframeUrl
        }

        return try {
            loadExtractor(normalizedUrl, "$mainUrl/", subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }
}
