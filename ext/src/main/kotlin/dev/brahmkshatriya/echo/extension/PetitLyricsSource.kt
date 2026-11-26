package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSlider
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * PetitLyrics XML API. The service only answers a licensed `clientAppId`; the probe without
 * one returned status 8150f001. A user may paste an id they are licensed to use; the extension
 * then uses the real API. It never falls back to a guessed id.
 */
internal class PetitLyricsSource : LyricsSource {

    private val throttle = Throttle(1200)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val appId = appId(settings)
        val query = track.query()
        if (query.title.isBlank() || query.artist.isBlank()) return emptyList()
        throttle.await()
        val form = "clientAppId=" + Http.encode(appId) +
            "&terminalType=pc&lyricsType=" + lyricsType(settings) +
            "&key_title=" + Http.encode(track.title) +
            "&key_artist=" + Http.encode(query.artist) +
            "&key_album=" + Http.encode(query.album)
        val xml = Http.postForm(API_URL, form)
        val status = tag(xml, "status")
        if (status != "00000000") {
            throw LyricsException("PetitLyrics refused the request (status " + (status ?: "unknown") + "). Check the client application id.")
        }
        val text = tag(xml, "lyricsString") ?: tag(xml, "lyrics") ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return listOf(
            Lyrics(
                id = "petitlyrics:" + candidateId(track.title, query.artist),
                title = track.title,
                subtitle = query.artist + " • PetitLyrics",
                lyrics = null,
                extras = mapOf(
                    "title" to track.title,
                    "artist" to query.artist,
                    "album" to query.album,
                ),
            ),
        )
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val appId = appId(settings)
        throttle.await()
        val form = "clientAppId=" + Http.encode(appId) +
            "&terminalType=pc&lyricsType=" + lyricsType(settings) +
            "&key_title=" + Http.encode(lyrics.extras["title"].orEmpty()) +
            "&key_artist=" + Http.encode(lyrics.extras["artist"].orEmpty()) +
            "&key_album=" + Http.encode(lyrics.extras["album"].orEmpty())
        val xml = Http.postForm(API_URL, form)
        val status = tag(xml, "status")
        if (status != "00000000") throw LyricsException("PetitLyrics request failed (status " + (status ?: "unknown") + ")")
        var text = tag(xml, "lyricsString") ?: tag(xml, "lyrics")
            ?: throw LyricsException("PetitLyrics returned no lyrics")
        text = decodeEntities(text)
        val lines = Lrc.parse(text)
        if (lines.isNotEmpty()) return lyrics.copy(lyrics = timed(lines, lines.any { it.words.size > 1 }))
        val plainLines = Lrc.parsePlain(text)
        if (plainLines.isNotEmpty()) return lyrics.copy(lyrics = plain(plainLines.joinToString("\n")))
        throw LyricsException("PetitLyrics payload could not be parsed")
    }

    private fun appId(settings: Settings?): String {
        val value = settings?.getString(KEY_APP_ID)?.trim().orEmpty()
        if (value.isEmpty()) {
            throw LyricsException(
                "PetitLyrics requires a licensed client application id. " +
                    "Add one in the extension settings to enable this provider.",
            )
        }
        return value
    }

    private fun lyricsType(settings: Settings?): Int = settings?.getInt(KEY_TYPE) ?: 1

    private fun tag(xml: String, name: String): String? {
        val cdata = Regex("<" + name + ">\\s*<!\\[CDATA\\[(.*?)]]>\\s*</" + name + ">", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        cdata.find(xml)?.let { return it.groupValues[1] }
        val plain = Regex("<" + name + ">(.*?)</" + name + ">", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return plain.find(xml)?.groupValues?.get(1)?.trim()
    }

    companion object {
        const val KEY_APP_ID = "client_app_id"
        const val KEY_TYPE = "lyrics_type"
        private const val API_URL = "https://p0.petitlyrics.com/api/GetPetitLyricsData.php"

        fun settings(): List<Setting> = listOf(
            SettingTextInput(
                title = "Licensed clientAppId",
                key = KEY_APP_ID,
                summary = "Issued by PetitLyrics. Required; the API rejects anything else.",
                defaultValue = "",
            ),
            SettingSlider(
                title = "Lyrics type",
                key = KEY_TYPE,
                summary = "0 = plain, 1 = line synced, 2 = word synced (as supported by PetitLyrics).",
                defaultValue = 1,
                from = 0,
                to = 2,
                steps = 2,
            ),
        )
    }
}
