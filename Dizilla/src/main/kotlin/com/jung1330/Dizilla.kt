// ! StreamTest - Dizilla Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

class Dizilla : MainAPI() {
    override var mainUrl              = "https://dizilla.now"
    override var name                 = "Dizilla"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler"
    )

    private val seriesHref   = Regex("""^/dizi/([a-z0-9\-]+)$""")
    private val episodeUrl   = Regex("""https://dizilla\.now/[a-z0-9\-]+?-(\d+)-sezon-(\d+)-bolum""")
    private val reserved     = setOf("dizi", "tum-bolumler", "arama", "giris", "kayit")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val out = mutableListOf<SearchResponse>()

        doc.select("a[href^='/dizi/']").forEach { a ->
            val href = a.attr("href").trim()
            val slug = seriesHref.find(href)?.groupValues?.get(1) ?: return@forEach
            if (slug in reserved) return@forEach

            val label = a.attr("aria-label").substringBefore(",").trim()
            val title = label.ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }

            val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))

            out.add(newTvSeriesSearchResponse(title, "$mainUrl/dizi/$slug", TvType.TvSeries) {
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
            Log.e("Dizilla", "Arama hatası: ${t.message}")
            return emptyList()
        }

        val out = mutableListOf<SearchResponse>()
        doc.select("a[href^='/dizi/']").forEach { a ->
            val href = a.attr("href").trim()
            val slug = seriesHref.find(href)?.groupValues?.get(1) ?: return@forEach
            if (slug in reserved) return@forEach

            val label = a.attr("aria-label").substringBefore(",").trim()
            val title = label.ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }

            out.add(newTvSeriesSearchResponse(title, "$mainUrl/dizi/$slug", TvType.TvSeries) {
                this.posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
            })
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text

        val title = runCatching {
            val doc = org.jsoup.Jsoup.parse(html)
            (doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?: doc.selectFirst("h1")?.text())
                ?.substringBefore(" izle")?.substringBefore(" İzle")?.substringBefore(" | ")?.trim()
        }.getOrNull() ?: return null

        val doc     = org.jsoup.Jsoup.parse(html)
        val poster  = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot    = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val episodes = episodeUrl.findAll(html).mapNotNull { m ->
            val season  = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val episode = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null

            newEpisode(m.value) {
                this.name    = "$season. Sezon $episode. Bölüm"
                this.season  = season
                this.episode = episode
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    /** Pichive oynatıcısındaki NP_CONFIG içinden HLS master adresini çıkarır. */
    private fun extractConfig(html: String): JSONObject? {
        val marker = "NP_CONFIG"
        val idx    = html.indexOf(marker)
        if (idx < 0) return null

        val start = html.indexOf('{', idx)
        if (start < 0) return null

        var depth = 0
        var inStr = false
        var esc   = false
        var end   = -1

        for (i in start until html.length) {
            val ch = html[i]
            when {
                esc -> esc = false
                ch == '\\' && inStr -> esc = true
                ch == '"' -> inStr = !inStr
                !inStr && ch == '{' -> depth++
                !inStr && ch == '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end < 0) return null

        return runCatching { JSONObject(html.substring(start, end + 1)) }.getOrNull()
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val pageHtml = try {
            app.get(data).text
        } catch (t: Throwable) {
            Log.e("Dizilla", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val config = extractConfig(pageHtml)
        if (config == null) {
            Log.e("Dizilla", "NP_CONFIG bulunamadı: $data")
            return false
        }

        val sources = config.optJSONArray("sources") ?: return false
        var emitted = false

        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val master = source.optString("master").trim()
            if (master.isBlank()) continue

            val links = try {
                M3u8Helper.generateM3u8(this.name, master, "$mainUrl/")
            } catch (t: Throwable) {
                emptyList()
            }

            if (links.isNotEmpty()) {
                links.forEach { callback.invoke(it) }
                emitted = true
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = master,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted = true
            }
        }

        if (!emitted) Log.e("Dizilla", "Yayın kaynağı bulunamadı: $data")
        return emitted
    }
}
