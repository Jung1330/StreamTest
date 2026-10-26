// ! StreamTest - TRAnimeİzle Eklentisi | Jung1330 (AI Destekli Olarak Geliştirilmiştir)

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TRAnimeIzle : MainAPI() {
    override var mainUrl              = "https://www.tranimeizle.live"
    override var name                 = "TRAnimeİzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val home = mutableListOf<SearchResponse>()

        doc.select("div.flx-block, div.col-news").forEach { el ->
            val a = el.selectFirst("a.news-image") ?: el.selectFirst("a") ?: return@forEach
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val title = el.selectFirst("div.bar h4")?.text()?.trim()
                ?: el.selectFirst("h4")?.text()?.trim()
                ?: a.attr("title").trim()
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            if (title.isNotEmpty()) {
                home.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}/arama/${query.trim()}"
        val doc = app.get(url).document

        val results = mutableListOf<SearchResponse>()
        doc.select("div.flx-block").forEach { el ->
            val href = fixUrlNull(el.attr("data-href"))
                ?: fixUrlNull(el.selectFirst("a.news-image")?.attr("href"))
                ?: return@forEach
            val title = el.selectFirst("div.bar h4")?.text()?.trim()
                ?: el.selectFirst("img")?.attr("alt")?.trim()
                ?: ""
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            if (title.isNotEmpty()) {
                results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" İzle")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(doc.selectFirst("div.anime-poster img")?.attr("src"))

        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val episodes = mutableListOf<Episode>()
        val episodeLinks = doc.select("a[href*='-bolum-izle']")

        for (a in episodeLinks) {
            val epHref = fixUrlNull(a.attr("href")) ?: continue
            val epTitle = a.text().trim().ifEmpty { a.attr("title").trim() }
            val epNum = Regex("""(\d+)\.\s*Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            episodes.add(newEpisode(epHref) {
                this.name = epTitle.ifEmpty { "${epNum}. Bölüm" }
                this.season = 1
                this.episode = epNum
            })
        }

        // Eğer tekil bölüm sayfası ise
        if (episodes.isEmpty() && url.contains("-bolum-izle")) {
            val epNum = Regex("""(\d+)\.\s*Bölüm""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            episodes.add(newEpisode(url) {
                this.name = title
                this.season = 1
                this.episode = epNum
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("TRAnimeIzle", "loadLinks data » $data")
        val doc = app.get(data).document
        val iframes = doc.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }

        var found = false
        for (iframe in iframes) {
            if (!iframe.contains("googletagmanager") && !iframe.contains("ads")) {
                Log.d("TRAnimeIzle", "Iframe bulundu » $iframe")
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
                found = true
            }
        }

        // VideoJS ya da direct video tagleri
        val videoSrc = fixUrlNull(doc.selectFirst("video source")?.attr("src"))
        if (videoSrc != null) {
            callback.invoke(
                ExtractorLink(
                    source  = this.name,
                    name    = this.name,
                    url     = videoSrc,
                    referer = "${mainUrl}/",
                    quality = Qualities.P1080.value
                )
            )
            found = true
        }

        return found
    }
}
