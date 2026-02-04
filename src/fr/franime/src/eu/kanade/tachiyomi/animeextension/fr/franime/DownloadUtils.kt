package eu.kanade.tachiyomi.animeextension.fr.franime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object DownloadUtils {

    // Copier le lien dans le presse-papiers
    fun copyLinkToClipboard(context: Context, url: String, filename: String? = null) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val displayText = filename?.let { "$it\n$url" } ?: url
        val clip = ClipData.newPlainText("Download Link", displayText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Lien copié !", Toast.LENGTH_SHORT).show()
    }

    // Ouvrir avec un téléchargeur externe
    fun openWithDownloader(context: Context, url: String, filename: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, filename ?: "Download")
        }
        context.startActivity(Intent.createChooser(intent, "Télécharger avec..."))
    }

    // Intent spécifique pour IDM
    fun openWithIDM(context: Context, url: String, filename: String? = null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("idm:$url")
                putExtra("extra_filename", filename)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // IDM non installé, fallback sur navigateur
            openInBrowser(context, url)
        }
    }

    // Intent pour JDownloader
    fun sendToJDownloader(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("http://127.0.0.1:9666/flash/add?source=aniyomi&url=${Uri.encode(url)}")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "JDownloader non détecté", Toast.LENGTH_SHORT).show()
        }
    }

    // Ouvrir dans le navigateur pour téléchargement manuel
    fun openInBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

    // Générer une liste de téléchargement M3U
    fun generateM3UPlaylist(videos: List<Pair<String, String>>): String {
        val sb = StringBuilder("#EXTM3U\n")
        videos.forEach { (title, url) ->
            sb.append("#EXTINF:-1,$title\n")
            sb.append("$url\n")
        }
        return sb.toString()
    }

    // Générer un fichier .txt avec tous les liens
    fun generateLinkList(episodes: List<Pair<String, String>>): String {
        return episodes.joinToString("\n") { (title, url) ->
            "$title: $url"
        }
    }
}