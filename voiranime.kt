package eu.kanade.tachiyomi.animeextension.fr.voiranime

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VoirAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "VoirAnime"
    override val baseUrl = "https://v6.voiranime.com"
    override val lang = "fr"
    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes-populaires/page/$page/", headers)
    override fun popularAnimeSelector() = "div.container-cards-item"
    override fun popularAnimeNextPageSelector() = "a.page-link[rel=next]"
 
    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val card = element.selectFirst("div.card-version > a")!!
        title = card?.selectFirst("div.card-title")?.text().orEmpty()
        setUrlWithoutDomain(card?.attr("href").orEmpty())
        thumbnail_url = card?.selectFirst("img")?.attr("src")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page/", headers)
    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun getFilterList() = VoirAnimeFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = VoirAnimeFilters.getSearchFilters(filters)
        val url = if (query.isNotBlank()) {
            "$baseUrl/recherche/$query/page/$page/".toHttpUrl()
        } else {
            "$baseUrl/catalogue/page/$page/".toHttpUrl().newBuilder().apply {
                addQueryParameter("type", params.types.joinToString(","))
                addQueryParameter("lang", params.languages.joinToString(","))
                addQueryParameter("genre", params.genres.joinToString(","))
            }.build()
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val infos = document.selectFirst("div.container-infos-film")
        title = infos?.selectFirst("h1.font-light")?.text().orEmpty()
        description = infos?.selectFirst("div.synopsis-resume")?.text()
        genre = infos?.select("div.container-infos-genre a")?.joinToString { it.text() }
        status = parseStatus(infos?.selectFirst("div.font-light:contains(Statut)")?.text())
    }

    private fun parseStatus(status: String?) = when (status) {
        "Statut: En cours" -> SAnime.ONGOING
        "Statut: Terminé" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.container-cards-episode a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val epNumStr = element.attr("href").substringAfterLast("/")
        val epNum = epNumStr.toFloatOrNull() ?: 0F
        episode_number = epNum
        name = "Épisode $epNum"
        setUrlWithoutDomain(element.attr("href"))
    }

    // ============================ Video Links =============================
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        document.select("div.container-lecteurs-video iframe").forEach { iframe ->
            val src = iframe.attr("abs:src")
            when {
                "sendvid.com" in src -> videos += sendvidExtractor.videosFromUrl(src)
                "sibnet.ru" in src -> videos += sibnetExtractor.videosFromUrl(src)
                "vk.com" in src -> videos += vkExtractor.videosFromUrl(src)
            }
        }
        return videos
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================ Utils =============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareByDescending { it.quality.contains(quality) },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualité préférée"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Sendvid", "Sibnet", "Vk")
            entryValues = arrayOf("1080", "720", "480", "360", "Sendvid", "Sibnet", "Vk")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}
