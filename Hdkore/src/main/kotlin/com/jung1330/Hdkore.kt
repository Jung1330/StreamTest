// ! StreamTest - HDKore Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class Hdkore : MainAPI() {
    override var mainUrl              = "https://hdkore1.com"
    override var name                 = "HDKore"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/"          to "Son Eklenenler",
        "$mainUrl/dramaizle" to "Tüm Diziler"
    )

    private val epRegex = Regex("""-(\d+)-bolum""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val out = mutableListOf<SearchResponse>()

        doc.select("div.iq-card[data-movie-data]").forEach { el ->
            val obj = runCatching { JSONObject(el.attr("data-movie-data")) }.getOrNull() ?: return@forEach

            val title = obj.optString("name").trim()
            val slug  = obj.optString("slug").trim()
            val type  = obj.optString("type").trim()
            if (title.isBlank() || slug.isBlank()) return@forEach

            val path = if (type == "movie") "/film/$slug" else "/dizi/$slug"

            out.add(newTvSeriesSearchResponse(title, "$mainUrl$path", TvType.TvSeries) {
                this.posterUrl = fixUrlNull(obj.optString("poster_image"))
            })
        }

        return newHomePageResponse(request.name, out.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Sitenin arama sonuçları istemci tarafında üretiliyor; DuckDuckGo site araması kullanılıyor.
        val q = URLEncoder.encode("site:hdkore1.com $query", "UTF-8")
        val doc = runCatching { app.get("https://html.duckduckgo.com/html/?q=$q").document }.getOrNull()
            ?: return emptyList()

        val prefix = "$mainUrl/dizi/"

        return doc.select("a.result__a").mapNotNull { a ->
            val raw = a.attr("href")
            val url = if (raw.contains("uddg=")) {
                runCatching { URLDecoder.decode(raw.substringAfter("uddg=").substringBefore("&"), "UTF-8") }.getOrNull()
            } else raw

            if (url == null || !url.startsWith(prefix)) return@mapNotNull null
            if (url.removePrefix(prefix).contains("/")) return@mapNotNull null

            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null

            newTvSeriesSearchResponse(title, url, TvType.TvSeries)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" | ")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot   = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        var episodes = doc.select("a[href*='/bolum/']").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val ep   = epRegex.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val name = a.text().trim().ifBlank { "$ep. Bölüm" }

            newEpisode(href) {
                this.name    = name
                this.season  = 1
                this.episode = ep
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        if (episodes.isEmpty() && url.contains("-bolum")) {
            episodes = listOf(newEpisode(url) {
                this.name    = title
                this.season  = 1
                this.episode = epRegex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = try {
            app.get(data).document
        } catch (t: Throwable) {
            Log.e("HDKore", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val tokenEl = doc.selectFirst("[data-api-pt]")
        val pt      = tokenEl?.attr("data-api-pt").orEmpty()
        val entId   = tokenEl?.attr("data-entertainment-id").orEmpty()
            .ifBlank { doc.selectFirst("[data-entertainment-id]")?.attr("data-entertainment-id").orEmpty() }

        if (pt.isBlank() || entId.isBlank()) {
            Log.e("HDKore", "Oynatıcı token'ı bulunamadı: $data")
            return false
        }

        val episode = epRegex.find(data)?.groupValues?.get(1) ?: "1"
        val apiUrl  = "$mainUrl/api/api-player/episode-url/$entId/$episode?pt=${URLEncoder.encode(pt, "UTF-8")}"

        fun fetchApi(url: String): String? = try {
            app.get(
                url,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to data
                )
            ).text
        } catch (t: Throwable) {
            Log.e("HDKore", "Oynatıcı API hatası: ${t.message}")
            null
        }

        // İlk deneme başarısızsa sitenin "fail kilidi"ni atlayarak bir kez daha dene
        val json = fetchApi(apiUrl)?.takeIf { it.contains("video_url") }
            ?: fetchApi("$apiUrl&force=1")

        val obj = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val videoUrl = obj?.optString("video_url").orEmpty()
        if (videoUrl.isBlank()) {
            Log.e("HDKore", "Video adresi alınamadı: ${json?.take(200)}")
            return false
        }

        val videoType = obj?.optString("video_type").orEmpty()
        val subtitle  = obj?.optString("subtitle_url").orEmpty()

        if (subtitle.isNotBlank()) {
            subtitleCallback.invoke(SubtitleFile("tr", fixUrl(subtitle)))
        }

        val quality = Regex("""[?&]br=(\d+)""").find(videoUrl)?.groupValues?.get(1)?.toIntOrNull()

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = videoUrl,
                type   = if (videoType == "mp4") ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = quality ?: Qualities.Unknown.value
            }
        )

        return true
    }
}
