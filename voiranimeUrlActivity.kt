package eu.kanade.tachiyomi.animeextension.fr.voiranime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts voiranime.com/anime/<item> intents
 * and redirects them to the main Aniyomi process.
 */
class VoirAnimeUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1 && pathSegments[1] != null) {
            val item = pathSegments[1]
            runCatching {
                val mainIntent = Intent().apply {
                    action = "eu.kanade.tachiyomi.ANIMESEARCH"
                    putExtra("query", "${VoirAnime.PREFIX_SEARCH}$item")
                    putExtra("filter", packageName)
                }

                startActivity(mainIntent)
            }.onFailure { e ->
                Log.e(tag, "Error starting activity", e)
            }
        } else {
            Log.e(tag, "could not parse uri from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
