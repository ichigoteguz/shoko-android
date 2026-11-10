package dev.shoko.remote.data

/** Best-effort search text from release filenames; the user can always refine the result. */
object AnimeSearchTitle {
    private val ignoreCase = RegexOption.IGNORE_CASE
    private val extension = Regex("\\.(?:mkv|mp4|avi|webm|m4v|mpeg|mpg|ts|m2ts|wmv|ogm|mov|flv|vob)$", ignoreCase)
    // Android's ICU parser requires literal delimiters escaped more strictly than the JVM.
    private val leadingTag = Regex("""^\s*(?:\[([^\]\r\n]+)\]|\{([^\}\r\n]+)\}|\(([^\)\r\n]+)\))\s*""")
    private val year = Regex("(?:19|20)\\d{2}")
    private val timestamp = Regex("^\\d{4}[-_. :]+\\d{2}[-_. :]+\\d{2}(?:[T _.:-]+\\d{2}){2,}.*$")
    private val technical = Regex(
        "(?<![\\p{L}\\p{N}])(?:\\d{3,4}[pi](?:-hi\\w*)?|\\d{3,4}x\\d{3,4}|" +
            "(?:blu[ ._-]*ray|bd|dvd|web)(?:[ ._-]*(?:rip|dl))?|" +
            "hevc|av1|[hx][ ._]?26[45]|hi10p|(?:full[ ._-]*)?hd|uhd|" +
            "(?:dual|multi)[ ._-]*audio|(?:english|japanese|eng|jpn)[ ._-]*(?:sub(?:title)?s?|dub)?|" +
            "aac|flac|opus|dts|e?ac3|dolby(?:[ ._-]*(?:atmos|vision))?|dovi)" +
            "(?![\\p{L}\\p{N}])",
        ignoreCase,
    )
    private val seasonEpisode = Regex("(?i)(?:^|[ ._-]+)S(\\d{1,2})[ ._-]*E\\d+(?:\\.\\d+)?(?:v\\d+)?")
    private val markedEpisode = Regex("(?i)[ ._-]+(?:E(?:P(?:ISODE)?)?)[ ._-]*\\d+(?:[.-]\\d+)?(?:v\\d+)?(?![\\p{L}\\p{N}])")
    private val bracketEpisode = Regex("""(?i)[ ._-]*\[(?:E(?:P(?:ISODE)?)?[ ._-]*)?(\d+(?:[.-]\d+)?)(?:v\d+)?\]""")
    private val dashEpisode = Regex("(?i)[ ._]+-+[ ._]*(\\d+(?:[.-]\\d+)?)(?:v\\d+)?(?![\\p{L}\\p{N}])")
    private val bareEpisode = Regex("(?i)[ ._]+(\\d{2,3})(?:v\\d+)?(?:[ ._-]+end)?[ ._-]*$")
    private val seasonSuffix = Regex("(?i)[ ._-]+S(?:EASON[ ._-]*)?(\\d{1,2})$")
    private val romanSuffix = Regex("\\s+(IX|VIII|VII|VI|IV|V|III|II|I)$")
    private val trailingYear = Regex(" ([12]\\d{3})$")
    private val hash = Regex("(?i)[a-f0-9]{8,}")
    private val whitespace = Regex("\\s+")

