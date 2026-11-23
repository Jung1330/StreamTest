// StreamTest | Jung1330

package com.jung1330

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fullhdfilmizlesene : MainAPI() {
    override var mainUrl              = "https://www.fullhdfilmizlesene.now"
    override var name                 = "FullHDFilmizlesene"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-filmler"                  to "Yeni Filmler",
        "$mainUrl/en-cok-izlenen-filmler"        to "En Çok İzlenenler",
        "$mainUrl/filmizle/aksiyon-filmleri"     to "Aksiyon",
        "$mainUrl/filmizle/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/filmizle/dram-filmler-izle"    to "Dram",
        "$mainUrl/filmizle/komedi-filmleri-izle" to "Komedi",
        "$mainUrl/filmizle/korku-filmleri-izle"  to "Korku",
        "$mainUrl/filmizle/animasyon-filmleri-izle" to "Animasyon",
        "$mainUrl/filmizle/aile-filmleri"        to "Aile"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a.tt") ?: this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = this.selectFirst("span.film-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: anchor.text().replace(Regex("\\s+izle$", RegexOption.IGNORE_CASE), "").trim()
        if (title.isBlank()) return null

        val srcset = this.selectFirst("picture source[srcset]")?.attr("srcset")
            ?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
        val poster = fixUrlNull(
            srcset?.takeIf { it.startsWith("http") || it.startsWith("/") }
                ?: this.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )
        val year = this.selectFirst("span.film-yil")?.text()?.trim()?.toIntOrNull()
        val rating = this.selectFirst("span.imdb")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.score = Score.from10(rating)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/$page"
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = doc.select("li.film").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/arama/$encoded", referer = "$mainUrl/").document
        return doc.select("li.film").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("\\s+izle$", RegexOption.IGNORE_CASE), "")
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("picture source[srcset]")?.attr("srcset")
                    ?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                ?: doc.selectFirst("img.afis, div.afis img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )

        val plot = doc.selectFirst("div.ozet-ic p")?.text()?.trim()
            ?: doc.selectFirst("div.ozet-ic")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val rating = doc.selectFirst("div.imdb-ic span")?.text()?.trim()
        val year = doc.selectFirst("div.film-info a[href*='/yil/']")?.text()
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
        val tags = doc.select("div.film-info a[rel='category tag']")
            .map { it.text().replace(Regex("\\s*Filmleri$", RegexOption.IGNORE_CASE), "").trim() }
            .filter { it.isNotBlank() }
        val actors = doc.select("div.film-info a[href*='/oyuncu/']")
            .map { Actor(it.text().trim()) }
            .filter { it.name.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(rating)
            this.tags = tags
            addActors(actors)
        }
    }

    private fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            when (c) {
                in 'a'..'z' -> sb.append(((c - 'a' + 13) % 26 + 'a'.code).toChar())
                in 'A'..'Z' -> sb.append(((c - 'A' + 13) % 26 + 'A'.code).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun rttDecode(encoded: String): String? {
        return try {
            val rotated = rot13(encoded.trim())
            val bytes = Base64.decode(rotated, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeRapidVid(encoded: String): String? {
        return try {
            val rev = encoded.reversed()
            val step1 = String(Base64.decode(rev, Base64.DEFAULT), Charsets.ISO_8859_1)
            val key = "K9L"
            val sb = StringBuilder(step1.length)
            for (i in step1.indices) {
                val shift = (key[i % key.length].code % 5) + 1
                sb.append(((step1[i].code - shift) and 0xFF).toChar())
            }
            String(Base64.decode(sb.toString(), Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractRapidVid(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(embedUrl, referer = "$mainUrl/").text
        val avMatch = Regex("""av\(['"]([^'"]+)['"]\)""").find(res)
        val m3u8Url = avMatch?.groupValues?.get(1)?.let { decodeRapidVid(it) }
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(res)?.value

        val origin = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: "https://rapidvid.org"

        // Extract VTT subtitles
        val trackRegex = Regex("""\{[^{}]*"file"\s*:\s*"([^"]+\.vtt[^"]*)"[^{}]*\}""")
        for (m in trackRegex.findAll(res)) {
            val block = m.value
            val vttUrl = m.groupValues[1].replace("\\/", "/")
            val label = Regex(""""label"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: "Türkçe"
            if (vttUrl.startsWith("http")) {
                subtitleCallback(newSubtitleFile(label, vttUrl))
            }
        }

        if (!m3u8Url.isNullOrBlank() && m3u8Url.startsWith("http")) {
            M3u8Helper.generateM3u8(
                sourceName,
                m3u8Url,
                "$origin/",
                headers = mapOf("Referer" to "$origin/", "Origin" to origin)
            ).forEach(callback)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, referer = "$mainUrl/").text
        val scxJson = Regex("""var\s+scx\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: return false

        val embedUrls = linkedMapOf<String, String>()
        try {
            val root = JSONObject(scxJson)
            val keys = root.keys()
            while (keys.hasNext()) {
                val playerKey = keys.next()
                val playerObj = root.optJSONObject(playerKey) ?: continue
                val sx = playerObj.optJSONObject("sx") ?: continue
                val langKeys = sx.keys()
                while (langKeys.hasNext()) {
                    val langKey = langKeys.next()
                    val labelSuffix = when (langKey) {
                        "t" -> "TR"
                        "d" -> "Dublaj"
                        "a" -> "Altyazı"
                        else -> langKey.uppercase()
                    }
                    val node = sx.opt(langKey)
                    val encodedList = mutableListOf<String>()
                    when (node) {
                        is JSONArray -> {
                            for (i in 0 until node.length()) {
                                node.optString(i)?.takeIf { it.isNotBlank() }?.let { encodedList.add(it) }
                            }
                        }
                        is JSONObject -> {
                            val subKeys = node.keys()
                            while (subKeys.hasNext()) {
                                node.optString(subKeys.next())?.takeIf { it.isNotBlank() }?.let { encodedList.add(it) }
                            }
                        }
                        is String -> if (node.isNotBlank()) encodedList.add(node)
                    }
                    for (enc in encodedList) {
                        val decoded = rttDecode(enc) ?: continue
                        val cleanUrl = if (decoded.startsWith("//")) "https:$decoded" else decoded
                        if (cleanUrl.startsWith("http")) {
                            embedUrls[cleanUrl] = "$name (${playerKey.uppercase()} $labelSuffix)"
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        for ((embedUrl, label) in embedUrls) {
            try {
                if (embedUrl.contains("rapidvid", ignoreCase = true)) {
                    extractRapidVid(embedUrl, label, subtitleCallback, callback)
                } else {
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                }
            } catch (_: Exception) {
            }
        }

        return embedUrls.isNotEmpty()
    }
}
