// ! StreamTest - HDKore Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder

class Hdkore : MainAPI() {
    override var mainUrl              = "https://hdkore1.com"
    override var name                 = "HDKore"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/"                                              to "Son Eklenenler",
        "$mainUrl/api/v3/tvshow-list?is_ajax=1&per_page=24"       to "Tüm Diziler",
        "$mainUrl/api/v3/tvshow-dublaj-list?is_ajax=1&per_page=24" to "Türkçe Dublaj Diziler",
        "$mainUrl/api/v3/movie-list?is_ajax=1&per_page=24"        to "Filmler"
    )

    private val epRegex      = Regex("""-(\d+)-bolum""")
    private val epQueryRegex = Regex("""[?&]ep=(\d+)""")
    private val numEpsRegex  = Regex(""""numberOfEpisodes"\s*:\s*(\d+)""")

    private fun parseMovieDataCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        doc.select("div.iq-card[data-movie-data]").forEach { el ->
            val obj = runCatching { JSONObject(el.attr("data-movie-data")) }.getOrNull() ?: return@forEach

            val title = obj.optString("name").trim()
            val slug  = obj.optString("slug").trim()
            val type  = obj.optString("type").trim()
            if (title.isBlank() || slug.isBlank()) return@forEach

            val poster = fixUrlNull(obj.optString("poster_image"))
            if (type == "movie") {
                out.add(
                    newMovieSearchResponse(title, "$mainUrl/film/$slug", TvType.Movie) {
                        this.posterUrl = poster
                    }
                )
            } else {
                out.add(
                    newTvSeriesSearchResponse(title, "$mainUrl/dizi/$slug", TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.contains("/api/v3/")) {
            val apiUrl = "${request.data}&page=$page"
            val jsonText = runCatching {
                app.get(apiUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to "$mainUrl/")).text
            }.getOrNull().orEmpty().trim()

            if (jsonText.isBlank()) return newHomePageResponse(request.name, emptyList())
            val htmlFrag = runCatching { JSONObject(jsonText).optString("html") }.getOrNull().orEmpty()
            if (htmlFrag.isBlank()) return newHomePageResponse(request.name, emptyList())

            val fragDoc = Jsoup.parse(htmlFrag, mainUrl)
            return newHomePageResponse(request.name, parseMovieDataCards(fragDoc))
        }

        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val doc = app.get(request.data).document
        return newHomePageResponse(request.name, parseMovieDataCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        // Sitenin kendi anlık JSON arama servisi
        val previewText = runCatching {
            app.get(
                "$mainUrl/api/v3/search-preview?q=$encoded",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to "$mainUrl/")
            ).text
        }.getOrNull().orEmpty().trim()

        val itemsArr = runCatching { JSONObject(previewText).optJSONArray("items") }.getOrNull()
        if (itemsArr != null) {
            val localResults = mutableListOf<SearchResponse>()
            for (i in 0 until itemsArr.length()) {
                val item = itemsArr.optJSONObject(i) ?: continue
                val source = item.optString("source", "local")
                if (source != "local") continue

                val title = item.optString("name").trim()
                val slug  = item.optString("slug").trim()
                val type  = item.optString("type").trim()
                val rawUrl = item.optString("url").trim()
                if (title.isBlank()) continue

                val targetUrl = fixUrlNull(rawUrl) ?: when {
                    slug.isBlank()   -> null
                    type == "movie"  -> "$mainUrl/film/$slug"
                    else             -> "$mainUrl/dizi/$slug"
                } ?: continue

                val poster = fixUrlNull(item.optString("poster_image"))
                if (type == "movie" || targetUrl.contains("/film/")) {
                    localResults.add(
                        newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                            this.posterUrl = poster
                        }
                    )
                } else {
                    localResults.add(
                        newTvSeriesSearchResponse(title, targetUrl, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
            if (localResults.isNotEmpty()) return localResults.distinctBy { it.url }
        }

        // Yedek DuckDuckGo araması
        val q = URLEncoder.encode("site:hdkore1.com $query", "UTF-8")
        val doc = runCatching { app.get("https://html.duckduckgo.com/html/?q=$q").document }.getOrNull()
            ?: return emptyList()

        val diziPrefix = "$mainUrl/dizi/"
        val filmPrefix = "$mainUrl/film/"

        return doc.select("a.result__a").mapNotNull { a ->
            val raw = a.attr("href")
            val url = if (raw.contains("uddg=")) {
                runCatching { URLDecoder.decode(raw.substringAfter("uddg=").substringBefore("&"), "UTF-8") }.getOrNull()
            } else raw

            if (url == null) return@mapNotNull null
            val title = a.text().substringBefore(" | ").trim()
            if (title.isBlank()) return@mapNotNull null

            when {
                url.startsWith(diziPrefix) && !url.removePrefix(diziPrefix).trimEnd('/').contains("/") ->
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries)

                url.startsWith(filmPrefix) && !url.removePrefix(filmPrefix).trimEnd('/').contains("/") ->
                    newMovieSearchResponse(title, url, TvType.Movie)

                else -> null
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" | ")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot   = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
            }
        }

        val explicitUrlByEp = linkedMapOf<Int, String>()
        val explicitNameByEp = linkedMapOf<Int, String>()
        doc.select("a[href*='/bolum/']").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val ep   = epRegex.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            if (!explicitUrlByEp.containsKey(ep)) {
                explicitUrlByEp[ep] = href
                val txt = a.text().trim()
                if (txt.isNotBlank()) explicitNameByEp[ep] = txt
            }
        }

        val slug = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
        val baseBolumUrl = doc.selectFirst("a[href*='/bolum/']")?.attr("href")
            ?.let { fixUrl(it).substringBefore("?") }
            ?: "$mainUrl/bolum/$slug"

        var totalEps = numEpsRegex.findAll(doc.html())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()

        if ((totalEps == null || totalEps <= 0) && explicitUrlByEp.isEmpty()) {
            val bolumDoc = runCatching { app.get(baseBolumUrl).document }.getOrNull()
            totalEps = bolumDoc?.selectFirst("[data-total-eps]")?.attr("data-total-eps")?.toIntOrNull()
        }

        val maxExplicit = explicitUrlByEp.keys.maxOrNull() ?: 0
        val finalCount = maxOf(totalEps ?: 0, maxExplicit)

        val episodes = when {
            finalCount > 0 -> (1..finalCount).map { ep ->
                val epUrl  = explicitUrlByEp[ep] ?: "$baseBolumUrl?ep=$ep"
                val epName = explicitNameByEp[ep] ?: "$ep. Bölüm"
                newEpisode(epUrl) {
                    this.name    = epName
                    this.season  = 1
                    this.episode = ep
                }
            }

            url.contains("-bolum") || url.contains("/bolum/") -> listOf(
                newEpisode(url) {
                    this.name    = "1. Bölüm"
                    this.season  = 1
                    this.episode = epRegex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                }
            )

            else -> emptyList()
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.substringBefore("?")
        val doc = try {
            app.get(pageUrl).document
        } catch (t: Throwable) {
            Log.e("HDKore", "Sayfa açılamadı: ${t.message}")
            return false
        }

        val tokenEl = doc.selectFirst("[data-api-pt]")
        val pt      = tokenEl?.attr("data-api-pt").orEmpty()
        val entId   = tokenEl?.attr("data-entertainment-id").orEmpty()
            .ifBlank { doc.selectFirst("[data-entertainment-id]")?.attr("data-entertainment-id").orEmpty() }

        if (pt.isNotBlank() && entId.isNotBlank()) {
            val episode = epQueryRegex.find(data)?.groupValues?.get(1)
                ?: epRegex.find(data)?.groupValues?.get(1)
                ?: "1"
            val apiUrl = "$mainUrl/api/api-player/episode-url/$entId/$episode?pt=${URLEncoder.encode(pt, "UTF-8")}"

            val apiHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to pageUrl
            )

            val firstJson: String? = runCatching { app.get(apiUrl, headers = apiHeaders).text }.getOrNull()
            val json: String? = if (firstJson != null && firstJson.contains("video_url")) {
                firstJson
            } else {
                runCatching { app.get("$apiUrl&force=1", headers = apiHeaders).text }.getOrNull() ?: firstJson
            }

            val obj = json?.trim()?.let { runCatching { JSONObject(it) }.getOrNull() }
            val videoUrl = obj?.optString("video_url")?.replace("\\/", "/")?.trim().orEmpty()
            if (videoUrl.isNotBlank()) {
                val videoType = obj?.optString("video_type").orEmpty()
                val subtitle  = obj?.optString("subtitle_url").orEmpty()

                if (subtitle.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile("Türkçe", fixUrl(subtitle)))
                }

                val quality = Regex("""[?&]br=(\d+)""").find(videoUrl)?.groupValues?.get(1)?.toIntOrNull()
                val reqHeaders = mapOf("Referer" to "$mainUrl/")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = videoUrl,
                        type   = if (videoType == "mp4") ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = reqHeaders
                        this.quality = quality ?: Qualities.P1080.value
                    }
                )
                return true
            }
        }

        // Film ve alternatif gömülü oynatıcılar için yedek çözüm
        var anyEmitted = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            anyEmitted = true
            callback.invoke(link)
        }

        val candidates = linkedSetOf<String>()
        doc.select("iframe#embed-fourth-iframe[src], iframe#embed-native-iframe[src], iframe[src]:not(.ad-video)").forEach { iframe ->
            val raw = iframe.attr("src").trim()
            if (raw.isBlank() || raw == "about:blank" || raw.contains("\${")) return@forEach
            fixUrlNull(raw)?.let { candidates.add(it) }
        }
        doc.select("[data-video-url]").forEach { el ->
            val raw = el.attr("data-video-url").trim()
            if (raw.startsWith("http")) candidates.add(raw)
        }

        for (src in candidates) {
            if (src.contains("youtube.com", true) || src.contains("youtu.be", true)) continue
            val cleanSrc = src.replace(" ", "%20")

            if (cleanSrc.substringBefore("?").endsWith(".mp4", ignoreCase = true) ||
                cleanSrc.substringBefore("?").endsWith(".m3u8", ignoreCase = true)
            ) {
                val isMp4 = cleanSrc.substringBefore("?").endsWith(".mp4", ignoreCase = true)
                val reqHeaders = mapOf("Referer" to "$mainUrl/")
                wrappedCallback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = cleanSrc,
                        type   = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = reqHeaders
                        this.quality = Qualities.P1080.value
                    }
                )
                continue
            }

            loadExtractor(cleanSrc, "$mainUrl/", subtitleCallback, wrappedCallback)
            if (!anyEmitted && cleanSrc.contains("playmata.com/e/", ignoreCase = true)) {
                val doodFallback = cleanSrc.replace("playmata.com", "dood.li", ignoreCase = true)
                loadExtractor(doodFallback, "$mainUrl/", subtitleCallback, wrappedCallback)
            }
        }

        return anyEmitted
    }
}
