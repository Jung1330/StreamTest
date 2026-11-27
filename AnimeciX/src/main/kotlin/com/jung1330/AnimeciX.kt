// ! StreamTest - AnimeciX Eklentisi | Jung1330 (AI Destekli Olarak Güncellenmiştir)

package com.jung1330

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeciX : MainAPI() {
    override var mainUrl              = "https://animecix.tv"
    private val initialMainUrl = mainUrl
    override var name                 = "AnimeciX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 200L
    override var sequentialMainPageScrollDelay = 200L

    override val mainPage = mainPageOf(
        "${mainUrl}/secure/titles?type=series&onlyStreamable=true" to "Seriler",
        "${mainUrl}/secure/titles?type=movie&onlyStreamable=true"  to "Filmler",
    )

    private fun getXehHeader(queryParams: String = ""): String {
        return try {
            val keyStr = "i4C7R2fXGocdYgFLzCbDlsJjukf8G58b"
            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            val plaintext = "{version}$queryParams".toByteArray(Charsets.UTF_8)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val encrypted = cipher.doFinal(plaintext)
            val ctB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)

            "$ctB64.$ivB64"
        } catch (e: Exception) {
            Log.e("AnimeciX", "Failed to generate X-E-H header", e)
            ""
        }
    }

    private fun getApiHeaders(queryParams: String = ""): Map<String, String> {
        return mapOf(
            "x-e-h" to getXehHeader(queryParams),
            "Referer" to "${mainUrl}/"
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DomainConfig.sync(this, "AnimeciX")
        val request = request.copy(data = DomainConfig.rewrite(request.data, initialMainUrl, mainUrl))
        val fullUrl = "${request.data}&page=${page}&perPage=16"
        val query = fullUrl.substringAfter("?", "")
        val response = app.get(
            fullUrl,
            headers = getApiHeaders(query)
        ).parsedSafe<Category>()

        val home = response?.pagination?.data?.map { anime ->
            newAnimeSearchResponse(
                anime.title,
                "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                TvType.Anime
            ) {
                this.posterUrl = fixUrlNull(anime.poster)
            }
        } ?: listOf<SearchResponse>()

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        DomainConfig.sync(this, "AnimeciX")
        val fullUrl = "${mainUrl}/secure/search/${query}?limit=20"
        val response = app.get(
            fullUrl,
            headers = getApiHeaders("limit=20")
        ).parsedSafe<Search>() ?: return listOf()

        return response.results.map { anime ->
            newAnimeSearchResponse(
                anime.title,
                "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                TvType.Anime
            ) {
                this.posterUrl = fixUrlNull(anime.poster)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        DomainConfig.sync(this, "AnimeciX")
        val url = DomainConfig.rewrite(url, initialMainUrl, mainUrl)
        val titleId = url.substringAfter("?titleId=")
        val response = app.get(
            url,
            headers = getApiHeaders("titleId=${titleId}")
        ).parsedSafe<Title>() ?: return null
        val episodes = mutableListOf<Episode>()

        if (response.title.titleType == "anime" && response.title.seasons.isNotEmpty()) {
            for (sezon in response.title.seasons) {
                val relUrl = "${mainUrl}/secure/related-videos?episode=1&season=${sezon.number}&videoId=0&titleId=${titleId}"
                val relQuery = relUrl.substringAfter("?", "")
                val sezonResponse = app.get(
                    relUrl,
                    headers = getApiHeaders(relQuery)
                ).parsedSafe<TitleVideos>() ?: continue
                for (video in sezonResponse.videos) {
                    episodes.add(newEpisode(video.url) {
                        this.name = "${video.seasonNum}. Sezon ${video.episodeNum}. Bölüm"
                        this.season = video.seasonNum
                        this.episode = video.episodeNum
                    })
                }
            }
        } else {
            val playableVideos = response.title.videos.filterNot {
                it.url.contains("youtube.com", ignoreCase = true) ||
                    it.url.contains("youtu.be", ignoreCase = true)
            }.ifEmpty { response.title.videos }

            if (playableVideos.isNotEmpty()) {
                val combinedUrls = playableVideos.map { it.url.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString("|")
                episodes.add(newEpisode(combinedUrls) {
                    this.name    = "Filmi İzle"
                    this.season  = 1
                    this.episode = 1
                })
            }
        }

        return newTvSeriesLoadResponse(
            response.title.title,
            "${mainUrl}/secure/titles/${response.title.id}?titleId=${response.title.id}",
            TvType.Anime,
            episodes
        ) {
            this.posterUrl = fixUrlNull(response.title.poster)
            this.year      = response.title.year
            this.plot      = response.title.description
            this.tags      = response.title.tags.map { it.name }
            this.score     = Score.from10(response.title.rating)
            addActors(response.title.actors.map { Actor(it.name, fixUrlNull(it.poster)) })
            addTrailer(response.title.trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        DomainConfig.sync(this, "AnimeciX")
        val data = DomainConfig.rewrite(data, initialMainUrl, mainUrl)
        Log.d("ACX", "data » $data")
        val rawCandidates = data.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val embedUrls = linkedSetOf<String>()

        for (item in rawCandidates) {
            val isInternal = !item.startsWith("http") || item.startsWith(mainUrl, ignoreCase = true)
            if (isInternal) {
                val fullUrl = if (item.startsWith("http")) item else "${mainUrl}/${item.trimStart('/')}"
                val query = fullUrl.substringAfter("?", "")

                if (fullUrl.contains("secure/best-video") && query.isNotBlank()) {
                    val epVideosUrl = "${mainUrl}/secure/episode-videos?$query"
                    runCatching {
                        val list = app.get(epVideosUrl, headers = getApiHeaders(query)).parsedSafe<Array<Video>>()
                        list?.forEach { v ->
                            val u = v.url.trim()
                            if (u.startsWith("http") && !u.contains("youtube.com", true) && !u.contains("youtu.be", true)) {
                                embedUrls.add(u)
                            }
                        }
                    }
                }

                runCatching {
                    val redirected = app.get(fullUrl, headers = getApiHeaders(query)).url
                    if (redirected.startsWith("http") && !redirected.contains("/secure/")) {
                        embedUrls.add(redirected)
                    }
                }
            } else {
                embedUrls.add(item)
            }
        }

        var anyLoaded = false
        for (iframeLink in embedUrls) {
            Log.d("ACX", "iframeLink » $iframeLink")
            if (loadExtractor(iframeLink, "${mainUrl}/", subtitleCallback, callback)) {
                anyLoaded = true
            }
        }

        return anyLoaded
    }
}
