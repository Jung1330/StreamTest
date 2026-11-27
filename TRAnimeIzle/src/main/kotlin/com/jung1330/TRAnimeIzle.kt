// ! StreamTest - TRAnimeİzle Eklentisi | Jung1330

// 2026-11: www.tranimeizle.live Nuxt/Vue tabanlı yeni bir uygulamaya taşındı.
// Eski HTML seçicileri (div.flx-block, a.news-image, div.bar h4, a[href*='-bolum'],
// select option[value*='bolum']) artık sayfada YOK; liste/arama/bölüm verisi
// tamamen JSON API'den geliyor. Bu sürüm sitenin kendi uçlarını kullanır:
//   GET  /api/anime/rankings/{yeni-eklenenler|en-cok-begenilenler|tamamlananlar}?limit=&offset=
//   POST /api/search/catalog   {"requests":[{"indexName":"anime","params":{"query":..,"hitsPerPage":..}}]}
//   GET  /api/anime/{slug}
//   GET  /api/anime/{slug}/episodes?limit=1000
//   GET  /api/episode/{bolumSlug}     -> videos[] (id, fansubName, providerName)
//   GET  /api/watch/{videoId}         -> "/w/{token}" + Set-Cookie: wid=... (çerez ZORUNLU)
//   GET  /w/{token}  (+wid çerezi)    -> HLS master playlist

