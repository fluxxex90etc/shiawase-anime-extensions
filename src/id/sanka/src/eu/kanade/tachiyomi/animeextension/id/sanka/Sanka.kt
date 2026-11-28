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
import kotlinx.coroutines.runBlocking
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
 * v14.3 (2026-11-28): VidHide playback fix — the odvidhide.com player
 * setup hides inside a Dean Edwards packed eval, which the
 * VidHideExtractor lib never unpacks, so vidhide extraction returned
 * empty. getVidHideVideos now unpacks the eval itself (radix 36,
 * ~600 words, verified on the 720p + 360p embeds) and reads the
 * hls2/hls3/hls4 links out of the unpacked `var links={...}` object.
 * The unpacked .m3u8 URLs 403 from a datacenter IP — on-device test
 * on Floppa's phone is still the proof that they play.
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
    // No "most popular" list in the API; the home endpoint's ongoing
    // (15) + completed (10) titles are shown together, completed first
    // by score. Single page.
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/home")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val home = json.decodeFromString<HomeResponse>(response.body.string()).data
        val ongoing = home?.ongoing?.animeList.orEmpty()
        val completed = home?.completed?.animeList.orEmpty()
            .sortedByDescending { it.score.toDoubleOrNull() ?: 0.0 }
        val anime = (ongoing + completed)
            .filter { it.animeId.isNotBlank() }
            .distinctBy { it.animeId }
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
        return runCatching { runBlocking { resolveEmbed("Default", fallback) } }.getOrNull().orEmpty()
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
        "vidhide" in url -> getVidHideVideos(name, url)
        url.endsWith(".mp4") || url.endsWith(".m3u8") ->
            listOf(Video(videoUrl = url, videoTitle = name, headers = headers))
        // Anything else (filedon/mega/odcdn embed pages, download gates)
        // is an HTML page, not a playable stream — never list it as one.
        else -> emptyList()
    }

    // VidHide (odvidhide.com) hides its player setup inside a Dean
    // Edwards packed eval — the VidHideExtractor lib never unpacks eval,
    // so its raw `file:"..."` search finds nothing and it returns
    // empty. Unpack the eval ourselves and read the stream links out
    // of the unpacked `var links={hls2,hls3,hls4}` object (verified
    // live 2026-11-28 on the 720p and 360p embeds of Tokyo Revengers
    // S4 E9: radix 36, ~600 words; the unpacked setup is
    // jwplayer("vplayer").setup({sources:[{file:links.hls4||
    // links.hls3||links.hls2,type:"hls"}]}).
    // The unpacked .m3u8 URLs 403 from a datacenter IP (VM) — they
    // should still play on the phone (residential IP).
    private suspend fun getVidHideVideos(name: String, url: String): List<Video> {
        // Try the extractor first (it handles unpacked pages), then
        // fall back to unpacking the packed eval ourselves.
        val extracted = runCatching { vidHideExtractor.videosFromUrl(url) }.getOrNull().orEmpty()
        if (extracted.isNotEmpty()) return extracted
        val html = runCatching {
            client.newCall(GET(url, headers)).awaitSuccess().use { it.body.string() }
        }.getOrNull() ?: return emptyList()
        val unpacked = unpackPackedEval(html) ?: return emptyList()
        return vidHideLinksToVideos(name, url, unpacked)
    }

    // Unpacks one Dean Edwards packed eval:
    // eval(function(p,a,c,k,e,d){...}('PAYLOAD',RADIX,COUNT,'W1|W2|...'.split('|'),0,{}))
    // Returns the unpacked source, or null when there is no packed eval.
    private fun unpackPackedEval(html: String): String? {
        val start = html.indexOf("eval(function(p,a,c,k,e,d)")
        if (start < 0) return null
        val argsStart = html.indexOf("}('", start)
        if (argsStart < 0) return null
        var pos = argsStart + 3 // at the opening quote of the payload
        val (payload, afterPayload) = readJsString(html, pos) ?: return null
        pos = afterPayload
        if (pos >= html.length || html[pos] != ',') return null
        pos++
        val radixEnd = html.indexOf(',', pos)
        if (radixEnd < 0) return null
        val radix = html.substring(pos, radixEnd).trim().toIntOrNull() ?: return null
        if (radix < 2 || radix > 62) return null
        pos = radixEnd + 1
        val countEnd = html.indexOf(',', pos)
        if (countEnd < 0) return null
        val count = html.substring(pos, countEnd).trim().toIntOrNull() ?: return null
        pos = countEnd + 1
        val (wordsStr, _) = readJsString(html, pos) ?: return null
        val words = wordsStr.split('|')
        // The packer substitutes from the last word down (while(c--)).
        var unpacked = payload
        for (c in count - 1 downTo 0) {
            val word = words.getOrNull(c).orEmpty()
            if (word.isEmpty()) continue
            val token = c.toBaseString(radix)
            unpacked = Regex("\\b$token\\b").replace(unpacked) { word }
        }
        return unpacked
    }

    // Reads one single-quoted JS string literal starting at pos (which
    // must be the opening quote); backslash escapes are resolved to
    // the escaped character. Returns the value and the position just
    // past the closing quote, or null when the string is unterminated.
    private fun readJsString(s: String, pos: Int): Pair<String, Int>? {
        if (pos >= s.length || s[pos] != '\'') return null
        val out = StringBuilder()
        var i = pos + 1
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                out.append(s[i + 1])
                i += 2
                continue
            }
            if (ch == '\'') return out.toString() to (i + 1)
            out.append(ch)
            i++
        }
        return null
    }

    // Int.toString(radix) only supports 2..36; the packer can use up
    // to 62 (digits 0-9a-zA-Z).
    private fun Int.toBaseString(radix: Int): String {
        if (radix <= 36) return toString(radix)
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var n = this
        if (n == 0) return "0"
        val out = StringBuilder()
        while (n > 0) {
            out.append(digits[n % radix])
            n /= radix
        }
        return out.reverse().toString()
    }

    // Reads the stream links out of the unpacked player setup. The
    // links object does not always carry all three keys (the 360p
    // sample has only hls2+hls3) — missing keys are skipped. Order:
    // the direct .m3u8 CDN link first, then the origin .m3u8, then
    // the .txt playlist (ExoPlayer is least likely to play that one).
    private fun vidHideLinksToVideos(name: String, embedUrl: String, unpacked: String): List<Video> {
        val linksBlock = LINKS_OBJECT_REGEX.find(unpacked)?.groupValues?.get(1) ?: return emptyList()
        val entries = LINK_ENTRY_REGEX.findAll(linksBlock)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val base = runCatching { embedUrl.toHttpUrl() }.getOrNull() ?: return emptyList()
        val orderedKeys = listOf("hls2", "hls4", "hls3")
        val urls = orderedKeys.mapNotNull { key ->
            entries[key]?.takeIf { it.isNotBlank() }?.let { raw ->
                base.resolve(raw)?.toString()
            }
        }
        return urls.mapIndexed { index, videoUrl ->
            val title = if (index == 0) "$name - VidHide" else "$name - VidHide (cadangan $index)"
            Video(videoUrl = videoUrl, videoTitle = title, headers = headers)
        }
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
        private val LINKS_OBJECT_REGEX = "links\\s*=\\s*\\{([^}]*)\\}".toRegex()
        private val LINK_ENTRY_REGEX = "\"(hls\\d)\"\\s*:\\s*\"([^\"]+)\"".toRegex()

        private val EPISODE_DATE_PATTERNS = arrayOf("d MMMM,yyyy", "d MMM,yyyy")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
