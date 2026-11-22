// ! StreamTest - Shortizle Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Shortizle : MainAPI() {
    override var mainUrl              = "https://shortizle.com"
    override var name                 = "Shortizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-cikanlar" to "Yeni Çıkanlar",
        "$mainUrl/trendler"      to "Trendler",
        "$mainUrl/populer"       to "Popüler",
        "$mainUrl/diziler"       to "Diziler",
        "$mainUrl/k-drama"       to "K-Drama",
        "$mainUrl/filmler"       to "Filmler"
    )

    private val bgUrlRegex = Regex("""url\(['"]?(https?://[^'")\s]+)['"]?\)""")

    private fun extractProps(doc: Document): JSONObject? {
        val raw = doc.selectFirst("script[data-page=app]")?.data()?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { JSONObject(raw).optJSONObject("props") }.getOrNull()
    }

    private fun extractPoster(item: JSONObject): String? {
        val direct = item.optString("poster").takeIf { it.isNotBlank() && it != "null" }
        if (direct != null) return fixUrlNull(direct)
        val grad = item.optString("poster_gradient")
        val fromGrad = bgUrlRegex.find(grad)?.groupValues?.getOrNull(1)
        return fixUrlNull(fromGrad)
    }

    private fun parseTitleArray(arr: JSONArray?): List<SearchResponse> {
        if (arr == null) return emptyList()
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val slug = item.optString("slug").trim()
            val title = item.optString("name").trim()
            if (slug.isBlank() || title.isBlank()) continue

            val url = "$mainUrl/icerik/$slug"
            if (!seen.add(url)) continue

            val poster = extractPoster(item)
            val year = item.optInt("year", 0).takeIf { it > 1900 }
            val type = item.optString("type").lowercase()

            if (type == "movie") {
                out.add(
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year      = year
                    }
                )
            } else {
                out.add(
                    newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
                        this.posterUrl = poster
                        this.year      = year
                    }
                )
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) request.data else "${request.data}?page=$page"
        val doc = app.get(targetUrl).document
        val props = extractProps(doc)
        val titlesObj = props?.optJSONObject("titles")
        val dataArr = titlesObj?.optJSONArray("data") ?: props?.optJSONArray("titles")
        val items = parseTitleArray(dataArr)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")

        val suggestText = runCatching {
            app.get(
                "$mainUrl/search/suggest?q=$encoded",
                headers = mapOf(
                    "Accept"           to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to "$mainUrl/"
                )
            ).text
        }.getOrNull().orEmpty()

        val suggestArr = runCatching { JSONObject(suggestText).optJSONArray("titles") }.getOrNull()
        val suggestItems = parseTitleArray(suggestArr)
        if (suggestItems.isNotEmpty()) return suggestItems

        val doc = runCatching { app.get("$mainUrl/search?q=$encoded").document }.getOrNull() ?: return emptyList()
        val props = extractProps(doc)
        val dataArr = props?.optJSONObject("titles")?.optJSONArray("data") ?: props?.optJSONArray("titles")
        return parseTitleArray(dataArr)
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = if (url.contains("/watch/")) {
            val slug = url.substringAfter("/watch/").substringBefore("/").substringBefore("?")
            "$mainUrl/icerik/$slug"
        } else {
            url
        }

        val doc = app.get(cleanUrl).document
        val props = extractProps(doc) ?: return null
        val titleObj = props.optJSONObject("title") ?: return null

        val slug = titleObj.optString("slug").trim().takeIf { it.isNotBlank() } ?: return null
        val title = titleObj.optString("name").trim().takeIf { it.isNotBlank() } ?: return null
        val plot = titleObj.optString("description").takeIf { it.isNotBlank() && it != "null" }
        val poster = extractPoster(titleObj)
            ?: fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val year = titleObj.optInt("year", 0).takeIf { it > 1900 }
        val isMovie = titleObj.optString("type").equals("movie", ignoreCase = true)

        val tags = mutableListOf<String>()
        val genresArr = titleObj.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                val gName = genresArr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (gName.isNotBlank()) tags.add(gName)
            }
        }

        val recommendations = parseTitleArray(props.optJSONArray("recommended"))

        val episodes = mutableListOf<Episode>()
        val seasonsArr = titleObj.optJSONArray("seasons")
        if (seasonsArr != null) {
            for (sIdx in 0 until seasonsArr.length()) {
                val sObj = seasonsArr.optJSONObject(sIdx) ?: continue
                val sNum = sObj.optInt("number", 1).coerceAtLeast(1)
                val epsArr = sObj.optJSONArray("episodes") ?: continue
                for (eIdx in 0 until epsArr.length()) {
                    val eObj = epsArr.optJSONObject(eIdx) ?: continue
                    if (eObj.optBoolean("is_upcoming", false)) continue
                    val eNum = eObj.optInt("number", eIdx + 1)
                    val eName = eObj.optString("name").trim().ifBlank { "$eNum. Bölüm" }
                    val watchUrl = "$mainUrl/watch/$slug/$sNum/$eNum"

                    episodes.add(
                        newEpisode(watchUrl) {
                            this.name    = eName
                            this.season  = sNum
                            this.episode = eNum
                        }
                    )
                }
            }
        }

        if (isMovie && episodes.size <= 1) {
            val watchUrl = episodes.firstOrNull()?.data ?: "$mainUrl/watch/$slug/1/1"
            return newMovieLoadResponse(title, cleanUrl, TvType.Movie, watchUrl) {
                this.posterUrl       = poster
                this.plot            = plot
                this.year            = year
                this.tags            = tags
                this.recommendations = recommendations
            }
        }

        return newTvSeriesLoadResponse(title, cleanUrl, TvType.AsianDrama, episodes) {
            this.posterUrl       = poster
            this.plot            = plot
            this.year            = year
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
        val pageResp = try {
            app.get(data)
        } catch (t: Throwable) {
            Log.e("Shortizle", "İzleme sayfası açılamadı: ${t.message}")
            return false
        }

        val props = extractProps(pageResp.document) ?: return false
        val extSources = props.optJSONObject("externalSources") ?: return false

        val kinds = listOf(
            "dublaj"  to "Türkçe Dublaj",
            "altyazi" to "Türkçe Altyazılı"
        )

        val seenUrls = LinkedHashSet<String>()
        val seenSubs = LinkedHashSet<String>()
        var emitted = false

        for ((kindKey, kindLabel) in kinds) {
            val arr = extSources.optJSONArray(kindKey) ?: continue
            for (i in 0 until arr.length()) {
                val srcObj = arr.optJSONObject(i) ?: continue
                var streamUrl = srcObj.optString("url").replace("\\/", "/").trim()
                var subUrl = srcObj.optString("subtitle_url").replace("\\/", "/").trim()
                var streamType = srcObj.optString("type").lowercase()

                val resolveUrl = srcObj.optString("resolve_url").replace("\\/", "/").trim()
                if (!streamUrl.startsWith("http") && resolveUrl.isNotBlank()) {
                    val resolvedJson = runCatching {
                        app.get(
                            fixUrl(resolveUrl),
                            headers = mapOf(
                                "Referer"          to data,
                                "Accept"           to "application/json",
                                "X-Requested-With" to "XMLHttpRequest"
                            ),
                            cookies = pageResp.cookies
                        ).text
                    }.getOrNull().orEmpty()

                    val rObj = runCatching { JSONObject(resolvedJson) }.getOrNull()
                    if (rObj != null) {
                        streamUrl = rObj.optString("url").replace("\\/", "/").trim()
                        if (subUrl.isBlank() || subUrl == "null") {
                            subUrl = rObj.optString("subtitle_url").replace("\\/", "/").trim()
                        }
                        if (streamType.isBlank()) {
                            streamType = rObj.optString("type").lowercase()
                        }
                    }
                }

                if (!streamUrl.startsWith("http") || !seenUrls.add(streamUrl)) continue

                if (subUrl.isNotBlank() && subUrl != "null") {
                    val fixedSub = fixUrlNull(subUrl)
                    if (fixedSub != null && seenSubs.add(fixedSub)) {
                        subtitleCallback.invoke(SubtitleFile("Türkçe", fixedSub))
                    }
                }

                val isHls = streamType == "hls" || streamUrl.contains(".m3u8", ignoreCase = true)
                val reqHeaders = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)

                callback.invoke(
                    newExtractorLink(
                        source = "${this.name} • $kindLabel",
                        name   = "${this.name} • $kindLabel",
                        url    = streamUrl,
                        type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = reqHeaders
                        this.quality = Qualities.P1080.value
                    }
                )
                emitted = true
            }
        }

        return emitted
    }
}
