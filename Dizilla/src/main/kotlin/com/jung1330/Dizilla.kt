// ! StreamTest - Dizilla Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

class Dizilla : MainAPI() {
    override var mainUrl              = "https://dizilla.la"
    override var name                 = "Dizilla"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler"
    )

    private val seriesHref   = Regex("""^/dizi/([a-z0-9\-]+)/?$""")
    private val episodeHref  = Regex("""/([a-z0-9\-]+?)-(\d+)-sezon-(\d+)-bolum""")
    private val reserved     = setOf("dizi", "tum-bolumler", "arama", "giris", "kayit")
    private val iframeRegex  = Regex("""https?://[^"'\s\\]+?/iframe\.php\?v=[A-Za-z0-9_\-]+""")
    private val gateConstRx  = Regex("""String\.fromCharCode\(([\d,\s]+)\)""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val doc = app.get(request.data).document
        val out = mutableListOf<SearchResponse>()

        doc.select("a[href^='/dizi/']").forEach { a ->
            val href = a.attr("href").trim()
            val slug = seriesHref.find(href)?.groupValues?.get(1) ?: return@forEach
            if (slug in reserved) return@forEach

            val label = a.attr("aria-label").substringBefore(",").trim()
            val title = label.ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }

            val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))

            out.add(newTvSeriesSearchResponse(title, "$mainUrl/dizi/$slug", TvType.TvSeries) {
                this.posterUrl = poster
            })
        }

        return newHomePageResponse(request.name, out.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            app.get("$mainUrl/ara?q=$encoded").document
        } catch (t: Throwable) {
            Log.e("Dizilla", "Arama hatası: ${t.message}")
            return emptyList()
        }

        val out = mutableListOf<SearchResponse>()
        doc.select("a[href^='/dizi/']").forEach { a ->
            val href = a.attr("href").trim()
            val slug = seriesHref.find(href)?.groupValues?.get(1) ?: return@forEach
            if (slug in reserved) return@forEach

            val label = a.attr("aria-label").substringBefore(",").trim()
            val title = label.ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }

            out.add(newTvSeriesSearchResponse(title, "$mainUrl/dizi/$slug", TvType.TvSeries) {
                this.posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
            })
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text

        val doc = org.jsoup.Jsoup.parse(html)
        val title = runCatching {
            (doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?: doc.selectFirst("h1")?.text())
                ?.substringBefore(" izle")?.substringBefore(" İzle")?.substringBefore(" | ")?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val poster  = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot    = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        // Bölüm listesi JSON-LD / Next.js verisi içinde "slug-3-sezon-1-bolum" biçiminde geçiyor.
        val unescaped = html.replace("\\\"", "\"")
        var episodes = episodeHref.findAll(unescaped).mapNotNull { m ->
            val season  = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val episode = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val path    = m.value.trimEnd('/')

            newEpisode(fixUrl(path)) {
                this.name    = "$season. Sezon $episode. Bölüm"
                this.season  = season
                this.episode = episode
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode })).toList()

        // Yedek: sayfadaki doğrudan bağlantılar
        if (episodes.isEmpty()) {
            episodes = doc.select("a[href*='-sezon-']").mapNotNull { a ->
                val href = a.attr("href").trim()
                val m = episodeHref.find(href) ?: return@mapNotNull null
                val season  = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val episode = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                newEpisode(fixUrl(href.substringBefore("?"))) {
                    this.name    = "$season. Sezon $episode. Bölüm"
                    this.season  = season
                    this.episode = episode
                }
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode })).toList()
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    /** Pichive oynatıcısındaki NP_CONFIG içinden JSON nesnesini çıkarır. */
    private fun extractConfig(html: String): JSONObject? {
        val marker = "NP_CONFIG"
        val idx    = html.indexOf(marker)
        if (idx < 0) return null

        val start = html.indexOf('{', idx)
        if (start < 0) return null

        var depth = 0
        var inStr = false
        var esc   = false
        var end   = -1

        for (i in start until html.length) {
            val ch = html[i]
            when {
                esc -> esc = false
                ch == '\\' && inStr -> esc = true
                ch == '"' -> inStr = !inStr
                !inStr && ch == '{' -> depth++
                !inStr && ch == '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end < 0) return null

        return runCatching { JSONObject(html.substring(start, end + 1)) }.getOrNull()
    }

    /**
     * Pichive NP_GATE (sitedeki window.NP_GATE): ondalık nonce + "|" + sabit + "|" + gate
     * SHA-256'sının 3. 32-bit kelimesinin (byte 8..11) alt 12 biti sıfır olmalı.
     */
    private fun gateNonce(gate: String, constant: String): String {
        val md   = MessageDigest.getInstance("SHA-256")
        val tail = "|$constant|$gate"

        for (n in 0 until 3_000_000) {
            val candidate = n.toString()
            val digest    = md.digest((candidate + tail).toByteArray(Charsets.UTF_8))
            val word = ((digest[8].toInt() and 0xFF) shl 24) or
                       ((digest[9].toInt() and 0xFF) shl 16) or
                       ((digest[10].toInt() and 0xFF) shl 8) or
                       (digest[11].toInt() and 0xFF)

            if ((word and 0xFFF) == 0) return candidate
        }
        return ""
    }

    /** İframe HTML'inden NP_GATE sabitini ("RT6Px-k_Tv" gibi) okur. */
    private fun gateConstant(html: String): String {
        val raw = gateConstRx.find(html)?.groupValues?.get(1).orEmpty()
        if (raw.isBlank()) return "RT6Px-k_Tv"

        val text = raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { it.toChar() }
            .joinToString("")

        return text.ifBlank { "RT6Px-k_Tv" }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val pageHtml = try {
            app.get(data).text
        } catch (t: Throwable) {
            Log.e("Dizilla", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val unescaped = pageHtml.replace("\\\"", "\"")
        val iframes = iframeRegex.findAll(unescaped).map { it.value }.distinct().toList()
            .ifEmpty { listOf(data) }

        var emitted = false

        for (iframe in iframes) {
            val iframeHtml = if (iframe == data) {
                unescaped
            } else {
                runCatching {
                    app.get(iframe, headers = mapOf("Referer" to data)).text
                }.getOrNull() ?: continue
            }

            val config = extractConfig(iframeHtml) ?: continue

            val gate     = config.optJSONObject("gate")
            val gateN    = gate?.optString("n").orEmpty()
            val suffix   = if (gateN.isNotBlank()) {
                "&p=" + gateNonce(gateN, gateConstant(iframeHtml))
            } else ""

            val subtitles = config.optJSONArray("subtitles")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val sub = subtitles.optJSONObject(i) ?: continue
                    val subUrl = fixUrlNull(sub.optString("url")) ?: continue
                    val label  = sub.optString("label").ifBlank { sub.optString("lang") }.ifBlank { "TR" }
                    subtitleCallback.invoke(SubtitleFile(label, subUrl + suffix))
                }
            }

            val sources = config.optJSONArray("sources") ?: continue
            val iframeOrigin = iframe.substringBefore("/iframe.php")
            val reqHeaders = mapOf("Referer" to iframe, "Origin" to iframeOrigin)

            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                if (source.optBoolean("trailer", false)) continue

                val master = source.optString("master").trim()
                if (master.isBlank()) continue

                val masterUrl = master + suffix
                val sourceName = source.optString("title").ifBlank { this.name }
                val label = if (sourceName == this.name) this.name else "${this.name} • $sourceName"

                // Master erişilebilir mi? Oynatıcıya verilecek adres doğrulanmış oluyor.
                val ok = runCatching {
                    val body = app.get(masterUrl, headers = reqHeaders).text
                    body.contains("#EXTM3U") || body.contains("#EXTINF")
                }.getOrDefault(false)

                if (!ok) {
                    Log.e("Dizilla", "Master yayın doğrulanamadı: $sourceName")
                    continue
                }

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = label,
                        url    = masterUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframe
                        this.headers = reqHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted = true
            }
        }

        if (!emitted) Log.e("Dizilla", "Yayın kaynağı bulunamadı: $data")
        return emitted
    }
}
