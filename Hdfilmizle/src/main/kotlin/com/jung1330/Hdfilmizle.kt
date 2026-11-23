// StreamTest | Jung1330

package com.jung1330

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Hdfilmizle : MainAPI() {
    override var mainUrl              = "https://www.hdfilmizle.live"
    override var name                 = "HDFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl"                      to "Son Eklenen Filmler",
        "$mainUrl/yabanci-dizi-izle-3"  to "Yabancı Diziler",
        "$mainUrl/tur/aksiyon-2"        to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu-1"    to "Bilim Kurgu",
        "$mainUrl/tur/dram-1"           to "Dram",
        "$mainUrl/tur/komedi-1"         to "Komedi",
        "$mainUrl/tur/korku-1"          to "Korku",
        "$mainUrl/tur/animasyon-1"      to "Animasyon",
        "$mainUrl/tur/macera-1"         to "Macera"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href.contains("/page/") || href.contains("-apk")) return null
        val title = this.attr("title").trim()
            .ifBlank { this.selectFirst("h2, h3, .title")?.text()?.trim() ?: "" }
            .ifBlank { this.selectFirst("img")?.attr("alt")?.trim() ?: "" }
        if (title.isBlank()) return null

        val posterRaw = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { !it.startsWith("data:") }
            ?: this.selectFirst("picture source[data-srcset]")?.attr("data-srcset")
                ?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
        val poster = fixUrlNull(posterRaw)
        val isSeries = href.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val doc = app.get(url, referer = "$mainUrl/").document
        val seen = hashSetOf<String>()
        val items = doc.select("a.poster").mapNotNull { el ->
            val res = el.toSearchResult() ?: return@mapNotNull null
            if (seen.add(res.url)) res else null
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // Try fast JSON search endpoint first
        try {
            val resText = app.post(
                "$mainUrl/search/",
                data = mapOf("query" to trimmed),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                )
            ).text.trim()
            if (resText.startsWith("[")) {
                val arr = JSONArray(resText)
                val list = mutableListOf<SearchResponse>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("name").trim()
                    val slug = obj.optString("slug").trim()
                    if (title.isBlank() || slug.isBlank()) continue
                    val type = obj.optString("type").lowercase()
                    val isSeries = type == "dizi" || type == "series"
                    val href = if (isSeries) "$mainUrl/dizi/$slug/" else "$mainUrl/$slug/"
                    val thumb = obj.optString("thumb_url").replace("\\/", "/").ifBlank {
                        obj.optString("thumb_webp").replace("\\/", "/")
                    }
                    val poster = fixUrlNull(thumb)
                    val year = obj.optInt("year", 0).takeIf { it > 1900 }
                    val rating = obj.optString("imdb_rating").takeIf { it.isNotBlank() }

                    if (isSeries) {
                        list.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                            this.score = Score.from10(rating)
                        })
                    } else {
                        list.add(newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                            this.score = Score.from10(rating)
                        })
                    }
                }
                if (list.isNotEmpty()) return list
            }
        } catch (_: Exception) {
        }

        // Fallback to HTML search
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        val seen = hashSetOf<String>()
        return doc.select("a.poster").mapNotNull { el ->
            val res = el.toSearchResult() ?: return@mapNotNull null
            if (seen.add(res.url)) res else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document
        val h1 = doc.selectFirst("h1") ?: return null
        val yearFromH1 = h1.selectFirst("small")?.text()?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
        val title = h1.ownText().trim().ifBlank {
            h1.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        }
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("picture.poster-auto img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )
        val plot = doc.selectFirst("div.card-body p, p.story, div.story")?.text()?.trim()
            ?.takeIf { it.length > 20 }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val epAnchors = doc.select("a[href*='/sezon-'][href*='/bolum-']")
        val isSeries = url.contains("/dizi/") || epAnchors.isNotEmpty()

        if (isSeries) {
            val seenEp = hashSetOf<String>()
            val episodes = epAnchors.mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                if (!seenEp.add(epHref)) return@mapNotNull null
                val sMatch = Regex("sezon-(\\d+)").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val eMatch = Regex("bolum-(\\d+)").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val epName = a.selectFirst("h3, .title")?.text()?.trim()
                    ?.ifBlank { a.attr("title").trim() }
                    ?: "Bölüm ${eMatch ?: 1}"
                newEpisode(epHref) {
                    this.name = epName
                    this.season = sMatch
                    this.episode = eMatch
                }
            }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = yearFromH1
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = yearFromH1
        }
    }

    private fun decodeVidrameXor(nums: List<Int>, key: String): String {
        if (key.isEmpty()) return ""
        val sb = StringBuilder(nums.size)
        for (i in nums.indices) {
            val ch = nums[i] xor key[i % key.length].code xor ((i * 17 + 13) and 255)
            sb.append(ch.toChar())
        }
        return sb.toString()
    }

    private fun decodeEeDd(encoded: String): String? {
        return try {
            var s = encoded.trim().replace('-', '+').replace('_', '/')
            while (s.length % 4 != 0) s += "="
            val decoded = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            val rot = StringBuilder(decoded.length)
            for (c in decoded) {
                when (c) {
                    in 'a'..'z' -> rot.append(((c - 'a' + 13) % 26 + 'a'.code).toChar())
                    in 'A'..'Z' -> rot.append(((c - 'A' + 13) % 26 + 'A'.code).toChar())
                    else -> rot.append(c)
                }
            }
            rot.reverse().toString().trim()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractVidrameOrVidmoxy(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(embedUrl, referer = "$mainUrl/").text
        val origin = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: "https://vidrame.live"

        // 1. Decode subtitle tracks encoded with fx:{d:[...],k:"..."}
        val trackRegex = Regex("""\{[^{}]*"label"\s*:\s*"([^"]+)"[^{}]*"fx"\s*:\s*\{\s*"d"\s*:\s*\[([\d,\s]+)\]\s*,\s*"k"\s*:\s*"([^"]+)"\s*\}[^{}]*\}""")
        for (m in trackRegex.findAll(html)) {
            val label = m.groupValues[1]
            val nums = m.groupValues[2].split(",").mapNotNull { it.trim().toIntOrNull() }
            val key = m.groupValues[3]
            val subUrl = decodeVidrameXor(nums, key)
            if (subUrl.startsWith("http")) {
                subtitleCallback(newSubtitleFile(label, subUrl))
            }
        }

        // 2. Try Vidrame XOR file decoder
        val xorMatch = Regex("""file\s*:\s*\(function\(d,k\)\{.*?\}\)\(\[([\d,\s]+)\]\s*,\s*['"]([^'"]+)['"]\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html)
        val m3u8FromXor = xorMatch?.let {
            val nums = it.groupValues[1].split(",").mapNotNull { n -> n.trim().toIntOrNull() }
            val key = it.groupValues[2]
            decodeVidrameXor(nums, key)
        }

        // 3. Try VidMoxy EE.dd(...) decoder
        val eeMatch = Regex("""EE\.dd\(['"]([^'"]+)['"]\)""").find(html)
        val m3u8FromEe = eeMatch?.groupValues?.get(1)?.let { decodeEeDd(it) }

        val finalUrl = listOfNotNull(m3u8FromXor, m3u8FromEe)
            .firstOrNull { it.startsWith("http") }
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value

        if (!finalUrl.isNullOrBlank()) {
            M3u8Helper.generateM3u8(
                sourceName,
                finalUrl,
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
        val partsJson = Regex("""let\s+parts\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)

        val embeds = linkedMapOf<String, String>()
        if (!partsJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(partsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val partName = obj.optString("name").ifBlank { "Player" }
                    val langTag = obj.optString("lang").uppercase()
                    val dataHtml = obj.optString("data").replace("\\/", "/")
                    val src = Regex("""src=["']([^"']+)["']""").find(dataHtml)?.groupValues?.get(1) ?: continue
                    val fullSrc = if (src.startsWith("//")) "https:$src" else src
                    if (fullSrc.startsWith("http")) {
                        val label = if (langTag.isNotBlank()) "$name ($partName $langTag)" else "$name ($partName)"
                        embeds[fullSrc] = label
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (embeds.isEmpty()) {
            val iframeSrc = Regex("""<iframe[^>]+data-src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            if (!iframeSrc.isNullOrBlank()) {
                embeds[iframeSrc] = name
            }
        }

        for ((embedUrl, label) in embeds) {
            try {
                if (embedUrl.contains("vidrame", ignoreCase = true) || embedUrl.contains("vidmoxy", ignoreCase = true)) {
                    extractVidrameOrVidmoxy(embedUrl, label, subtitleCallback, callback)
                } else {
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                }
            } catch (_: Exception) {
            }
        }

        return embeds.isNotEmpty()
    }
}
