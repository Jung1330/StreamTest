// ! StreamTest - Diziyou Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class Diziyou : MainAPI() {
    override var mainUrl              = "https://www.diziyou.one"
    override var name                 = "Diziyou"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/"             to "Son Eklenenler",
        "$mainUrl/dizi-arsivi/" to "Dizi Arşivi"
    )

    private val seriesRegex  = Regex("""^https://www\.diziyou\.one/([a-z0-9\-]+)/$""")
    private val reserved     = setOf(
        "dizi-arsivi", "uye-ol", "iletisim", "gizlilik", "dmca", "hakkimizda",
        "page", "category", "tag", "feed", "wp-login"
    )
    private val seasonRegex  = Regex("""(\d+)-sezon-(\d+)-bolum""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        val out = mutableListOf<SearchResponse>()

        doc.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val slug = seriesRegex.find(href)?.groupValues?.get(1) ?: return@forEach
            if (slug in reserved) return@forEach

            val img   = a.selectFirst("img") ?: return@forEach
            val title = img.attr("alt").trim().ifBlank { a.attr("title").trim() }
            if (title.isBlank()) return@forEach

            val poster = fixUrlNull(img.attr("src")) ?: fixUrlNull(img.attr("data-src"))

            out.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }

        return newHomePageResponse(request.name, out.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            app.get("$mainUrl/?s=$encoded").document
        } catch (t: Throwable) {
            Log.e("Diziyou", "Arama hatası: ${t.message}")
            return emptyList()
        }

        val out = mutableListOf<SearchResponse>()
        doc.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val slug = seriesRegex.find(href)?.groupValues?.get(1) ?: return@forEach
            if (slug in reserved) return@forEach

            val img   = a.selectFirst("img") ?: return@forEach
            val title = img.attr("alt").trim().ifBlank { a.attr("title").trim() }
            if (title.isBlank()) return@forEach

            out.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(img.attr("src"))
            })
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val title = rawTitle
            .substringBefore(" izle")
            .substringBefore(" İzle")
            .substringBefore(" | ")
            .trim()

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot   = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val episodes = doc.select("a[href*='-sezon-'][href*='-bolum']").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val m    = seasonRegex.find(href) ?: return@mapNotNull null
            val s    = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val e    = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null

            newEpisode(href) {
                this.name    = "$s. Sezon $e. Bölüm"
                this.season  = s
                this.episode = e
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val page = try {
            app.get(data).document
        } catch (t: Throwable) {
            Log.e("Diziyou", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val iframe = page.selectFirst("iframe[src]")?.attr("src") ?: return false
        val playerUrl = fixUrl(iframe)

        val playerHtml = try {
            app.get(playerUrl, headers = mapOf("Referer" to data)).text
        } catch (t: Throwable) {
            Log.e("Diziyou", "Oynatıcı açılamadı: ${t.message}")
            return false
        }

        val streamUrl = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""").find(playerHtml)?.value
            ?: return false

        val links = try {
            M3u8Helper.generateM3u8(this.name, streamUrl, playerUrl)
        } catch (t: Throwable) {
            emptyList()
        }

        if (links.isNotEmpty()) {
            links.forEach { callback.invoke(it) }
            return true
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = streamUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