    fun fromFileName(name: String): String? {
        var title = name.trim().substringAfterLast('/').substringAfterLast('\\')
            .replace(extension, "").trim()
        if (title.isEmpty() || timestamp.matches(title) || hash.matches(title)) return null

        // The first bracket is normally the release group. A bracketed title followed
        // directly by an episode separator is meaningful and stays in the query.
        leadingTag.find(title)?.let { match ->
            val contents = match.groupValues.drop(1).first { it.isNotEmpty() }
            val rest = title.substring(match.range.last + 1)
            if (rest.isNotBlank() && !contents.all(Char::isDigit) &&
                !Regex("^-[ ._]*\\d").containsMatchIn(rest)
            ) title = rest
        }
        while (true) {
            val tag = leadingTag.find(title) ?: break
            val contents = tag.groupValues.drop(1).first { it.isNotEmpty() }
            val rest = title.substring(tag.range.last + 1)
            if (rest.isBlank() || !isMetadata(contents)) break
            title = rest
        }

        var season: Int? = null
        var foundEpisode = false
        val numberedSeason = seasonEpisode.find(title)
        if (numberedSeason != null && meaningful(title.substring(0, numberedSeason.range.first))) {
            season = numberedSeason.groupValues[1].toIntOrNull()
            title = title.substring(0, numberedSeason.range.first)
            foundEpisode = true
        } else {
            val episode = sequenceOf(
                markedEpisode.findAll(title),
                dashEpisode.findAll(title).filter { !year.matches(it.groupValues[1]) },
                bracketEpisode.findAll(title).filter { !year.matches(it.groupValues[1]) },
            ).flatten().filter { meaningful(title.substring(0, it.range.first)) }
                .minByOrNull { it.range.first }
            if (episode != null) {
                title = title.substring(0, episode.range.first)
                foundEpisode = true
            }
        }

        // Once technical information starts, the remainder is a release suffix.
        // Keeping the part before it also handles scene-style names ending -GROUP.
        technical.find(title)?.let { match ->
            val beforeTag = title.substring(0, match.range.first)
            val openingBracket = beforeTag.indexOfLast { it in "[({" }
            val insideBracket = openingBracket >= 0 && beforeTag.substring(openingBracket).none { it in "])}" }
            title = title.substring(0, if (insideBracket) openingBracket else match.range.first)
        }
        title = trimSeparators(title)
        while (true) {
            val suffix = Regex("""[ ._-]*[\[\(\{]([^\]\}\)]+)[\]\}\)]$""").find(title) ?: break
            if (!isMetadata(suffix.groupValues[1])) break
            title = trimSeparators(title.substring(0, suffix.range.first))
        }
        title = unwrapTitle(title)

        if (!foundEpisode) {
            bareEpisode.find(title)?.let { match ->
                val prefix = title.substring(0, match.range.first)
                if (match.groupValues[1] != "00" && meaningful(prefix) &&
                    !Regex("(?i)(?:movie|part)[ ._-]*$").containsMatchIn(prefix)
                ) {
                    title = prefix
                    foundEpisode = true
                }
            }
        }
        title = trimSeparators(title)
        if (' ' !in title) title = title.replace(Regex("[_.]+"), " ")
        title = whitespace.replace(title, " ").trim()

        if (foundEpisode) {
            seasonSuffix.find(title)?.let { match ->
                season = match.groupValues[1].toIntOrNull()
                title = title.substring(0, match.range.first)
            }
            romanSuffix.find(title)?.let { match ->
                season = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")
                    .indexOf(match.groupValues[1]) + 1
                title = title.substring(0, match.range.first)
            }
        }
        title = title.replace(trailingYear, " ($1)")
            .replace(Regex("(?i)(?: -)? TV$"), " (TV)")
        if (foundEpisode && season != null && season != 1 &&
            !Regex("\\((?:19|20)\\d{2}\\)").containsMatchIn(title)
        ) title += " S$season"

        return title.takeIf { meaningful(it) && !hash.matches(it) && !it.equals("episode", true) }
    }

    fun fromFileNames(names: List<String>): String? {
        val counts = linkedMapOf<String, Int>()
        names.sorted().mapNotNull(::fromFileName).forEach { title ->
            counts[title] = (counts[title] ?: 0) + 1
        }
        if (counts.isEmpty()) return null
        if (counts.values.any { it > 1 }) {
            return counts.keys.reduce { best, next -> if (counts.getValue(best) > counts.getValue(next)) best else next }
        }

        val titles = counts.keys.sorted()
        var prefix = titles.first().commonPrefixWith(titles.last())
        // Do not turn different names into a fragment from the middle of a word.
        if (prefix.isNotEmpty() && prefix.length < titles.first().length &&
            prefix.last().isLetterOrDigit() && titles.first()[prefix.length].isLetterOrDigit()
        ) prefix = prefix.substringBeforeLast(' ', "")
        return trimSeparators(prefix).takeIf(::meaningful) ?: counts.keys.first()
    }

    private fun isMetadata(value: String): Boolean = hash.matches(value.trim()) ||
        technical.containsMatchIn(value) || Regex(
            "(?i)(?:(?:multi(?:ple)?[ ._-]*)?(?:sub(?:title)?s?|dub(?:bed)?)|" +
                "[257]\\.[0124]|\\d+[ ._-]*bit|uncensored|censored)",
        ).matches(value.trim())

    private fun meaningful(value: String): Boolean = value.any(Char::isLetterOrDigit)

    private fun trimSeparators(value: String): String = value.trim { it.isWhitespace() || it in "._-" }

    private fun unwrapTitle(value: String): String {
        var result = value
        while (result.length > 2 && ((result.first() == '[' && result.last() == ']') ||
                (result.first() == '{' && result.last() == '}'))
        ) result = result.substring(1, result.length - 1).trim()
        return result
    }
}
