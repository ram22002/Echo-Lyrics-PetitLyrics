package dev.brahmkshatriya.echo.extension

import org.junit.Assert.assertEquals
import org.junit.Test

class PetitXmlTest {

    @Test
    fun readsCdataAndPlainTags() {
        val xml = "<response><status>00000000</status><lyricsString><![CDATA[[00:01.00]Hi]]></lyricsString></response>"
        assertEquals("00000000", tagOf(xml, "status"))
        assertEquals("[00:01.00]Hi", tagOf(xml, "lyricsString"))
    }

    private fun tagOf(xml: String, name: String): String? {
        val cdata = Regex("<" + name + ">\\s*<!\\[CDATA\\[(.*?)]]>\\s*</" + name + ">", setOf(RegexOption.DOT_MATCHES_ALL))
        cdata.find(xml)?.let { return it.groupValues[1] }
        val plain = Regex("<" + name + ">(.*?)</" + name + ">", setOf(RegexOption.DOT_MATCHES_ALL))
        return plain.find(xml)?.groupValues?.get(1)?.trim()
    }
}
