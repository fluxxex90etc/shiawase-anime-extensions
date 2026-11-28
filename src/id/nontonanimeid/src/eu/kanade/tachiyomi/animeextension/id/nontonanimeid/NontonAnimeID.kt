package eu.kanade.tachiyomi.animeextension.id.nontonanimeid

import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * NontonAnimeID — anime sub Indo from nontonanime.id (Laravel site,
 * Indonesian). Written 2026-11-28; every claim below was probed live
 * from the site that day (Naruto + Sono Bisque Doll S2 2025).
 *
 * VERIFIED:
 * - GET / → homepage with server-rendered sections "Top ON-GOING",
 *   "Upcoming", "Top Movie" (no paginated list page; /anime is 404).
 *   Cards: <a href="/anime/<slug>"> with a cover <img src=".../cover/...">
 *   (alt = title) and a title div. "Top ON-GOING" is used for both
 *   Popular and Latest (no dedicated latest feed exists).
 * - GET /live-search?q=<query> → {"data":[{id, title, slug, poster,
 *   type, release_year, episodes, duration, rating}]} (JSON, no key).
 * - GET /anime/<slug> → detail page; h1.hero-title, badges
 *   (TV/2002/Completed), p.hero-short-description, <h3>Genres</h3>
 *   + <p>Action, Adventure, ...</p>; first img[src*=/cover/] is the
 *   main poster; the episode list rides in the page as
 *   const allEpisodes = [{id, anime_id, episode_number, title,
 *   air_date, video_url, video_url2, video_url3, server1_name,
 *   server2_name, server3_name, ...}].
 * - Episode URL pattern: /<slug>-episode-<n> (detail page links it).
 * - video_url is a /stream/v2/<base64> link; the token decodes (once,
 *   sometimes twice) to a direct .mp4 on storage.animekita.org —
 *   HEAD 200 + ranged GET 206 verified for Naruto ep 1 and Bisque
 *   Doll S2 ep 1-2. Server names are HD-1 / HD-2 / HD-3; video_url2/3
 *   are usually null (all 221 Naruto episodes, all 12 Bisque Doll
 *   episodes: only video_url set).
 *
 * KNOWN LIMITS (v1):
 * - yoreplayer/blogger video.g URLs (old episodes, e.g. Naruto ep 220)
 *   return an HTML page whose video URL is JS-loaded — not extractable
 *   with a regex, so those servers are skipped, not listed as videos.
 *   Same for blogspot/mega embeds (HTML pages, never listed).
 * - The site has +18 titles (a card carries a +18 overlay), so the
 *   extension is flagged MIXED — the app's NSFW setting must be ON.
 */
