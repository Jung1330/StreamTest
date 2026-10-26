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
        val titleId = url.substringAfter("?titleId=")
        val response = app.get(
            url,
            headers = getApiHeaders("titleId=${titleId}")
        ).parsedSafe<Title>() ?: return null
        val episodes = mutableListOf<Episode>()

        if (response.title.titleType == "anime") {
            for (sezon in response.title.seasons) {
                val relUrl = "${mainUrl}/secure/related-videos?episode=1&season=${sezon.number}&videoId=0&titleId=${titleId}"
                val relQuery = relUrl.substringAfter("?", "")
                val sezonResponse = app.get(
                    relUrl,
                    headers = getApiHeaders(relQuery)
                ).parsedSafe<TitleVideos>() ?: return null
                for (video in sezonResponse.videos) {
                    episodes.add(newEpisode(video.url) {
                        this.name = "${video.seasonNum}. Sezon ${video.episodeNum}. Bölüm"
                        this.season = video.seasonNum
                        this.episode = video.episodeNum
                    })
                }
            }
        } else {
            if (response.title.videos.isNotEmpty()) {
                episodes.add(newEpisode(response.title.videos.first().url) {
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
        Log.d("ACX", "data » $data")
        val fullUrl = if (data.startsWith("http")) data else "${mainUrl}/${data}"
        val query = fullUrl.substringAfter("?", "")
        val iframeLink = app.get(
            fullUrl,
            headers = getApiHeaders(query)
        ).url
        Log.d("ACX", "iframeLink » $iframeLink")

        loadExtractor(iframeLink, "${mainUrl}/", subtitleCallback, callback)

        return true
    }
}
