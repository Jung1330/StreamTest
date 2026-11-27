// ! StreamTest - Diziyou Eklentisi | Jung1330

package com.jung1330

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Diziyou : MainAPI() {
    override var mainUrl              = "https://www.diziyou.love"
    private val initialMainUrl = mainUrl
    override var name                 = "Diziyou"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/"             to "Son Eklenenler",
        "$mainUrl/dizi-arsivi/" to "Dizi Arşivi"
    )

    private val reserved = setOf(
        "dizi-arsivi", "son-eklenenler", "uye-ol", "iletisim", "gizlilik", "dmca", "hakkimizda",
        "page", "category", "tag", "feed", "wp-login"
    )
    private val seasonRegex = Regex("""(\d+)-sezon-(\d+)-bolum""")
    private val epSlugRegex = Regex("""^([a-z0-9\-]+?)-\d+-sezon-\d+-bolum$""")

    private fun extractValidPoster(scope: Element?): String? {
        if (scope == null) return null
        for (img in scope.select("img")) {
            val raw = img.attr("data-src").ifBlank { img.attr("src") }.trim()
            if (raw.isBlank() || raw.startsWith("data:") || raw.contains("/themes/")) continue
            return fixUrlNull(raw)
        }
        return null
    }

    private fun parseSeriesItems(doc: Document): List<SearchResponse> {
        val byUrl = linkedMapOf<String, SearchResponse>()

        // 1) Dizi Arşivi (.single-item), Arama sayfası (#list-series) ve AJAX arama (#searchelement) kartları
        doc.select("div.single-item, div#list-series, div#searchelement").forEach { item ->
            val linkEl = item.selectFirst("#categorytitle a[href], .cat-img a[href], a[href]") ?: return@forEach
            val href = fixUrlNull(linkEl.attr("href")) ?: return@forEach
            if (!href.startsWith("$mainUrl/")) return@forEach

            val path = href.removePrefix("$mainUrl/").trimEnd('/')
            val slug = epSlugRegex.find(path)?.groupValues?.get(1) ?: path
            if (slug.isBlank() || slug in reserved || slug.contains("/")) return@forEach

            val title = item.selectFirst("#categorytitle a")?.text()?.trim().orEmpty()
                .ifBlank { item.select("a[href]").firstOrNull { it.text().isNotBlank() }?.text()?.trim().orEmpty() }
                .ifBlank { linkEl.attr("title").substringBefore(" izle").trim() }
                .ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }

            val poster = extractValidPoster(item)
            val seriesUrl = "$mainUrl/$slug/"

            byUrl[seriesUrl] = newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

        // 2) Ana sayfa son bölümler ve diğer bağlantılar
        doc.select("a[href]").forEach { a ->
            // Her sayfada basılan alfabetik "tüm diziler" indeksi (ul.alphabetical-category-list, ~1173 link)
            // sonuç listesini tamamen kirletiyor; bu indeksteki bağlantıları atla.
            if (a.parents().any { it.hasClass("alphabetical-category-list") }) return@forEach

            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!href.startsWith("$mainUrl/")) return@forEach

            val path = href.removePrefix("$mainUrl/").trimEnd('/')
            if (!path.matches(Regex("""^[a-z0-9\-]+$"""))) return@forEach

            val slug = epSlugRegex.find(path)?.groupValues?.get(1) ?: path
            if (slug in reserved) return@forEach

            val seriesUrl = "$mainUrl/$slug/"
            val existing = byUrl[seriesUrl]
            val poster = extractValidPoster(a) ?: extractValidPoster(a.parent())
            if (existing != null && existing.posterUrl != null) return@forEach

            val rawTitle = a.attr("title").substringBefore(" izle").trim()
                .replace(Regex("""\s+\d+\.\s*Sezon\s+\d+\.\s*B[öo]l[üu]m.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { existing?.name.orEmpty() }
                .ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }

            byUrl[seriesUrl] = newTvSeriesSearchResponse(rawTitle, seriesUrl, TvType.TvSeries) {
                this.posterUrl = poster ?: existing?.posterUrl
            }
        }

        return byUrl.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DomainConfig.sync(this, "Diziyou")
        val request = request.copy(data = DomainConfig.rewrite(request.data, initialMainUrl, mainUrl))
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        return newHomePageResponse(request.name, parseSeriesItems(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        DomainConfig.sync(this, "Diziyou")
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val ajaxItems = runCatching {
            val ajaxDoc = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to "$mainUrl/"),
                data = mapOf("action" to "data_fetch", "keyword" to trimmed)
            ).document
            parseSeriesItems(ajaxDoc)
        }.getOrNull().orEmpty()

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val pageItems = try {
            val doc = app.get("$mainUrl/?s=$encoded").document
            parseSeriesItems(doc)
        } catch (t: Throwable) {
            Log.e("Diziyou", "Arama hatası: ${t.message}")
            emptyList()
        }

        return (ajaxItems + pageItems).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        DomainConfig.sync(this, "Diziyou")
        val url = DomainConfig.rewrite(url, initialMainUrl, mainUrl)
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val title = rawTitle
            .substringBefore(" izle")
            .substringBefore(" İzle")
            .substringBefore(" | ")
            .trim()

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: extractValidPoster(doc)
        val plot   = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val episodes = doc.select("a[href*='-sezon-'][href*='-bolum']").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val m    = seasonRegex.find(href) ?: return@mapNotNull null
            val s    = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val e    = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null

            newEpisode(href) {
                this.name    = "$s. Sezon $e. Bölüm"
                this.season  = s
                this.episode = e
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        DomainConfig.sync(this, "Diziyou")
        val data = DomainConfig.rewrite(data, initialMainUrl, mainUrl)
        val page = try {
            app.get(data).document
        } catch (t: Throwable) {
            Log.e("Diziyou", "Bölüm sayfası açılamadı: ${t.message}")
            return false
        }

        val iframe = page.selectFirst("iframe[src]")?.attr("src") ?: return false
        val rawPlayerUrl = fixUrl(iframe)
        val basePlayerUrl = rawPlayerUrl
            .replace("_tr.html", ".html")
            .replace("_enSub.html", ".html")

        val optionIds = page.select("span.diziyouOption[id]").map { it.attr("id").trim() }.toSet()
        val variants = buildList {
            if (optionIds.isEmpty() || "turkceAltyazili" in optionIds) {
                add(basePlayerUrl to "${this@Diziyou.name} (Türkçe Altyazılı)")
            }
            if ("turkceDublaj" in optionIds) {
                add(basePlayerUrl.replace(".html", "_tr.html") to "${this@Diziyou.name} (Türkçe Dublaj)")
            }
            if ("ingilizceAltyazili" in optionIds) {
                add(basePlayerUrl.replace(".html", "_enSub.html") to "${this@Diziyou.name} (İngilizce Altyazılı)")
            }
            if (isEmpty()) {
                add(rawPlayerUrl to this@Diziyou.name)
            }
        }

        val seenSubs = mutableSetOf<String>()
        var anyEmitted = false

        for ((playerUrl, linkLabel) in variants) {
            val playerHtml = try {
                app.get(playerUrl, headers = mapOf("Referer" to data)).text
            } catch (t: Throwable) {
                Log.e("Diziyou", "Oynatıcı açılamadı ($playerUrl): ${t.message}")
                continue
            }

            val playerDoc = org.jsoup.Jsoup.parse(playerHtml, playerUrl)
            playerDoc.select("track[src]").forEach { track ->
                val kind = track.attr("kind").lowercase()
                if (kind == "thumbnails" || kind == "chapters") return@forEach
                val subUrl = fixUrlNull(track.attr("src")) ?: return@forEach
                if (!seenSubs.add(subUrl)) return@forEach
                val subLabel = track.attr("label").trim()
                    .ifBlank { track.attr("srclang").trim() }
                    .ifBlank { "Türkçe" }
                subtitleCallback.invoke(SubtitleFile(subLabel, subUrl))
            }

            val streamUrl = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""").find(playerHtml)?.value
                ?: continue

            val reqHeaders = mapOf("Referer" to playerUrl)
            val links = try {
                M3u8Helper.generateM3u8(this.name, streamUrl, playerUrl, headers = reqHeaders, name = linkLabel)
            } catch (t: Throwable) {
                emptyList()
            }

            if (links.isNotEmpty()) {
                links.forEach { callback.invoke(it) }
                anyEmitted = true
                continue
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = linkLabel,
                    url    = streamUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = playerUrl
                    this.headers = reqHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
            anyEmitted = true
        }

        return anyEmitted
    }
}
