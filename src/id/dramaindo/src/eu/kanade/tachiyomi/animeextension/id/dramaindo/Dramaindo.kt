package eu.kanade.tachiyomi.animeextension.id.dramaindo

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Dramaindo — drakor / dracin / dorama sub Indo.
 *
 * DRAFT (2026-11-27, research stage). NOT uploaded, NOT built, NOT tested.
 * Written from live browser inspections of web.dramaindo.app on 2026-11-27
 * (16:20–16:28 WIB); see hidden_files/dramaindo-extension-draft.md for the
 * full verification log.
 *
 * VERIFIED (live, read-only browser checks):
 * - Site live at https://web.dramaindo.app (gateway https://dramaindo.app).
 *   Catalog /jelajahi: 5,010 titles, 209 pages, ongoing first.
 * - Cards: <a href="/drama/<slug>-<year>"> wrapping <img alt="Poster <title>">
 *   (src /media/covers/...) + "EP N" badge text. Title is a link, not a heading.
 * - Detail /drama/<slug>-<year>: <h1> "<title> (YYYY) Sub Indo", genre line
 *   "Fantasy / Historical", "China · 22 episode tersedia · Subtitle Indonesia",
 *   <h2> "Sinopsis" + synopsis paragraph, label/value pairs Negara / Tahun
 *   rilis / Format / Episode tersedia / Status tayang ("Sedang tayang").
 * - Episodes are <button data-episode="N">s (text is just the number,
 *   "Episode N" lives in aria-label). Clicking only adds a hash
 *   (#episode_1); the player is an inline native <video> on the same
 *   detail page. Quality is 720p only, no server choice.
 * - Download links in the detail DOM: /api/v1/videos/<videoId>/download
 *   ?episode=<episodeId> (Ep1: 73805/73261 "720p 471 MB", Ep2: 73806/73262
 *   "720p 481 MB"; both ids increment by 1 per episode).
 * - Search: /jelajahi?q=<query> (spaces become +) — verified with a live query.
 * - The site's own player works without login (free 720p).
 * - RAW HTML (verified 2026-11-27 ~16:40 WIB with plain curl, no JS): the
 *   site is server-rendered — homepage has 41 <a href="/drama/…"> anchors,
 *   /jelajahi has 48, the detail page has a real <h1>, 23 "Episode N"
 *   matches and real download links. Jsoup selectors below WILL match.
 * - PAGINATION (verified same time): /jelajahi has real links
 *   /jelajahi?page=2, ?page=3, ?page=4, ?page=5 — ?page=N is the real
 *   parameter, and a[href*="page="] is the real next-page selector.
 *
 * UNVERIFIED — first on-device test must confirm or correct these:
 * 1. PLAYBACK: videoListParse returns the site's own download URL as the
 *    Video URL. Whether ExoPlayer plays it as a stream (vs a forced file
 *    download, or a missing Referer/token) is UNPROVEN. If it fails, the
 *    next step is a desktop DevTools Network capture of the real stream
 *    request — not available in the browser used for this research.
 * 2. TAG NAMES below are inferred from accessibility roles (link → <a>,
 *    heading level 1 → <h1>, button → <button>), NOT read from source —
 *    classes could not be read at all, so NO selector below uses a class.
 *    Selectors use only verified href/src/text patterns.
 * 3. Episode/download pairing is by DOM order (both lists in episode
 *    order). If an episode has no download link yet, counts diverge and
 *    the tail episodes are skipped (minOf) — check the last episodes.
 */
@Source
abstract class Dramaindo :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // Base URL is a preference (Floppa's requirement): the site moves domain
    // often, so a move must only need a link change, not a rebuild.
    override val baseUrl: String
        get() = (preferences.getString(BASE_URL_KEY, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
            .trimEnd('/')

    // =========================== Anime Details ============================
    // h1: "<title> (YYYY) Sub Indo" — strip the year + "Sub Indo" suffix.
    // Genre is a bare text line ("Fantasy / Historical"); matched by regex
    // over the page text because it has no wrapping element to select.
    // Status/labels are label/value pairs (probably <dt>/<dd>); matched on
    // text, not on tag names.
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val rawTitle = document.selectFirst("h1")?.text().orEmpty()
        title = TITLE_SUFFIX_REGEX.find(rawTitle)?.groupValues?.get(1) ?: rawTitle
        thumbnail_url = document.selectFirst("img[src*=/media/covers/]")?.absUrl("src")
        genre = GENRE_REGEX.find(document.body().text())?.value.orEmpty()
        status = parseStatus(labelValue(document, "Status tayang"))

        description = buildString {
            document.selectFirst("h2:contains(Sinopsis)")?.nextElementSibling()?.text()
                ?.takeIf { it.isNotBlank() }?.let { append(it) }
            labelValue(document, "Negara")?.let { append("\n\nNegara: $it") }
            labelValue(document, "Tahun rilis")?.let { append("\nTahun: $it") }
            labelValue(document, "Format")?.let { append("\nFormat: $it") }
            labelValue(document, "Episode tersedia")?.let { append("\nEpisode tersedia: $it") }
        }
    }

    private fun parseStatus(statusString: String?): Int {
        val s = statusString?.lowercase() ?: return SAnime.UNKNOWN
        return when {
            "tayang" in s || "ongoing" in s -> SAnime.ONGOING
            "tamat" in s || "selesai" in s || "completed" in s -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // Finds a label/value pair by the label's own text ("Negara" → "China")
    // and returns the next sibling element's text. Tag names unverified.
    private fun labelValue(document: Document, label: String): String? {
        val labelEl = document.select("*")
            .firstOrNull { it.ownText().trim() == label } ?: return null
        return labelEl.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ============================== Episodes ==============================
    // Episode <button>s have no href, so the default selector-based episode
    // parsing cannot work. Buttons carry only the number as text ("1", "2",
    // …) — the episode number lives in data-episode (aria-label "Episode N"
    // agrees). Pair each button with the download <a> at the same DOM
    // position (both lists are in episode order) and stash the video/episode
    // ids in the episode URL's query — fragments are stripped by OkHttp,
    // but query params survive to videoListParse.
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val detailPath = response.request.url.encodedPath
        val buttons = document.select("button[data-episode]")
        val links = document.select("a[href*=\"/download?episode=\"]")
        val count = minOf(buttons.size, links.size)
        return (0 until count).mapNotNull { i ->
            val number = buttons[i].attr("data-episode").toIntOrNull()
                ?: return@mapNotNull null
            val ids = DOWNLOAD_HREF_REGEX.find(links[i].attr("href"))
                ?: return@mapNotNull null
            val (videoId, episodeId) = ids.destructured
            SEpisode.create().apply {
                name = "Episode $number"
                episode_number = number.toFloat()
                date_upload = 0L
                setUrlWithoutDomain("$detailPath?ep=$number&vid=$videoId&eid=$episodeId")
            }
        }
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    // /jelajahi lists ongoing first (5,010 titles, 209 pages). Pagination
    // parameter (?page=N) is UNVERIFIED — see the header comment.
    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img[alt^=\"Poster \"]")
        title = img?.attr("alt")?.removePrefix("Poster ").orEmpty()
        thumbnail_url = img?.absUrl("src")
    }

    override fun latestUpdatesNextPageSelector() = "a[href*=\"page=\"]"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/jelajahi?page=$page")

    override fun latestUpdatesSelector() = "a[href^=\"/drama/\"]:has(img[alt^=\"Poster \"])"

    // ============================== Popular ===============================
    // No separate popular list; /jelajahi (ongoing first) is the closest
    // equivalent and uses the same card markup.
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun popularAnimeRequest(page: Int) = latestUpdatesRequest(page)

    override fun popularAnimeSelector() = latestUpdatesSelector()

    // =============================== Search ===============================
    // /jelajahi?q=<query> — verified live (spaces become +).
    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return GET("$baseUrl/jelajahi?page=$page")
        val url = "$baseUrl/jelajahi".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url)
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()

    // ============================ Video Links =============================
    // V1: the site's own download URL is the video URL (720p only, no
    // server choice on the site). UNPROVEN whether ExoPlayer plays it as a
    // stream — see the header comment. Referer is the detail page.
    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url
        val videoId = url.queryParameter("vid") ?: return emptyList()
        val episodeId = url.queryParameter("eid") ?: return emptyList()
        val detailUrl = url.newBuilder().query(null).build().toString()
        val videoHeaders = headers.newBuilder().set("Referer", detailUrl).build()
        return listOf(
            Video(
                videoUrl = "$baseUrl/api/v1/videos/$videoId/download?episode=$episodeId",
                videoTitle = "720p",
                headers = videoHeaders,
            ),
        )
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    // No genre browsing on the site; search is by text.
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
    )

    // ============================== Settings ==============================
    // Only setting: the base URL, so a domain move is a link change.
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_KEY
            title = "Base URL"
            setDefaultValue(DEFAULT_BASE_URL)
            summary = "%s"
            dialogTitle = "Base URL"
        }.also { screen.addPreference(it) }
    }

    // ============================= Utilities ==============================
    // Only one quality exists (720p), so there is nothing to sort.
    override fun List<Video>.sortVideos(): List<Video> = this

    companion object {
        private const val DEFAULT_BASE_URL = "https://web.dramaindo.app"
        private const val BASE_URL_KEY = "base_url"

        private val TITLE_SUFFIX_REGEX = "^(.*?) \\(\\d{4}\\) Sub Indo$".toRegex()
        private val GENRE_REGEX = "[A-Za-z][A-Za-z ]+ / [A-Za-z][A-Za-z ]+".toRegex()
        private val DOWNLOAD_HREF_REGEX =
            "/api/v1/videos/(\\d+)/download\\?episode=(\\d+)".toRegex()
    }
}
