// ! StreamTest - Drama Dizilerim Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Dramadizilerim : MainAPI() {
    override var mainUrl              = "https://dramadizilerim.com"
    override var name                 = "Drama Dizilerim"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler?page="             to "Tüm Diziler",
        "$mainUrl/turkce-dublaj-drama?page=" to "Türkçe Dublaj",
        "$mainUrl/kore-dramalari?page="      to "Kore Dramaları",
        "$mainUrl/cin-dramalari?page="       to "Çin Dramaları"
    )

    private val numEpsRegex    = Regex(""""numberOfEpisodes"\s*:\s*(\d+)""")
    private val epCountRegex   = Regex(""""episodeCount"\s*:\s*(\d+)""")
    private val episodeIdRegex = Regex(""""episodeId"\s*:\s*"([a-zA-Z0-9_-]{8,})"""")

    private fun parseCatalogCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        doc.select("article.title-card a.card-link[href*='/dizi/'], a.card-link[href*='/dizi/'], a.video-item-card[href*='/dizi/']")
            .forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                if (!href.contains("/dizi/")) return@forEach

                val rawTitle = a.selectFirst("h3, h2, .card-title, .video-item-title")?.text()?.trim().orEmpty()
                    .ifBlank { a.attr("aria-label").substringBefore(" — ").substringBefore(" - ").trim() }
                    .ifBlank { a.attr("title").substringBefore(" İzle").trim() }
                    .ifBlank { a.selectFirst("img")?.attr("alt")?.removeSuffix(" dizi afişi")?.trim().orEmpty() }

                if (rawTitle.isBlank()) return@forEach

                val img = a.selectFirst("img")
                val poster = fixUrlNull(img?.attr("src")?.ifBlank { null } ?: img?.attr("data-src"))

                out.add(
                    newTvSeriesSearchResponse(rawTitle, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            }

        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page").document
        return newHomePageResponse(request.name, parseCatalogCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        val htmlResults = runCatching {
            val doc = app.get("$mainUrl/diziler?q=$encoded").document
            parseCatalogCards(doc)
        }.getOrNull().orEmpty()

        if (htmlResults.isNotEmpty()) return htmlResults

        val jsonText = runCatching {
            app.get("$mainUrl/api/catalog?q=$encoded&language=all&pageSize=24").text
        }.getOrNull() ?: return emptyList()

        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("items") ?: root.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            val href = item.optString("href").ifBlank {
                item.optString("slug").takeIf { it.isNotBlank() }?.let { "/dizi/$it" }.orEmpty()
            }
            val fullUrl = fixUrlNull(href) ?: continue
            if (title.isBlank()) continue

            out.add(
                newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(item.optString("poster"))
                }
            )
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val doc = Jsoup.parse(html)

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val title = rawTitle
            .substringBefore(" Full HD")
            .substringBefore(" İzle")
            .substringBefore(" izle")
            .substringBefore(" | ")
            .substringBefore(" — ")
            .trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val slug = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
        val unescaped = html.replace("\\\"", "\"").replace("\\\\", "\\")

        var totalEps = numEpsRegex.findAll(unescaped)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()
            ?: epCountRegex.find(unescaped)?.groupValues?.get(1)?.toIntOrNull()

        if (totalEps == null || totalEps <= 0) {
            val watchHtml = runCatching { app.get("$mainUrl/izle/$slug").text }.getOrNull().orEmpty()
                .replace("\\\"", "\"")
            totalEps = numEpsRegex.findAll(watchHtml)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull()
        }

        val count = (totalEps ?: 1).coerceAtLeast(1)
        val episodes = (1..count).map { ep ->
            newEpisode("$mainUrl/izle/$slug?s=1&e=$ep") {
                this.name    = "1. Sezon $ep. Bölüm"
                this.season  = 1
                this.episode = ep
            }
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
        val watchHtml = try {
            app.get(data).text
        } catch (t: Throwable) {
            Log.e("DramaDizilerim", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val unescaped = watchHtml.replace("\\\"", "\"").replace("\\\\", "\\")
        val episodeId = episodeIdRegex.find(unescaped)?.groupValues?.get(1)
        if (episodeId.isNullOrBlank()) {
            Log.e("DramaDizilerim", "episodeId bulunamadı: $data")
            return false
        }

        val pbUrl = "$mainUrl/api/playback?episodeId=${URLEncoder.encode(episodeId, "UTF-8")}"
        val reqHeaders = mapOf("Referer" to data)
        val pbText = try {
            app.get(pbUrl, headers = reqHeaders).text
        } catch (t: Throwable) {
            Log.e("DramaDizilerim", "Playback API hatası: ${t.message}")
            return false
        }

        val pbJson = runCatching { JSONObject(pbText) }.getOrNull() ?: return false
        if (!pbJson.optBoolean("success", false)) return false

        val captions = pbJson.optJSONArray("captions")
        if (captions != null) {
            val seenSubs = mutableSetOf<String>()
            for (i in 0 until captions.length()) {
                val cap = captions.optJSONObject(i) ?: continue
                val subUrl = fixUrlNull(cap.optString("url")) ?: continue
                if (!seenSubs.add(subUrl)) continue
                val lang = cap.optString("label").ifBlank { cap.optString("language") }.ifBlank { "TR" }
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }
        }

        val rawStreamUrl = pbJson.optString("url").trim()
        val streamUrl = fixUrlNull(rawStreamUrl) ?: return false
        val kind = pbJson.optString("kind").lowercase()

        if (kind == "mp4" || streamUrl.endsWith(".mp4", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = streamUrl,
                    type   = ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.headers = reqHeaders
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        val playlist = runCatching { app.get(streamUrl, headers = reqHeaders).text }.getOrNull().orEmpty()
        if (playlist.contains("#EXT-X-MEDIA:") && playlist.contains("TYPE=AUDIO", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = streamUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.headers = reqHeaders
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        val links = runCatching {
            M3u8Helper.generateM3u8(this.name, streamUrl, data, headers = reqHeaders)
        }.getOrNull().orEmpty()

        if (links.isNotEmpty()) {
            links.forEach(callback)
            return true
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = streamUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = data
                this.headers = reqHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
