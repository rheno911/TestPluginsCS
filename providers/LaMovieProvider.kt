package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class LaMovieProvider : MainAPI() {
    override val name = "La.Movie"
    override val mainUrl = "https://la.movie"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val lang = "es" // Spanish (LAT)
    override val hasMainPage = true
    override val hasSearch = true

    // ========================================================================
    // MAIN PAGE (Películas recién añadidas, Preferidas, etc.)
    // ========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document

        val sections = mutableListOf<HomePageList>()

        // Common card container used on this site
        val cards = doc.select("div.flw-item")

        if (cards.isNotEmpty()) {
            val movies = cards.mapNotNull { card ->
                val titleElement = card.selectFirst("h3.film-name a, div.title a, a") ?: return@mapNotNull null
                val title = titleElement.text().trim()
                val href = titleElement.attr("href")
                val poster = card.selectFirst("img")?.attr("src") ?: card.selectFirst("img")?.attr("data-src")

                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = fixUrl(poster ?: "")
                    this.year = card.selectFirst(".year, .meta")?.text()?.trim()?.toIntOrNull()
                    // Quality tag is usually "LAT"
                    this.quality = card.selectFirst(".quality")?.text()
                }
            }

            if (movies.isNotEmpty()) {
                sections.add(HomePageList("Películas recién añadidas", movies))
            }
        }

        // You can add more sections (Series, Animes, Preferidas) the same way if you see extra containers
        // Example: sections.add(HomePageList("Preferidas por los usuarios", ...))

        return HomePageResponse(sections.ifEmpty { listOf(HomePageList("Recientes", emptyList())) })
    }

    // ========================================================================
    // SEARCH
    // ========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        // Most common search endpoint for sites like this (change if Network tab shows different)
        // Try these one by one if the first doesn't work:
        // 1. /search?keyword=...
        // 2. /buscar?q=...
        // 3. /?s=...
        val searchUrl = "$mainUrl/search?keyword=$query"

        val doc = app.get(searchUrl, referer = mainUrl).document

        return doc.select("div.flw-item").mapNotNull { card ->
            val titleElement = card.selectFirst("h3.film-name a, div.title a, a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")
            val poster = card.selectFirst("img")?.attr("src") ?: card.selectFirst("img")?.attr("data-src")

            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster ?: "")
                this.year = card.selectFirst(".year")?.text()?.toIntOrNull()
            }
        }
    }

    // ========================================================================
    // LOAD DETAIL PAGE (movie or series)
    // ========================================================================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1.film-name, h1, .title")?.text()?.trim() ?: "Unknown Title"
        val poster = doc.selectFirst("img.poster, .film-poster img")?.attr("src") ?: doc.selectFirst("img")?.attr("src")
        val plot = doc.selectFirst(".description, .plot, .storyline, .film-description")?.text()?.trim()
        val year = doc.selectFirst(".year, .meta-year")?.text()?.trim()?.toIntOrNull()
        val rating = doc.selectFirst(".rating, .imdb, .tmdb")?.text()

        // Check if it's a series (has episode list)
        val episodeElements = doc.select("div.episode, a.episode-link, .episode-list a") // adjust if needed

        return if (episodeElements.isNotEmpty()) {
            // === SERIES / ANIME ===
            val episodes = episodeElements.mapIndexed { index, ep ->
                val epTitle = ep.text().trim()
                val epUrl = fixUrl(ep.attr("href"))
                newEpisode(epUrl) {
                    this.name = epTitle.ifBlank { "Episodio ${index + 1}" }
                    this.episode = index + 1
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = plot
                this.year = year
                this.rating = rating?.toFloatOrNull()
            }
        } else {
            // === MOVIE ===
            newMovieLoadResponse(title, url, TvType.Movie, url) {  // url is passed to get links later
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = plot
                this.year = year
                this.rating = rating?.toFloatOrNull()
            }
        }
    }

    // ========================================================================
    // VIDEO LINKS (extractors)
    // ========================================================================
    override suspend fun loadLinks(
        data: String,  // this is the detail page URL we passed above
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document

        // Look for video embeds / iframes / player sources
        // Common patterns on these sites:
        val iframes = doc.select("iframe[src], video source, .player iframe")

        iframes.forEach { element ->
            val playerUrl = element.attr("src") ?: element.attr("data-src")
            if (playerUrl.isNotBlank() && playerUrl.startsWith("http")) {
                // Try to extract with built-in CloudStream extractors
                loadExtractor(fixUrl(playerUrl), data, subtitleCallback, callback)
            }
        }

        // If the site uses custom players (e.g. dood, vidoza, etc.), they will be auto-detected above.
        // If you see other video links in devtools, add them here manually.

        return true
    }
}
