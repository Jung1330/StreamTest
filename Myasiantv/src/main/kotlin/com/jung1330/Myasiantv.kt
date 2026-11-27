// ! StreamTest - MyAsianTV (EN) Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class Myasiantv : MainAPI() {
    override var mainUrl              = "https://ww7.myasiantv.rest"
    private val initialMainUrl = mainUrl
    override var name                 = "MyAsianTV (EN)"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/drama-10/" to "Asian Dramas (Eng Sub)",
        "$mainUrl/movies-2/" to "Asian Movies (Eng Sub)",
        "$mainUrl/"          to "Recent Episodes"
    )

    private val epNumRegex    = Regex("""-ep-(\d+)""", RegexOption.IGNORE_CASE)
    private val packedRegex   = Regex("""(?s)eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\s*\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\)\s*(?:,\s*0\s*,\s*\{\s*\}\s*)?\)\)""")
    private val m3u8FileRegex = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val trackObjRegex = Regex("""\{[^{}]*?["']?file["']?\s*:\s*["']([^"']+\.(?:vtt|srt)(?:\?[^"']*)?)["'][^{}]*\}""", RegexOption.IGNORE_CASE)

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s+Episode\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(\d{4}\)"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseItems(doc: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()

        doc.select("ul.items > li").forEach { li ->
            val linkEl = li.selectFirst("h2 a[href], a[href]") ?: return@forEach
            val href = fixUrlNull(linkEl.attr("href")) ?: return@forEach
            if (!seen.add(href)) return@forEach

            val rawTitle = li.selectFirst("h2 a")?.text()?.trim().orEmpty()
                .ifBlank { linkEl.attr("title").trim() }
                .ifBlank { li.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val title = cleanTitle(rawTitle)
            if (title.isBlank()) return@forEach

            val img = li.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
            val isMovie = !href.contains("/series/") && (doc.location().contains("/movies-2") || href.contains("-movie"))

            if (isMovie) {
                out.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.year      = year
                    }
                )
            } else {
                out.add(
                    newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                        this.posterUrl = poster
                        this.year      = year
                    }
                )
            }
        }

        if (out.isEmpty()) {
            doc.select("div.row h3 a[href]").forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                if (!seen.add(href)) return@forEach
                val rawTitle = a.text().trim()
                val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val title = cleanTitle(rawTitle)
                if (title.isBlank()) return@forEach

                out.add(
                    newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                        this.year = year
                    }
                )
            }
        }

        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DomainConfig.sync(this, "Myasiantv")
        val request = request.copy(data = DomainConfig.rewrite(request.data, initialMainUrl, mainUrl))
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        val items = parseItems(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        DomainConfig.sync(this, "Myasiantv")
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            app.get("$mainUrl/?s=$encoded").document
        } catch (t: Throwable) {
            Log.e("MyAsianTV", "Search error: ${t.message}")
            return emptyList()
        }
        return parseItems(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        DomainConfig.sync(this, "Myasiantv")
        val url = DomainConfig.rewrite(url, initialMainUrl, mainUrl)
        var doc = app.get(url).document
        var targetUrl = url

        if (!url.contains("/series/")) {
            val seriesLink = doc.selectFirst("a[href*='/series/']")?.attr("href")?.let { fixUrlNull(it) }
            if (seriesLink != null) {
                val seriesDoc = runCatching { app.get(seriesLink).document }.getOrNull()
                if (seriesDoc != null) {
                    doc = seriesDoc
                    targetUrl = seriesLink
                }
            }
        }

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            doc.selectFirst("div.movie img, div.left img, img.poster")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val plot = doc.selectFirst("div.info p, div.description, meta[name='description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val episodes = doc.select("ul.list-episode li a[href], a[href*='-ep-']").mapNotNull { a ->
            val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!epHref.startsWith("$mainUrl/")) return@mapNotNull null
            val epNum = epNumRegex.find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null

            newEpisode(epHref) {
                this.name    = "Episode $epNum"
                this.season  = 1
                this.episode = epNum
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, targetUrl, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
            }
        }

        return newTvSeriesLoadResponse(title, targetUrl, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, target: String): String {
        val clean = target.replace("\\/", "/").trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        if (clean.startsWith("//")) return "https:$clean"
        return runCatching { URI(baseUrl).resolve(clean).toString() }.getOrElse {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value.orEmpty()
            if (clean.startsWith("/")) "$origin$clean" else "${baseUrl.substringBeforeLast("/")}/$clean"
        }
    }

    private suspend fun extractVidmoly(
        embedUrl: String,
        referer: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = runCatching {
            app.get(embedUrl, headers = mapOf("Referer" to referer))
        }.getOrNull() ?: return false

        val finalUrl = res.url.ifBlank { embedUrl }
        val candidates = buildList {
            add(res.text)
            packedRegex.findAll(res.text).forEach { block ->
                runCatching { JsUnpacker(block.value).unpack() }.getOrNull()?.let { add(it) }
            }
        }

        for (src in candidates) {
            trackObjRegex.findAll(src).forEach { m ->
                val obj = m.value
                if (obj.contains("thumbnails", ignoreCase = true)) return@forEach
                val subUrl = resolveRelativeUrl(finalUrl, m.groupValues[1])
                if (!seenSubs.add(subUrl)) return@forEach
                val lbl = Regex("""["']?label["']?\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1) ?: "English"
                subtitleCallback.invoke(SubtitleFile(lbl, subUrl))
            }
        }

        val m3u8 = candidates.firstNotNullOfOrNull { src ->
            m3u8FileRegex.find(src)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
        } ?: return false

        val originRef = runCatching {
            val u = URI(finalUrl)
            "${u.scheme}://${u.host}/"
        }.getOrDefault("https://vidmoly.to/")

        val reqHeaders = mapOf("Referer" to originRef, "Origin" to originRef.trimEnd('/'))
        callback.invoke(
            newExtractorLink(
                source = "$name (VidMoly)",
                name   = "$name (VidMoly)",
                url    = m3u8,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = originRef
                this.headers = reqHeaders
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        DomainConfig.sync(this, "Myasiantv")
        val data = DomainConfig.rewrite(data, initialMainUrl, mainUrl)
        val doc = try {
            app.get(data).document
        } catch (t: Throwable) {
            Log.e("MyAsianTV", "Episode page failed: ${t.message}")
            return false
        }

        val serverUrls = LinkedHashSet<String>()

        doc.select("iframe[src], iframe[data-src], [data-video]").forEach { el ->
            val raw = el.attr("data-video").ifBlank { el.attr("src") }.ifBlank { el.attr("data-src") }.trim()
            if (raw.isBlank() || raw.contains("googletagmanager") || raw.contains("youtube.com")) return@forEach
            val fixed = resolveRelativeUrl(data, raw)

            if (fixed.contains("zumivids.com/", ignoreCase = true)) {
                val zumiDoc = runCatching { app.get(fixed, headers = mapOf("Referer" to "$mainUrl/")).document }.getOrNull()
                zumiDoc?.select(".server-item[data-video], #embedvideo[data-src], iframe[src]")?.forEach { srv ->
                    val sUrl = srv.attr("data-video").ifBlank { srv.attr("data-src") }.ifBlank { srv.attr("src") }.trim()
                    if (sUrl.startsWith("http")) serverUrls.add(sUrl)
                }
            } else if (fixed.startsWith("http")) {
                serverUrls.add(fixed)
            }
        }

        val seenSubs = LinkedHashSet<String>()
        var emitted = false

        for (rawUrl in serverUrls) {
            if (rawUrl.contains("vidmoly", ignoreCase = true)) {
                if (extractVidmoly(rawUrl, "https://zumivids.com/", seenSubs, subtitleCallback, callback)) {
                    emitted = true
                    continue
                }
            }
            val normalized = rawUrl
                .replace("vidmoly.org", "vidmoly.to")
                .replace("vidmoly.biz", "vidmoly.to")
                .replace("mixdrop.top", "mixdrop.co")
            if (loadExtractor(normalized, "https://zumivids.com/", subtitleCallback, callback)) {
                emitted = true
            }
        }

        return emitted
    }
}
