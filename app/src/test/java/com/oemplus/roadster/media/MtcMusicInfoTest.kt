package com.oemplus.roadster.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MtcMusicInfoTest {
    private fun read(raw: String?) = MtcMusicInfo.parse(raw).let { Triple(it.title, it.artist, it.album) }

    @Test
    fun nothingYet() {
        assertTrue(MtcMusicInfo.parse(null).empty)
        assertTrue(MtcMusicInfo.parse("   ").empty)
    }

    @Test
    fun fieldsSplitByTheModulesFfByte() {
        assertEquals(Triple("Plastic Love", "Mariya Takeuchi", "Variety"), read("Plastic Love\u00FFMariya Takeuchi\u00FFVariety"))
    }

    @Test
    fun countersAndTimesBetweenFieldsAreSkipped() {
        assertEquals(
            Triple("Plastic Love", "Mariya Takeuchi", "Variety"),
            read("Plastic Love\u00FFMariya Takeuchi\u00FF291000\u00FF3\u00FF12\u00FFVariety"),
        )
    }

    @Test
    fun newlinesAndBars() {
        assertEquals(Triple("Stay With Me", "Miki Matsubara", "Pocket Park"), read("Stay With Me\nMiki Matsubara\nPocket Park"))
        assertEquals(Triple("Stay With Me", "Miki Matsubara", null), read("Stay With Me|Miki Matsubara"))
    }

    @Test
    fun aMissingArtistDoesNotPullTheAlbumForward() {
        assertEquals(Triple("Ride on Time", null, "Ride on Time"), read("Ride on Time\u00FF\u00FFRide on Time"))
    }

    @Test
    fun aNumberAsTheTitleIsStillTheTitle() {
        assertEquals(Triple("1979", "The Smashing Pumpkins", null), read("1979\u00FFThe Smashing Pumpkins\u00FF265000"))
    }

    @Test
    fun labelledPairs() {
        assertEquals(
            Triple("4:00A.M.", "Taeko Onuki", "Mignonne"),
            read("title=4:00A.M., artist=Taeko Onuki, album=Mignonne, duration=337000"),
        )
    }

    @Test
    fun json() {
        assertEquals(
            Triple("Midnight Pretenders", "Tomoko Aran", "Fuyü-Kükan"),
            read("""{"title":"Midnight Pretenders","artist":"Tomoko Aran","album":"Fuyü-Kükan","duration":312000}"""),
        )
    }

    @Test
    fun placeholdersAreNotShown() {
        assertEquals(Triple("Telephone Number", null, null), read("Telephone Number\u00FFUnknown\u00FF<unknown>"))
    }

    @Test
    fun anythingElseIsShownAsTheTitle() {
        assertEquals(Triple("Junko Ohashi - Telephone Number", null, null), read("Junko Ohashi - Telephone Number"))
        assertEquals(Triple("Tyler, The Creator: live", null, null), read("Tyler, The Creator: live"))
    }
}
