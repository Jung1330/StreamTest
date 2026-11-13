// StreamTest | Jung1330

package com.jung1330

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Filmmodu : MainAPI() {
    override var mainUrl = "https://filmmodu.cc"
    override var name = "Filmmodu"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-filmler" to "Yeni Filmler",
        "$mainUrl/trend-filmler" to "Trend Filmler",
        "$mainUrl/populer-filmler" to "Popüler Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/filmizle/aksiyon-filmleri-izle" to "Aksiyon Filmleri",
        "$mainUrl/filmizle/bilim-kurgu-filmleri-izle" to "Bilim Kurgu Filmleri",
        "$mainUrl/filmizle/dram-filmleri-izle" to "Dram Filmleri",
        "$mainUrl/filmizle/korku-filmleri-izle" to "Korku Filmleri",
        "$mainUrl/filmizle/komedi-filmleri-hd-izle" to "Komedi Filmleri",
        "$mainUrl/filmizle/yerli-film-izle" to "Yerli Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.trimEnd('/')
        val targetUrl = if (page <= 1) baseUrl else "$baseUrl/$page"
        val document = app.get(targetUrl).document
        val items = parseMovieBoxes(document)
        val hasNext = document.selectFirst("a[href*='/${page + 1}'], a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun parseMovieBoxes(document: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<SearchResponse>()

        document.select("article.movie_box").forEach { box ->
            val item = box.toSearchResult() ?: return@forEach
            if (seen.add(item.url)) {
                results.add(item)
            }
        }
        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a.image[href], a[href]") ?: return null
        val url = fixUrlNull(linkEl.attr("href")) ?: return null
        if (!url.contains("/film/") && !url.contains("/dizi/")) return null

        val title = (selectFirst("h2, .kart-ad")?.text()
            ?: linkEl.attr("title").replace(Regex("""\s*izle.*""", RegexOption.IGNORE_CASE), "")
            ).trim()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        val year = selectFirst(".year")?.text()?.trim()?.toIntOrNull()

        return if (url.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    private data class SearchOneriItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    private data class SearchOneriResponse(
        @JsonProperty("films") val films: List<SearchOneriItem>? = null,
        @JsonProperty("series") val series: List<SearchOneriItem>? = null
    )

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val homeResp = app.get(mainUrl)
        val csrf = homeResp.document.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()

        val searchResp = app.post(
            "$mainUrl/arama/oneri",
            headers = mapOf(
                "X-CSRF-TOKEN" to csrf,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json",
                "Referer" to "$mainUrl/"
            ),
            cookies = homeResp.cookies,
            data = mapOf("q" to trimmed)
        ).text

        val parsed = tryParseJson<SearchOneriResponse>(searchResp) ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        val seen = LinkedHashSet<String>()

        parsed.films?.forEach { item ->
            val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val url = fixUrlNull(item.url) ?: return@forEach
            if (seen.add(url)) {
                results.add(
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = fixUrlNull(item.poster)
                        this.year = item.year
                    }
                )
            }
        }

        parsed.series?.forEach { item ->
            val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val url = fixUrlNull(item.url) ?: return@forEach
            if (seen.add(url)) {
                results.add(
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = fixUrlNull(item.poster)
                        this.year = item.year
                    }
                )
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.replace(Regex("""/dizi/([^/?#]+)/sezon-\d+/bolum-\d+.*"""), "/dizi/$1")
        val document = app.get(cleanUrl).document

        val ldJson = document.select("script[type=application/ld+json]")
            .map { it.data() }
            .joinToString("\n")

        val title = Regex(""""name"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(ldJson)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" (")?.substringBefore(" izle")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("link[rel=image_src]")?.attr("href")
        )

        val description = Regex(""""description"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(ldJson)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex(""""dateCreated"\s*:\s*"(\d{4})""")
            .find(ldJson)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = document.select("a[href*='/filmizle/'], a[href*='/tur/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() && !t.contains("Filmleri", ignoreCase = true) }
        }.ifEmpty {
            Regex(""""genre"\s*:\s*\[(.*?)]""").find(ldJson)?.groupValues?.getOrNull(1)
                ?.let { raw ->
                    Regex(""""([^"]+)"""").findAll(raw).map { it.groupValues[1] }.toList()
                }.orEmpty()
        }.distinct()

        val actors = Regex(""""@type"\s*:\s*"Person"\s*,\s*"name"\s*:\s*"([^"]+)"""")
            .findAll(ldJson)
            .map { Actor(it.groupValues[1]) }
            .distinctBy { it.name }
            .toList()

        val recommendations = parseMovieBoxes(document).filter { it.url != cleanUrl }

        if (cleanUrl.contains("/dizi/")) {
            val episodes = mutableListOf<Episode>()
            val epRegex = Regex("""/sezon-(\d+)/bolum-(\d+)""")

            document.select("a.dz-bolum[href]").forEach { epLink ->
                val epUrl = fixUrlNull(epLink.attr("href")) ?: return@forEach
                val match = epRegex.find(epUrl)
                val sNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val eNum = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                val epLabel = epLink.selectFirst(".dz-ad")?.text()?.trim()
                val epSubTitle = epLink.attr("title").trim().takeIf { it.isNotBlank() }
                val fullEpName = when {
                    epLabel != null && epSubTitle != null -> "$epLabel - $epSubTitle"
                    epLabel != null -> epLabel
                    epSubTitle != null -> epSubTitle
                    else -> "$sNum. Sezon ${eNum ?: 1}. Bölüm"
                }

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = fullEpName
                        this.season = sNum
                        this.episode = eNum
                    }
                )
            }

            return newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

        return newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    private data class PlayerSourceItem(
        @JsonProperty("i") val i: Int? = null,
        @JsonProperty("s") val s: String? = null,
        @JsonProperty("y") val y: String? = null
    )

    private data class PxResponse(
        @JsonProperty("u") val u: String? = null
    )

    private data class BePlayerCipher(
        @JsonProperty("ct") val ct: String? = null,
        @JsonProperty("iv") val iv: String? = null,
        @JsonProperty("s") val s: String? = null
    )

    private data class BePlayerSubtitle(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null
    )

    private data class BePlayerDecrypted(
        @JsonProperty("video_location") val videoLocation: String? = null,
        @JsonProperty("strSubtitles") val strSubtitles: List<BePlayerSubtitle>? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResp = app.get(data)
        val document = pageResp.document
        val csrf = document.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
        val dataKRaw = document.selectFirst("#f_player[data-k]")?.attr("data-k").orEmpty()
        if (dataKRaw.isBlank()) return false

        val sources = tryParseJson<List<PlayerSourceItem>>(dataKRaw).orEmpty()
        var foundAny = false

        for (src in sources) {
            val idVal = src.i ?: continue
            val sigVal = src.s?.takeIf { it.isNotBlank() } ?: continue
            if (!src.y.isNullOrBlank()) continue // Skip YouTube trailers

            try {
                val pxText = app.post(
                    "$mainUrl/px",
                    headers = mapOf(
                        "X-CSRF-TOKEN" to csrf,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json",
                        "Referer" to data
                    ),
                    cookies = pageResp.cookies,
                    data = mapOf(
                        "i" to idVal.toString(),
                        "s" to sigVal
                    )
                ).text

                val embedUrl = tryParseJson<PxResponse>(pxText)?.u?.takeIf { it.isNotBlank() } ?: continue
                if (extractEmbedStream(embedUrl, subtitleCallback, callback)) {
                    foundAny = true
                }
            } catch (_: Exception) {
            }
        }

        return foundAny
    }

    private suspend fun extractEmbedStream(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val hostOrigin = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: "https://vidmixi.com"

        try {
            val embedHtml = app.get(embedUrl, referer = "$mainUrl/").text
            val beMatch = Regex(
                """bePlayer\(\s*['"]([^'"]+)['"]\s*,\s*['"](\{.*?\})['"]\s*\)""",
                RegexOption.DOT_MATCHES_ALL
            ).find(embedHtml)

            if (beMatch != null) {
                val pass = beMatch.groupValues[1]
                val cipherJson = beMatch.groupValues[2]
                val decryptedJson = decryptCryptoJsAes(pass, cipherJson)
                if (!decryptedJson.isNullOrBlank()) {
                    val beData = tryParseJson<BePlayerDecrypted>(decryptedJson)
                    val videoLoc = beData?.videoLocation?.takeIf { it.isNotBlank() }
                    if (!videoLoc.isNullOrBlank()) {
                        beData.strSubtitles?.forEach { sub ->
                            val rawSubFile = sub.file?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                            val subUrl = if (rawSubFile.startsWith("http")) rawSubFile else "$hostOrigin$rawSubFile"
                            val label = sub.label?.takeIf { it.isNotBlank() } ?: sub.language ?: "Türkçe"
                            subtitleCallback.invoke(SubtitleFile(label, subUrl))
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = "$name (Bepeak)",
                                name = "$name (Bepeak)",
                                url = videoLoc,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$hostOrigin/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }
                }
            }
        } catch (_: Exception) {
        }

        try {
            if (loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)) {
                found = true
            }
        } catch (_: Exception) {
        }

        return found
    }

    private fun decryptCryptoJsAes(passphrase: String, jsonPayload: String): String? {
        return try {
            val cipherObj = tryParseJson<BePlayerCipher>(jsonPayload) ?: return null
            val ctBase64 = cipherObj.ct ?: return null
            val ivHex = cipherObj.iv ?: return null
            val saltHex = cipherObj.s ?: return null

            val ctBytes = Base64.decode(ctBase64, Base64.DEFAULT)
            val ivBytes = hexToBytes(ivHex)
            val saltBytes = hexToBytes(saltHex)
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)

            // OpenSSL EVP_BytesToKey with MD5 (32-byte key for AES-256)
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(passBytes)
            md5.update(saltBytes)
            val d1 = md5.digest()

            md5.reset()
            md5.update(d1)
            md5.update(passBytes)
            md5.update(saltBytes)
            val d2 = md5.digest()

            val keyBytes = ByteArray(32)
            System.arraycopy(d1, 0, keyBytes, 0, 16)
            System.arraycopy(d2, 0, keyBytes, 16, 16)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )
            String(cipher.doFinal(ctBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }
}
