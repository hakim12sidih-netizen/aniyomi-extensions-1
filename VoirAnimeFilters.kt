package eu.kanade.tachiyomi.animeextension.fr.voiranime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object VoirAnimeFilters {

    open class CheckBoxFilterList(name: String, values: List<AnimeFilter.CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }
    }

    class TypesFilter : CheckBoxFilterList("Type", VoirAnimeFiltersData.TYPES.map { CheckBoxVal(it.first, false) })
    class LangFilter : CheckBoxFilterList("Langue", VoirAnimeFiltersData.LANGUAGES.map { CheckBoxVal(it.first, false) })
    class GenresFilter : CheckBoxFilterList("Genre", VoirAnimeFiltersData.GENRES.map { CheckBoxVal(it.first, false) })

    val FILTER_LIST get() = AnimeFilterList(
        TypesFilter(),
        LangFilter(),
        GenresFilter(),
    )

    data class SearchFilters(
        val types: List<String> = emptyList(),
        val languages: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
    )

    fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
        if (filters.isEmpty()) return SearchFilters()
        return SearchFilters(
            filters.parseCheckbox<TypesFilter>(VoirAnimeFiltersData.TYPES),
            filters.parseCheckbox<LangFilter>(VoirAnimeFiltersData.LANGUAGES),
            filters.parseCheckbox<GenresFilter>(VoirAnimeFiltersData.GENRES),
        )
    }

    private object VoirAnimeFiltersData {
        val TYPES = arrayOf(
            Pair("Anime", "anime"),
            Pair("Film", "film"),
            Pair("OAV", "oav"),
            Pair("ONA", "ona"),
        )

        val LANGUAGES = arrayOf(
            Pair("VF", "vf"),
            Pair("VOSTFR", "vostfr"),
        )

        val GENRES = arrayOf(
            Pair("Action", "action"),
            Pair("Aventure", "aventure"),
            Pair("Comédie", "comédie"),
            Pair("Drame", "drame"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantastique", "fantastique"),
            Pair("Fantasy", "fantasy"),
            Pair("Horreur", "horreur"),
            Pair("Josei", "josei"),
            Pair("Magie", "magie"),
            Pair("Mecha", "mecha"),
            Pair("Mystère", "mystere"),
            Pair("Policier", "policier"),
            Pair("Psychologique", "psychologique"),
            Pair("Romance", "romance"),
            Pair("School Life", "school-life"),
            Pair("Science-fiction", "science-fiction"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Surnaturel", "surnaturel"),
            Pair("Thriller", "thriller"),
            Pair("Tranche de vie", "tranche-de-vie"),
            Pair("Vampire", "vampire"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    }
}
