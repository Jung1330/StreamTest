// ! StreamTest - DramaDünyam Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Dramadunyam : MainAPI() {
    override var mainUrl              = "https://dramadunyam.com"
    override var name                 = "DramaDünyam"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/api/yeni-eklenenler?adet=24&sayfa=" to "Yeni Eklenenler",
        "$mainUrl/api/akis?seed=1&adet=24&sayfa="     to "Keşfet / Akış"
    )

    @Volatile
    private var ticketCookies: Map<String, String> = emptyMap()

    private suspend fun ensureTicket(force: Boolean = false): Map<String, String> {
        if (!force && ticketCookies.isNotEmpty()) return ticketCookies
        val resp = runCatching {
            app.get("$mainUrl/api/config", headers = mapOf("Referer" to "$mainUrl/"))
        }.getOrNull()
        if (resp != null && resp.cookies.isNotEmpty()) {
            ticketCookies = resp.cookies
        }
        return ticketCookies
    }

    private suspend fun apiGetJson(url: String, extraHeaders: Map<String, String> = emptyMap()): JSONObject? {
        var cookies = ensureTicket(false)
        val headers = mapOf("Referer" to "$mainUrl/") + extraHeaders

        var resp = runCatching { app.get(url, headers = headers, cookies = cookies) }.getOrNull()
        if (resp == null || resp.code == 412 || resp.text.contains("\"bilet\"")) {
            cookies = ensureTicket(true)
            resp = runCatching { app.get(url, headers = headers, cookies = cookies) }.getOrNull()
        }
        val text = resp?.text?.trim().orEmpty()
        if (text.isBlank()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun parseItems(arr: JSONArray?): List<SearchResponse> {
        if (arr == null) return emptyList()
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val slug = item.optString("slug").trim()
            val title = item.optString("title").trim().ifBlank { item.optString("name").trim() }
            if (slug.isBlank() || title.isBlank()) continue

            val seriesUrl = "$mainUrl/dizi/$slug"
            if (!seen.add(seriesUrl)) continue

            val cover = fixUrlNull(item.optString("cover").ifBlank { item.optString("poster") })

            out.add(
                newTvSeriesSearchResponse(title, seriesUrl, TvType.AsianDrama) {
                    this.posterUrl = cover
                }
            )
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val obj = apiGetJson("${request.data}$page")
        val arr = obj?.optJSONArray("items") ?: obj?.optJSONArray("data")
        val items = parseItems(arr)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")

        val obj = apiGetJson("$mainUrl/api/search?q=$encoded&page=1&limit=24")
        val arr = obj?.optJSONArray("data") ?: obj?.optJSONArray("items")
        return parseItems(arr)
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfter("/dizi/").substringBefore("/").substringBefore("?").trim()
        if (slug.isBlank()) return null

        val obj = apiGetJson("$mainUrl/api/series/$slug") ?: return null
        val seriesId = obj.optString("id").trim().ifBlank { slug }
        val title = obj.optString("title").trim().takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(obj.optString("cover"))
        val plot = obj.optString("description").trim().takeIf { it.isNotBlank() && it != "null" }

        val tags = buildList {
            obj.optString("platform").trim().takeIf { it.isNotBlank() && it != "null" }?.let { add(it) }
            obj.optString("genre").split(",").map { it.trim() }.filter { it.isNotBlank() && it != "null" }.forEach { add(it) }
        }.distinct()

        val epCount = maxOf(
            obj.optInt("available_episodes", 0),
            obj.optInt("total_episodes", 0),
            1
        )

        val episodes = (1..epCount).map { epNum ->
            newEpisode("$mainUrl/play/$seriesId/$epNum") {
                this.name    = "$epNum. Bölüm"
                this.season  = 1
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, "$mainUrl/dizi/$slug", TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var obj: JSONObject? = null
        for (attempt in 0 until 5) {
            obj = apiGetJson(
                data,
                extraHeaders = mapOf(
                    "X-DD-Native-MP4"    to "1",
                    "X-DD-HLS-Subtitles" to "1"
                )
            )
            if (obj == null) break
            val err = obj.optString("hata")
            if (err == "yenileniyor" || err == "hazirlaniyor") {
                val waitSec = obj.optLong("retry_after", 2L).coerceIn(1L, 4L)
                delay(waitSec * 1000L)
                continue
            }
            break
        }

        if (obj == null) {
            Log.e("DramaDünyam", "Yayın yanıtı alınamadı: $data")
            return false
        }

        val streamUrl = fixUrlNull(obj.optString("url").replace("\\/", "/").trim()) ?: return false
        val typeStr = obj.optString("type").lowercase()
        val isHls = typeStr == "hls" || streamUrl.contains(".m3u8", ignoreCase = true)

        val subsArr = obj.optJSONArray("subtitles") ?: obj.optJSONArray("alt")
        if (subsArr != null) {
            for (i in 0 until subsArr.length()) {
                val sub = subsArr.optJSONObject(i) ?: continue
                val subUrl = fixUrlNull(sub.optString("src").ifBlank { sub.optString("url") }) ?: continue
                val label = sub.optString("label").ifBlank { sub.optString("dil") }.ifBlank { "Türkçe" }
                subtitleCallback.invoke(SubtitleFile(label, subUrl))
            }
        }

        val reqHeaders = mapOf("Referer" to "$mainUrl/")
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = streamUrl,
                type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.headers = reqHeaders
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }
}
