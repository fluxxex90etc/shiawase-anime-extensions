package eu.kanade.tachiyomi.animeextension.id.nontonanimeid

import kotlinx.serialization.Serializable

@Serializable
class SearchResponse(val data: List<SearchItem> = emptyList())

@Serializable
class SearchItem(
    val id: Int = 0,
    val title: String = "",
    val slug: String = "",
    val poster: String = "",
    val type: String = "",
    val release_year: Int = 0,
    val episodes: Int = 0,
    val duration: Int = 0,
    val rating: String = "",
)

@Serializable
class EpisodeDto(
    val episode_number: Int = 0,
    val title: String? = null,
    val air_date: String? = null,
    val video_url: String? = null,
    val video_url2: String? = null,
    val video_url3: String? = null,
    val server1_name: String? = null,
    val server2_name: String? = null,
    val server3_name: String? = null,
)