@Source
abstract class NontonAnimeID :
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

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.hero-title")?.text()
            ?: document.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = document.select("img[src*=\"/cover/\"]").firstOrNull()?.absUrl("src")
        description = document.selectFirst("p.hero-short-description")?.text()
        genre = document.selectFirst("h3:containsOwn(Genres)")
            ?.nextElementSibling()?.text()
        status = parseStatus(document.select("span.badge-red").joinToString(" ") { it.text() })
    }

    private fun parseStatus(statusString: String?): Int {
        val s = statusString?.lowercase() ?: return SAnime.UNKNOWN
        return when {
            "ongoing" in s -> SAnime.ONGOING
            "completed" in s || "tamat" in s || "selesai" in s -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val body = response.body.string()
        val slug = response.request.url.encodedPath
            .substringAfter("/anime/").substringBefore("/")
        return parseAllEpisodes(body).mapNotNull { item ->
            val number = item.episode_number.takeIf { it > 0 } ?: return@mapNotNull null
            SEpisode.create().apply {
                name = "Episode $number" + (item.title?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                episode_number = number.toFloat()
                date_upload = parseAirDate(item.air_date)
                setUrlWithoutDomain("/$slug-episode-$number")
            }
        }.sortedBy { it.episode_number }
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // Air dates are ISO 8601 ("2002-10-02T17:00:00.000000Z"); only the
    // date part is used.
    private fun parseAirDate(dateString: String?): Long {
        val date = dateString?.take(10).orEmpty()
        if (date.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)?.time ?: 0L
        }.getOrNull() ?: 0L
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val anime = homeSectionAnime(response.useAsJsoup(), "Top ON-GOING")
        return AnimesPage(anime, false)
    }

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val anime = homeSectionAnime(response.useAsJsoup(), "Top ON-GOING")
        return AnimesPage(anime, false)
    }

    override fun popularAnimeSelector() = throw UnsupportedOperationException()

    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()

    // Cards of one homepage section: the <h2> heading's parent div is
    // followed by the cards container. Falls back to every /anime/ link
    // on the page (deduped) if the section markup moved.
    private fun homeSectionAnime(document: Document, heading: String): List<SAnime> {
        val cards = document.selectFirst("h2:contains($heading)")
            ?.parent()?.nextElementSibling()
            ?.select("a[href*=\"/anime/\"]")
            .orEmpty()
            .ifEmpty { document.select("a[href*=\"/anime/\"]") }
        return cards.distinctBy { it.attr("href") }
            .mapNotNull { cardToSAnime(it) }
    }

    private fun cardToSAnime(element: Element): SAnime? {
        val href = element.attr("href").ifBlank { return null }
        val path = href.replace(Regex("^https?://[^/]+"), "")
            .takeIf { it.startsWith("/anime/") } ?: return null
        // The card's first <img> is a banner with an EMPTY alt — the
        // title lives on the cover img's alt. Take the first non-blank
        // alt (an empty alt is not null, so a plain ?: never fires).
        val title = element.select("img[alt]")
            .map { it.attr("alt").trim() }
            .firstOrNull { it.isNotBlank() }
            ?: path.substringAfterLast("/").replace('-', ' ').trim()
        if (title.isBlank()) return null
        return SAnime.create().apply {
            setUrlWithoutDomain(path)
            this.title = title
            thumbnail_url = element.selectFirst("img[src*=\"/cover/\"]")?.absUrl("src")
                ?: element.selectFirst("img")?.absUrl("src")
        }
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return latestUpdatesRequest(page)
        val url = "$baseUrl/live-search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .build()
        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val items = runCatching {
            json.decodeFromString<SearchResponse>(response.body.string()).data
        }.getOrNull().orEmpty()
        val anime = items.filter { it.slug.isNotBlank() }.map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain("/anime/${item.slug}")
                title = item.title
                thumbnail_url = item.poster
            }
        }
        return AnimesPage(anime, false)
    }

    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    // The episode page embeds the same allEpisodes JSON as the detail
    // page; the current episode's video_url fields are resolved here.
    // Only /stream/v2/ tokens (→ direct mp4) and direct .mp4/.m3u8
    // links are listed — yoreplayer/blogger and blogspot/mega embeds
    // are HTML pages, not streams.
    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}")

    override fun videoListParse(response: Response): List<Video> {
        val number = EPISODE_NUMBER_REGEX.find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episode = parseAllEpisodes(response.body.string())
            .firstOrNull { it.episode_number == number } ?: return emptyList()
        val servers = listOf(
            episode.server1_name to episode.video_url,
            episode.server2_name to episode.video_url2,
            episode.server3_name to episode.video_url3,
        )
        return servers.mapNotNull { (name, url) ->
            val raw = url?.trim().orEmpty()
            if (raw.isBlank()) return@mapNotNull null
            val resolved = when {
                "/stream/v2/" in raw -> resolveStreamUrl(raw)
                raw.endsWith(".mp4") || raw.endsWith(".m3u8") -> raw
                else -> null
            } ?: return@mapNotNull null
            Video(
                videoUrl = resolved,
                videoTitle = name?.trim().orEmpty().ifBlank { "Server" },
                headers = headers,
            )
        }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // A /stream/v2/<token> decodes to the direct file URL; sometimes the
    // decoded URL is another /stream/v2/ link (double-encoded), so
    // decode until no /stream/v2/ is left (max 3 rounds).
    private fun resolveStreamUrl(url: String): String? {
        var current = url.trim()
        var attempts = 0
        while ("/stream/v2/" in current && attempts < 3) {
            val token = current.substringAfter("/stream/v2/")
                .substringBefore("?").substringBefore("#").substringBefore("\"").trim()
            val decoded = decodeBase64(token) ?: return null
            current = decoded.trim()
            attempts++
        }
        return current.takeIf { "/stream/v2/" !in it && it.isNotBlank() }
    }

    private fun decodeBase64(token: String): String? = runCatching {
        val normalized = token.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        Base64.decode(padded, Base64.DEFAULT).toString(Charsets.UTF_8)
    }.getOrNull()

    // The episode array rides in a <script> as: const allEpisodes = [...];
    // extracted by bracket counting (string-aware, so a "];" inside a
    // description can never cut the array short).
    private fun parseAllEpisodes(body: String): List<EpisodeDto> {
        val array = extractJsonArray(body, "const allEpisodes = ") ?: return emptyList()
        return runCatching { json.decodeFromString<List<EpisodeDto>>(array) }.getOrNull().orEmpty()
    }

    private fun extractJsonArray(body: String, marker: String): String? {
        val start = body.indexOf(marker).takeIf { it >= 0 } ?: return null
        val arrayStart = body.indexOf('[', start).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in arrayStart until body.length) {
            val c = body[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return body.substring(arrayStart, i + 1)
                    }
                }
            }
        }
        return null
    }

    // ============================== Filters ===============================
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
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://nontonanime.id"
        private const val BASE_URL_KEY = "base_url"

        private val EPISODE_NUMBER_REGEX = "-episode-(\\d+)".toRegex()
    }
}
