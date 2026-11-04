// ! StreamTest - Diziyou Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    private val reserved = setOf(
        "dizi-arsivi", "uye-ol", "iletisim", "gizlilik", "dmca", "hakkimizda",
        "page", "category", "tag", "feed", "wp-login"
    )
    private val seasonRegex = Regex("""(\d+)-sezon-(\d+)-bolum""")
    private val epSlugRegex = Regex("""^([a-z0-9\-]+?)-\d+-sezon-\d+-bolum$""")

    private fun extractValidPoster(scope: Element?): String? {
        if (scope == null) return null
        for (img in scope.select("img")) {
            val raw = img.attr("data-src").ifBlank { img.attr("src") }.trim()
            if (raw.isBlank() || raw.startsWith("data:") || raw.contains("/themes/")) continue
            return fixUrlNull(raw)
        }
        return null
    }

    private fun parseSeriesItems(doc: Document): List<SearchResponse> {
        val byUrl = linkedMapOf<String, SearchResponse>()

        // 1) Dizi Arşivi ve Arama kartları (.single-item)
        doc.select("div.single-item").forEach { item ->
            val linkEl = item.selectFirst("#categorytitle a[href], .cat-img a[href], a[href]") ?: return@forEach
            val href = fixUrlNull(linkEl.attr("href")) ?: return@forEach
            if (!href.startsWith("$mainUrl/")) return@forEach

            val path = href.removePrefix("$mainUrl/").trimEnd('/')
            val slug = epSlugRegex.find(path)?.groupValues?.get(1) ?: path
            if (slug.isBlank() || slug in reserved || slug.contains("/")) return@forEach

            val title = item.selectFirst("#categorytitle a")?.text()?.trim().orEmpty()
                .ifBlank { linkEl.attr("title").substringBefore(" izle").trim() }
                .ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }

            val poster = extractValidPoster(item)
            val seriesUrl = "$mainUrl/$slug/"

            byUrl[seriesUrl] = newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

        // 2) Ana sayfa son bölümler ve diğer bağlantılar
        doc.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!href.startsWith("$mainUrl/")) return@forEach

            val path = href.removePrefix("$mainUrl/").trimEnd('/')
            if (!path.matches(Regex("""^[a-z0-9\-]+$"""))) return@forEach

            val slug = epSlugRegex.find(path)?.groupValues?.get(1) ?: path
            if (slug in reserved) return@forEach

            val seriesUrl = "$mainUrl/$slug/"
            val existing = byUrl[seriesUrl]
            val poster = extractValidPoster(a) ?: extractValidPoster(a.parent())
            if (existing != null && existing.posterUrl != null) return@forEach

            val rawTitle = a.attr("title").substringBefore(" izle").trim()
                .replace(Regex("""\s+\d+\.\s*Sezon\s+\d+\.\s*B[öo]l[üu]m.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { existing?.name.orEmpty() }
                .ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }

            byUrl[seriesUrl] = newTvSeriesSearchResponse(rawTitle, seriesUrl, TvType.TvSeries) {
                this.posterUrl = poster ?: existing?.posterUrl
            }
        }

        return byUrl.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        return newHomePageResponse(request.name, parseSeriesItems(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            app.get("$mainUrl/?s=$encoded").document
        } catch (t: Throwable) {
            Log.e("Diziyou", "Arama hatası: ${t.message}")
            return emptyList()
        }
        return parseSeriesItems(doc)
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
            ?: extractValidPoster(doc)
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

        val reqHeaders = mapOf("Referer" to playerUrl)
        val links = try {
            M3u8Helper.generateM3u8(this.name, streamUrl, playerUrl, headers = reqHeaders)
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
                this.headers = reqHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
