package eu.kanade.tachiyomi.animeextension.fr.franime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime as ApiAnime
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Episode as ApiEpisode

class FRAnime : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "FRAnime"
    override val baseUrl = "https://franime.fr"
    override val lang = "fr"
    override val supportsLatest = true

    private val apiBaseUrl = "https://api.franime.fr/api"
    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================================================
    // FlareSolverr support
    // ============================================================

    private val flareSolverrUrl: String
        get() = preferences.getString(FLARESOLVERR_URL_KEY, FLARESOLVERR_URL_DEFAULT)!!

    private fun flareSolverrGet(url: String): String? {
        val payload = """
            {
                "cmd": "request.get",
                "url": "$url",
                "maxTimeout": 60000
            }
        """.trimIndent()

        val body = payload.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$flareSolverrUrl/v1")
            .post(body)
            .header("Content-Type", "application/json")
            .build()

        return runCatching {
            client.newCall(req).execute().use { response ->
                val responseBody = response.body.string()
                val fsResponse = json.decodeFromString<FlareSolverrResponse>(responseBody)
                fsResponse.solution.response
            }
        }.getOrNull()
    }

    // ============================================================
    // Popular anime
    // ============================================================

    override fun popularAnimeRequest(page: Int): Request = GET("$apiBaseUrl/animes", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val list = parseAnimeList(response)
            .sortedByDescending { it.note }
        return toPagedResult(list, response.request.url.queryParameter("page")?.toIntOrNull() ?: 1)
    }

    // ============================================================
    // Latest anime
    // ============================================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiBaseUrl/animes", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val list = parseAnimeList(response)
            .sortedByDescending { maxOf(it.updateTime ?: 0L, it.updateTimeVf ?: 0L) }
        return toPagedResult(list, response.request.url.queryParameter("page")?.toIntOrNull() ?: 1)
    }

    // ============================================================
    // Search
    // ============================================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$apiBaseUrl/animes", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val all = parseAnimeList(response)
        val query = response.request.url.queryParameter("query") ?: ""

        val filters = getFilterList()
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val formatFilter = filters.filterIsInstance<FormatFilter>().firstOrNull()

        val filtered = all.filter { anime ->
            val matchesQuery = if (query.isNotBlank()) {
                val q = query.lowercase(Locale.ROOT)
                listOfNotNull(
                    anime.title,
                    anime.originalTitle,
                    anime.titlesAlt.en,
                    anime.titlesAlt.enJp,
                    anime.titlesAlt.jaJp,
                ).any { it.lowercase(Locale.ROOT).contains(q) }
            } else {
                true
            }

            val matchesGenre = genreFilter?.selectedValue()?.let { genre ->
                if (genre.isBlank()) true else anime.genres.any { it.equals(genre, ignoreCase = true) }
            } ?: true

            val matchesStatus = statusFilter?.selectedValue()?.let { status ->
                if (status.isBlank()) true else anime.status.equals(status, ignoreCase = true)
            } ?: true

            val matchesFormat = formatFilter?.selectedValue()?.let { format ->
                if (format.isBlank()) true else anime.format.equals(format, ignoreCase = true)
            } ?: true

            matchesQuery && matchesGenre && matchesStatus && matchesFormat
        }

        return toPagedResult(filtered, response.request.url.queryParameter("page")?.toIntOrNull() ?: 1)
    }

    // ============================================================
    // Anime details
    // ============================================================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val animeId = animeIdFromUrl(anime.url)
        return GET("$apiBaseUrl/anime-by-id/$animeId", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = json.decodeFromString(ApiAnime.serializer(), response.body.string())
        return anime.toSAnime()
    }

    // ============================================================
    // Episode list
    // ============================================================

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = animeIdFromUrl(anime.url)
        return GET("$apiBaseUrl/anime-by-id/$animeId", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val anime = json.decodeFromString(ApiAnime.serializer(), response.body.string())
        val animeId = anime.id.toString()

        val episodes = mutableListOf<SEpisode>()
        anime.seasons.forEachIndexed { seasonIndex, season ->
            val seasonNumber = seasonIndex + 1
            season.episodes.forEachIndexed { epIndex, ep ->
                val epNumStr = extractEpisodeNumberString(ep, epIndex + 1)
                val epNumFloat = epNumStr.toFloatOrNull() ?: (epIndex + 1).toFloat()

                val voPlayers = ep.languages.vo?.players.orEmpty()
                if (voPlayers.isNotEmpty()) {
                    episodes.add(
                        buildEpisode(animeId, seasonNumber, epNumStr, epNumFloat, "vo", ep.title),
                    )
                }

                val vfPlayers = ep.languages.vf?.players.orEmpty()
                if (vfPlayers.isNotEmpty()) {
                    episodes.add(
                        buildEpisode(animeId, seasonNumber, epNumStr, epNumFloat, "vf", ep.title),
                    )
                }
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    private fun buildEpisode(
        animeId: String,
        seasonNumber: Int,
        epNumStr: String,
        epNumFloat: Float,
        lang: String,
        title: String?,
    ): SEpisode {
        val langLabel = if (lang == "vo") "VOSTFR" else "VF"
        val episodeName = title?.ifBlank { null } ?: "Épisode $epNumStr"
        return SEpisode.create().apply {
            name = "S$seasonNumber - $episodeName ($langLabel)"
            episode_number = epNumFloat
            url = "$baseUrl/watch?animeId=$animeId&season=$seasonNumber&ep=$epNumStr&lang=$lang"
        }
    }

    // ============================================================
    // Video list
    // ============================================================

    override fun videoListRequest(episode: SEpisode): Request {
        val ref = episodeRefFromUrl(episode.url)
        return Request.Builder()
            .url("$apiBaseUrl/anime-by-id/${ref.animeId}")
            .headers(headers)
            .tag(EpisodeRef::class.java, ref)
            .build()
    }

    override fun videoListParse(response: Response): List<Video> {
        val ref = response.request.tag(EpisodeRef::class.java) ?: return emptyList()
        val anime = json.decodeFromString(ApiAnime.serializer(), response.body.string())

        val season = anime.seasons.getOrNull(ref.seasonNumber - 1) ?: return emptyList()
        val episode = season.episodes.firstOrNull { extractEpisodeNumberString(it, 0) == ref.episodeNumber }
            ?: return emptyList()

        val players = when (ref.lang) {
            "vo" -> episode.languages.vo?.players.orEmpty()
            "vf" -> episode.languages.vf?.players.orEmpty()
            else -> emptyList()
        }

        val allowedHosters = preferences.getStringSet(
            HOSTER_SELECTION_KEY,
            setOf("sibnet", "sendvid", "vidmoly", "filemoon"),
        ) ?: emptySet()

        val videos = mutableListOf<Video>()
        players.map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() && it !in IGNORE_PLAYERS }
            .filter { allowedHosters.contains(it) }
            .forEach { lecteur ->
                val link = resolveLecteurLink(
                    animeId = ref.animeId,
                    season = ref.seasonNumber,
                    episode = ref.episodeNumber,
                    lecteur = lecteur,
                    lang = ref.lang,
                ) ?: return@forEach

                when {
                    "sibnet" in link -> {
                        videos.addAll(SibnetExtractor(client).videosFromUrl(link, prefix = "FRAnime: "))
                    }
                    "sendvid" in link -> {
                        videos.addAll(SendvidExtractor(client, headers).videosFromUrl(link, prefix = "FRAnime: "))
                    }
                    "vidmoly" in link -> {
                        videos.addAll(VidMolyExtractor(client).videosFromUrl(link, prefix = "FRAnime: "))
                    }
                    "filemoon" in link || "filemoon.sx" in link || "filemoon.to" in link -> {
                        videos.addAll(FilemoonExtractor(client).videosFromUrl(link, prefix = "FRAnime: "))
                    }
                    else -> {
                        videos.add(Video(link, "FRAnime: $lecteur", link))
                    }
                }
            }

        return videos
    }

    private fun resolveLecteurLink(
        animeId: String,
        season: Int,
        episode: String,
        lecteur: String,
        lang: String,
    ): String? {
        val url = "$apiBaseUrl/anime/$animeId/$season/$episode/$lecteur/$lang"

        val body = flareSolverrGet(url) ?: runCatching {
            client.newCall(GET(url, headers)).execute().use { it.body.string() }
        }.getOrNull() ?: return null

        val direct = extractUrlFromResponse(body)
        return if (direct != null && !direct.contains("franime.fr", ignoreCase = true)) direct else null
    }

    private fun extractUrlFromResponse(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("http", ignoreCase = true) && !trimmed.contains("<")) {
            return trimmed
        }

        val jsonUrl = runCatching {
            val element = json.parseToJsonElement(trimmed)
            findFirstUrl(element)
        }.getOrNull()
        if (!jsonUrl.isNullOrBlank()) return jsonUrl

        val doc = Jsoup.parse(trimmed)
        val candidates = listOf(
            doc.selectFirst("iframe[src]")?.attr("abs:src"),
            doc.selectFirst("a[href]")?.attr("abs:href"),
            doc.selectFirst("source[src]")?.attr("abs:src"),
            doc.selectFirst("video[src]")?.attr("abs:src"),
        ).filterNotNull().filter { it.startsWith("http") }
        if (candidates.isNotEmpty()) return candidates.first()

        val regexUrl = Regex("https?://[^\"'\\s<]+")
        return regexUrl.find(trimmed)?.value
    }

    private fun findFirstUrl(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> {
                val content = element.content
                if (content.startsWith("http")) content else null
            }
            is JsonObject -> {
                element.values.asSequence().mapNotNull { findFirstUrl(it) }.firstOrNull()
            }
            else -> null
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun parseAnimeList(response: Response): List<ApiAnime> =
        json.decodeFromString(ListSerializer(ApiAnime.serializer()), response.body.string())

    private fun toPagedResult(list: List<ApiAnime>, page: Int): AnimesPage {
        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= list.size) {
            return AnimesPage(emptyList(), false)
        }
        val toIndex = minOf(fromIndex + PAGE_SIZE, list.size)
        val pageItems = list.subList(fromIndex, toIndex).map { it.toSAnime() }
        val hasNext = toIndex < list.size
        return AnimesPage(pageItems, hasNext)
    }

    private fun ApiAnime.toSAnime(): SAnime = SAnime.create().apply {
        title = titlesAlt.en ?: title ?: originalTitle
        thumbnail_url = posterSmall ?: poster
        url = "/anime/${id}"
        description = this@toSAnime.description
        genre = genres.joinToString(", ")
        status = when (status.lowercase(Locale.ROOT)) {
            "en cours" -> SAnime.ONGOING
            "terminé", "termine" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        author = format
    }

    private fun animeIdFromUrl(url: String): String =
        url.substringAfterLast("/").substringBefore("?")

    private fun extractEpisodeNumberString(ep: ApiEpisode, fallback: Int): String {
        val title = ep.title ?: return fallback.toString()
        val match = Regex("""\d+(\.\d+)?""").find(title)
        return match?.value ?: fallback.toString()
    }

    private fun episodeRefFromUrl(url: String): EpisodeRef {
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null) {
            val animeId = httpUrl.queryParameter("animeId") ?: ""
            val season = httpUrl.queryParameter("season")?.toIntOrNull() ?: 1
            val episode = httpUrl.queryParameter("ep") ?: "1"
            val lang = httpUrl.queryParameter("lang") ?: "vo"
            return EpisodeRef(animeId, season, episode, lang)
        }
        return EpisodeRef("", 1, "1", "vo")
    }

    // ============================================================
    // Filters
    // ============================================================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Filtres (ignorés si recherche texte)"),
        GenreFilter(),
        StatusFilter(),
        FormatFilter(),
    )

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        arrayOf(
            "Tous",
            "Action", "Aventure", "Comédie", "Drame", "Fantaisie",
            "Horreur", "Mystère", "Romance", "Science-fiction",
            "Slice of Life", "Sport", "Surnaturel", "Thriller",
        ),
    ) {
        private val values = arrayOf(
            "",
            "Action", "Aventure", "Comédie", "Drame", "Fantaisie",
            "Horreur", "Mystère", "Romance", "Science-fiction",
            "Slice of Life", "Sport", "Surnaturel", "Thriller",
        )
        fun selectedValue() = values[state]
    }

    private class StatusFilter : AnimeFilter.Select<String>(
        "Statut",
        arrayOf("Tous", "EN COURS", "TERMINÉ"),
    ) {
        private val values = arrayOf("", "EN COURS", "TERMINÉ")
        fun selectedValue() = values[state]
    }

    private class FormatFilter : AnimeFilter.Select<String>(
        "Format",
        arrayOf("Tous", "TV", "Film", "ONA", "OVA", "Special"),
    ) {
        private val values = arrayOf("", "TV", "Film", "ONA", "OVA", "Special")
        fun selectedValue() = values[state]
    }

    // ============================================================
    // Settings
    // ============================================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = FLARESOLVERR_URL_KEY
            title = "URL de FlareSolverr"
            summary = "Adresse de votre instance FlareSolverr (ex: http://10.0.2.2:8191)"
            entries = arrayOf(
                "http://10.0.2.2:8191",
                "http://127.0.0.1:8191",
                "http://localhost:8191",
            )
            entryValues = entries
            setDefaultValue(FLARESOLVERR_URL_DEFAULT)
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = HOSTER_SELECTION_KEY
            title = "Hosters à utiliser"
            entries = arrayOf("Sibnet", "Sendvid", "Vidmoly", "Filemoon")
            entryValues = arrayOf("sibnet", "sendvid", "vidmoly", "filemoon")
            setDefaultValue(setOf("sibnet", "sendvid", "vidmoly", "filemoon"))
        }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val FLARESOLVERR_URL_KEY = "flaresolverr_url"
        private const val FLARESOLVERR_URL_DEFAULT = "http://10.0.2.2:8191"
        private const val HOSTER_SELECTION_KEY = "hoster_selection"
        private val IGNORE_PLAYERS = setOf("telechargement unique", "telechargement", "download")
    }
}

private data class EpisodeRef(
    val animeId: String,
    val seasonNumber: Int,
    val episodeNumber: String,
    val lang: String,
)

// ============================================================
// FlareSolverr response models
// ============================================================

@Serializable
data class FlareSolverrResponse(
    val status: String,
    val solution: FlareSolverrSolution,
)

@Serializable
data class FlareSolverrSolution(
    val url: String,
    val response: String,
    val cookies: List<FlareSolverrCookie> = emptyList(),
    val userAgent: String = "",
)

@Serializable
data class FlareSolverrCookie(
    val name: String,
    val value: String,
    val domain: String = "",
    val path: String = "/",
    val httpOnly: Boolean = false,
    val secure: Boolean = false,
)
