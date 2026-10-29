// ! StreamTest - TRAnimeİzle Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class TRAnimeIzle : MainAPI() {
    override var mainUrl              = "https://www.tranimeizle.live"
    override var name                 = "TRAnimeİzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/listeler/eklenen/sayfa-"  to "Son Eklenenler",
        "${mainUrl}/listeler/populer/sayfa-"  to "Popüler Animeler",
        "${mainUrl}/listeler/yenibolum/sayfa-" to "Son Bölümler"
    )

    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        doc.select("div.flx-block").forEach { el ->
            val anchor  = el.selectFirst("a.news-image") ?: el.selectFirst("a[href]")
            val rawHref = el.attr("data-href").ifBlank { anchor?.attr("href").orEmpty() }
            val href    = fixUrlNull(rawHref) ?: return@forEach

            val title = el.selectFirst("div.bar h4")?.text()?.trim()
                ?: el.selectFirst("h4")?.text()?.trim()
                ?: el.selectFirst("img")?.attr("alt")?.trim()
                ?: anchor?.attr("title")?.trim()
                ?: ""

            if (title.isBlank()) return@forEach

            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(el.selectFirst("img")?.attr("data-src"))

            out.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            })
        }

        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page").document
        return newHomePageResponse(request.name, parseCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return try {
            parseCards(app.get("${mainUrl}/arama/$encoded").document)
        } catch (t: Throwable) {
            Log.e("TRAnimeIzle", "Arama başarısız: ${t.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        if (doc.title().contains("Bot Kontrol", true)) {
            Log.e("TRAnimeIzle", "Bot koruması: $url")
            throw ErrorLoadingException("TRAnimeİzle bot koruması devrede, sayfa okunamadı")
        }

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val title = rawTitle
            .substringBefore(" İzle")
            .substringBefore(" izle")
            .substringBefore(" - TRAnimeİzle")
            .substringBefore(" – TRAnimeİzle")
            .trim()

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(doc.selectFirst("div.anime-poster img")?.attr("src"))

        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val episodes = mutableListOf<Episode>()

        doc.select("a[href*='-bolum']").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!href.contains("bolum")) return@forEach

            val text = a.text().trim()

            val seasonNum = Regex("""(\d+)\.\s*Sezon""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-sezon""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            val epNum = Regex("""(\d+)\.\s*Bölüm""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-bolum""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach

            episodes.add(newEpisode(href) {
                this.name    = text.ifEmpty { "$epNum. Bölüm" }
                this.season  = seasonNum
                this.episode = epNum
            })
        }

        // Bazı sayfalarda bölümler <select> içinde geliyor
        if (episodes.isEmpty()) {
            doc.select("select option[value*='bolum']").forEach { option ->
                val href = fixUrlNull(option.attr("value")) ?: return@forEach
                val ep   = Regex("""(\d+)-bolum""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(option.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach

                episodes.add(newEpisode(href) {
                    this.name    = option.text().trim().ifEmpty { "$ep. Bölüm" }
                    this.season  = 1
                    this.episode = ep
                })
            }        }

        // Tekil bölüm sayfası
        if (episodes.isEmpty() && url.contains("-bolum")) {
            val ep = Regex("""(\d+)-bolum""").find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\.\s*Bölüm""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            episodes.add(newEpisode(url) {
                this.name    = title
                this.season  = 1
                this.episode = ep
            })
        }

        val distinctEpisodes = episodes
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.Anime, distinctEpisodes) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = try {
            app.get(data).text
        } catch (t: Throwable) {
            Log.e("TRAnimeIzle", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        if (html.contains("Bot Kontrol")) {
            Log.e("TRAnimeIzle", "Bot koruması: $data")
            return false
        }

        val normalized = html.replace("\\/", "/")
        val found      = linkedSetOf<String>()

        // 1) iframe (src veya lazy data-src)
        Regex("""<iframe[^>]+?(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { found.add(it.groupValues[1]) }

        // 2) video / source etiketleri
        Regex("""<(?:video|source)[^>]+?(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { found.add(it.groupValues[1]) }

        // 3) "sources": [{"label": "...", "url": "..."}]
        Regex("""label\\?"\s*:\s*\\?"([^"\\]+)\\?"\s*,\s*\\?"url\\?"\s*:\s*\\?"([^"\\]+)\\?"""")
            .findAll(normalized)
            .forEach { found.add(it.groupValues[2]) }

        // 4) Bilinen oynatıcı sağlayıcıları (fallback)
        Regex(
            """https?://(?:[\w.-]+\.)*(?:ok\.ru|mail\.ru|sibnet\.ru|voe\.sx|vidmoly\.\w+|""" +
                    """anizmplayer\.com|abyssplayer\.com|dailymotion\.com|dood\.\w+|""" +
                    """streamtape\.com|mp4upload\.com|sfile\.mobi|youtube\.com|youtu\.be)/[^\s"'\\<>]+"""
        ).findAll(normalized).forEach { found.add(it.value) }

        var loaded = false
        for (raw in found) {
            val videoUrl = raw.trim().replace("&amp;", "&")
            if (!videoUrl.startsWith("http")) continue
            if (videoUrl.contains("googletagmanager") || videoUrl.contains("google-analytics")) continue
            if (videoUrl.contains("doubleclick") || videoUrl.contains("/ads")) continue

            Log.d("TRAnimeIzle", "Oynatıcı » $videoUrl")
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
                if (!handled) Log.w("TRAnimeIzle", "Bu oynatıcı için extractor yok: $videoUrl")
            }
        }

        if (!loaded) Log.e("TRAnimeIzle", "Hiçbir oynatıcı bulunamadı: $data")
        return loaded
    }
}
