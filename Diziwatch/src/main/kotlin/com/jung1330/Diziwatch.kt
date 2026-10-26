// ! StreamTest - Diziwatch Eklentisi | Jung1330 (AI Destekli Olarak Geliştirilmiştir)

package com.jung1330

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Diziwatch : MainAPI() {
    override var mainUrl              = "https://diziwatch.org"
    override var name                 = "Diziwatch"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/anime-arsivi?sayfa=" to "Anime Arşivi",
        "${mainUrl}/yeni-bolumler"       to "Yeni Bölümler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?sayfa=")) "${request.data}$page" else request.data
        val doc = app.get(url).document
        val home = mutableListOf<SearchResponse>()

        doc.select("a[href^='/dizi/']").forEach { a ->
            val img = a.selectFirst("img")
            val title = img?.attr("alt")?.trim() ?: a.text().trim()
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val poster = fixUrlNull(img?.attr("src"))

            if (title.isNotEmpty() && !href.contains("/sezon-")) {
                home.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}/api/ara?q=${query.trim()}"
        val results = app.get(url).parsedSafe<List<DiziwatchSearchResult>>() ?: return emptyList()

        return results.map { item ->
            newAnimeSearchResponse(item.title, "${mainUrl}/dizi/${item.slug}", TvType.Anime) {
                this.posterUrl = fixUrlNull(item.poster)
                this.year = item.year
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val year = doc.selectFirst("span.info:contains(Yapım Yılı) + span")?.text()?.trim()?.toIntOrNull()
        val tags = doc.selectFirst("span.info:contains(Tür) + span")?.text()?.split(",")?.map { it.trim() } ?: emptyList()

        val episodeLinks = doc.select("a[href*='/sezon-'][href*='/bolum-']")
        val episodes = mutableListOf<Episode>()

        for (a in episodeLinks) {
            val href = fixUrlNull(a.attr("href")) ?: continue
            val seasonMatch = Regex("""sezon-(\d+)""").find(href)
            val episodeMatch = Regex("""bolum-(\d+)""").find(href)

            val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            episodes.add(newEpisode(href) {
                this.name = "${season}. Sezon ${episode}. Bölüm"
                this.season = season
                this.episode = episode
            })
        }

        val distinctEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.Anime, distinctEpisodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("Diziwatch", "loadLinks data » $data")
        val html = app.get(data).text

        val foundUrls = mutableSetOf<String>()

        // 1. Escaped JSON formatındaki label/url çiftlerini yakala
        val escapedMatches = Regex("""label\\":\\"([^\\"]+)\\",\\"url\\":\\"([^\\"]+)\\"""").findAll(html)
        for (match in escapedMatches) {
            val rawUrl = match.groupValues[2].replace("\\/", "/")
            if (rawUrl.startsWith("http")) foundUrls.add(rawUrl)
        }

        // 2. Standart JSON formatındaki label/url çiftlerini yakala
        val unescapedMatches = Regex("""(?:label|url)"\s*:\s*"([^"]+)""").findAll(html)
        for (match in unescapedMatches) {
            val valStr = match.groupValues[1].replace("\\/", "/")
            if (valStr.startsWith("http")) foundUrls.add(valStr)
        }

        // 3. Bilinen video oynatıcı sağlayıcılarını metin içinden doğrudan tara (Fallback)
        val providerMatches = Regex("""https://(?:ok\.ru|my\.mail\.ru|video\.sibnet\.ru|voe\.sx|vidmoly\.[a-z]+|player\.abyssplayer\.com|anizmplayer\.com)/[^\s"'\\]+""").findAll(html)
        for (match in providerMatches) {
            val cleanUrl = match.value.replace("\\/", "/")
            foundUrls.add(cleanUrl)
        }

        // 4. Sayfadaki iframe etiketlerini tara
        val iframeMatches = Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html)
        for (iframe in iframeMatches) {
            val cleanIframe = iframe.groupValues[1].replace("\\/", "/")
            if (cleanIframe.startsWith("http")) foundUrls.add(cleanIframe)
        }

        for (videoUrl in foundUrls) {
            Log.d("Diziwatch", "Oynatıcı bulundu » $videoUrl")
            loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
        }

        return foundUrls.isNotEmpty()
    }

    data class DiziwatchSearchResult(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )
}
