package com.nguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/films/phim-moi-cap-nhat?page=" to "Phim Mới Cập Nhật",
        "$mainUrl/api/films/danh-sach/phim-bo?page=" to "Phim Bộ",
        "$mainUrl/api/films/danh-sach/phim-le?page=" to "Phim Lẻ",
        "$mainUrl/api/films/danh-sach/hoat-hinh?page=" to "Hoạt Hình / Anime",
        "$mainUrl/api/films/danh-sach/tv-shows?page=" to "TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("${request.data}$page").parsedSafe<FilmListResponse>()
        val homeList = response?.items?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, homeList, hasNext = homeList.isNotEmpty())
    }

    private fun FilmItem.toSearchResult(): SearchResponse? {
        val itemSlug = this.slug ?: return null
        val title = this.name ?: return null
        val poster = this.thumbUrl ?: this.posterUrl

        return newAnimeSearchResponse(title, "$mainUrl/api/film/$itemSlug", TvType.TvSeries) {
            this.posterUrl = poster
            this.otherName = originalName
            addDubStatus(
                isDub = currentEpisode?.contains("Thuyết Minh", true) == true ||
                        currentEpisode?.contains("Lồng Tiếng", true) == true,
                isSub = true
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/films/search?keyword=$encodedQuery"
        val response = app.get(url).parsedSafe<FilmListResponse>()
        return response?.items?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/api/film/$slug"
        val response = app.get(apiUrl).parsedSafe<FilmDetailResponse>()
        val movie = response?.movie ?: return null

        val episodeMap = linkedMapOf<String, MutableList<EpisodeSourceData>>()
        val episodeNames = linkedMapOf<String, String>()

        movie.episodes?.forEach { server ->
            val serverName = server.serverName ?: "Server"
            server.items?.forEach { ep ->
                val epKey = ep.slug ?: ep.name ?: "full"
                episodeNames[epKey] = ep.name ?: "Full"
                val list = episodeMap.getOrPut(epKey) { mutableListOf() }
                list.add(
                    EpisodeSourceData(
                        serverName = serverName,
                        m3u8 = ep.m3u8,
                        embed = ep.embed
                    )
                )
            }
        }

        val episodes = episodeMap.entries.mapIndexed { index, entry ->
            val epName = episodeNames[entry.key] ?: "${index + 1}"
            val epNumber = epName.filter { it.isDigit() }.toIntOrNull() ?: (index + 1)
            newEpisode(entry.value.toJson()) {
                this.name = "Tập $epName"
                this.episode = epNumber
            }
        }

        val isSeries = episodes.size > 1
        val cleanDescription = movie.description?.replace(Regex("<.*?>"), "")?.trim()

        return if (isSeries) {
            newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
                this.posterUrl = movie.thumbUrl ?: movie.posterUrl
                this.backgroundPosterUrl = movie.posterUrl ?: movie.thumbUrl
                this.plot = cleanDescription
            }
        } else {
            val movieData = episodeMap.values.firstOrNull()?.toJson() ?: ""
            newMovieLoadResponse(movie.name ?: "", url, TvType.Movie, movieData) {
                this.posterUrl = movie.thumbUrl ?: movie.posterUrl
                this.backgroundPosterUrl = movie.posterUrl ?: movie.thumbUrl
                this.plot = cleanDescription
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val sources = parseJson<List<EpisodeSourceData>>(data)

        sources.forEach { source ->
            val m3u8Url = source.m3u8
            val embedUrl = source.embed

            if (!m3u8Url.isNullOrBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} - ${source.serverName}",
                        url = m3u8Url,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            } else if (!embedUrl.isNullOrBlank()) {
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }

    data class FilmListResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("items") val items: List<FilmItem>? = null
    )

    data class FilmItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("current_episode") val currentEpisode: String? = null
    )

    data class FilmDetailResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("movie") val movie: MovieDetail? = null
    )

    data class MovieDetail(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("episodes") val episodes: List<ServerGroup>? = null
    )

    data class ServerGroup(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("items") val items: List<EpisodeItem>? = null
    )

    data class EpisodeItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )

    data class EpisodeSourceData(
        @JsonProperty("serverName") val serverName: String,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("embed") val embed: String? = null
    )
}
