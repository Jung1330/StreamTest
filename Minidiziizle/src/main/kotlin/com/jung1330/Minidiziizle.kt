// ! StreamTest - Mini Dizi İzle Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Minidiziizle : MainAPI() {
    override var mainUrl              = "https://minidiziizle.com"
    override var name                 = "MiniDiziİzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/dublajli-mini-diziler/"          to "Dublajlı Mini Diziler",
        "$mainUrl/altyazili-mini-diziler/"         to "Altyazılı Mini Diziler",
        "$mainUrl/en-cok-izlenenler/"              to "En Çok İzlenenler",
        "$mainUrl/kategori/cin-yapimi-diziler/"    to "Çin Yapımı Diziler",
        "$mainUrl/kategori/bati-yapimi-diziler/"   to "Batı Yapımı Diziler"
    )

    private val epNumRegex = Regex("""-(\d+)-bolum/?$""", RegexOption.IGNORE_CASE)

    private fun parseCards(doc: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()

        doc.select("article.mdz-kart, div.mdz-kart__eylemler[data-link]").forEach { el ->
            val actions = if (el.hasAttr("data-link")) el else el.selectFirst(".mdz-kart__eylemler[data-link]")
            val rawHref = actions?.attr("data-link")?.ifBlank { null }
                ?: el.selectFirst("a[href*='/icerik/']")?.attr("href")
                ?: return@forEach

            val href = fixUrlNull(rawHref) ?: return@forEach
            if (!href.contains("/icerik/") || !seen.add(href)) return@forEach

            val img = el.selectFirst("img")
            val title = actions?.attr("data-baslik")?.trim().orEmpty()
                .ifBlank { el.selectFirst("h2, h3, .mdz-kart__baslik")?.text()?.trim().orEmpty() }
                .ifBlank { img?.attr("alt")?.removeSuffix(" afişi")?.trim().orEmpty() }
            if (title.isBlank()) return@forEach

            val poster = fixUrlNull(
                actions?.attr("data-afis")?.ifBlank { null }
                    ?: img?.attr("data-src")?.ifBlank { null }
                    ?: img?.attr("src")
            )

            out.add(
                newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                    this.posterUrl = poster
                }
            )
        }

        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")

        val jsonText = runCatching {
            app.get(
                "$mainUrl/wp-json/minidizizle/v1/arama?q=$encoded",
                headers = mapOf("Referer" to "$mainUrl/")
            ).text
        }.getOrNull().orEmpty()

        val root = runCatching { JSONObject(jsonText) }.getOrNull()
        val arr = root?.optJSONArray("sonuclar")
        if (arr != null) {
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("baslik").trim()
                val link = fixUrlNull(item.optString("link")) ?: continue
                if (title.isBlank()) continue
                val poster = fixUrlNull(item.optString("afis"))

                out.add(
                    newTvSeriesSearchResponse(title, link, TvType.AsianDrama) {
                        this.posterUrl = poster
                    }
                )
            }
            if (out.isNotEmpty()) return out.distinctBy { it.url }
        }

        val doc = runCatching { app.get("$mainUrl/?s=$encoded").document }.getOrNull() ?: return emptyList()
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val title = rawTitle
            .substringBefore(" İzle")
            .substringBefore(" izle")
            .substringBefore(" | ")
            .substringBefore(" – ")
            .trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".mdz-kart__eylemler[data-afis]")?.attr("data-afis")
                ?: doc.selectFirst("img.wp-post-image")?.attr("src")
        )
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val tags = doc.select("a[href*='/kategori/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Yapımlar", ignoreCase = true) }
            .distinct()

        val episodes = doc.select("a[href*='/bolum/']").mapNotNull { a ->
            val epUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val epNum = epNumRegex.find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(a.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            newEpisode(epUrl) {
                this.name    = "$epNum. Bölüm"
                this.season  = 1
                this.episode = epNum
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }

        val recommendations = parseCards(doc).filter { it.url != url }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl       = poster
            this.plot            = plot
            this.tags            = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data).document
        } catch (t: Throwable) {
            Log.e("MiniDiziİzle", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val playerEl = doc.selectFirst(".mdz-oynatici[data-bolum][data-jeton]") ?: return false
        val bolumId  = playerEl.attr("data-bolum").trim()
        val jeton    = playerEl.attr("data-jeton").trim()
        val isPool   = playerEl.attr("data-havuz").trim() == "1"
        if (bolumId.isBlank() || jeton.isBlank()) return false

        val endpoints = if (isPool) listOf("pool-playback", "playback") else listOf("playback", "pool-playback")
        val langVariants = listOf(
            "dublaj"  to "Türkçe Dublaj",
            "altyazi" to "Türkçe Altyazılı"
        )

        val seenUrls = LinkedHashSet<String>()
        val seenSubs = LinkedHashSet<String>()
        var emitted = false

        for ((langKey, langLabel) in langVariants) {
            for (epType in endpoints) {
                val apiUrl = "$mainUrl/wp-json/minidizizle/v1/$epType/$bolumId?token=${URLEncoder.encode(jeton, "UTF-8")}&lang=$langKey"
                val jsonText = runCatching {
                    app.get(apiUrl, headers = mapOf("Referer" to data)).text
                }.getOrNull().orEmpty()

                val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
                val streamUrl = obj.optString("video_url").replace("\\/", "/").trim()
                if (!streamUrl.startsWith("http")) continue
                if (!seenUrls.add(streamUrl)) break

                val subUrl = fixUrlNull(obj.optString("altyazi_url").ifBlank { obj.optString("subtitle_url") })
                if (subUrl != null && seenSubs.add(subUrl)) {
                    subtitleCallback.invoke(SubtitleFile("Türkçe", subUrl))
                }

                val isHls = obj.optString("tur").equals("hls", ignoreCase = true) || streamUrl.contains(".m3u8", ignoreCase = true)
                val reqHeaders = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)

                callback.invoke(
                    newExtractorLink(
                        source = "${this.name} • $langLabel",
                        name   = "${this.name} • $langLabel",
                        url    = streamUrl,
                        type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = reqHeaders
                        this.quality = Qualities.P1080.value
                    }
                )
                emitted = true
                break
            }
        }

        return emitted
    }
}