package com.jung1330

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class TRAnimeIzle : MainAPI() {
    override var mainUrl              = "https://www.tranimeizle.live"
    private val initialMainUrl = mainUrl
    override var name                 = "TRAnimeİzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    private val pageSize     = 24
    private val seasonRegex  = Regex("""(\d+)-sezon""", RegexOption.IGNORE_CASE)

    override val mainPage = mainPageOf(
        "$mainUrl/api/anime/rankings/yeni-eklenenler"     to "Son Eklenenler",
        "$mainUrl/api/anime/rankings/en-cok-begenilenler" to "Popüler Animeler",
        "$mainUrl/api/anime/rankings/tamamlananlar"       to "Tamamlananlar"
    )

    private fun AnimeCard.toSearchResponse(): SearchResponse? {
        val animeSlug = slug?.trim().orEmpty()
        if (animeSlug.isEmpty()) return null

        val animeTitle = title?.primary?.trim().orEmpty()
        if (animeTitle.isEmpty()) return null

        val poster = fixUrlNull(posterUrl)
        val animeYear = year

        return newAnimeSearchResponse(animeTitle, "$mainUrl/anime/$animeSlug", TvType.Anime) {
            this.posterUrl = poster
            this.year      = animeYear
        }
    }

    private fun slugOf(url: String): String? {
        val clean = url.trim().trimEnd('/')
        if (clean.isEmpty()) return null
        return clean.substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DomainConfig.sync(this, "TRAnimeIzle")
        val request = request.copy(data = DomainConfig.rewrite(request.data, initialMainUrl, mainUrl))
        val offset = ((page - 1).coerceAtLeast(0)) * pageSize
        val response = try {
            app.get("${request.data}?limit=$pageSize&offset=$offset").parsedSafe<RankingsResponse>()
        } catch (t: Throwable) {
            Log.e("TRAnimeIzle", "Ana sayfa yüklenemedi: ${t.message}")
            null
        }

        val items = response?.items.orEmpty().mapNotNull { it.anime?.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        DomainConfig.sync(this, "TRAnimeIzle")
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val payload = mapOf(
            "requests" to listOf(
                mapOf(
                    "indexName" to "anime",
                    "params" to mapOf(
                        "query"       to trimmed,
                        "hitsPerPage" to pageSize
                    )
                )
            )
        )

        val response = try {
            app.post("$mainUrl/api/search/catalog", json = payload).parsedSafe<CatalogResponse>()
        } catch (t: Throwable) {
            Log.e("TRAnimeIzle", "Arama başarısız: ${t.message}")
            null
        }

        return response?.results.orEmpty()
            .flatMap { it.hits.orEmpty() }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        DomainConfig.sync(this, "TRAnimeIzle")
        val url = DomainConfig.rewrite(url, initialMainUrl, mainUrl)
        val slug = slugOf(url) ?: return null

        val detail = (try {
            app.get("$mainUrl/api/anime/$slug").parsedSafe<AnimeDetail>()
        } catch (t: Throwable) {
            Log.e("TRAnimeIzle", "Detay yüklenemedi: ${t.message}")
            null
        }) ?: return null

        val title = detail.title?.primary?.trim().orEmpty().ifBlank { return null }
        val poster = fixUrlNull(detail.posterUrl)
        val plot = detail.synopsis
            ?.let { runCatching { Jsoup.parse(it).text() }.getOrNull() }
            ?.trim()
            ?.ifBlank { null }
        val tags = detail.genres.orEmpty()
            .mapNotNull { it.name?.trim()?.ifBlank { null } }
            .distinct()

        val episodeResponse = try {
            app.get("$mainUrl/api/anime/$slug/episodes?limit=3000&offset=0").parsedSafe<EpisodesResponse>()
        } catch (t: Throwable) {
            Log.e("TRAnimeIzle", "Bölüm listesi alınamadı: ${t.message}")
            null
        }

        val episodes = episodeResponse?.items.orEmpty().mapNotNull { item ->
            val episodeSlug = item.slug?.trim().orEmpty()
            if (episodeSlug.isEmpty()) return@mapNotNull null

            val episodeNum = item.number ?: return@mapNotNull null
            val seasonNum  = seasonRegex.find(episodeSlug)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epName     = item.title?.trim()?.ifBlank { null } ?: "$seasonNum. Sezon $episodeNum. Bölüm"
            val epPoster   = fixUrlNull(item.thumbnailUrl)

            newEpisode("$mainUrl/$episodeSlug") {
                this.name      = epName
                this.season    = seasonNum
                this.episode   = episodeNum
                this.posterUrl = epPoster
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        if (episodes.isEmpty()) {
            // Film tipindeki kayıtlarda bölüm yok; oynatılabilir kaynak da yok.
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = detail.year
                this.tags      = tags
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = detail.year
            this.tags      = tags
            this.score     = Score.from10(detail.averageScore)
        }
    }

    private data class ResolvedStream(
        val url: String,
        val headers: Map<String, String>,
        val hls: Boolean,
    )

    /**
     * /api/watch/{videoId} iki tür yanıt döner:
     *  - "/w/{token}"  -> HLS master; yanıtın Set-Cookie "wid" çereziyle birlikte istenmeli (çerezsiz 403)
     *  - "https://..." -> harici embed adresi (ör. luffytra2.top/embed2/?id=...)
     */
    private suspend fun resolveStream(videoId: String, pageReferer: String): ResolvedStream? {
        val response = try {
            app.get("$mainUrl/api/watch/$videoId", headers = mapOf("Referer" to pageReferer))
        } catch (t: Throwable) {
            Log.e("TRAnimeIzle", "Kaynak çözümlenemedi: ${t.message}")
            return null
        }

        val body = response.text.trim()

        if (body.startsWith("/w/")) {
            val cookieHeader = response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val headers = buildMap {
                put("Referer", "$mainUrl/")
                if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
            }
            return ResolvedStream("$mainUrl$body", headers, true)
        }

        if (body.startsWith("http://") || body.startsWith("https://")) {
            return ResolvedStream(body, mapOf("Referer" to pageReferer), false)
        }

        Log.w("TRAnimeIzle", "Beklenmeyen kaynak yanıtı: $body")
        return null
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        DomainConfig.sync(this, "TRAnimeIzle")
        val data = DomainConfig.rewrite(data, initialMainUrl, mainUrl)
        val slug = slugOf(data) ?: return false

        val episode = (try {
            app.get("$mainUrl/api/episode/$slug").parsedSafe<EpisodeDetail>()
        } catch (t: Throwable) {
            Log.e("TRAnimeIzle", "Bölüm kaynakları alınamadı: ${t.message}")
            null
        }) ?: return false

        val pageReferer = "$mainUrl/"
        var emitted = false

        for (video in episode.videos.orEmpty()) {
            val videoId = video.id?.trim().orEmpty()
            if (videoId.isEmpty()) continue

            val resolved = resolveStream(videoId, pageReferer) ?: continue

            val label = listOfNotNull(video.fansubName, video.providerName)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" - ")
                .ifBlank { name }

            if (!resolved.hls) {
                Log.d("TRAnimeIzle", "Harici oynatıcı » ${resolved.url}")
                if (loadExtractor(resolved.url, pageReferer, subtitleCallback, callback)) {
                    emitted = true
                }
                continue
            }

            val generated = try {
                M3u8Helper.generateM3u8(
                    source    = this.name,
                    streamUrl = resolved.url,
                    referer   = pageReferer,
                    headers   = resolved.headers,
                    name      = label
                )
            } catch (t: Throwable) {
                Log.e("TRAnimeIzle", "m3u8 çözümlenemedi: ${t.message}")
                emptyList()
            }

            if (generated.isNotEmpty()) {
                generated.forEach { callback.invoke(it) }
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = label,
                        url    = resolved.url,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageReferer
                        this.headers = resolved.headers
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            emitted = true
        }

        if (!emitted) Log.e("TRAnimeIzle", "Hiçbir oynatıcı bulunamadı: $data")
        return emitted
    }
}

data class RankingsResponse(
    @JsonProperty("items") val items: List<RankingItem> = emptyList(),
)

data class RankingItem(
    @JsonProperty("anime") val anime: AnimeCard? = null,
)

data class AnimeCard(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("title") val title: AnimeTitle? = null,
    @JsonProperty("posterUrl") val posterUrl: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

data class AnimeTitle(
    @JsonProperty("primary") val primary: String? = null,
)

data class CatalogResponse(
    @JsonProperty("results") val results: List<CatalogResult> = emptyList(),
)

data class CatalogResult(
    @JsonProperty("hits") val hits: List<AnimeCard> = emptyList(),
)

data class AnimeDetail(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("title") val title: AnimeTitle? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("posterUrl") val posterUrl: String? = null,
    @JsonProperty("averageScore") val averageScore: Double? = null,
    @JsonProperty("genres") val genres: List<AnimeGenre> = emptyList(),
)

data class AnimeGenre(
    @JsonProperty("name") val name: String? = null,
)

data class EpisodesResponse(
    @JsonProperty("items") val items: List<EpisodeItem> = emptyList(),
)

data class EpisodeItem(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumbnailUrl") val thumbnailUrl: String? = null,
)

data class EpisodeDetail(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("videos") val videos: List<EpisodeVideo> = emptyList(),
)

data class EpisodeVideo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("fansubName") val fansubName: String? = null,
    @JsonProperty("providerName") val providerName: String? = null,
)
