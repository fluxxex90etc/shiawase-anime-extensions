package eu.kanade.tachiyomi.animeextension.id.ryukomikanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
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
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class RyukomikAnime :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }

    // =========================== Anime Details ============================
    // Title from the <h1>; the rest rides in the Next.js flight data as
    // escaped JSON in <script> tags (Genre, genres[], synopsis, status,
    // studio, duration, totalEpisode). The cover is the first <img> with a
    // non-empty alt that is not the site icon.
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val scripts = document.select("script").joinToString("\n") { it.data() }.replace("\\\"", "\"")
        // Scope all field regexes to the detail data object (it starts at the
        // "Genre" field) so a navigation script's genre list is never picked up.
        val dataStart = scripts.indexOf("\"Genre\":\"")
        val data = if (dataStart >= 0) scripts.substring(dataStart) else scripts

        title = document.selectFirst("h1")?.text().orEmpty()
        genre = Regex("\"genres\":\\[([^\\]]*)\\]").find(data)
            ?.groupValues?.get(1)
            ?.let { Regex("\"name\":\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.joinToString() }
            ?: data.stringField("Genre")
        status = parseStatus(data.stringField("status"))
        artist = data.stringField("studio")
        thumbnail_url = document.select("img[src]").firstOrNull {
            it.attr("alt").isNotBlank() && !it.attr("src").endsWith("/icon.png")
        }?.absUrl("src")

        description = buildString {
            data.stringField("synopsis")?.unescapeJson()?.let { append(it) }
            data.stringField("duration")?.let { append("\n\nDurasi: $it") }
            data.stringField("season")?.let { append("\nMusim: $it") }
            Regex("\"totalEpisode\":(\\d+)").find(data)?.groupValues?.get(1)?.let {
                append("\nTotal Episode: $it")
            }
        }
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
    // Direct links: <a href="/anime/episode/<slug>-episode-N-sub-indo?s=..&t=..">
    // with a <span>Episode N</span> inside. No upload dates on the page.
    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val href = element.attr("href")
        setUrlWithoutDomain(href)
        name = element.selectFirst("span")?.text() ?: "Episode"
        episode_number = EPISODE_NUMBER_REGEX.find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
        date_upload = 0L
    }

    override fun episodeListSelector() = "a[href^=\"/anime/episode/\"]"

    // =============================== Latest ===============================
    // /anime/terbaru is a single page of 50 cards; ?page= is ignored by the
    // site, so there is no next page.
    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")?.attr("src")
        title = element.selectFirst("img")?.attr("alt")?.removeSuffix(" Sub Indo").orEmpty()
    }

    override fun latestUpdatesNextPageSelector() = "a[href*=\"page=\"]"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime/terbaru")

    override fun latestUpdatesSelector() = "a[href^=\"/anime/detail/\"]"

    // ============================== Popular ===============================
    // No /anime/popular endpoint (404); the ongoing list is the closest
    // equivalent and uses the same card markup.
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime/ongoing")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    // =============================== Search ===============================
    // /anime/search?q= returns the same cards as /anime/terbaru. Verified
    // live: anime-only results, no manga links.
    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return GET("$baseUrl/anime/terbaru")
        val url = "$baseUrl/anime/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url)
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()

    // ============================ Video Links =============================
    // The episode page embeds its servers as JSON entries, e.g.
    // {"name":"480p otakuwatch7","iframe":"https://desustream.com/dstream/..."}
    // {"name":"360p - Mega","url":"https://link.desustream.com/?id=..."}
    // {"name":"720p vidhide","iframe":"https://odvidhide.com/embed/..."}.
    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string().replace("\\\"", "\"")
        val servers = SERVER_REGEX.findAll(html).map { match ->
            Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }.toList()

        return servers.parallelMapNotNullBlocking { server ->
            runCatching { getVideosFromServer(server) }.getOrNull()
        }.parallelCatchingFlatMapBlocking { it }
    }

    private suspend fun getVideosFromServer(server: Triple<String, String, String>): List<Video> {
        val (name, _, url) = server
        return when {
            "desustream.com/dstream" in url || "desustream.net/dstream" in url -> getDesuStreamVideos(name, url)
            "vidhide" in url || "odvidhide" in url -> vidHideExtractor.videosFromUrl(url)
            "mp4upload" in url -> {
                client.newCall(GET(url, headers)).awaitSuccess().let {
                    val doc = it.useAsJsoup()
                    val script = doc.selectFirst("script:containsData(player.src)")!!.data()
                    val videoUrl = script.substringAfter("src: \"").substringBefore('"')
                    listOf(Video(videoUrl = videoUrl, videoTitle = "$name - Mp4upload", headers = headers))
                }
            }
            "link.desustream.com" in url -> {
                // Redirect hub: follow it, then dispatch on the final host.
                val finalUrl = client.newCall(GET(url, headers)).awaitSuccess().use { it.request.url.toString() }
                if (finalUrl == url) return emptyList()
                getVideosFromServer(Triple(name, "url", finalUrl))
            }
            else -> emptyList()
        }
    }

    // DesuStream pages serve a plain <video><source src="...googlevideo...">
    // (verified live); the JWPlayer sources:[{file:'...'}] form is kept as
    // a fallback from the OtakuDesu branch.
    private suspend fun getDesuStreamVideos(name: String, url: String): List<Video> {
        val body = client.newCall(GET(url, headers)).awaitSuccess().body.string()
        val direct = SOURCE_TAG_REGEX.find(body)?.groupValues?.get(1)
            ?: JWPLAYER_REGEX.find(body)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(Video(videoUrl = direct, videoTitle = "$name - DesuStream", headers = headers))
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    // No genre browsing on the site (/anime/genre/<slug> is 404).
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
    )

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }

        screen.addPreference(videoQualityPref)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.videoTitle.contains(quality) },
        )
    }

    private fun String.stringField(key: String): String? = Regex("\"$key\":\"([^\"]*)\"").find(this)?.groupValues?.get(1)

    private fun String.unescapeJson(): String = replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

    companion object {
        private val EPISODE_NUMBER_REGEX = "-episode-(\\d+)-".toRegex()
        private val SERVER_REGEX = "\"name\":\"(\\d+p[^\"]*)\",\"(iframe|url)\":\"([^\"]+)\"".toRegex()
        private val SOURCE_TAG_REGEX = "<source[^>]+src=\"([^\"]+)\"".toRegex()
        private val JWPLAYER_REGEX = "sources:\\[\\{file:'([^']+)'".toRegex()

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
