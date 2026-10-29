// ! StreamTest - Diziwatch Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import java.net.URLEncoder

class Diziwatch : MainAPI() {
    override var mainUrl              = "https://diziwatch.org"
    override var name                 = "Diziwatch"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/anime-arsivi?sayfa=" to "Anime Arşivi",
        "${mainUrl}/yeni-bolumler?sayfa=" to "Son Bölümler"
    )

    private val cardRegex    = Regex("""^/dizi/([^/]+)(?:/sezon-\d+/bolum-\d+)?$""")
    private val episodeRegex = Regex("""sezon-(\d+)/bolum-(\d+)""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc  = app.get("${request.data}$page").document
        val seen = mutableSetOf<String>()
        val home = mutableListOf<SearchResponse>()
        val onlyEpisodes = request.data.contains("/yeni-bolumler")

        doc.select("a[href^='/dizi/']").forEach { a ->
            val href = a.attr("href").substringBefore("?").substringBefore("#").trim()
            val match = cardRegex.find(href) ?: return@forEach
            val slug  = match.groupValues[1]
            if (slug.isBlank()) return@forEach
            if (onlyEpisodes && !href.contains("/sezon-")) return@forEach

            val seriesUrl = "$mainUrl/dizi/$slug"
            if (!seen.add(seriesUrl)) return@forEach

            val img = a.selectFirst("img")
            val title = img?.attr("alt")?.trim().orEmpty()
                .ifEmpty { a.attr("title").trim() }
                .ifEmpty { a.text().trim() }
                .ifEmpty { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }

            val poster = fixUrlNull(img?.attr("src")) ?: fixUrlNull(img?.attr("data-src"))

            home.add(newAnimeSearchResponse(title, seriesUrl, TvType.Anime) {
                this.posterUrl = poster
            })
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val text = try {
            app.get("${mainUrl}/api/ara?q=$encoded").text
        } catch (t: Throwable) {
            Log.e("Diziwatch", "Arama başarısız: ${t.message}")
            return emptyList()
        }
        val results = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()

        val out = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item  = results.optJSONObject(i) ?: continue
            val slug  = item.optString("slug").trim()
            val title = item.optString("title").trim()
            if (slug.isBlank() || title.isBlank()) continue

            val year = item.optInt("year").takeIf { it > 1900 }
            out.add(newAnimeSearchResponse(title, "$mainUrl/dizi/$slug", TvType.Anime) {
                this.posterUrl = fixUrlNull(item.optString("poster"))
                this.year      = year
            })
        }
        return out
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val title = rawTitle
            .substringBefore(" izle")
            .substringBefore(" İzle")
            .substringBefore(" – diziwatch")
            .substringBefore(" - diziwatch")
            .trim()

        val poster      = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val year        = doc.selectFirst("span.info:contains(Yapım Yılı) + span")?.text()?.trim()?.toIntOrNull()
        val tags        = doc.selectFirst("span.info:contains(Tür) + span")?.text()
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val episodes = mutableListOf<Episode>()
        doc.select("a[href*='/sezon-'][href*='/bolum-']").forEach { a ->
            val href  = fixUrlNull(a.attr("href")) ?: return@forEach
            val match = episodeRegex.find(a.attr("href")) ?: return@forEach
            val sn    = match.groupValues[1].toIntOrNull() ?: return@forEach
            val ep    = match.groupValues[2].toIntOrNull() ?: return@forEach

            episodes.add(newEpisode(href) {
                this.name    = "$sn. Sezon $ep. Bölüm"
                this.season  = sn
                this.episode = ep
            })
        }

        val distinctEpisodes = episodes
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        if (distinctEpisodes.isEmpty()) {
            Log.e("Diziwatch", "Bölüm bulunamadı: $url")
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, distinctEpisodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = try {
            app.get(data).text
        } catch (t: Throwable) {
            Log.e("Diziwatch", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val normalized = html.replace("\\/", "/")
        val found      = linkedSetOf<String>()

        // 1) Sayfadaki "sources" JSON'u: {"label":"...","url":"..."}
        Regex("""label\\?"\s*:\s*\\?"([^"\\]+)\\?"\s*,\s*\\?"url\\?"\s*:\s*\\?"([^"\\]+)\\?"""")
            .findAll(normalized)
            .forEach { found.add(it.groupValues[2]) }

        // 2) Bilinen oynatıcı sağlayıcıları (fallback)
        Regex(
            """https?://(?:[\w.-]+\.)*(?:ok\.ru|mail\.ru|sibnet\.ru|voe\.sx|vidmoly\.\w+|""" +
                    """anizmplayer\.com|abyssplayer\.com|dailymotion\.com|dood\.\w+|""" +
                    """streamtape\.com|mp4upload\.com|sfile\.mobi)/[^\s"'\\<>]+"""
        ).findAll(normalized).forEach { found.add(it.value) }

        // 3) iframe (src veya lazy data-src)
        Regex("""<iframe[^>]+?(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { found.add(it.groupValues[1]) }

        // 4) video / source etiketleri
        Regex("""<(?:video|source)[^>]+?(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { found.add(it.groupValues[1]) }

        var loaded = false
        for (raw in found) {
            val videoUrl = raw.trim().replace("&amp;", "&")
            if (!videoUrl.startsWith("http")) continue
            if (videoUrl.contains("google") || videoUrl.contains("doubleclick") || videoUrl.contains("/ads")) continue

            Log.d("Diziwatch", "Oynatıcı » $videoUrl")
            loaded = true

            if (videoUrl.matches(Regex(""".*\.(m3u8|mp4)(\?.*)?$""", RegexOption.IGNORE_CASE))) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = videoUrl,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                val handled = loadExtractor(videoUrl, "$mainUrl/", subtitleCallback, callback)
                if (!handled) Log.w("Diziwatch", "Bu oynatıcı için extractor yok: $videoUrl")
            }
        }

        if (!loaded) Log.e("Diziwatch", "Hiçbir oynatıcı bulunamadı: $data")
        return loaded
    }
}
