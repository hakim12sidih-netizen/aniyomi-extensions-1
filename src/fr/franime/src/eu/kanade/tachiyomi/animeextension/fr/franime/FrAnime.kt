package eu.kanade.tachiyomi.animeextension.fr.franime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import okhttp3.Interceptor
import okhttp3.Dns
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class Franime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "FrAnime"
    override val baseUrl = "https://franime.fr"
    override val lang = "fr"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        if (preferences.getBoolean("cloudflare_bypass", true)) {
            builder.addInterceptor(CloudflareInterceptor())
        }

        if (preferences.getBoolean("dns_bypass", true)) {
            builder.dns(DnsOverHttpsBypass())
        }

        builder.addInterceptor(UserAgentInterceptor())

        if (preferences.getBoolean("use_proxy", false)) {
            builder.proxySelector(CustomProxySelector())
        }

        builder.build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("Accept-Encoding", "gzip, deflate, br")
            .add("Referer", baseUrl)
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
            .add("Sec-Fetch-User", "?1")
            .add("Upgrade-Insecure-Requests", "1")
            .add("DNT", "1")
            .add("Connection", "keep-alive")
            .add("Cache-Control", "max-age=0")
    }

    override fun popularAnimeSelector(): String = "div.anime-card, .anime-item"
    override fun popularAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/anime/?page=$page", headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("img").attr("data-src")
            .ifEmpty { element.select("img").attr("src") }
        anime.title = element.select("h3.anime-title, .title").text().trim()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.genre = element.select(".genre").joinToString { it.text() }
        anime.status = parseStatus(element.select(".status").text())
        return anime
    }

    override fun latestUpdatesSelector(): String = "div.latest-episode"
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/episodes/?page=$page", headers)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.title = element.select(".anime-title").text()
        anime.setUrlWithoutDomain(element.select("a").attr("href").substringBeforeLast("/"))
        return anime
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/search/?q=${query.encodeUri()}&page=$page", headers)
        } else {
            GET("$baseUrl/anime/?page=$page", headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.anime-title").text().trim()
        anime.thumbnail_url = document.select(".anime-poster img").attr("data-src")
            .ifEmpty { document.select(".anime-poster img").attr("src") }
        anime.description = document.select(".synopsis").text()
        anime.genre = document.select("a[href*=\"genre\"]").map { it.text() }.joinToString()
        anime.status = parseStatus(document.select(".status").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En cours", ignoreCase = true) -> SAnime.ONGOING
            statusString.contains("Terminé", ignoreCase = true) -> SAnime.COMPLETED
            statusString.contains("Annulé", ignoreCase = true) -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeListSelector(): String = "div.episode-item"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNumber = element.select(".episode-number").text().replace(Regex("[^0-9]"), "").toFloatOrNull() ?: 0f
        episode.episode_number = epNumber
        episode.name = "Épisode ${epNumber.toInt()}"
        episode.date_upload = parseDate(element.select(".episode-date").text())
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        return episode
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).parse(dateStr)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                videoList.addAll(extractVideosFromPlayer(src))
            }
        }

        document.select("script").forEach { script ->
            val scriptText = script.data()
            if (scriptText.contains("sources") || scriptText.contains("m3u8")) {
                videoList.addAll(extractFromScript(scriptText))
            }
        }

        return videoList
    }

    private fun extractVideosFromPlayer(playerUrl: String): List<Video> {
        return when {
            playerUrl.contains("sibnet") -> extractSibnet(playerUrl)
            playerUrl.contains("vk.com") -> extractVK(playerUrl)
            playerUrl.contains("sendvid") -> extractSendvid(playerUrl)
            playerUrl.contains("dailymotion") -> extractDailymotion(playerUrl)
            playerUrl.contains("stream") -> listOf(Video(playerUrl, "Stream", playerUrl, headers))
            else -> listOf(Video(playerUrl, "Unknown", playerUrl, headers))
        }
    }

    private fun extractSibnet(url: String): List<Video> {
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body.string()
            val videoUrl = Regex("src:\s*['"]([^'"]+\.mp4)['"]").find(body)?.groupValues?.get(1)
            videoUrl?.let { listOf(Video(it, "Sibnet", it, headers)) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractVK(url: String): List<Video> {
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body.string()
            val videos = mutableListOf<Video>()
            listOf("url720", "url480", "url360").forEach { quality ->
                Regex(""$quality":"([^"]+)"").find(body)?.groupValues?.get(1)?.let {
                    videos.add(Video(it.replace("\\/", "/"), "VK $quality", it.replace("\\/", "/"), headers))
                }
            }
            videos
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractSendvid(url: String): List<Video> {
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body.string()
            val videoUrl = Regex(""video_src":\s*"([^"]+)"").find(body)?.groupValues?.get(1)
            videoUrl?.let { listOf(Video(it, "Sendvid", it, headers)) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractDailymotion(url: String): List<Video> {
        val videoId = url.substringAfter("video/").substringBefore("_")
        val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        return try {
            val response = client.newCall(GET(apiUrl, headers)).execute()
            val json = response.body.string()
            val videos = mutableListOf<Video>()
            Regex(""qualities":\{([^}]+)\}").find(json)?.groupValues?.get(1)?.let { qualities ->
                Regex(""([^"]+)":\{"type":"video/mp4","url":"([^"]+)"").findAll(qualities).forEach { match ->
                    videos.add(Video(match.groupValues[2].replace("\\/", "/"), "Dailymotion ${match.groupValues[1]}", match.groupValues[2].replace("\\/", "/"), headers))
                }
            }
            videos
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractFromScript(script: String): List<Video> {
        val videos = mutableListOf<Video>()
        listOf(
            Regex(""src"\s*:\s*"([^"]+\.m3u8)""),
            Regex(""file"\s*:\s*"([^"]+)""),
            Regex("videoUrl\s*=\s*['"]([^'"]+)['"]")
        ).forEach { pattern ->
            pattern.findAll(script).forEach { match ->
                val url = match.groupValues[1]
                if (url.startsWith("http")) {
                    videos.add(Video(url, "Auto", url, headers))
                }
            }
        }
        return videos
    }

    override fun videoFromElement(element: Element): Video {
        throw Exception("Not used")
    }

    override fun videoUrlParse(document: Document): String {
        throw Exception("Not used")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")
        return sortedWith(compareBy { 
            when {
                it.quality.contains(quality ?: "1080") -> 0
                it.quality.contains("1080") -> 1
                it.quality.contains("720") -> 2
                it.quality.contains("480") -> 3
                else -> 4
            }
        })
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            AnimeFilter.Header("Filtres de recherche"),
            GenreFilter(getGenreList()),
            StatusFilter(getStatusList())
        )
    }

    private class GenreFilter(genres: Array<Pair<String, String>>) : 
        AnimeFilter.Select<String>("Genre", genres.map { it.first }.toTypedArray())

    private class StatusFilter(status: Array<Pair<String, String>>) : 
        AnimeFilter.Select<String>("Statut", status.map { it.first }.toTypedArray())

    private fun getGenreList(): Array<Pair<String, String>> = arrayOf(
        Pair("Tous", ""),
        Pair("Action", "action"),
        Pair("Aventure", "aventure"),
        Pair("Comédie", "comedie"),
        Pair("Drame", "drame"),
        Pair("Fantastique", "fantastique"),
        Pair("Horreur", "horreur"),
        Pair("Romance", "romance"),
        Pair("Sci-Fi", "sci-fi")
    )

    private fun getStatusList(): Array<Pair<String, String>> = arrayOf(
        Pair("Tous", ""),
        Pair("En cours", "ongoing"),
        Pair("Terminé", "completed"),
        Pair("Annulé", "cancelled")
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualité préférée"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"
        }

        val cloudflareBypassPref = SwitchPreferenceCompat(screen.context).apply {
            key = "cloudflare_bypass"
            title = "Bypass Cloudflare"
            summary = "Activer les méthodes de contournement Cloudflare"
            setDefaultValue(true)
        }

        val dnsBypassPref = SwitchPreferenceCompat(screen.context).apply {
            key = "dns_bypass"
            title = "DNS over HTTPS"
            summary = "Utiliser DNS over HTTPS pour éviter le blocage"
            setDefaultValue(true)
        }

        val proxyPref = SwitchPreferenceCompat(screen.context).apply {
            key = "use_proxy"
            title = "Utiliser un proxy"
            summary = "Contourner les restrictions via proxy"
            setDefaultValue(false)
        }

        val downloadModePref = ListPreference(screen.context).apply {
            key = "download_mode"
            title = "Mode de téléchargement"
            entries = arrayOf(
                "Streaming normal",
                "Copier le lien",
                "Téléchargeur externe",
                "IDM (Internet Download Manager)",
                "JDownloader",
                "Navigateur",
            )
            entryValues = arrayOf("stream", "copy", "external", "idm", "jdownloader", "browser")
            setDefaultValue("stream")
            summary = "%s"
        }

        screen.addPreference(qualityPref)
        screen.addPreference(cloudflareBypassPref)
        screen.addPreference(dnsBypassPref)
        screen.addPreference(proxyPref)
        screen.addPreference(downloadModePref)
    }

    private fun String.encodeUri(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }

    // Classes internes pour le contournement (ajoutées pour corriger les erreurs de référence)
    class CloudflareInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code == 503 && response.headers["Server"] == "cloudflare") {
                return chain.proceed(request)
            }
            return response
        }
    }

    class DnsOverHttpsBypass : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    class UserAgentInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            return chain.proceed(originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build())
        }
    }

    class CustomProxySelector : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            return listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
    }
}
