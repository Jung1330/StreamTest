// ! StreamTest - JetFilmizle Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class Jetfilmizle : MainAPI() {
    override var mainUrl              = "https://jetfilmizle.app"
    override var name                 = "JetFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler"          to "Son Eklenen Filmler",
        "$mainUrl/turkce-dublaj"    to "Türkçe Dublaj Filmler",
        "$mainUrl/turkce-altyazili" to "Türkçe Altyazılı Filmler",
        "$mainUrl/yerli-filmler"    to "Yerli Filmler",
        "$mainUrl/yabanci-diziler"  to "Yabancı Diziler",
        "$mainUrl/yerli-diziler"    to "Yerli Diziler"
    )

    private val yearInTitleRegex = Regex("""\((\d{4})\)""")
    private val anyYearRegex     = Regex("""\b(19|20)\d{2}\b""")
    private val packedRegex      = Regex("""(?s)eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\s*\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\)\s*(?:,\s*0\s*,\s*\{\s*\}\s*)?\)\)""")
    private val m3u8FileRegex    = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val trackObjRegex    = Regex("""\{[^{}]*?["']?file["']?\s*:\s*["']([^"']+\.(?:vtt|srt)(?:\?[^"']*)?)["'][^{}]*\}""", RegexOption.IGNORE_CASE)

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(yearInTitleRegex, " ")
            .substringBefore(" Full")
            .substringBefore(" izle")
            .substringBefore(" İzle")
            .substringBefore(" | ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', ':')
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        doc.select("div.jf-card").forEach { card ->
            val a = card.selectFirst("a[href]") ?: return@forEach
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!href.contains("/film/") && !href.contains("/dizi/")) return@forEach

            val rawTitle = card.selectFirst("h3.fc-title, .fc-title")?.text()?.trim().orEmpty()
                .ifBlank { a.attr("title").trim() }
                .ifBlank { card.selectFirst("img")?.attr("alt")?.trim().orEmpty() }

            val title = cleanTitle(rawTitle)
            if (title.isBlank()) return@forEach

            val img = card.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
                ?.takeIf { !it.startsWith("data:") }
            val year = card.selectFirst("span.fc-year, .fc-year")?.text()?.trim()?.toIntOrNull()

            if (href.contains("/dizi/")) {
                out.add(
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year      = year
                    }
                )
            } else {
                out.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.year      = year
                    }
                )
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data.trimEnd('/')}/sayfa-$page"
        val doc = app.get(pageUrl).document
        return newHomePageResponse(request.name, parseCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            app.get("$mainUrl/arama?q=$encoded").document
        } catch (t: Throwable) {
            Log.e("JetFilmizle", "Arama hatası: ${t.message}")
            return emptyList()
        }
        return parseCards(doc)
    }

    private fun mapPlayerTypeLabel(playerType: String): String {
        return when (playerType.lowercase().trim()) {
            "dublaj"    -> "TR Dublaj"
            "altyazili" -> "TR Altyazılı"
            "genel"     -> "Orijinal"
            else        -> ""
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val year = yearInTitleRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: anyYearRegex.find(rawTitle)?.value?.toIntOrNull()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            doc.selectFirst(".film-poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
        val tags = doc.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && !it.equals("Tüm Türler", ignoreCase = true) }
            .distinct()

        val filmId = doc.selectFirst("input[name='film_id']")?.attr("value")?.trim().orEmpty()
        val epButtons = doc.select("button.episode-btn[data-season][data-episode][data-source-index]")

        if (url.contains("/dizi/") || epButtons.isNotEmpty()) {
            val grouped = linkedMapOf<Pair<Int, Int>, JSONArray>()

            epButtons.forEach { btn ->
                val s = btn.attr("data-season").trim().toIntOrNull() ?: return@forEach
                val e = btn.attr("data-episode").trim().toIntOrNull() ?: return@forEach
                val idx = btn.attr("data-source-index").trim()
                if (idx.isBlank()) return@forEach

                val pType = btn.attr("data-player-type").trim().ifBlank { "altyazili" }
                val typeLabel = mapPlayerTypeLabel(pType).ifBlank { btn.text().trim().ifBlank { "Kaynak" } }

                val key = s to e
                val arr = grouped.getOrPut(key) { JSONArray() }
                arr.put(
                    JSONObject()
                        .put("idx", idx)
                        .put("type", pType)
                        .put("label", typeLabel)
                )
            }

            val episodes = grouped.entries.map { (se, sourcesArr) ->
                val (s, e) = se
                val payload = JSONObject()
                    .put("url", url)
                    .put("filmId", filmId)
                    .put("sources", sourcesArr)
                    .toString()

                newEpisode(payload) {
                    this.name    = "$s. Sezon $e. Bölüm"
                    this.season  = s
                    this.episode = e
                }
            }.sortedWith(compareBy({ it.season }, { it.episode }))

            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        }

        val sourcesArr = JSONArray()
        doc.select("button.player-source-btn[data-source-index]:not(.fragman-btn)").forEach { btn ->
            val idx = btn.attr("data-source-index").trim()
            if (idx.isBlank()) return@forEach
            val pType = btn.attr("data-player-type").trim().ifBlank { "dublaj" }
            val btnName = btn.text().replace(Regex("""\s+"""), " ").trim().ifBlank { "Kaynak" }
            val typeTag = mapPlayerTypeLabel(pType)
            val fullLabel = if (typeTag.isNotEmpty() && !btnName.contains(typeTag, ignoreCase = true)) {
                "$btnName - $typeTag"
            } else {
                btnName
            }

            sourcesArr.put(
                JSONObject()
                    .put("idx", idx)
                    .put("type", pType)
                    .put("label", fullLabel)
            )
        }

        val moviePayload = JSONObject()
            .put("url", url)
            .put("filmId", filmId)
            .put("sources", sourcesArr)
            .toString()

        return newMovieLoadResponse(title, url, TvType.Movie, moviePayload) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, target: String): String {
        val clean = target.replace("\\/", "/").trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        if (clean.startsWith("//")) return "https:$clean"
        return runCatching { URI(baseUrl).resolve(clean).toString() }.getOrElse {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value.orEmpty()
            if (clean.startsWith("/")) "$origin$clean" else "${baseUrl.substringBeforeLast("/")}/$clean"
        }
    }

    /** Belirtilen işaretçiden sonra gelen ilk dengeli `{ ... }` JSON nesnesini çıkarır. */
    private fun extractJsonObjectAfter(html: String, marker: String): JSONObject? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val start = html.indexOf('{', idx + marker.length)
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
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return null
        return runCatching { JSONObject(html.substring(start, end + 1)) }.getOrNull()
    }

    private fun emitJsonTracks(
        tracksArr: JSONArray?,
        baseUrl: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (tracksArr == null) return
        for (i in 0 until tracksArr.length()) {
            val tr = tracksArr.optJSONObject(i) ?: continue
            val kind = tr.optString("kind").lowercase()
            if (kind == "thumbnails") continue
            val rawFile = tr.optString("file").ifBlank { tr.optString("src") }.trim()
            if (rawFile.isBlank()) continue
            val subUrl = resolveRelativeUrl(baseUrl, rawFile)
            if (!seenSubs.add(subUrl)) continue

            val label = tr.optString("label")
                .ifBlank { tr.optString("language") }
                .ifBlank { "Türkçe" }
            subtitleCallback.invoke(SubtitleFile(label, subUrl))
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
                source    = this@Jetfilmizle.name,
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

    private suspend fun emitDirectVideo(
        videoUrl: String,
        referer: String,
        linkName: String,
        qualityVal: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val reqHeaders = mapOf("Referer" to referer)
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = linkName,
                url    = videoUrl,
                type   = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.headers = reqHeaders
                this.quality = qualityVal
            }
        )
    }

    private suspend fun extractVidmoly(
        embedUrl: String,
        referer: String,
        linkName: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = runCatching {
            app.get(embedUrl, headers = mapOf("Referer" to referer))
        }.getOrNull() ?: return false

        val finalUrl = res.url.ifBlank { embedUrl }
        val candidates = buildList {
            add(res.text)
            packedRegex.findAll(res.text).forEach { block ->
                runCatching { JsUnpacker(block.value).unpack() }.getOrNull()?.let { add(it) }
            }
        }

        for (src in candidates) {
            trackObjRegex.findAll(src).forEach { m ->
                val obj = m.value
                if (obj.contains("thumbnails", ignoreCase = true)) return@forEach
                val subUrl = resolveRelativeUrl(finalUrl, m.groupValues[1])
                if (!seenSubs.add(subUrl)) return@forEach
                val lbl = Regex("""["']?label["']?\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1) ?: "Türkçe"
                subtitleCallback.invoke(SubtitleFile(lbl, subUrl))
            }
        }

        val m3u8 = candidates.firstNotNullOfOrNull { src ->
            m3u8FileRegex.find(src)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
        } ?: return false

        val originRef = runCatching {
            val u = URI(finalUrl)
            "${u.scheme}://${u.host}/"
        }.getOrDefault("https://vidmoly.to/")

        return emitHlsLinks(m3u8, originRef, linkName, callback)
    }

    private suspend fun resolveEmbedSource(
        rawEmbedUrl: String,
        label: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = when {
            rawEmbedUrl.startsWith("//") -> "https:$rawEmbedUrl"
            rawEmbedUrl.startsWith("http://ok.ru", ignoreCase = true) ->
                "https://" + rawEmbedUrl.removePrefix("http://")
            else -> rawEmbedUrl
        }
        val linkName = "${this.name} ($label)"
        val vpReferer = "https://videopark.top/"

        when {
            embedUrl.contains("videopark.top/oplayer/", ignoreCase = true) -> {
                val html = runCatching {
                    app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                }.getOrNull() ?: return

                val videoData = extractJsonObjectAfter(html, "VIDEO_DATA") ?: return
                emitJsonTracks(videoData.optJSONArray("tracks") ?: videoData.optJSONArray("subtitles"), embedUrl, seenSubs, subtitleCallback)

                val mp4s = videoData.optJSONArray("mp4Sources")
                if (mp4s != null) {
                    for (i in 0 until mp4s.length()) {
                        val item = mp4s.optJSONObject(i) ?: continue
                        val file = item.optString("file").replace("\\/", "/").trim()
                        if (file.isBlank()) continue
                        val q = item.optInt("height", 0).takeIf { it > 0 }
                            ?: getQualityFromName(item.optString("label"))
                        emitDirectVideo(resolveRelativeUrl(embedUrl, file), vpReferer, linkName, q, callback)
                    }
                }
            }

            embedUrl.contains("videopark.top/go/", ignoreCase = true) -> {
                val html = runCatching {
                    app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                }.getOrNull() ?: return

                val workerBase = Regex("""WORKER_BASE\s*=\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trimEnd('/')
                    ?: return
                val videoId = Regex("""VIDEO_ID\s*=\s*["']?(\d+)["']?""")
                    .find(html)?.groupValues?.get(1)
                    ?: return

                val infoJson = runCatching {
                    JSONObject(app.get("$workerBase/v/$videoId/info", headers = mapOf("Referer" to vpReferer)).text)
                }.getOrNull()

                val qualities = infoJson?.optJSONArray("qualities")
                var emittedAny = false
                if (qualities != null && qualities.length() > 0) {
                    for (i in 0 until qualities.length()) {
                        val rawQ = qualities.opt(i)?.toString()?.trim().orEmpty()
                        val qNum = rawQ.filter { it.isDigit() }.toIntOrNull() ?: continue
                        emitDirectVideo("$workerBase/v/$videoId?q=$qNum", vpReferer, linkName, qNum, callback)
                        emittedAny = true
                    }
                }
                if (!emittedAny) {
                    emitDirectVideo("$workerBase/v/$videoId", vpReferer, linkName, Qualities.P1080.value, callback)
                }
            }

            embedUrl.contains("videopark.top/vip/", ignoreCase = true) -> {
                val html = runCatching {
                    app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                }.getOrNull() ?: return

                val workerUrl = Regex("""WORKER_URL\s*=\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trimEnd('/')
                    ?: return
                val pubId = Regex("""PUB_ID\s*=\s*["']([^"']*)["']""").find(html)?.groupValues?.get(1).orEmpty()
                val videoId = Regex("""VIDEO_ID\s*=\s*["']?([^"';,\s]+)["']?""").find(html)?.groupValues?.get(1).orEmpty()
                val videoTitle = Regex("""VIDEO_TITLE\s*=\s*["']([^"']*)["']""").find(html)?.groupValues?.get(1).orEmpty()
                val publisherId = Regex("""PUBLISHER_ID\s*=\s*["']([^"']*)["']""").find(html)?.groupValues?.get(1).orEmpty()

                val apiCandidates = buildList {
                    if (pubId.isNotBlank()) {
                        val qPub = URLEncoder.encode(pubId, "UTF-8")
                        val qTitle = URLEncoder.encode(videoTitle, "UTF-8")
                        val qPid = URLEncoder.encode(publisherId, "UTF-8")
                        add("$workerUrl/api/stream?pubId=$qPub&title=$qTitle&publisherId=$qPid")
                    }
                    if (videoId.isNotBlank() && videoId != "null") {
                        add("$workerUrl/api/video?id=${URLEncoder.encode(videoId, "UTF-8")}")
                    }
                }

                for (apiUrl in apiCandidates) {
                    val dataJson = runCatching {
                        JSONObject(app.get(apiUrl, headers = mapOf("Referer" to vpReferer)).text)
                    }.getOrNull() ?: continue

                    emitJsonTracks(
                        dataJson.optJSONArray("tracks") ?: dataJson.optJSONArray("subtitles"),
                        embedUrl,
                        seenSubs,
                        subtitleCallback
                    )

                    var foundStream = false
                    val mp4s = dataJson.optJSONArray("mp4Sources")
                    if (mp4s != null) {
                        for (i in 0 until mp4s.length()) {
                            val item = mp4s.optJSONObject(i) ?: continue
                            val file = item.optString("file").replace("\\/", "/").trim()
                            if (file.isBlank()) continue
                            val q = item.optInt("height", 0).takeIf { it > 0 }
                                ?: getQualityFromName(item.optString("label"))
                            emitDirectVideo(resolveRelativeUrl(apiUrl, file), vpReferer, linkName, q, callback)
                            foundStream = true
                        }
                    }

                    val hlsFile = dataJson.optJSONObject("hlsSource")?.optString("file")
                        ?.ifBlank { null }
                        ?: dataJson.optString("hls").ifBlank { null }
                        ?: dataJson.optString("file").ifBlank { null }

                    if (!hlsFile.isNullOrBlank()) {
                        val cleanHls = resolveRelativeUrl(apiUrl, hlsFile)
                        if (emitHlsLinks(cleanHls, vpReferer, linkName, callback)) {
                            foundStream = true
                        }
                    }

                    if (foundStream) break
                }
            }

            embedUrl.contains("videopark.top/you/v/", ignoreCase = true) -> {
                val html = runCatching {
                    app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                }.getOrNull() ?: return

                val initial = extractJsonObjectAfter(html, "const initial")
                    ?: extractJsonObjectAfter(html, "initial")
                    ?: return

                emitJsonTracks(initial.optJSONArray("tracks"), embedUrl, seenSubs, subtitleCallback)

                val sources = initial.optJSONArray("sources") ?: return
                for (i in 0 until sources.length()) {
                    val srcObj = sources.optJSONObject(i) ?: continue
                    val rawFile = srcObj.optString("file").replace("\\/", "/").trim()
                    if (rawFile.isBlank()) continue
                    val streamUrl = resolveRelativeUrl(embedUrl, rawFile)
                    if (srcObj.optString("type").contains("mp4", ignoreCase = true) || streamUrl.endsWith(".mp4", true)) {
                        val q = getQualityFromName(srcObj.optString("label"))
                        emitDirectVideo(streamUrl, vpReferer, linkName, q, callback)
                    } else {
                        emitHlsLinks(streamUrl, vpReferer, linkName, callback)
                    }
                }
            }

            embedUrl.contains("videopark.top/jdizi/", ignoreCase = true) -> {
                val html = runCatching {
                    app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                }.getOrNull() ?: return

                val rawProxy = Regex("""rawProxyUrl\s*=\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
                    ?: return
                val workerUrl = Regex("""workerUrl\s*=\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trimEnd('/')

                val candidates = buildList {
                    if (!workerUrl.isNullOrBlank()) add("$workerUrl$rawProxy")
                    add(resolveRelativeUrl("https://videopark.top/", rawProxy))
                }
                for (cand in candidates) {
                    if (emitHlsLinks(cand, embedUrl, linkName, callback)) break
                }
            }

            embedUrl.contains("videopark.top/titan/", ignoreCase = true) -> {
                val html = runCatching {
                    app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                }.getOrNull() ?: return

                val sd = extractJsonObjectAfter(html, "var _sd")
                    ?: extractJsonObjectAfter(html, "_sd")
                    ?: return

                emitJsonTracks(sd.optJSONArray("subtitles"), embedUrl, seenSubs, subtitleCallback)

                val streamUrl = sd.optString("stream_url").replace("\\/", "/").trim()
                if (streamUrl.isNotBlank()) {
                    emitHlsLinks(resolveRelativeUrl(embedUrl, streamUrl), vpReferer, linkName, callback)
                }
            }

            embedUrl.contains("videopark.top/", ignoreCase = true) -> {
                val doc = runCatching {
                    app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).document
                }.getOrNull() ?: return

                val innerIframe = doc.selectFirst("iframe[src], iframe[data-src]")
                    ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                    ?.let { resolveRelativeUrl(embedUrl, it) }
                    ?: return

                if (innerIframe.contains("vidmoly", ignoreCase = true)) {
                    if (!extractVidmoly(innerIframe, vpReferer, linkName, seenSubs, subtitleCallback, callback)) {
                        loadExtractor(innerIframe, vpReferer, subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(innerIframe, vpReferer, subtitleCallback, callback)
                }
            }

            embedUrl.contains("vidmoly", ignoreCase = true) -> {
                if (!extractVidmoly(embedUrl, "$mainUrl/", linkName, seenSubs, subtitleCallback, callback)) {
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }

            else -> {
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = runCatching { JSONObject(data) }.getOrNull()
        val pageUrl = parsed?.optString("url")?.ifBlank { null } ?: data
        val sourcesJson = parsed?.optJSONArray("sources")

        val initRes = try {
            app.get(pageUrl)
        } catch (t: Throwable) {
            Log.e("JetFilmizle", "Sayfa açılamadı: ${t.message}")
            return false
        }

        val sessionCookies = initRes.cookies
        val pageDoc = initRes.document
        val filmId = parsed?.optString("filmId")?.ifBlank { null }
            ?: pageDoc.selectFirst("input[name='film_id']")?.attr("value")?.trim().orEmpty()

        val embeds = mutableListOf<Pair<String, String>>()
        val seenEmbedUrls = mutableSetOf<String>()

        if (sourcesJson != null && sourcesJson.length() > 0 && filmId.isNotBlank()) {
            for (i in 0 until sourcesJson.length()) {
                val srcObj = sourcesJson.optJSONObject(i) ?: continue
                val idx = srcObj.optString("idx").trim()
                val pType = srcObj.optString("type").trim()
                val label = srcObj.optString("label").trim().ifBlank { "Kaynak ${i + 1}" }
                if (idx.isBlank()) continue

                val respHtml = runCatching {
                    app.post(
                        "$mainUrl/jetplayer",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer"          to pageUrl
                        ),
                        cookies = sessionCookies,
                        data = mapOf(
                            "film_id"      to filmId,
                            "source_index" to idx,
                            "player_type"  to pType
                        )
                    ).text
                }.getOrNull().orEmpty()

                if (respHtml.isBlank()) continue

                val iframeSrc = Jsoup.parse(respHtml)
                    .selectFirst("iframe[src], iframe[data-src]")
                    ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                    ?.let { resolveRelativeUrl(pageUrl, it) }
                    ?: continue

                if (iframeSrc.contains("youtube.com", true) || iframeSrc.contains("youtu.be", true)) continue
                if (seenEmbedUrls.add(iframeSrc)) {
                    embeds.add(iframeSrc to label)
                }
            }
        }

        if (embeds.isEmpty()) {
            pageDoc.select("iframe[src], iframe[data-src]").forEachIndexed { i, iframe ->
                val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
                if (raw.isBlank()) return@forEachIndexed
                val resolved = resolveRelativeUrl(pageUrl, raw)
                if (resolved.contains("youtube.com", true) || resolved.contains("youtu.be", true)) return@forEachIndexed
                if (seenEmbedUrls.add(resolved)) {
                    embeds.add(resolved to "Kaynak ${i + 1}")
                }
            }
        }

        if (embeds.isEmpty()) return false

        var anyEmitted = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            anyEmitted = true
            callback.invoke(link)
        }
        val seenSubs = mutableSetOf<String>()

        for ((embedUrl, label) in embeds) {
            resolveEmbedSource(embedUrl, label, seenSubs, subtitleCallback, wrappedCallback)
        }

        return anyEmitted
    }
}
