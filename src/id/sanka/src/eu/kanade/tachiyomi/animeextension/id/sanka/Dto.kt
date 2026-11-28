package eu.kanade.tachiyomi.animeextension.id.sanka

import kotlinx.serialization.Serializable

// DTOs for the Sanka API (https://www.sankavollerei.web.id).
// Every field name below was checked against the saved responses in
// ~/workspace/.scratch-repos/sanka/ (2026-11-28). Fields the code does
// not read are omitted; unknown keys are ignored (ignoreUnknownKeys).

@Serializable
data class SearchResponse(
    val ok: Boolean = false,
    val statusCode: Int = 0,
    val data: SearchData? = null,
)

@Serializable
data class SearchData(
    val animeList: List<AnimeCard> = emptyList(),
)

// Shared card shape: search items carry status/score, ongoing/home items
// carry episodes/releaseDay/latestReleaseDate/lastReleaseDate, genre items
// carry studios/season. All fields optional with defaults.
@Serializable
data class AnimeCard(
    val title: String = "",
    val poster: String = "",
    val status: String = "",
    val score: String = "",
    val animeId: String = "",
    val href: String = "",
    val episodes: Int? = null,
    val releaseDay: String = "",
    val latestReleaseDate: String = "",
    val lastReleaseDate: String = "",
    val studios: String = "",
    val season: String = "",
)

@Serializable
data class DetailResponse(
    val ok: Boolean = false,
    val statusCode: Int = 0,
    val data: DetailData? = null,
)

@Serializable
data class DetailData(
    val title: String = "",
    val poster: String = "",
    val japanese: String = "",
    val score: String = "",
    val producers: String = "",
    val type: String = "",
    val status: String = "",
    val episodes: Int? = null,
    val duration: String = "",
    val aired: String = "",
    val studios: String = "",
    val synopsis: Synopsis? = null,
    val genreList: List<GenreItem> = emptyList(),
    val episodeList: List<EpisodeItem> = emptyList(),
)

@Serializable
data class Synopsis(
    val paragraphs: List<String> = emptyList(),
)

@Serializable
data class GenreItem(
    val title: String = "",
    val genreId: String = "",
    val href: String = "",
)

@Serializable
data class EpisodeItem(
    val title: String = "",
    val eps: Int = 0,
    val date: String = "",
    val episodeId: String = "",
    val href: String = "",
)

@Serializable
data class EpisodeResponse(
    val ok: Boolean = false,
    val statusCode: Int = 0,
    val data: EpisodeData? = null,
)

@Serializable
data class EpisodeData(
    val title: String = "",
    val animeId: String = "",
    val releaseTime: String = "",
    val defaultStreamingUrl: String = "",
    val server: ServerBlock? = null,
)

@Serializable
data class ServerBlock(
    val qualities: List<ServerQuality> = emptyList(),
)

@Serializable
data class ServerQuality(
    val title: String = "",
    val serverList: List<ServerEntry> = emptyList(),
)

@Serializable
data class ServerEntry(
    val title: String = "",
    val serverId: String = "",
    val href: String = "",
)

@Serializable
data class ServerResponse(
    val ok: Boolean = false,
    val statusCode: Int = 0,
    val data: ServerData? = null,
)

@Serializable
data class ServerData(
    val url: String = "",
)

@Serializable
data class OngoingResponse(
    val ok: Boolean = false,
    val statusCode: Int = 0,
    val data: OngoingData? = null,
    val pagination: Pagination? = null,
)

@Serializable
data class OngoingData(
    val animeList: List<AnimeCard> = emptyList(),
)

@Serializable
data class Pagination(
    val currentPage: Int = 1,
    val hasPrevPage: Boolean = false,
    val prevPage: Int? = null,
    val hasNextPage: Boolean = false,
    val nextPage: Int? = null,
    val totalPages: Int = 1,
)

@Serializable
data class HomeResponse(
    val ok: Boolean = false,
    val statusCode: Int = 0,
    val data: HomeData? = null,
)

@Serializable
data class HomeData(
    val ongoing: HomeSection? = null,
    val completed: HomeSection? = null,
)

@Serializable
data class HomeSection(
    val animeList: List<AnimeCard> = emptyList(),
)
