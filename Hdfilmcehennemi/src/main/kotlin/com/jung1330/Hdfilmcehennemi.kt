// ! StreamTest - HDFilmCehennemi Eklentisi | Jung1330

package com.jung1330

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Hdfilmcehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/film-izle-2/" to "Filmler",
        "$mainUrl/yabancidiziizle-10/"   to "Yabancı Diziler",
        "$mainUrl/serifilmlerim-8/"      to "Seri Filmler"
    )

    private val filmRegex        = Regex("""^https://www\.hdfilmcehennemi\.nl/[a-z0-9\-]+/?$""")
    private val seriesRegex      = Regex("""^https://www\.hdfilmcehennemi\.nl/dizi/([^/]+)/?$""")
    private val episodeUrlRegex  = Regex("""^https://www\.hdfilmcehennemi\.nl/dizi/([^/]+)/sezon-\d+/bolum-\d+/?$""")
    private val packedRegex      = Regex("""(?s)eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\s*\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\)\s*(?:,\s*0\s*,\s*\{\s*\}\s*)?\)\)""")
    private val payloadCallRegex = Regex("""\("([^"]{20,})"\.split\("([^"]+)"\)\)""")
    private val directFileRegex  = Regex(""""?file"?\s*:\s*"(https?://[^"]+(?:\.m3u8|master\.txt)[^"]*)"""")
    private val trackObjRegex    = Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+\.(?:vtt|srt)(?:\?[^"]*)?)"[^{}]*\}""", RegexOption.IGNORE_CASE)
    private val rapidIdRegex     = Regex("""[?&]rapidrame_id=([a-zA-Z0-9_-]+)""")
    private val muxedAudioRegex  = Regex("""-v\d+-a\d+\.m3u8""", RegexOption.IGNORE_CASE)

    private data class HlsAudioTrack(
        val label: String,
        val token: String?,
        val isPreferred: Boolean
    )

    private data class HlsVariant(
        val url: String,
        val height: Int
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val doc = app.get(request.data).document

        val items = doc.select("a.poster").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

            val title = a.attr("title").trim().ifBlank { a.text().trim() }
            if (title.isBlank()) return@mapNotNull null

            val img    = a.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src"))
                ?: fixUrlNull(img?.attr("src"))?.takeIf { !it.startsWith("data:") }

            val epMatch     = episodeUrlRegex.find(href)
            val directMatch = seriesRegex.find(href)
            when {
                epMatch != null -> newTvSeriesSearchResponse(
                    title.substringBefore(" izle").trim(),
                    "$mainUrl/dizi/${epMatch.groupValues[1]}/",
                    TvType.TvSeries
                ) { this.posterUrl = poster }

                directMatch != null -> newTvSeriesSearchResponse(
                    title.substringBefore(" izle").trim(),
                    "$mainUrl/dizi/${directMatch.groupValues[1]}/",
                    TvType.TvSeries
                ) { this.posterUrl = poster }

                filmRegex.matches(href) -> newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }

                else -> null
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        // Sitenin kendi JSON arama servisi (X-Requested-With: fetch başlığı gerekli)
        val json = try {
            app.get(
                "$mainUrl/search?q=$encoded",
                headers = mapOf("X-Requested-With" to "fetch")
            ).text
        } catch (t: Throwable) {
            return searchWithDuckDuckGo(query)
        }

        val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull()
            ?: return searchWithDuckDuckGo(query)

        val out = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val html = results.optString(i)
            if (html.isBlank()) continue

            val el = runCatching { Jsoup.parse(html).selectFirst("a.search-result") }.getOrNull() ?: continue
            val href = fixUrlNull(el.attr("href")) ?: continue
            val title = el.selectFirst("h4.title")?.text()?.trim()
                ?: el.selectFirst("img")?.attr("alt")?.trim()
                ?: continue
            if (title.isBlank()) continue

            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))
            val isSeries = el.selectFirst("span.type")?.text()?.contains("Dizi", true) == true
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            if (isSeries) {
                out.add(newTvSeriesSearchResponse(title, href, type) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, href, type) { this.posterUrl = poster })
            }
        }
        return out.distinctBy { it.url }
    }

    /** Arama servisi bozulursa DuckDuckGo site aramasına düşer. */
    private suspend fun searchWithDuckDuckGo(query: String): List<SearchResponse> {
        val q = URLEncoder.encode("site:hdfilmcehennemi.nl $query", "UTF-8")
        val doc = try {
            app.get("https://html.duckduckgo.com/html/?q=$q").document
        } catch (t: Throwable) {
            return emptyList()
        }

        return doc.select("a.result__a").mapNotNull { a ->
            val raw = a.attr("href")
            val url = if (raw.contains("uddg=")) {
                runCatching { URLDecoder.decode(raw.substringAfter("uddg=").substringBefore("&"), "UTF-8") }.getOrNull()
            } else raw

            if (url == null || !filmRegex.matches(url)) return@mapNotNull null

            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null

            newMovieSearchResponse(title, url, TvType.Movie)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val title = rawTitle.substringBefore(" izle").substringBefore(" | ").trim()

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot   = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val year   = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        val tags   = doc.select("a[href*='/tur/'], a[href*='/kategori/']")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        if (url.contains("/dizi/")) {
            val episodes = doc.select("a[href*='/sezon-'][href*='/bolum-']").mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val m = Regex("""sezon-(\d+)/bolum-(\d+)""").find(href) ?: return@mapNotNull null
                val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val e = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null

                newEpisode(href) {
                    this.name    = "$s. Sezon $e. Bölüm"
                    this.season  = s
                    this.episode = e
                }
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        }

        val hasPlayer = doc.select("iframe").any {
            val s = it.attr("data-src").ifBlank { it.attr("src") }
            s.contains("/video/embed/") || s.contains("/rplayer/") || s.contains("rapid", true)
        } || doc.select("[data-video]").isNotEmpty()
        if (!hasPlayer) return null

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
        }
    }

    /** Padded olmayan base64 verisini de çözebilen yardımcı. */
    private fun b64(value: String): String {
        val clean = value.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val bytes = runCatching { Base64.decode(padded, Base64.DEFAULT) }
            .getOrElse { Base64.decode(clean, Base64.DEFAULT) }
        return String(bytes, Charsets.ISO_8859_1)
    }

    /** Embed / rplayer sayfasındaki obfuscate edilmiş yayın adresini çözer. */
    private fun decodeRaw(payload: String, host: String, sep: String): String {
        val parts = payload.split(sep).toMutableList()
        val len   = parts.size - 2
        val c78   = len % 7
        val ggn   = 8 + (len % 5)

        val kr3   = parts.removeAt(ggn)
        val key   = parts.removeAt(c78) + host
        var text  = parts.joinToString("")

        if (kr3.length > 2048) text = text.reversed()

        var d0 = 0
        var u8 = 0
        for (i in key.indices) {
            val c = key[i].code
            d0 = (d0 * 37 + c) % 241
            u8 = (u8 + ((c shl 1) xor i)) and 255
        }

        val r = (d0 * 3 + u8) % 256
        val w = (u8 % 11) + 5
        var g = ((u8 * 251 + d0) % 65519) + 1

        for (i in kr3.length - 1 downTo 0) {
            when (kr3[i]) {
                '7' -> text = b64(text)
                '3' -> text = text.reversed()
                else -> {
                    val shift = (26 - ((kr3[i].code - 96) % 26)) % 26
                    text = text.map { ch ->
                        if (ch in 'a'..'z' || ch in 'A'..'Z') {
                            val base = if (ch.code <= 90) 65 else 97
                            (((ch.code - base + shift) % 26) + base).toChar()
                        } else ch
                    }.joinToString("")
                }
            }
        }

        if (key.length > 4096) text = b64(text)

        val n = text.length
        val z = IntArray(n)
        for (i in n - 1 downTo 1) {
            g = (g * 97 + 41) % 65519
            z[i] = g % (i + 1)
        }

        val chars = text.toCharArray()
        for (i in 1 until n) {
            val j = z[i]
            val t = chars[i]
            chars[i] = chars[j]
            chars[j] = t
        }
        text = String(chars)

        var m = r
        val sb = StringBuilder()
        for (i in text.indices) {
            val c = text[i].code
            m = (m * 5 + w) % 256
            sb.append(((c xor m) and 0xFF).toChar())
            m = (m + c) % 256
        }

        val bytes = ByteArray(sb.length) { (sb[it].code and 0xFF).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    /** Hostname kullanan (CloseLoad) ve kullanmayan (rplayer) varyantların ikisini de dener. */
    private fun decodePayload(payload: String, host: String, sep: String): String? {
        for (candidateHost in listOf(host, "").distinct()) {
            val decoded = runCatching { decodeRaw(payload, candidateHost, sep).trim() }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                return decoded
            }
        }
        return null
    }

    private fun unescapeJs(value: String): String {
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(value) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }.replace("\\/", "/")
    }

    private fun resolveRelativeUrl(baseUrl: String, target: String): String {
        if (target.startsWith("http://") || target.startsWith("https://")) return target
        if (target.startsWith("//")) return "https:$target"
        return runCatching { URI(baseUrl).resolve(target).toString() }.getOrElse {
            val cleanBase = baseUrl.substringBefore("?")
            if (target.startsWith("/")) {
                val origin = Regex("""^https?://[^/]+""").find(cleanBase)?.value.orEmpty()
                "$origin$target"
            } else {
                val parent = cleanBase.substringBeforeLast("/", "")
                "$parent/$target"
            }
        }
    }

    private fun registerEmbedCandidate(rawUrl: String?, out: MutableSet<String>) {
        val clean = fixUrlNull(rawUrl) ?: return
        if (clean.contains("youtube.com", true) || clean.contains("youtu.be", true)) return
        rapidIdRegex.find(clean)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { rid ->
            out.add("$mainUrl/rplayer/$rid/")
        }
        if (clean.contains("/rplayer/", true) || clean.contains("/video/embed/", true) || clean.contains("rapid", true)) {
            out.add(clean)
        }
    }

    /**
     * HLS oynatma listesini hem ExoPlayer hem de CloudStream indiricisiyle uyumlu şekilde çözer:
     * - rplayer / Rapidrame (nginx-vod) çift sesli yayınlarda ses+görüntü birleşik (muxed) `-v1-a1.m3u8` / `-v1-a2.m3u8`
     *   medya listelerini üreterek indiricideki `M3u8 contains no video with audio` hatasını önler.
     * - CloseLoad çift sesli yayınlarda sessiz video varyantına bölünmesini engelleyip ana listeyi korur.
     */
    private suspend fun emitHlsLinks(
        mirrorLabel: String,
        streamUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val reqHeaders = mapOf("Referer" to referer)
        val playlist = runCatching {
            app.get(streamUrl, headers = reqHeaders, verify = false).text
        }.getOrNull().orEmpty()

        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val lines = playlist.lineSequence().map { it.trim() }.toList()
            val audioTracks = mutableListOf<HlsAudioTrack>()
            val variants = mutableListOf<HlsVariant>()

            for (i in lines.indices) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO", ignoreCase = true)) {
                    val uriAttr = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1) ?: continue
                    val nameAttr = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.get(1).orEmpty()
                    val langAttr = Regex("""LANGUAGE="([^"]+)"""").find(line)?.groupValues?.get(1).orEmpty()
                    val isDefault = line.contains("DEFAULT=YES", ignoreCase = true)
                    val token = Regex("""-a(\d+)\.m3u8""", RegexOption.IGNORE_CASE).find(uriAttr)?.groupValues?.get(1)
                    val label = nameAttr.ifBlank { langAttr }.ifBlank { "Ses" }
                    val isPreferred = label.contains("türk", ignoreCase = true) ||
                        langAttr.equals("tr", ignoreCase = true) ||
                        langAttr.equals("tur", ignoreCase = true) ||
                        isDefault
                    audioTracks.add(HlsAudioTrack(label, token, isPreferred))
                } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value
                    val nextLine = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?: continue
                    variants.add(HlsVariant(resolveRelativeUrl(streamUrl, nextLine), height))
                }
            }

            val muxedVariants = variants.filter { muxedAudioRegex.containsMatchIn(it.url) }
                .sortedByDescending { it.height }
            val numberedAudios = audioTracks.filter { it.token != null }
                .distinctBy { it.token }
                .sortedByDescending { it.isPreferred }

            if (numberedAudios.isNotEmpty() && muxedVariants.isNotEmpty()) {
                for (audio in numberedAudios) {
                    val linkName = if (numberedAudios.size > 1) "$mirrorLabel (${audio.label})" else mirrorLabel
                    for (variant in muxedVariants) {
                        val muxedUrl = variant.url.replace(
                            Regex("""-a\d+\.m3u8""", RegexOption.IGNORE_CASE),
                            "-a${audio.token}.m3u8"
                        )
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name   = linkName,
                                url    = muxedUrl,
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer
                                this.headers = reqHeaders
                                this.quality = variant.height
                            }
                        )
                    }
                }
                return true
            }

            if (audioTracks.isNotEmpty()) {
                // Harici ses kanalı olup muxed varyant sunmayan CloseLoad yayınlarını parçalama (sessiz video oluşmasın)
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = mirrorLabel,
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

            if (variants.isNotEmpty()) {
                for (variant in variants.sortedByDescending { it.height }) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name   = mirrorLabel,
                            url    = variant.url,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.headers = reqHeaders
                            this.quality = variant.height
                        }
                    )
                }
                return true
            }
        }

        if (playlist.isNotBlank() && !playlist.contains("#EXTINF") && !playlist.contains("#EXTM3U")) {
            return false
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = mirrorLabel,
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

    private suspend fun extractMirror(
        embedUrl: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
        } catch (t: Throwable) {
            Log.e("HDFilmCehennemi", "Oynatıcı açılamadı ($embedUrl): ${t.message}")
            return false
        }

        val host = runCatching { URI(embedUrl).host }.getOrNull() ?: "hdfilmcehennemi.mobi"
        val isRapid = embedUrl.contains("/rplayer/", ignoreCase = true) || embedUrl.contains("rapid", ignoreCase = true)
        val mirrorLabel = if (isRapid) "${this.name} Rapid" else "${this.name} CloseLoad"

        val candidates = buildList {
            add(html)
            packedRegex.findAll(html).forEach { block ->
                runCatching { JsUnpacker(block.value).unpack() }.getOrNull()?.let { add(it) }
            }
        }

        for (src in candidates) {
            trackObjRegex.findAll(src).forEach { match ->
                val obj = match.value
                if (obj.contains("thumbnails", ignoreCase = true)) return@forEach
                val rawSub = unescapeJs(match.groupValues[1]).trim()
                val subUrl = resolveRelativeUrl(embedUrl, rawSub)
                if (!seenSubs.add(subUrl)) return@forEach

                val rawLabel = Regex(""""label"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                    ?: Regex(""""language"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                    ?: "Türkçe"
                val subLang = unescapeJs(rawLabel).trim().ifBlank { "Türkçe" }
                subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
            }
        }

        var streamUrl: String? = null
        for (src in candidates) {
            for (call in payloadCallRegex.findAll(src)) {
                val decoded = decodePayload(call.groupValues[1], host, call.groupValues[2])
                if (decoded != null) {
                    streamUrl = decoded
                    break
                }
            }
            if (streamUrl != null) break
            streamUrl = directFileRegex.find(src)?.groupValues?.get(1)?.let { unescapeJs(it) }
            if (streamUrl != null) break
        }

        if (streamUrl.isNullOrBlank()) {
            Log.e("HDFilmCehennemi", "Yayın adresi çözülemedi: $embedUrl")
            return false
        }

        return emitHlsLinks(mirrorLabel, streamUrl, embedUrl, callback)
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val collected = linkedSetOf<String>()
        val isDirectEmbed = data.contains("/rplayer/", ignoreCase = true) || data.contains("/video/embed/", ignoreCase = true)

        if (isDirectEmbed) {
            registerEmbedCandidate(data, collected)
        } else {
            val page = runCatching { app.get(data).document }.getOrNull() ?: return false

            page.select("iframe").forEach { iframe ->
                registerEmbedCandidate(iframe.attr("data-src").ifBlank { iframe.attr("src") }, collected)
            }

            // Sayfadaki alternatif oynatıcı butonlarını da kontrol et (/video/{id}/ JSON servisi)
            val altIds = page.select("[data-video]")
                .filterNot { it.hasClass("active") }
                .map { it.attr("data-video").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(4)

            for (vid in altIds) {
                val json = runCatching {
                    app.get(
                        "$mainUrl/video/$vid/",
                        headers = mapOf("X-Requested-With" to "fetch", "Referer" to data)
                    ).text
                }.getOrNull() ?: continue

                val fragHtml = runCatching {
                    JSONObject(json).optJSONObject("data")?.optString("html")
                }.getOrNull().orEmpty()

                if (fragHtml.isNotBlank()) {
                    Jsoup.parse(fragHtml).select("iframe").forEach { iframe ->
                        registerEmbedCandidate(iframe.attr("data-src").ifBlank { iframe.attr("src") }, collected)
                    }
                }
            }
        }

        val embedUrls = collected.sortedByDescending { u ->
            u.contains("/rplayer/", ignoreCase = true) || u.contains("rapid", ignoreCase = true)
        }
        if (embedUrls.isEmpty()) return false

        val seenSubs = mutableSetOf<String>()
        var anyEmitted = false
        for (embedUrl in embedUrls) {
            if (extractMirror(embedUrl, seenSubs, subtitleCallback, callback)) {
                anyEmitted = true
            }
        }
        return anyEmitted
    }
}
