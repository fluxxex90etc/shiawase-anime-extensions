package eu.kanade.tachiyomi.animeextension.id.moviebox

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest

/**
 * MovieBox — film & series from movieboxhd.net (the MovieBox app,
 * moviebox.ph), with the site in Indonesian and Indonesian subtitles.
 * Written 2026-11-28; every claim below was probed live from the
 * site/API that day (Avatar 2009, A Wrinkle in Time, Stranger Things).
 *
 * API (base https://movieboxhd.net/wefeed-h5api-bff):
 * - GET  /subject/trending?page=&perPage=18 — trending titles.
 * - POST /subject/filter {channelId, page, perPage, sort} — browse.
 *   channelId 1 = film, 2 = series; sort "popular" / "latest" (string).
 * - POST /subject/search {keyword, page, perPage, subjectType?} —
 *   subjectType 1 = film, 2 = series, omitted = both.
 * - GET  /detail?detailPath=<slug> — title, synopsis, genre, cover,
 *   dubs (language versions), resource.seasons.
 * - GET  /subject/play?subjectId=&se=&ep=&detailPath=&streamSignType=1
 *   — streams (MP4/DASH/HLS). Movies use se=0, ep=0; series use the
 *   real season/episode numbers.
 * - GET  /subject/caption?format=MP4&id=<streamId>&subjectId=
 *   &detailPath= — subtitle tracks (lan "in_id" = Indonesian).
 * - GET  /home?host=<host> — sets a `token` cookie (JWT).
 *
 * AUTH (verified live):
 * - X-Client-Token "<unix-seconds>,<md5 of the reversed timestamp>"
 *   (the site's ZC(): e = floor(now/1000), t = reverse(String(e)),
 *   r = MD5(t)). REQUIRED by the play endpoint — without it the
 *   stream list comes back empty. Harmless on the other endpoints,
 *   but it MUST NOT be sent on search (the search backend rejects
 *   the request when that header is present).
 * - Search needs "Authorization: Bearer <JWT>", where the JWT is the
 *   `token` cookie from GET /home. Without it search answers 400
 *   "invalid token". The JWT is cached in memory and refreshed when
 *   older than an hour, or once on a 400 (retry).
 * - X-Request-Lang "id" makes genres/labels Indonesian.
 * - X-Vip-Restrict "1" is sent on play calls, like the site does.
 *
 * BAHASA INDONESIA:
 * - A title's detail lists `dubs` — one version per subtitle/dub
 *   language. When an "Indonesian sub" version (lanCode "id",
 *   type 1) exists, episodes play through THAT version's subjectId
 *   and slug, so the video opens with Indonesian subtitles. Not
 *   every title has one (Avatar 2009 has none) — then the original
 *   version is used.
 * - Every video also gets the Indonesian subtitle track attached
 *   (caption lan "in_id", a signed .srt URL) when one exists.
 *
 * KNOWN LIMITS (v1):
 * - Only the free MP4 streams are listed (360p/480p). The 720p and
 *   1080p MP4s and the DASH streams are VIP-locked (their URLs come
 *   back empty), so 480p is the free max. DASH would also need its
 *   signCookie / X-MB-Token header handling.
 * - The MP4 URLs are signed (?sign=...&t=...) and expire; they are
 *   fetched fresh every time a video is opened, so that is fine.
 * - Series episode lists come from resource.seasons (maxEp per
 *   season); episode air dates are not in the API, so date_upload
 *   is left unknown.
 */
