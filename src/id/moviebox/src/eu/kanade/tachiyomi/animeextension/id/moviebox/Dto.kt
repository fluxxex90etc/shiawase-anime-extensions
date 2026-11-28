package eu.kanade.tachiyomi.animeextension.id.moviebox

import kotlinx.serialization.Serializable

@Serializable
class FilterResponse(
    val code: Int = -1,
    val data: FilterData = FilterData(),
)

@Serializable
class FilterData(
    val pager: Pager = Pager(),
    val items: List<SubjectItem> = emptyList(),
)

@Serializable
class Pager(
    val hasMore: Boolean = false,
    val nextPage: String = "",
    val page: String = "",
    val perPage: Int = 0,
    val totalCount: Int = 0,
)

@Serializable
class SearchResponse(
    val code: Int = -1,
    val data: SearchData = SearchData(),
)

@Serializable
class SearchData(
    val pager: Pager = Pager(),
    val items: List<SubjectItem> = emptyList(),
)

@Serializable
class SubjectItem(
    val subjectId: String = "",
    val subjectType: Int = 0,
    val title: String = "",
    val description: String = "",
    val releaseDate: String = "",
    val duration: Int = 0,
    val genre: String = "",
    val cover: Cover = Cover(),
    val countryName: String = "",
    val imdbRatingValue: String = "",
    val detailPath: String = "",
)

@Serializable
class Cover(val url: String = "")

@Serializable
class DetailResponse(
    val code: Int = -1,
    val data: DetailData = DetailData(),
)

@Serializable
class DetailData(
    val subject: SubjectDetail = SubjectDetail(),
    val resource: Resource = Resource(),
)

@Serializable
class SubjectDetail(
    val subjectId: String = "",
    val subjectType: Int = 0,
    val title: String = "",
    val description: String = "",
    val releaseDate: String = "",
    val duration: Int = 0,
    val genre: String = "",
    val cover: Cover = Cover(),
    val countryName: String = "",
    val imdbRatingValue: String = "",
    val detailPath: String = "",
    val dubs: List<Dub> = emptyList(),
)

@Serializable
class Dub(
    val subjectId: String = "",
    val lanName: String = "",
    val lanCode: String = "",
    val original: Boolean = false,
    val type: Int = 0,
    val detailPath: String = "",
)

@Serializable
class Resource(
    val seasons: List<Season> = emptyList(),
    val source: String = "",
    val uploadBy: String = "",
)

@Serializable
class Season(
    val se: Int = 0,
    val maxEp: Int = 0,
    val allEp: String = "",
    val resolutions: List<Resolution> = emptyList(),
)

@Serializable
class Resolution(
    val resolution: Int = 0,
    val epNum: Int = 0,
)

@Serializable
class PlayResponse(
    val code: Int = -1,
    val data: PlayData = PlayData(),
)

@Serializable
class PlayData(
    val streams: List<Stream> = emptyList(),
    val dash: List<Stream> = emptyList(),
    val hls: List<Stream> = emptyList(),
    val freeNum: Int = 0,
    val limited: Boolean = false,
    val limitedCode: String = "",
    val hasResource: Boolean = false,
    val vipLocked: Boolean = false,
)

@Serializable
class Stream(
    val format: String = "",
    val id: String = "",
    val url: String = "",
    val resolutions: String = "",
    val codecName: String = "",
    val vipLocked: Boolean = false,
    val size: String = "",
)

@Serializable
class CaptionResponse(
    val code: Int = -1,
    val data: CaptionData = CaptionData(),
)

@Serializable
class CaptionData(val captions: List<Caption> = emptyList())

@Serializable
class Caption(
    val id: String = "",
    val lan: String = "",
    val lanName: String = "",
    val url: String = "",
    val size: String = "",
    val delay: Int = 0,
)
