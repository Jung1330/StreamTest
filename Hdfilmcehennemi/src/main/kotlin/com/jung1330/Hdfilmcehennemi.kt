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
        "$mainUrl/yabancidiziizle-6/"    to "Diziler"
    )

    private val filmRegex        = Regex("""^https://www\.hdfilmcehennemi\.nl/[a-z0-9\-]+/$""")
    private val episodeUrlRegex  = Regex("""^https://www\.hdfilmcehennemi\.nl/dizi/([^/]+)/sezon-\d+/bolum-\d+/$""")
    private val encodedRegex     = Regex("""nvs6\("([^"]+)"\.split\("@"\)\)""")
    private val rplayerPayload   = Regex("""=\w+\("([^"]+)"\.split\("\^"\)\)""")
    private val subtitleRegex = Regex(""""file"\s*:\s*"([^"]+\.vtt)"\s*,\s*"kind"\s*:\s*"captions"\s*,\s*"label"\s*:\s*"([^"]+)"""")
    private val contentRegex  = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document

        val items = doc.select("a.poster").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

            val title = a.attr("title").trim().ifBlank { a.text().trim() }
            if (title.isBlank()) return@mapNotNull null

            val img    = a.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src"))
                ?: fixUrlNull(img?.attr("src"))?.takeIf { !it.startsWith("data:") }

            val seriesMatch = episodeUrlRegex.find(href)
            when {
                seriesMatch != null -> newTvSeriesSearchResponse(
                    title.substringBefore(" izle"),
                    "$mainUrl/dizi/${seriesMatch.groupValues[1]}/",
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

        val embed = doc.select("iframe").asSequence()
            .mapNotNull { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }
            .firstOrNull { it.contains("/video/embed/") }
            ?: return null

        return newMovieLoadResponse(title, url, TvType.Movie, embed) {
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

    /** Embed sayfasındaki obfuscate edilmiş yayın adresini çözer. */
    private fun decodeSource(data: String, host: String): String {
        val parts = data.split("@").toMutableList()
        val len   = parts.size - 2
        val p7    = len % 7
        val um    = 8 + (len % 5)

        val e2n  = parts.removeAt(um)
        val key  = parts.removeAt(p7) + host
        var text = parts.joinToString("")

        if (key.length > 4096) {
            text = b64(text)
        }

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

        for (i in e2n.length - 1 downTo 0) {
            when (e2n[i]) {
                '7' -> text = b64(text)
                '3' -> text = text.reversed()
                else -> {
                    val shift = (26 - ((e2n[i].code - 96) % 26)) % 26
                    text = text.map { ch ->
                        if (ch in 'a'..'z' || ch in 'A'..'Z') {
                            val base = if (ch.code <= 90) 65 else 97
                            (((ch.code - base + shift) % 26) + base).toChar()
                        } else ch
                    }.joinToString("")
                }
            }
        }

        if (e2n.length > 2048) text = text.reversed()

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

    /** rplayer sayfasındaki paketlenmiş (packer) script'i çözüp yayın adresini üretir. */
    private suspend fun loadRplayer(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = try {
            app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
        } catch (t: Throwable) {
            Log.e("HDFilmCehennemi", "rplayer açılamadı: ${t.message}")
            return false
        }

        val script = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("eval(function(p,a,c,k,e,") }
            ?: return false

        val unpacked = runCatching { getAndUnpack(script) }.getOrNull() ?: script
        val payload  = rplayerPayload.find(unpacked)?.groupValues?.get(1) ?: return false

        val streamUrl = runCatching { decodePack(payload, "", "^") }.getOrNull()
        if (streamUrl.isNullOrBlank() || !streamUrl.startsWith("http")) {
            Log.e("HDFilmCehennemi", "rplayer çözülemedi: $url")
            return false
        }

        val links = try {
            M3u8Helper.generateM3u8(this.name, streamUrl, url)
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
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /** Embed sayfasındaki paketlenmiş/obfuscate edilmiş yayın adresini çözer. */
    private fun resolvePacked(html: String, host: String): String? {
        fun parse(src: String): String? {
            val varName = Regex("""sources\s*:\s*\[\{file:\s*(\w+)""").find(src)?.groupValues?.get(1) ?: return null
            val call = Regex("""var\s+""" + Regex.escape(varName) + """\s*=\s*\w+\("([^"]+)"\.split\("([^"]+)"\)\)""")
                .find(src) ?: return null
            return decodePack(call.groupValues[1], host, call.groupValues[2])
        }

        parse(html)?.let { return it }

        val packed = Regex("""(?s)eval\(function\(p,a,c,k,e,[dr]\).*?""").find(html)?.value ?: return null
        val unpacked = runCatching { getAndUnpack(packed) }.getOrNull() ?: return null
        return parse(unpacked)
    }

    /** rplayer decoder'ı (hostname kullanmayan varyant). */
    private fun decodePack(payload: String, host: String, sep: String): String {
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.contains("/rplayer/")) {
            return loadRplayer(data, subtitleCallback, callback)
        }

        // Bölüm sayfası geldiyse oynatıcı iframe'ini bul
        val embedUrl: String = if (data.contains("/sezon-") && data.contains("/bolum-")) {
            val page = runCatching { app.get(data).document }.getOrNull() ?: return false
            val iframe = page.select("iframe").asSequence()
                .mapNotNull { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }
                .firstOrNull() ?: return false

            if (iframe.contains("/rplayer/")) return loadRplayer(iframe, subtitleCallback, callback)
            iframe
        } else data

        val html = try {
            app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
        } catch (t: Throwable) {
            return false
        }

        val host = runCatching { URI(embedUrl).host }.getOrNull() ?: "hdfilmcehennemi.mobi"

        val streamUrl = encodedRegex.find(html)?.let { match ->
            runCatching { decodeSource(match.groupValues[1], host) }.getOrNull()
        } ?: runCatching { resolvePacked(html, host) }.getOrNull()

        if (streamUrl.isNullOrBlank()) {
            Log.e("HDFilmCehennemi", "Yayın adresi çözülemedi: $embedUrl")
            return false
        }

        subtitleRegex.findAll(html).forEach { match ->
            val subUrl  = match.groupValues[1].replace("\\/", "/")
            val subLang = match.groupValues[2].lowercase()
            subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
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
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