@Source
abstract class MovieBox :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val json = Json { ignoreUnknownKeys = true }

    // Base URL is a preference (Floppa's requirement): a domain move must
    // only need a link change, not a rebuild.
    override val baseUrl: String
        get() = (preferences.getString(BASE_URL_KEY, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
            .trimEnd('/')

    private val apiBase: String get() = "$baseUrl/wefeed-h5api-bff"

    // Search JWT (the `token` cookie from GET /home), cached in memory.
    @Volatile private var cachedJwt: String? = null

    @Volatile private var jwtFetchedAt: Long = 0L

    // ============================== Headers ===============================
    private fun apiHeaders(extra: Map<String, String> = emptyMap()): Headers {
        val builder = headers.newBuilder()
            .add("Accept", "application/json")
            .add("X-Request-Lang", "id")
            .add("X-Client-Info", """{"timezone":"Asia/Jakarta"}""")
        extra.forEach { (name, value) -> builder.add(name, value) }
        return builder.build()
    }

    private fun clientToken(): String {
        val ts = System.currentTimeMillis() / 1000
        val reversed = ts.toString().reversed()
        val md5 = MessageDigest.getInstance("MD5")
            .digest(reversed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "$ts,$md5"
    }

    private fun getJwt(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedJwt != null && now - jwtFetchedAt < JWT_MAX_AGE_MS) {
            return cachedJwt
        }
        val host = baseUrl.substringAfter("://").substringBefore("/")
        val request = GET("$apiBase/home?host=$host", apiHeaders())
        val token = runCatching {
            client.newCall(request).execute().use { response ->
                response.headers("Set-Cookie")
                    .firstNotNullOfOrNull { JWT_COOKIE_REGEX.find(it)?.groupValues?.get(1) }
            }
        }.getOrNull()
        if (!token.isNullOrBlank()) {
            cachedJwt = token
            jwtFetchedAt = now
        }
        return cachedJwt
    }

    private fun postJson(url: String, body: String, headers: Headers): Request = Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .headers(headers)
        .build()

    // ============================== Popular ===============================
    // Popular and Latest each merge the film list (channelId 1) and the
    // series list (channelId 2) for the same page, interleaved, so one
    // list shows both. The page and sort are repeated as query params
    // on the request URL so the parse step can re-derive them (the
    // response body carries no page info of its own).
    override fun popularAnimeRequest(page: Int): Request = filterRequest(page, "popular")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val sort = response.request.url.queryParameter("sort") ?: "popular"
        val first = parseFilterResponse(response)
        val second = runCatching { fetchFilterPage(page, sort, 2) }.getOrNull()
        val merged = interleave(first.first, second?.first.orEmpty())
        return AnimesPage(merged, first.second || (second?.second == true))
    }

    override fun popularAnimeSelector() = throw UnsupportedOperationException()

    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = filterRequest(page, "latest")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val sort = response.request.url.queryParameter("sort") ?: "latest"
        val first = parseFilterResponse(response)
        val second = runCatching { fetchFilterPage(page, sort, 2) }.getOrNull()
        val merged = interleave(first.first, second?.first.orEmpty())
        return AnimesPage(merged, first.second || (second?.second == true))
    }

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    private fun filterRequest(page: Int, sort: String, channelId: Int = 1): Request {
        val body = buildJsonObject {
            put("channelId", channelId)
            put("page", page)
            put("perPage", FILTER_PER_PAGE)
            put("sort", sort)
        }.toString()
        val url = "$apiBase/subject/filter?page=$page&sort=$sort"
        return postJson(url, body, apiHeaders())
    }

    private fun fetchFilterPage(page: Int, sort: String, channelId: Int): Pair<List<SAnime>, Boolean> {
        val response = client.newCall(filterRequest(page, sort, channelId)).execute()
        return response.use { parseFilterResponse(it) }
    }

    private fun parseFilterResponse(response: Response): Pair<List<SAnime>, Boolean> {
        val parsed = runCatching {
            json.decodeFromString<FilterResponse>(response.body.string())
        }.getOrNull() ?: return emptyList<SAnime>() to false
        if (parsed.code != 0) return emptyList<SAnime>() to false
        val anime = parsed.data.items.mapNotNull { it.toSAnime() }
        return anime to parsed.data.pager.hasMore
    }

    private fun interleave(first: List<SAnime>, second: List<SAnime>): List<SAnime> {
        val merged = ArrayList<SAnime>(first.size + second.size)
        val max = maxOf(first.size, second.size)
        for (i in 0 until max) {
            if (i < first.size) merged.add(first[i])
            if (i < second.size) merged.add(second[i])
        }
        return merged
    }

    private fun SubjectItem.toSAnime(): SAnime? {
        if (subjectId.isBlank() || detailPath.isBlank()) return null
        return SAnime.create().apply {
            setUrlWithoutDomain("/moviedetail/$detailPath?id=$subjectId")
            title = this@toSAnime.title
            thumbnail_url = cover.url.takeIf { it.isNotBlank() }
            description = this@toSAnime.description.takeIf { it.isNotBlank() }
            genre = this@toSAnime.genre.takeIf { it.isNotBlank() }
            status = if (subjectType == 1) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return latestUpdatesRequest(page)
        val typeState = filters.filterIsInstance<AnimeFilter.Select<String>>()
            .firstOrNull()?.state ?: 0
        val body = buildJsonObject {
            put("keyword", query.trim())
            put("page", page)
            put("perPage", SEARCH_PER_PAGE)
            if (typeState > 0) put("subjectType", typeState)
        }.toString()
        val jwt = getJwt()
        val extra = if (!jwt.isNullOrBlank()) {
            mapOf("Authorization" to "Bearer $jwt")
        } else {
            emptyMap()
        }
        // No X-Client-Token here: the search backend rejects the
        // request when that header is present.
        return postJson("$apiBase/subject/search", body, apiHeaders(extra))
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        parseSearchResponse(response)?.let { return it }
        // The search token may have expired — refresh it once and retry
        // the same request.
        val retried = runCatching { retrySearch(response.request) }.getOrNull()
        return retried ?: AnimesPage(emptyList(), false)
    }

    private fun parseSearchResponse(response: Response): AnimesPage? {
        val parsed = runCatching {
            json.decodeFromString<SearchResponse>(response.body.string())
        }.getOrNull() ?: return null
        if (parsed.code != 0) return null
        val anime = parsed.data.items.mapNotNull { it.toSAnime() }
        return AnimesPage(anime, parsed.data.pager.hasMore)
    }

    private fun retrySearch(original: Request): AnimesPage? {
        val jwt = getJwt(forceRefresh = true) ?: return null
        val bodyString = runCatching {
            val buffer = Buffer()
            original.body?.writeTo(buffer)
            buffer.readUtf8()
        }.getOrNull() ?: return null
        val request = postJson(
            original.url.toString(),
            bodyString,
            apiHeaders(mapOf("Authorization" to "Bearer $jwt")),
        )
        val response = client.newCall(request).execute()
        return response.use { parseSearchResponse(it) }
    }

    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = slugFromUrl(anime.url)
        return GET("$apiBase/detail?detailPath=$slug", apiHeaders())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val detail = runCatching {
            json.decodeFromString<DetailResponse>(response.body.string()).data.subject
        }.getOrNull() ?: return SAnime.create()
        return SAnime.create().apply {
            title = detail.title
            thumbnail_url = detail.cover.url.takeIf { it.isNotBlank() }
            description = buildString {
                append(detail.description)
                if (detail.imdbRatingValue.isNotBlank() && detail.imdbRatingValue != "0") {
                    if (isNotEmpty()) append("\n\n")
                    append("IMDb: ${detail.imdbRatingValue}")
                }
                if (detail.countryName.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(detail.countryName)
                }
            }.takeIf { it.isNotBlank() }
            genre = detail.genre.takeIf { it.isNotBlank() }
            status = if (detail.subjectType == 1) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    private fun slugFromUrl(url: String): String = url.substringAfter("/moviedetail/").substringBefore("?").substringBefore("/")

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val slug = slugFromUrl(anime.url)
        return GET("$apiBase/detail?detailPath=$slug", apiHeaders())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = runCatching {
            json.decodeFromString<DetailResponse>(response.body.string()).data
        }.getOrNull() ?: return emptyList()
        val subject = data.subject
        // Prefer the Indonesian-sub version when the title has one, so
        // the video opens with Indonesian subtitles by default. The
        // video files are the same across versions; only the default
        // subtitle differs.
        val version = subject.dubs.firstOrNull { it.lanCode == "id" && it.type == 1 }
        val playSubjectId = version?.subjectId?.takeIf { it.isNotBlank() } ?: subject.subjectId
        val playSlug = version?.detailPath?.takeIf { it.isNotBlank() } ?: subject.detailPath
        if (playSubjectId.isBlank() || playSlug.isBlank()) return emptyList()
        return when (subject.subjectType) {
            1 -> listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1f
                    url = "$playSubjectId|$playSlug|0|0"
                },
            )
            else -> data.resource.seasons.sortedBy { it.se }.flatMap { season ->
                (1..season.maxEp).map { ep ->
                    SEpisode.create().apply {
                        name = "S${season.se} E$ep"
                        episode_number = (season.se * 100 + ep).toFloat()
                        url = "$playSubjectId|$playSlug|${season.se}|$ep"
                    }
                }
            }
        }
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val parts = episode.url.split("|")
        val subjectId = parts.getOrNull(0).orEmpty()
        val slug = parts.getOrNull(1).orEmpty()
        val se = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val ep = parts.getOrNull(3)?.toIntOrNull() ?: 0
        val url = "$apiBase/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$slug&streamSignType=1"
        return GET(
            url,
            apiHeaders(
                mapOf(
                    "X-Client-Token" to clientToken(),
                    "X-Vip-Restrict" to "1",
                ),
            ),
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val parsed = runCatching {
            json.decodeFromString<PlayResponse>(response.body.string())
        }.getOrNull() ?: return emptyList()
        if (parsed.code != 0) return emptyList()
        val data = parsed.data
        // Free streams are the MP4 files; the 720p/1080p MP4s and the
        // DASH streams are VIP-locked (their URLs come back empty),
        // so 480p is the free max.
        val streams = data.streams.filter { it.format == "MP4" && !it.vipLocked && it.url.isNotBlank() }
        if (streams.isEmpty()) return emptyList()
        val videos = streams.map { stream ->
            Video(
                videoUrl = stream.url,
                videoTitle = "${stream.resolutions}p",
                headers = headers,
            )
        }
        // Attach the Indonesian subtitle track (caption lan "in_id") to
        // every video. A caption failure must not drop the videos.
        val subjectId = response.request.url.queryParameter("subjectId").orEmpty()
        val slug = response.request.url.queryParameter("detailPath").orEmpty()
        val subtitle = runCatching {
            indonesianSubtitle(subjectId, slug, streams.first().id)
        }.getOrNull()
        return if (subtitle == null) {
            videos
        } else {
            videos.map { it.copy(subtitleTracks = listOf(subtitle)) }
        }
    }

    private fun indonesianSubtitle(subjectId: String, slug: String, streamId: String): Track? {
        if (subjectId.isBlank() || slug.isBlank() || streamId.isBlank()) return null
        val url = "$apiBase/subject/caption?format=MP4&id=$streamId&subjectId=$subjectId&detailPath=$slug"
        val response = client.newCall(GET(url, apiHeaders())).execute()
        val captions = response.use {
            runCatching {
                json.decodeFromString<CaptionResponse>(it.body.string()).data.captions
            }.getOrNull()
        } ?: return null
        val indonesian = captions.firstOrNull { it.lan == "in_id" && it.url.isNotBlank() } ?: return null
        return Track(indonesian.url, "Indonesian")
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Select("Tipe", arrayOf("Semua", "Film", "Series")),
        AnimeFilter.Header("Tipe hanya berlaku untuk pencarian"),
    )

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_KEY
            title = "Base URL"
            setDefaultValue(DEFAULT_BASE_URL)
            summary = "%s"
            dialogTitle = "Base URL"
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.videoTitle.contains(quality) },
        )
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://movieboxhd.net"
        private const val BASE_URL_KEY = "base_url"

        private const val FILTER_PER_PAGE = 18
        private const val SEARCH_PER_PAGE = 20

        private const val JWT_MAX_AGE_MS = 60 * 60 * 1000L
        private val JWT_COOKIE_REGEX = "token=([^;]+)".toRegex()

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "480p"
        private val PREF_QUALITY_ENTRIES = arrayOf("480p", "360p")
    }
}
