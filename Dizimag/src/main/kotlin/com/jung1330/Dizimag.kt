// StreamTest | Jung1330

package com.jung1330

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Dizimag : MainAPI() {
    override var mainUrl = "https://ddizimag.icu"
    override var name = "Dizimag"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Yabancı Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animes" to "Animeler",
        "$mainUrl/platform/netflix" to "Netflix Yapımları",
        "$mainUrl/platform/disney" to "Disney+ Yapımları",
        "$mainUrl/platform/amazon-prime" to "Amazon Prime Yapımları",
        "$mainUrl/platform/apple-tv" to "Apple TV+ Yapımları",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu-fantastik" to "Bilim Kurgu & Fantastik",
        "$mainUrl/tur/dram" to "Dram"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val targetUrl = if (page <= 1) request.data else "${request.data}${separator}page=$page"
        val document = app.get(targetUrl).document
        val items = parseCatalogCards(document)
        val hasNext = document.selectFirst("a[href*='page=${page + 1}'], a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun parseCatalogCards(document: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<SearchResponse>()

        document.select("a.group.block[href]").forEach { card ->
            val item = card.toSearchResult() ?: return@forEach
            if (seen.add(item.url)) {
                results.add(item)
            }
        }
        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = attr("href").trim()
        if (rawHref.isBlank()) return null

        val cleanHref = rawHref
            .replace(Regex("""/(dizi|anime)/([^/?#]+)/sezon-\d+/bolum-\d+.*"""), "/$1/$2")
            .replace(Regex("""/film/([^/?#]+)/izle.*"""), "/film/$1")

        val url = fixUrlNull(cleanHref) ?: return null
        if (!url.contains("/dizi/") && !url.contains("/film/") && !url.contains("/anime/")) return null

        val title = (selectFirst("h3")?.text()
            ?: selectFirst("img")?.attr("alt")?.replace(Regex("""\s*(Dizi|Film|Anime)\s*izle.*""", RegexOption.IGNORE_CASE), "")
            )?.trim().orEmpty()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        val year = selectFirst("p")?.text()?.trim()?.toIntOrNull()

        return when {
            url.contains("/film/") -> newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
            url.contains("/anime/") -> newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = posterUrl
                this.year = year
            }
            else -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/ara?q=$encoded").document
        return parseCatalogCards(document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url
            .replace(Regex("""/(dizi|anime)/([^/?#]+)/sezon-\d+/bolum-\d+.*"""), "/$1/$2")
            .replace(Regex("""/film/([^/?#]+)/izle.*"""), "/film/$1")
            .substringBefore("?")
            .substringBefore("#")

        val document = app.get(cleanUrl).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img[src*='/posters/']")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val backgroundPoster = fixUrlNull(
            document.selectFirst("img[src*='/backdrops/']")?.attr("src")
                ?: poster
        )

        val ldJsonScripts = document.select("script[type=application/ld+json]").map { it.data() }
        val fullLdJson = ldJsonScripts.joinToString("\n")

        val description = Regex(""""description"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(fullLdJson)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            ?.replace("\\r", "")
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex(""""(?:startDate|datePublished)"\s*:\s*"(\d{4})""")
            .find(fullLdJson)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = Regex(""""genre"\s*:\s*\[([^\]]*)]""")
            .find(fullLdJson)?.groupValues?.getOrNull(1)
            ?.let { raw -> Regex(""""([^"]+)"""").findAll(raw).map { it.groupValues[1] }.toList() }
            .orEmpty()
            .ifEmpty {
                document.select("a.rounded-badge[href*='/tur/']").mapNotNull {
                    it.text().trim().takeIf { t -> t.isNotBlank() }
                }
            }
            .distinct()

        val actorNames = Regex(""""@type"\s*:\s*"Person"\s*,\s*"name"\s*:\s*"([^"]+)"""")
            .findAll(fullLdJson)
            .map { Actor(it.groupValues[1]) }
            .distinctBy { it.name }
            .toList()

        val trailerUrl = Regex(""""embedUrl"\s*:\s*"(https://www\.youtube\.com/embed/[^"]+)"""")
            .find(fullLdJson)?.groupValues?.getOrNull(1)

        val recommendations = parseCatalogCards(document).filter { it.url != cleanUrl }

        if (cleanUrl.contains("/film/")) {
            val watchUrl = "$cleanUrl/izle"
            return newMovieLoadResponse(title, cleanUrl, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.plot = description
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                if (actorNames.isNotEmpty()) addActors(actorNames)
                addTrailer(trailerUrl)
            }
        }

        // Collect all seasons for TV Series / Anime
        val seasonNums = document.select("a[href*='?sezon=']")
            .mapNotNull { link ->
                Regex("""[?&]sezon=(\d+)""").find(link.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .distinct()
            .sorted()
            .ifEmpty { listOf(1) }

        val episodes = mutableListOf<Episode>()
        val seenEpUrls = LinkedHashSet<String>()

        for (sNum in seasonNums) {
            val seasonDoc = if (sNum == 1) document else app.get("$cleanUrl?sezon=$sNum").document
            parseSeasonEpisodes(seasonDoc, sNum, seenEpUrls, episodes)
        }

        val tvType = if (cleanUrl.contains("/anime/")) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, cleanUrl, tvType, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backgroundPoster
            this.plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
            if (actorNames.isNotEmpty()) addActors(actorNames)
            addTrailer(trailerUrl)
        }
    }

    private fun parseSeasonEpisodes(
        doc: Document,
        expectedSeason: Int,
        seenEpUrls: MutableSet<String>,
        episodes: MutableList<Episode>
    ) {
        val epRegex = Regex("""/sezon-(\d+)/bolum-(\d+)""")
        doc.select("a.group[href*='/sezon-'][href*='/bolum-']").forEach { epEl ->
            val epUrl = fixUrlNull(epEl.attr("href")) ?: return@forEach
            val match = epRegex.find(epUrl) ?: return@forEach
            val sNum = match.groupValues[1].toIntOrNull() ?: expectedSeason
            val eNum = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (!seenEpUrls.add(epUrl)) return@forEach

            val epTitle = epEl.selectFirst("p.font-semibold")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: "$sNum. Sezon $eNum. Bölüm"
            val thumb = fixUrlNull(epEl.selectFirst("img")?.attr("src"))

            episodes.add(
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = sNum
                    this.episode = eNum
                    this.posterUrl = thumb
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageHtml = app.get(data).text
        var foundAny = false

        // Extract Pilavyer data-pv slugs and script host from <template data-tbx-defer> or page HTML
        val pvSlugs = Regex("""data-pv=["']([^"']+)["']""")
            .findAll(pageHtml)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val coreHost = Regex("""https?://[^/"']+/assets/js/core\.js""")
            .find(pageHtml)?.value?.substringBefore("/assets/js/core.js")
            ?: "https://play2.pilavyerplay.top"

        for (slug in pvSlugs) {
            try {
                val playerUrl = "$coreHost/assets/js/s.php?s=${java.net.URLEncoder.encode(slug, "UTF-8")}"
                val playerHtml = app.get(
                    playerUrl,
                    referer = "$mainUrl/",
                    headers = mapOf("Origin" to mainUrl)
                ).text

                val playerJsonStr = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                    .find(playerHtml)?.groupValues?.getOrNull(1)

                if (!playerJsonStr.isNullOrBlank()) {
                    val config = runCatching { JSONObject(playerJsonStr) }.getOrNull()
                    val streamUrl = config?.optString("stream")?.takeIf { it.isNotBlank() }
                    if (!streamUrl.isNullOrBlank()) {
                        val subsArr = config.optJSONArray("subs")
                        if (subsArr != null) {
                            for (i in 0 until subsArr.length()) {
                                val sub = subsArr.optJSONObject(i) ?: continue
                                val subUrl = sub.optString("src").takeIf { it.isNotBlank() } ?: continue
                                val label = sub.optString("label").takeIf { it.isNotBlank() }
                                    ?: sub.optString("lang").takeIf { it.isNotBlank() }
                                    ?: "Türkçe"
                                subtitleCallback.invoke(SubtitleFile(label, subUrl))
                            }
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = "$name (Pilavyer)",
                                name = "$name (Pilavyer)",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$coreHost/"
                                this.headers = mapOf(
                                    "Referer" to "$coreHost/",
                                    "Origin" to coreHost
                                )
                                this.quality = Qualities.P720.value
                            }
                        )
                        foundAny = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Also check any standard iframes if present
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(pageHtml)
            .map { it.groupValues[1] }
            .filter { !it.contains("youtube.com") && !it.contains("googletagmanager") }
            .forEach { rawIframe ->
                val iframeUrl = fixUrlNull(rawIframe) ?: return@forEach
                try {
                    if (loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)) {
                        foundAny = true
                    }
                } catch (_: Exception) {
                }
            }

        return foundAny
    }
}
