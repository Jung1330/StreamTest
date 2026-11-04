// ! StreamTest - KoreanTürk Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Koreanturk : MainAPI() {
    override var mainUrl              = "https://koreanturk.tv"
    override var name                 = "KoreanTürk"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-arsivi/"                    to "Kore Dizileri",
        "$mainUrl/film-arsivi/"                    to "Kore Filmleri",
        "$mainUrl/dizi-kategori/bl-dizileri-izle/" to "BL Dizileri",
        "$mainUrl/yapim/2026-kore-dizileri/"       to "2026 Kore Dizileri",
        "$mainUrl/yapim/2025-kore-dizileri/"       to "2025 Kore Dizileri"
    )

    private val seasonUrlRegex   = Regex("""(\d+)-sezon""", RegexOption.IGNORE_CASE)
    private val epUrlRegex       = Regex("""-(\d+)-bolum""", RegexOption.IGNORE_CASE)
    private val epTextRegex      = Regex("""(\d+)\.\s*B[öo]l[üu]m""", RegexOption.IGNORE_CASE)
    private val videoParamRegex  = Regex("""[?&]video=([^&#]+)""", RegexOption.IGNORE_CASE)
    private val packedRegex      = Regex("""(?s)eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\s*\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\)\s*(?:,\s*0\s*,\s*\{\s*\}\s*)?\)\)""")
    private val m3u8FileRegex    = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val trackObjRegex    = Regex("""\{[^{}]*?["']?file["']?\s*:\s*["']([^"']+\.(?:vtt|srt)(?:\?[^"']*)?)["'][^{}]*\}""", RegexOption.IGNORE_CASE)

    private fun cleanTitle(raw: String): String {
        return raw
            .substringBefore(" Türkçe Altyazılı")
            .substringBefore(" izle")
            .substringBefore(" İzle")
            .substringBefore(" | ")
            .replace(Regex("""\(\d{4}\)"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', ':')
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        doc.select("div.series-box, div.film-box").forEach { box ->
            val a = box.selectFirst(".movie-details .name a[href], .movie-box > a[href], a[href]")
                ?: return@forEach
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (href.contains("/dizi-kategori/") || href.contains("/yapim/") || href.contains("/oyuncu/")) {
                return@forEach
            }

            val rawTitle = box.selectFirst(".movie-details .name a")?.text()?.trim().orEmpty()
                .ifBlank { box.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { a.attr("title").trim() }
                .ifBlank { a.text().trim() }

            val title = cleanTitle(rawTitle)
            if (title.isBlank()) return@forEach

            val img = box.selectFirst(".poster img, img")
            val poster = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
                ?.takeIf { !it.startsWith("data:") }
            val year = box.selectFirst(".year, .release")?.text()?.let { txt ->
                Regex("""\b(19|20)\d{2}\b""").find(txt)?.value?.toIntOrNull()
            }

            if (href.contains("/dizi/") || box.hasClass("series-box")) {
                out.add(
                    newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                        this.posterUrl = poster
                        this.year      = year
                    }
                )
            } else {
                out.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.year      = year
                    }
                )
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(pageUrl).document
        return newHomePageResponse(request.name, parseCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            app.get("$mainUrl/?s=$encoded").document
        } catch (t: Throwable) {
            Log.e("KoreanTürk", "Arama hatası: ${t.message}")
            return emptyList()
        }
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1.film, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".movie-left .poster img, .poster img")
                    ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        )
        val plot = doc.selectFirst(".movies-data .desc, .movies-data p")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val year = doc.selectFirst("li.release a, .release a")?.text()?.trim()?.let { txt ->
            Regex("""\b(19|20)\d{2}\b""").find(txt)?.value?.toIntOrNull()
        }
        val tags = doc.select(".movies-data .category a, .category a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val epAnchors = doc.select("div.ep-box a[href], a[href*='/bolum/']")
        if (url.contains("/dizi/") || epAnchors.isNotEmpty()) {
            val episodes = epAnchors.mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                if (!epHref.contains("/bolum/") && !epHref.contains("-bolum")) return@mapNotNull null

                val s = seasonUrlRegex.find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val e = epUrlRegex.find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: epTextRegex.find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(epHref) {
                    this.name    = "$s. Sezon $e. Bölüm"
                    this.season  = s
                    this.episode = e
                }
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
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

    private suspend fun emitHlsLinks(
        streamUrl: String,
        referer: String,
        linkName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = Regex("""^https?://[^/]+""").find(referer)?.value.orEmpty()
        val reqHeaders = buildMap {
            put("Referer", referer)
            if (origin.isNotEmpty()) put("Origin", origin)
        }

        val generated = runCatching {
            M3u8Helper.generateM3u8(
                source    = this@Koreanturk.name,
                streamUrl = streamUrl,
                referer   = referer,
                headers   = reqHeaders,
                name      = linkName
            )
        }.getOrNull().orEmpty()

        if (generated.isNotEmpty()) {
            generated.forEach(callback)
            return true
        }

        val checkText = runCatching { app.get(streamUrl, headers = reqHeaders, verify = false).text }.getOrNull().orEmpty()
        if (checkText.isNotBlank() && !checkText.contains("#EXTM3U") && !checkText.contains("#EXTINF")) {
            return false
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = linkName,
                url    = streamUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.headers = reqHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private suspend fun extractVidmoly(
        embedUrl: String,
        referer: String,
        linkName: String,
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
                val lbl = Regex("""["']?label["']?\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1) ?: "Türkçe"
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

        return emitHlsLinks(m3u8, originRef, linkName, callback)
    }

    private suspend fun extractHintPlayer(
        iframeUrl: String,
        pageUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val originRef = runCatching {
            val u = URI(iframeUrl)
            "${u.scheme}://${u.host}/"
        }.getOrDefault("https://player.hintdizi.com/")

        var streamPath = videoParamRegex.find(iframeUrl)?.groupValues?.get(1)?.let { enc ->
            runCatching { URLDecoder.decode(enc, "UTF-8").trim() }.getOrNull()
        }

        if (streamPath.isNullOrBlank()) {
            val playerHtml = runCatching {
                app.get(iframeUrl, headers = mapOf("Referer" to pageUrl)).text
            }.getOrNull().orEmpty()
            streamPath = m3u8FileRegex.find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
                ?: Regex("""["'](/[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(playerHtml)?.groupValues?.get(1)?.trim()
        }

        val finalStream = streamPath?.takeIf { it.isNotBlank() }?.let { resolveRelativeUrl(originRef, it) }
            ?: return false

        val linkName = "${this.name} ($label)"
        if (finalStream.endsWith(".mp4", ignoreCase = true)) {
            val reqHeaders = mapOf("Referer" to originRef)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = linkName,
                    url    = finalStream,
                    type   = ExtractorLinkType.VIDEO
                ) {
                    this.referer = originRef
                    this.headers = reqHeaders
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        return emitHlsLinks(finalStream, originRef, linkName, callback)
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
            Log.e("KoreanTürk", "Sayfa açılamadı: ${t.message}")
            return false
        }

        val partPages = linkedMapOf<String, String>()
        partPages[data] = "Ana Oynatıcı"

        val basePrefix = data.trimEnd('/') + "/"
        mainDoc.select(".video-links a[href], .player-control a[href], .part-links a[href], .keremiya_part a[href]")
            .forEachIndexed { idx, a ->
                val partHref = fixUrlNull(a.attr("href")) ?: return@forEachIndexed
                if (!partHref.startsWith(basePrefix) && partHref.trimEnd('/') != data.trimEnd('/')) {
                    return@forEachIndexed
                }
                val normKey = if (partHref.trimEnd('/') == data.trimEnd('/')) data else partHref
                val lbl = a.text().replace(Regex("""\s+"""), " ").trim().ifBlank { "Alternatif ${idx + 1}" }
                if (!partPages.containsKey(normKey)) {
                    partPages[normKey] = lbl
                }
            }

        var anyEmitted = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            anyEmitted = true
            callback.invoke(link)
        }
        val seenSubs = mutableSetOf<String>()
        val seenIframes = mutableSetOf<String>()

        for ((partUrl, partLabel) in partPages) {
            val doc = if (partUrl == data) {
                mainDoc
            } else {
                runCatching { app.get(partUrl, headers = mapOf("Referer" to data)).document }.getOrNull() ?: continue
            }

            doc.select(".video-content iframe, .player-box iframe, .video-box iframe, iframe[src]").forEach { iframe ->
                val rawSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
                if (rawSrc.isBlank() || rawSrc.startsWith("about:")) return@forEach

                var iframeUrl = resolveRelativeUrl(partUrl, rawSrc)
                if (iframeUrl.startsWith("http://ok.ru", ignoreCase = true)) {
                    iframeUrl = "https://" + iframeUrl.removePrefix("http://")
                }

                if (iframeUrl.contains("youtube.com", true) ||
                    iframeUrl.contains("youtu.be", true) ||
                    iframeUrl.contains("facebook.com", true) ||
                    iframeUrl.contains("google.com", true)
                ) {
                    return@forEach
                }

                if (!seenIframes.add(iframeUrl)) return@forEach

                when {
                    iframeUrl.contains("hintdizi.com", ignoreCase = true) || videoParamRegex.containsMatchIn(iframeUrl) -> {
                        extractHintPlayer(iframeUrl, partUrl, partLabel, wrappedCallback)
                    }

                    iframeUrl.contains("vidmoly", ignoreCase = true) -> {
                        if (!extractVidmoly(iframeUrl, "$mainUrl/", "${this.name} ($partLabel)", seenSubs, subtitleCallback, wrappedCallback)) {
                            loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                        }
                    }

                    else -> {
                        loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                    }
                }
            }
        }

        return anyEmitted
    }
}
