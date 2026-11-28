package eu.kanade.tachiyomi.animeextension.id.sanka

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Sanka — anime sub Indo via the Sanka API (sankavollerei.web.id).
 * JSON-driven (no HTML scraping). Written 2026-11-28 from the saved API
 * responses in ~/workspace/.scratch-repos/sanka/ (probed live 2026-11-28);
 * see files/riset-source-novel-nonton-2.md section 1.
 *
 * VERIFIED (live, from the saved responses):
 * - Envelope {ok, statusCode, data} on every endpoint; no API key;
 *   documented limit 100 req/min.
 * - GET /anime/search/<query> → data.animeList[] (title, poster, status,
 *   score, animeId, href). Single page (pagination null).
 * - GET /anime/anime/<animeId> → detail + episodeList[] (title, eps,
 *   date "28 November,2026", episodeId, href). Episode 9 of Tokyo
 *   Revengers S4 is dated 28 Nov 2026 — data is fresh.
 * - GET /anime/episode/<episodeId> → defaultStreamingUrl (DesuStream
 *   embed) + server.qualities[] (360p/480p/720p) with serverList[]
 *   (title, serverId, href).
 * - GET /anime/server/<serverId> → {data: {url}} — the direct embed URL
 *   (sample: desustream.net/dstream/arcg/?id=...).
 * - GET /anime/ongoing-anime → data.animeList[25] + pagination
 *   (currentPage, hasNextPage, nextPage, totalPages = 6); page 2 has
 *   the same shape with currentPage 2.
 * - GET /anime/home → data.ongoing.animeList[15] +
 *   data.completed.animeList[10] (completed items carry score).
 *
 * UNVERIFIED — first on-device test / CI build must confirm or correct:
 * 1. PAGE PARAM: /anime/ongoing-anime is assumed to paginate with
 *    ?page=N (the saved page-2 file proves pagination exists, not the
 *    parameter name). If wrong, pages 2+ return page 1 again.
 * 2. PLAYBACK: no embed page was fetched in this pass — the DesuStream
 *    <source src> / JWPlayer extraction is copied from the Ryukomik
 *    extension (verified live there) but not proven against a Sanka
 *    embed URL; VidHide dispatch assumes the resolved URL is a VidHide
 *    embed page; the generic "embed"/.mp4/.m3u8 branch is a guess.
 * 3. MULTI-WORD SEARCH: /anime/search/<query> was only probed with the
 *    single word "naruto"; spaces are percent-encoded via addPathSegment.
 * 4. POPULAR is a proxy: the home endpoint has no "most popular" list,
 *    so Popular shows the completed titles sorted by score descending.
 * 5. Response-based parse overrides (*Parse(response)) follow the same
 *    open-method pattern as Tachiyomi's ParsedHttpSource, which this
 *    class mirrors; the document-based methods throw (unused).
 * 6. kotlinx-serialization comes from the keiyoushi extension plugin
 *    (same plugin the manga module uses for its @Serializable DTOs).
 * 7. API is a single point of failure (one developer's VPS) and its
 *    Novel API endpoints are unmapped — not used here.
 */
@Source
abstract class Sanka :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }

    private val json = Json { ignoreUnknownKeys = true }

    // Base URL is a preference (Floppa's requirement): a domain move must
    // only need a link change, not a rebuild.
    override val baseUrl: String
        get() = (preferences.getString(BASE_URL_KEY, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
            .trimEnd('/')

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}")

    override fun animeDetailsParse(response: Response): SAnime {
        val data = json.decodeFromString<DetailResponse>(response.body.string()).data
            ?: return SAnime.create()
        return SAnime.create().apply {
            title = data.title
            thumbnail_url = data.poster
            genre = data.genreList.joinToString { it.title }
            status = parseStatus(data.status)
            artist = data.studios
            val desc = buildString {
                data.synopsis?.paragraphs?.joinToString("\n\n")
                    ?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (data.type.isNotBlank()) append("\n\nTipe: ${data.type}")
                if (data.duration.isNotBlank()) append("\nDurasi: ${data.duration}")
                if (data.aired.isNotBlank()) append("\nTayang: ${data.aired}")
                if (data.producers.isNotBlank()) append("\nProduser: ${data.producers}")
                if (data.japanese.isNotBlank()) append("\nJudul Jepang: ${data.japanese}")
                if (data.score.isNotBlank()) append("\nSkor: ${data.score}")
            }.trim()
            if (desc.isNotEmpty()) description = desc
        }
    }

    // JSON source: the document-based details parse is unused.
    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    private fun parseStatus(statusString: String?): Int {
        val s = statusString?.lowercase() ?: return SAnime.UNKNOWN
        return when {
            "ongoing" in s -> SAnime.ONGOING
            "completed" in s || "tamat" in s || "selesai" in s -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    // The episode list rides in the details JSON, so episodeListParse
    // re-fetches the same details endpoint and reads episodeList[].
    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = json.decodeFromString<DetailResponse>(response.body.string()).data
            ?: return emptyList()
        return data.episodeList.mapNotNull { item ->
            val number = item.eps.takeIf { it > 0 } ?: return@mapNotNull null
            SEpisode.create().apply {
                name = "Episode $number"
                episode_number = number.toFloat()
                date_upload = parseEpisodeDate(item.date)
                setUrlWithoutDomain("/anime/episode/${item.episodeId}")
            }
        }
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // Episode dates look like "28 November,2026" (Indonesian month names).
    private fun parseEpisodeDate(dateString: String): Long {
        if (dateString.isBlank()) return 0L
        for (pattern in EPISODE_DATE_PATTERNS) {
            try {
                return SimpleDateFormat(pattern, Locale("in", "ID")).parse(dateString)?.time ?: 0L
            } catch (_: Exception) {
                // Try the next pattern.
            }
        }
        return 0L
    }

    // =============================== Latest ===============================
    // /anime/ongoing-anime: 25 titles per page, pagination object with
    // hasNextPage (verified for pages 1 and 2).
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/ongoing-anime?page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val body = json.decodeFromString<OngoingResponse>(response.body.string())
        val anime = body.data?.animeList.orEmpty()
            .filter { it.animeId.isNotBlank() }
            .map { it.toSAnime() }
        return AnimesPage(anime, body.pagination?.hasNextPage ?: false)
    }

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // ============================== Popular ===============================
    // No "most popular" list in the API; the home endpoint's completed
    // titles carry scores, so the highest-scored ones are the proxy.
    // Single page.
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/home")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val completed = json.decodeFromString<HomeResponse>(response.body.string())
            .data?.completed?.animeList.orEmpty()
        val anime = completed
            .filter { it.animeId.isNotBlank() }
            .sortedByDescending { it.score.toDoubleOrNull() ?: 0.0 }
            .map { it.toSAnime() }
        return AnimesPage(anime, false)
    }

    override fun popularAnimeSelector() = throw UnsupportedOperationException()

    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    // /anime/search/<query> — query as a path segment. Single page.
    // A blank query falls back to the ongoing list (its body also decodes
    // as SearchResponse: same data.animeList shape, pagination ignored).
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return latestUpdatesRequest(page)
        val url = "$baseUrl/anime/search".toHttpUrl().newBuilder()
            .addPathSegment(query.trim())
            .build()
        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val anime = json.decodeFromString<SearchResponse>(response.body.string())
            .data?.animeList.orEmpty()
            .filter { it.animeId.isNotBlank() }
            .map { it.toSAnime() }
        return AnimesPage(anime, false)
    }

    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    private fun AnimeCard.toSAnime(): SAnime = SAnime.create().apply {
        setUrlWithoutDomain("/anime/anime/$animeId")
        title = this@toSAnime.title.removeSuffix(" Subtitle Indonesia")
        thumbnail_url = poster
    }

    // ============================ Video Links =============================
    // GET /anime/episode/<episodeId> gives the server list; each server
    // entry is resolved via GET /anime/server/<serverId> to a direct
    // embed URL, then dispatched by the final host. One episode with 12
    // servers = 13 API calls — inside the 100 req/min limit.
    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}")

    override fun videoListParse(response: Response): List<Video> {
        val episode = json.decodeFromString<EpisodeResponse>(response.body.string()).data
            ?: return emptyList()
        val servers = episode.server?.qualities.orEmpty().flatMap { quality ->
            quality.serverList.map { server ->
                Triple(quality.title.trim(), server.title.trim(), server.serverId)
            }
        }
        val videos = servers.parallelMapNotNullBlocking { server ->
            runCatching { getVideosFromServer(server) }.getOrNull()
        }.parallelCatchingFlatMapBlocking { it }
        if (videos.isNotEmpty()) return videos
        // Fallback: the default stream (a DesuStream embed) when no
        // server entry resolved to a playable video.
        val fallback = episode.defaultStreamingUrl.trim()
        if (fallback.isBlank()) return emptyList()
        return runCatching { resolveEmbed("Default", fallback) }.getOrNull().orEmpty()
    }

    private suspend fun getVideosFromServer(server: Triple<String, String, String>): List<Video> {
        val (quality, name, serverId) = server
        val embedUrl = client.newCall(GET("$baseUrl/anime/server/$serverId", headers))
            .awaitSuccess().use { it.body.string() }
            .let { json.decodeFromString<ServerResponse>(it).data?.url.orEmpty() }
            .trim()
        if (embedUrl.isBlank()) return emptyList()
        return resolveEmbed("$quality $name", embedUrl)
    }

    private suspend fun resolveEmbed(name: String, url: String): List<Video> = when {
        "desustream.com/dstream" in url || "desustream.net/dstream" in url ->
            getDesuStreamVideos(name, url)
        "vidhide" in url -> vidHideExtractor.videosFromUrl(url)
        url.endsWith(".mp4") || url.endsWith(".m3u8") || "embed" in url ->
            listOf(Video(videoUrl = url, videoTitle = name, headers = headers))
        else -> emptyList()
    }

    // DesuStream pages serve a plain <video><source src="..."> (verified
    // live in the Ryukomik extension); the JWPlayer sources:[{file:'...'}]
    // form is kept as a fallback from the OtakuDesu branch.
    private suspend fun getDesuStreamVideos(name: String, url: String): List<Video> {
        val body = client.newCall(GET(url, headers)).awaitSuccess().use { it.body.string() }
        val direct = SOURCE_TAG_REGEX.find(body)?.groupValues?.get(1)
            ?: JWPLAYER_REGEX.find(body)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(Video(videoUrl = direct, videoTitle = "$name - DesuStream", headers = headers))
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    // The API has /anime/genre/<slug>, but no genre list endpoint was
    // probed — no hardcoded genre filter in v1.
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
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

    // ============================= Utilities ==============================
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.videoTitle.contains(quality) },
        )
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://www.sankavollerei.web.id"
        private const val BASE_URL_KEY = "base_url"

        private val SOURCE_TAG_REGEX = "<source[^>]+src=\"([^\"]+)\"".toRegex()
        private val JWPLAYER_REGEX = "sources:\\[\\{file:'([^']+)'".toRegex()

        private val EPISODE_DATE_PATTERNS = arrayOf("d MMMM,yyyy", "d MMM,yyyy")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
