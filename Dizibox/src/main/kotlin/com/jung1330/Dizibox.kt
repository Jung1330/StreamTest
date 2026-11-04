// ! StreamTest - DiziBOX Eklentisi | Jung1330

package com.jung1330

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Dizibox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBOX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-arsivi/"                                       to "Son Eklenen Diziler",
        "$mainUrl/dizi-arsivi/?ulke=&tur=&yil=&imdb=&orderby=imdb"    to "IMDb Puanına Göre",
        "$mainUrl/dizi-arsivi/?tur=aksiyon"                           to "Aksiyon",
        "$mainUrl/dizi-arsivi/?tur=dram"                              to "Dram",
        "$mainUrl/dizi-arsivi/?tur=bilimkurgu"                        to "Bilimkurgu",
        "$mainUrl/dizi-arsivi/?tur=suc"                               to "Suç"
    )

    private val seasonEpUrlRegex  = Regex("""(\d+)-sezon-(\d+)-bolum""", RegexOption.IGNORE_CASE)
    private val seasonEpTextRegex = Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*B[öo]l[üu]m""", RegexOption.IGNORE_CASE)
    private val epOnlyUrlRegex    = Regex("""-(\d+)-bolum""", RegexOption.IGNORE_CASE)
    private val seasonOnlyRegex   = Regex("""(\d+)-sezon""", RegexOption.IGNORE_CASE)
    private val cryptoJsRegex     = Regex("""CryptoJS\.AES\.decrypt\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)""")
    private val jwFileRegex       = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+)["']""")
    private val m3u8FileRegex     = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val trackObjRegex     = Regex("""\{[^{}]*?["']?file["']?\s*:\s*["']([^"']+\.(?:vtt|srt)(?:\?[^"']*)?)["'][^{}]*\}""", RegexOption.IGNORE_CASE)
    private val packedRegex       = Regex("""(?s)eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\s*\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\)\s*(?:,\s*0\s*,\s*\{\s*\}\s*)?\)\)""")
    private val atobUnescapeRegex = Regex("""atob\(\s*unescape\(\s*["']([^"']+)["']\s*\)\s*\)""")
    private val atobDirectRegex   = Regex("""atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")

    private fun dbxCookies(): Map<String, String> =
        mapOf("dbxu" to (System.currentTimeMillis() / 1000).toString())

    private fun buildPageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) {
            val path = base.substringBefore("?").trimEnd('/')
            val query = base.substringAfter("?")
            "$path/page/$page/?$query"
        } else {
            "${base.trimEnd('/')}/page/$page/"
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .substringBefore(" izle")
            .substringBefore(" İzle")
            .substringBefore(" | ")
            .trim()
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        doc.select("article.detailed-article, article.article-series-poster").forEach { article ->
            val linkEl = article.selectFirst("h3 a[href*='/diziler/'], h2 a[href*='/diziler/'], a[href*='/diziler/']")
                ?: return@forEach
            val href = fixUrlNull(linkEl.attr("href")) ?: return@forEach

            val rawTitle = article.selectFirst("h3 a, h2 a")?.text()?.trim().orEmpty()
                .ifBlank { article.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { linkEl.attr("title").trim() }
                .ifBlank { linkEl.text().trim() }

            val title = cleanTitle(rawTitle)
            if (title.isBlank()) return@forEach

            val img = article.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
                ?.takeIf { !it.startsWith("data:") }
            val year = article.selectFirst("a[href*='/yil/'], .year")?.text()?.trim()?.toIntOrNull()

            out.add(
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year      = year
                }
            )
        }

        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildPageUrl(request.data, page)
        val doc = app.get(pageUrl, cookies = dbxCookies()).document
        return newHomePageResponse(request.name, parseCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            app.get("$mainUrl/?s=$encoded", cookies = dbxCookies()).document
        } catch (t: Throwable) {
            Log.e("DiziBOX", "Arama hatası: ${t.message}")
            return emptyList()
        }
        return parseCards(doc)
    }

    private fun parseSeasonEpisodes(doc: Document, fallbackSeason: Int): List<Episode> {
        return doc.select("a.season-episode[href], #episodes-list a[href], div.episodes-list a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (href.contains("/diziler/")) return@mapNotNull null

            val urlMatch  = seasonEpUrlRegex.find(href)
            val textMatch = seasonEpTextRegex.find(a.text())
            val epMatch   = epOnlyUrlRegex.find(href)

            val season = urlMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: textMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: fallbackSeason
            val episode = urlMatch?.groupValues?.get(2)?.toIntOrNull()
                ?: textMatch?.groupValues?.get(2)?.toIntOrNull()
                ?: epMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null

            newEpisode(href) {
                this.name    = "$season. Sezon $episode. Bölüm"
                this.season  = season
                this.episode = episode
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cookies = dbxCookies()
        val doc = app.get(url, cookies = cookies).document

        val rawTitle = doc.selectFirst("h1 a")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            doc.selectFirst("figure img.main-cover")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val plot = doc.selectFirst("div.tv-story")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val year = doc.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val allEpisodes = mutableListOf<Episode>()
        val visitedUrls = mutableSetOf(url.trimEnd('/'))

        allEpisodes.addAll(parseSeasonEpisodes(doc, fallbackSeason = 1))

        val seasonLinks = doc.select("#seasons-list a[href]")
        seasonLinks.forEachIndexed { idx, a ->
            val seasonHref = fixUrlNull(a.attr("href")) ?: return@forEachIndexed
            val norm = seasonHref.trimEnd('/')
            val inferredSeason = seasonOnlyRegex.find(seasonHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: (idx + 1)

            // İlk sezon ana dizi sayfasında zaten parse edildiyse tekrar çekme
            if (!visitedUrls.add(norm) || (inferredSeason == 1 && allEpisodes.any { it.season == 1 })) {
                return@forEachIndexed
            }

            val seasonDoc = runCatching {
                app.get(seasonHref, headers = mapOf("Referer" to url), cookies = cookies).document
            }.getOrNull() ?: return@forEachIndexed

            allEpisodes.addAll(parseSeasonEpisodes(seasonDoc, fallbackSeason = inferredSeason))
        }

        val episodes = allEpisodes
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, target: String): String {
        val cleanTarget = target.replace("\\/", "/").trim()
        if (cleanTarget.startsWith("http://") || cleanTarget.startsWith("https://")) return cleanTarget
        if (cleanTarget.startsWith("//")) return "https:$cleanTarget"
        return runCatching { URI(baseUrl).resolve(cleanTarget).toString() }.getOrElse {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value.orEmpty()
            if (cleanTarget.startsWith("/")) "$origin$cleanTarget" else "${baseUrl.substringBeforeLast("/")}/$cleanTarget"
        }
    }

    /** OpenSSL Salted__ (EVP_BytesToKey MD5) biçimindeki CryptoJS.AES verisini çözer. */
    private fun decryptCryptoJsAes(cipherTextB64: String, passphrase: String): String? {
        return runCatching {
            val raw = Base64.decode(cipherTextB64.trim(), Base64.DEFAULT)
            if (raw.size <= 16) return null
            val header = String(raw.copyOfRange(0, 8), Charsets.US_ASCII)
            if (header != "Salted__") return null

            val salt      = raw.copyOfRange(8, 16)
            val ct        = raw.copyOfRange(16, raw.size)
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            val md        = MessageDigest.getInstance("MD5")

            var derived = ByteArray(0)
            var block   = ByteArray(0)
            while (derived.size < 48) {
                md.reset()
                if (block.isNotEmpty()) md.update(block)
                md.update(passBytes)
                md.update(salt)
                block = md.digest()
                derived += block
            }

            val keySpec = SecretKeySpec(derived.copyOfRange(0, 32), "AES")
            val ivSpec  = IvParameterSpec(derived.copyOfRange(32, 48))
            val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun emitSubtitlesFromSource(
        src: String,
        baseUrl: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        trackObjRegex.findAll(src).forEach { match ->
            val obj = match.value
            if (obj.contains("thumbnails", ignoreCase = true)) return@forEach
            val subUrl = resolveRelativeUrl(baseUrl, match.groupValues[1])
            if (!seenSubs.add(subUrl)) return@forEach

            val label = Regex("""["']?label["']?\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1)
                ?: Regex("""["']?language["']?\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1)
                ?: "Türkçe"
            subtitleCallback.invoke(SubtitleFile(label.replace("\\/", "/").trim().ifBlank { "Türkçe" }, subUrl))
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
                source    = this@Dizibox.name,
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

    private suspend fun extractKingPlayer(
        kingUrl: String,
        mirrorUrl: String,
        label: String,
        cookies: Map<String, String>,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val wrapperDoc = runCatching {
            app.get(kingUrl, headers = mapOf("Referer" to mirrorUrl), cookies = cookies).document
        }.getOrNull() ?: return false

        val innerSrc = wrapperDoc.selectFirst("iframe[src]")?.attr("src")
            ?.let { resolveRelativeUrl(kingUrl, it) }
            ?: return false

        val embedHtml = runCatching {
            app.get(innerSrc, headers = mapOf("Referer" to "$mainUrl/")).text
        }.getOrNull() ?: return false

        val candidateBlobs = buildList {
            cryptoJsRegex.findAll(embedHtml).forEach { m ->
                decryptCryptoJsAes(m.groupValues[1], m.groupValues[2])?.let { add(it) }
            }
            add(embedHtml)
        }

        for (blob in candidateBlobs) {
            emitSubtitlesFromSource(blob, innerSrc, seenSubs, subtitleCallback)
        }

        var streamUrl: String? = null
        for (blob in candidateBlobs) {
            for (m in jwFileRegex.findAll(blob)) {
                val candidate = m.groupValues[1].replace("\\/", "/").trim()
                if (!candidate.endsWith(".vtt", true) && !candidate.endsWith(".srt", true)) {
                    streamUrl = candidate
                    break
                }
            }
            if (streamUrl != null) break
        }

        if (streamUrl == null) {
            val embedId = Regex("""/embed/([a-zA-Z0-9_-]+)""").find(innerSrc)?.groupValues?.get(1)
            val origin = Regex("""^https?://[^/]+""").find(innerSrc)?.value
            if (!embedId.isNullOrBlank() && !origin.isNullOrBlank()) {
                streamUrl = "$origin/embed/sheila/$embedId"
            }
        }

        val finalStream = streamUrl ?: return false
        return emitHlsLinks(finalStream, innerSrc, "${this.name} ($label)", callback)
    }

    private suspend fun extractVidmolyEmbed(
        embedUrl: String,
        referer: String,
        label: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
        val res = runCatching {
            app.get(normalized, headers = mapOf("Referer" to referer))
        }.getOrNull() ?: return false

        val finalEmbedUrl = res.url.ifBlank { normalized }
        val candidates = buildList {
            add(res.text)
            packedRegex.findAll(res.text).forEach { block ->
                runCatching { JsUnpacker(block.value).unpack() }.getOrNull()?.let { add(it) }
            }
        }

        for (src in candidates) {
            emitSubtitlesFromSource(src, finalEmbedUrl, seenSubs, subtitleCallback)
        }

        val m3u8 = candidates.firstNotNullOfOrNull { src ->
            m3u8FileRegex.find(src)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
        } ?: return false

        val originReferer = runCatching {
            val u = URI(finalEmbedUrl)
            "${u.scheme}://${u.host}/"
        }.getOrDefault("https://vidmoly.to/")

        return emitHlsLinks(m3u8, originReferer, "${this.name} ($label)", callback)
    }

    private suspend fun extractMolyWrapper(
        molyUrl: String,
        mirrorUrl: String,
        label: String,
        cookies: Map<String, String>,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = runCatching {
            app.get(molyUrl, headers = mapOf("Referer" to mirrorUrl), cookies = cookies)
        }.getOrNull() ?: return false

        val html = res.text
        val decodedHtml = atobUnescapeRegex.find(html)?.groupValues?.get(1)?.let { enc ->
            runCatching {
                val unescaped = URLDecoder.decode(enc, "UTF-8")
                String(Base64.decode(unescaped, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        } ?: atobDirectRegex.find(html)?.groupValues?.get(1)?.let { b64 ->
            runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        }

        val searchHtml = (decodedHtml ?: "") + "\n" + html
        val innerIframe = org.jsoup.Jsoup.parse(searchHtml).selectFirst("iframe[src]")?.attr("src")
            ?.let { resolveRelativeUrl(molyUrl, it) }
            ?: return false

        if (innerIframe.contains("vidmoly", ignoreCase = true)) {
            if (extractVidmolyEmbed(innerIframe, "$mainUrl/", label, seenSubs, subtitleCallback, callback)) {
                return true
            }
        }
        return loadExtractor(innerIframe, "$mainUrl/", subtitleCallback, callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cookies = dbxCookies()
        val mainDoc = try {
            app.get(data, cookies = cookies).document
        } catch (t: Throwable) {
            Log.e("DiziBOX", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val mirrors = linkedMapOf<String, String>()
        val selectedLabel = mainDoc.selectFirst("select.woca-linkpages-dd option[selected]")
            ?.text()?.trim().orEmpty().ifBlank { "DBX Pro" }
        mirrors[data] = selectedLabel

        mainDoc.select("select.woca-linkpages-dd option[value]").forEach { opt ->
            val optHref = fixUrlNull(opt.attr("value").trim()) ?: return@forEach
            if (!optHref.startsWith("http")) return@forEach
            val lbl = opt.text().trim().ifBlank { "Alternatif" }
            if (!mirrors.containsKey(optHref)) mirrors[optHref] = lbl
        }

        mainDoc.select(".video-toolbar a[href]").forEach { a ->
            val optHref = fixUrlNull(a.attr("href").trim()) ?: return@forEach
            if (!optHref.startsWith(data.trimEnd('/'))) return@forEach
            val lbl = a.text().trim().ifBlank { "Alternatif" }
            if (!mirrors.containsKey(optHref)) mirrors[optHref] = lbl
        }

        var anyEmitted = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            anyEmitted = true
            callback.invoke(link)
        }
        val seenSubs = mutableSetOf<String>()

        for ((mirrorUrl, rawLabel) in mirrors) {
            val label = rawLabel.replace(Regex("""\s+"""), " ").trim()
            val doc = if (mirrorUrl == data) {
                mainDoc
            } else {
                runCatching {
                    app.get(mirrorUrl, headers = mapOf("Referer" to data), cookies = cookies).document
                }.getOrNull() ?: continue
            }

            val iframeSrc = doc.selectFirst("#video-area iframe[src], .video-area iframe[src], iframe[src*='/player/']")
                ?.attr("src")?.trim()
                ?: continue
            val iframeUrl = resolveRelativeUrl(mirrorUrl, iframeSrc)

            when {
                iframeUrl.contains("/player/king/", ignoreCase = true) -> {
                    extractKingPlayer(iframeUrl, mirrorUrl, label, cookies, seenSubs, subtitleCallback, wrappedCallback)
                }

                iframeUrl.contains("/player/moly/", ignoreCase = true) -> {
                    extractMolyWrapper(iframeUrl, mirrorUrl, label, cookies, seenSubs, subtitleCallback, wrappedCallback)
                }

                iframeUrl.contains("vidmoly", ignoreCase = true) -> {
                    if (!extractVidmolyEmbed(iframeUrl, "$mainUrl/", label, seenSubs, subtitleCallback, wrappedCallback)) {
                        loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                    }
                }

                iframeUrl.contains("/player/haydi.php", ignoreCase = true) || iframeUrl.contains("?v=") -> {
                    val b64Param = Regex("""[?&]v=([^&]+)""").find(iframeUrl)?.groupValues?.get(1)
                    val decodedTarget = b64Param?.let { param ->
                        runCatching {
                            val urlDec = URLDecoder.decode(param, "UTF-8")
                            String(Base64.decode(urlDec, Base64.DEFAULT), Charsets.UTF_8).trim()
                        }.getOrNull()
                    }
                    if (!decodedTarget.isNullOrBlank()) {
                        val targetUrl = when {
                            decodedTarget.startsWith("//") -> "https:$decodedTarget"
                            decodedTarget.startsWith("http://ok.ru", ignoreCase = true) ->
                                "https://" + decodedTarget.removePrefix("http://")
                            else -> decodedTarget
                        }
                        if (targetUrl.contains("vidmoly", ignoreCase = true)) {
                            extractVidmolyEmbed(targetUrl, "$mainUrl/", label, seenSubs, subtitleCallback, wrappedCallback)
                        } else {
                            loadExtractor(targetUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                        }
                    }
                }

                else -> {
                    loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                }
            }
        }

        return anyEmitted
    }
}
