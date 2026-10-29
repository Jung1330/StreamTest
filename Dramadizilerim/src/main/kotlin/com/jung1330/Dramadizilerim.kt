// ! StreamTest - Drama Dizilerim Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import java.net.URLEncoder

class Dramadizilerim : MainAPI() {
    override var mainUrl              = "https://dramadizilerim.com"
    override var name                 = "Drama Dizilerim"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi?page=" to "Tüm Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page").document
        val out = mutableListOf<SearchResponse>()

        doc.select("a.video-item-card").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!href.contains("/dizi/")) return@forEach

            val title = a.selectFirst("h3.video-item-title")?.text()?.trim()
                ?: a.attr("title").substringBefore(" İzle").trim()
                ?: return@forEach
            if (title.isBlank()) return@forEach

            val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))

            out.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }

        return newHomePageResponse(request.name, out.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = try {
            app.get(
                "$mainUrl/api/search?q=$encoded",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
        } catch (t: Throwable) {
            Log.e("DramaDizilerim", "Arama hatası: ${t.message}")
            return emptyList()
        }

        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SearchResponse>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            val slug  = item.optString("slug").trim()
            if (title.isBlank() || slug.isBlank()) continue

            out.add(newTvSeriesSearchResponse(title, "$mainUrl/dizi/$slug", TvType.TvSeries) {
                this.posterUrl = fixUrlNull(item.optString("poster"))
                this.year      = item.optInt("year").takeIf { it > 1900 }
            })
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val title = rawTitle
            .substringBefore(" Full HD")
            .substringBefore(" İzle")
            .substringBefore(" izle")
            .substringBefore(" | ")
            .trim()

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot   = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val episodes = doc.select("a.wp-ecard[data-episode]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val season  = a.attr("data-season").toIntOrNull() ?: 1
            val episode = a.attr("data-episode").toIntOrNull() ?: return@mapNotNull null

            val label = a.attr("aria-label").substringBefore(" - ").trim()
            val name  = label.ifBlank { "$season. Sezon $episode. Bölüm" }

            newEpisode(href) {
                this.name    = name
                this.season  = season
                this.episode = episode
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = try {
            app.get(data).document
        } catch (t: Throwable) {
            Log.e("DramaDizilerim", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val iframe = doc.select("iframe[src*=embed]").firstOrNull()?.attr("src")
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: return false

        val embedUrl = fixUrl(iframe).replace("&amp;", "&")

        val embedHtml = try {
            app.get(embedUrl, headers = mapOf("Referer" to data)).text
        } catch (t: Throwable) {
            Log.e("DramaDizilerim", "Oynatıcı açılamadı: ${t.message}")
            return false
        }

        val streamUrl = Regex("""let source\s*=\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""").find(embedHtml)?.value

        if (streamUrl.isNullOrBlank()) {
            Log.e("DramaDizilerim", "Yayın adresi bulunamadı: $data")
            return false
        }

        val links = try {
            M3u8Helper.generateM3u8(this.name, streamUrl, embedUrl)
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
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
