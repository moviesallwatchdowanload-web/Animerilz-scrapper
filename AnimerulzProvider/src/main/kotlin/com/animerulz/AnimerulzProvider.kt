package com.animerulz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimerulzProvider : MainAPI() {

    override var mainUrl = "https://animerulz.co.in"
    override var name = "Animerulz"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama
    )

    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/trending" to "Trending",
        "$mainUrl/airing" to "Airing",
        "$mainUrl/completed" to "Completed",
        "$mainUrl/language/hindi" to "Hindi",
        "$mainUrl/language/tamil" to "Tamil",
        "$mainUrl/language/telugu" to "Telugu",
        "$mainUrl/language/english" to "English",
        "$mainUrl/language/japanese" to "Japanese"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = cfHeaders()).document
        val list = doc.select("a[href^=/anime-]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").takeIf { it.startsWith("/anime-") } ?: return null
        val img = selectFirst("img") ?: return null
        val title = img.attr("alt").ifBlank { img.attr("title") }.ifBlank { text().trim() }
        if (title.isBlank()) return null

        val poster = img.attr("src").ifBlank { img.attr("data-src") }
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"

        val isSeries = title.contains(Regex("(?i)(season|series|episode)"))
        return newMovieSearchResponse(title, fullHref, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?q=$encoded"
        val doc = app.get(url, headers = cfHeaders()).document
        val results = doc.select("a[href^=/anime-]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = cfHeaders()).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img")?.attr("src")

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("p")?.text()?.trim()

        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()

        val genres = doc.select("a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val epLinks = doc.select("a[href*=/watch/], a[href*=/episode]").distinctBy { it.attr("href") }

        if (epLinks.isNotEmpty()) {
            val episodes = epLinks.mapIndexedNotNull { idx, a ->
                val epUrl = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val epTitle = a.text().trim().ifBlank { "Episode ${idx + 1}" }
                val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                newEpisode(epUrl) {
                    name = epTitle
                    episode = epNum
                }
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                }
            }
        }

        val data = EpisodeLink(url)
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val link = try {
                parseJson<EpisodeLink>(data)
            } catch (e: Exception) {
                EpisodeLink(data)
            }

            val doc = app.get(link.source, headers = cfHeaders()).document

            doc.select("iframe[src]").amap { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) loadExtractor(src, mainUrl, subtitleCallback, callback)
            }

            doc.select("video source[src], video[src]").amap { video ->
                val src = video.attr("src")
                if (src.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink("Animerulz", "Animerulz", src, ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                        }
                    )
                }
            }

            val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(doc.html())?.value
            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink("Animerulz", "Animerulz", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Animerulz", "loadLinks error: ${e.message}")
        }
        return true
    }

    private fun cfHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val cookies = AnimerulzPlugin.getCfCookies()
        if (cookies.isNotEmpty()) headers["Cookie"] = cookies
        val uaSaved = AnimerulzPlugin.getCfUserAgent()
        headers["User-Agent"] = uaSaved.ifEmpty { ua }
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        headers["Accept-Language"] = "en-US,en;q=0.9,hi;q=0.8"
        return headers
    }

    data class EpisodeLink(val source: String)
}
