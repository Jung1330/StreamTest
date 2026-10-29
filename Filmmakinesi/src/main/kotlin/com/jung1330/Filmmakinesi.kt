// ! StreamTest - FilmMakinesi Eklentisi | Jung1330

package com.jung1330

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder

class Filmmakinesi : MainAPI() {
    override var mainUrl              = "https://www.filmmakinesi.to"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler-1/" to "Filmler"
    )

    private val encodedRegex  = Regex("""c82\("([^"]+)"\.split\("\^"\)\)""")
    private val subtitleRegex = Regex(""""file"\s*:\s*"([^"]+\.vtt)"\s*,\s*"kind"\s*:\s*"captions"\s*,\s*"label"\s*:\s*"([^"]+)"""")
    private val contentRegex  = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")

    private fun cleanTitle(raw: String): String {
        return raw
            .substringBefore(" | ")
            .replace(Regex("""(?i)\b(1080p|720p|480p|4k|full\s*hd|hd|filmi|izle|türkçe|dublaj|altyazılı)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', ':', ',')
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}sayfa/$page/"
        val doc = app.get(url).document

        val items = doc.select("a.item[data-title]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!href.contains("/film/")) return@mapNotNull null

            val title = a.attr("data-title").trim()
            if (title.isBlank()) return@mapNotNull null

            val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            app.get("$mainUrl/arama/?s=$encoded").document
        } catch (t: Throwable) {
            return emptyList()
        }

        return doc.select("a.item[data-title]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!href.contains("/film/") && !href.contains("/dizi/")) return@mapNotNull null

            val title = a.attr("data-title").trim()
            if (title.isBlank()) return@mapNotNull null

            val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val title  = cleanTitle(rawTitle)
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot   = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val year   = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        val tags   = doc.select("a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

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
    private fun decodeSource(data: String, host: String, sep: String = "^"): String {
        val parts = data.split(sep).toMutableList()
        val len   = parts.size - 2
        val p7    = len % 7
        val um    = 8 + (len % 5)

        val e2n  = parts.removeAt(um)
        val key  = parts.removeAt(p7) + host
        var text = parts.joinToString("")

        if (e2n.length > 2048) text = text.reversed()

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

        if (key.length > 4096) {
            text = b64(text)
        }

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

    /** Yeni tip embed'ler: sources:[{file:VAR}] + var VAR=fn("payload".split(SEP)) */
    private fun resolvePacked(html: String, host: String): String? {
        fun parse(src: String): String? {
            val varName = Regex("""sources\s*:\s*\[\{file:\s*(\w+)""").find(src)?.groupValues?.get(1) ?: return null
            val call = Regex("""var\s+""" + Regex.escape(varName) + """\s*=\s*\w+\("([^"]+)"\.split\("([^"]+)"\)\)""")
                .find(src) ?: return null
            return decodeSource(call.groupValues[1], host, call.groupValues[2])
        }

        parse(html)?.let { return it }

        val packed = Regex("""(?s)eval\(function\(p,a,c,k,e,[dr]\).*?""").find(html)?.value ?: return null
        val unpacked = runCatching { getAndUnpack(packed) }.getOrNull() ?: return null
        return parse(unpacked)
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val embedUrl = data

        val html = try {
            app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
        } catch (t: Throwable) {
            return false
        }

        val host = runCatching { URI(embedUrl).host }.getOrNull() ?: "closeload.filmmakinesi.to"

        val streamUrl = encodedRegex.find(html)?.let { match ->
            runCatching { decodeSource(match.groupValues[1], host) }.getOrNull()
        } ?: runCatching { resolvePacked(html, host) }.getOrNull()

        if (streamUrl.isNullOrBlank()) {
            Log.e("FilmMakinesi", "Yayın adresi çözülemedi: $embedUrl")
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
