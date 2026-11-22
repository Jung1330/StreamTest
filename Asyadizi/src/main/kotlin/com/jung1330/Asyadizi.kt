// ! StreamTest - AsyaDizi Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class Asyadizi : MainAPI() {
    override var mainUrl              = "https://asyadizi.com"
    override var name                 = "AsyaDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-arsivi/"       to "Dizi Arşivi",
        "$mainUrl/cin-dizisi-izle/"   to "Çin Dizileri",
        "$mainUrl/bl-dizileri-izle/"  to "BL Dizileri",
        "$mainUrl/anime-izle/"        to "Animeler",
        "$mainUrl/filmler-arsivi/"    to "Asya Filmleri"
    )

    private val seasonRegex = Regex("""(\d+)-sezon""", RegexOption.IGNORE_CASE)
    private val epNumRegex  = Regex("""-(\d+)-bolum""", RegexOption.IGNORE_CASE)

    private fun cleanTitle(raw: String): String {
        return raw
            .substringBefore(" Türkçe Altyazılı")
            .substringBefore(" izle")
            .substringBefore(" İzle")
            .substringBefore(" - Asya")
            .substringBefore(" | ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', ':')
    }

    private fun parseItems(doc: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()

        doc.select("div.single-item, div#list-series").forEach { item ->
            val linkEl = item.selectFirst(".categorytitle a[href], #categorytitle a[href], .cat-img a[href], a[href]")
                ?: return@forEach
            val href = fixUrlNull(linkEl.attr("href")) ?: return@forEach
            if (!href.startsWith("$mainUrl/") || !seen.add(href)) return@forEach

            val rawTitle = item.selectFirst(".categorytitle a, #categorytitle a")?.text()?.trim().orEmpty()
                .ifBlank { linkEl.attr("title").trim() }
                .ifBlank { item.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            val title = cleanTitle(rawTitle)
            if (title.isBlank()) return@forEach

            val img = item.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
                ?.takeIf { !it.startsWith("data:") }

            val isMovie = href.contains("/film/", ignoreCase = true) || (!href.contains("/dizi/") && doc.location().contains("/filmler-arsivi"))
            if (isMovie) {
                out.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                )
            } else {
                out.add(
                    newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        val items = parseItems(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            app.get("$mainUrl/?s=$encoded").document
        } catch (t: Throwable) {
            Log.e("AsyaDizi", "Arama hatası: ${t.message}")
            return emptyList()
        }
        return parseItems(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".cat-img img, .dizi-afis img, img[src*='/uploads/']")?.attr("src")
        )
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val episodes = doc.select("a[href*='-bolum']").mapNotNull { a ->
            val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!epHref.startsWith("$mainUrl/")) return@mapNotNull null
            val s = seasonRegex.find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val e = epNumRegex.find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null

            newEpisode(epHref) {
                this.name    = "$s. Sezon $e. Bölüm"
                this.season  = s
                this.episode = e
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        if (episodes.isEmpty() && !url.contains("/dizi/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    private fun resolveUrl(baseUrl: String, target: String): String {
        val clean = target.replace("\\/", "/").trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        if (clean.startsWith("//")) return "https:$clean"
        return runCatching { URI(baseUrl).resolve(clean).toString() }.getOrElse {
            if (clean.startsWith("/")) "$mainUrl$clean" else "$mainUrl/$clean"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mainDoc = try {
            app.get(data).document
        } catch (t: Throwable) {
            Log.e("AsyaDizi", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val pages = linkedSetOf(data)
        val prefix = data.trimEnd('/') + "/"
        mainDoc.select("#alt a[href], .part-links a[href], .video-links a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (href.startsWith(prefix)) pages.add(href)
        }

        val seenIframes = LinkedHashSet<String>()
        var emitted = false

        for (pageUrl in pages) {
            val doc = if (pageUrl == data) mainDoc else runCatching { app.get(pageUrl, referer = data).document }.getOrNull() ?: continue

            doc.select("iframe[vdo-src], iframe[src], iframe[data-src]").forEach { iframe ->
                val raw = iframe.attr("vdo-src")
                    .ifBlank { iframe.attr("src") }
                    .ifBlank { iframe.attr("data-src") }
                    .trim()
                if (raw.isBlank() || raw.startsWith("javascript:") || raw.startsWith("about:")) return@forEach

                var resolved = resolveUrl(pageUrl, raw)
                if (resolved.contains("odnoklassniki.ru/", ignoreCase = true)) {
                    resolved = resolved.replace("odnoklassniki.ru/", "ok.ru/", ignoreCase = true)
                }
                if (resolved.contains("vidmoly.biz", ignoreCase = true) || resolved.contains("vidmoly.org", ignoreCase = true)) {
                    resolved = resolved.replace(Regex("""vidmoly\.(?:biz|org)""", RegexOption.IGNORE_CASE), "vidmoly.to")
                }
                if (resolved.contains("youtube.com", true) || resolved.contains("vimeo.com/video/'+", true) || resolved.contains("vzaar.com/'+", true)) {
                    return@forEach
                }

                if (!seenIframes.add(resolved)) return@forEach
                if (loadExtractor(resolved, "$mainUrl/", subtitleCallback, callback)) {
                    emitted = true
                }
            }
        }

        return emitted
    }
}
