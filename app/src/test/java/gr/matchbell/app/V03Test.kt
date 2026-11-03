package gr.matchbell.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/** Regression tests for the 0.3.0 fixes and features. */
class V03Test {
    private fun at(value: String): Instant = LocalDateTime.parse(value).atZone(Athens).toInstant()
    private val now = at("2026-11-03T12:00:00")
    private fun game(id: String, channel: String = "Novasports Prime", title: String = "ΠΑΟΚ - ΑΕΚ") =
        Broadcast(id, at("2026-11-03T21:00:00"), title, channel, listOf(Tag("podosfairo", "Ποδόσφαιρο")))

    @Test fun hiddenChannelSkipsAutomaticButKeepsManualReminders() {
        val p = Preferences(teams = listOf(TeamRule("ΠΑΟΚ")), hiddenChannels = setOf("Novasports Prime"))
        assertFalse(p.wantsReminder(game("1")))
        assertTrue(p.wantsReminder(game("2", channel = "ΕΡΤ1")))
        assertTrue(p.copy(manual = setOf("1")).wantsReminder(game("1")))
    }
    @Test fun hiddenChannelsChangeAlarmsButLanguageAndPaletteDoNot() {
        val p = Preferences(sports = setOf("podosfairo"))
        assertEquals(p.reminderSettings(), p.copy(language = "en", palette = "ocean", theme = "amoled").reminderSettings())
        assertNotEquals(p.reminderSettings(), p.copy(hiddenChannels = setOf("ΕΡΤ1")).reminderSettings())
    }
    @Test fun pruningDropsIdsOfBroadcastsThatLeftTheProgramme() {
        val p = Preferences(manual = setOf("1", "old"), muted = setOf("old2"), favorites = setOf("1", "gone"))
        val pruned = p.pruned(listOf(game("1")))
        assertEquals(setOf("1"), pruned.manual)
        assertTrue(pruned.muted.isEmpty())
        assertEquals(setOf("1"), pruned.favorites)
        assertSame(pruned, pruned.pruned(listOf(game("1"))))
    }
    @Test fun emptyProgrammeNeverWipesSelections() {
        val p = Preferences(manual = setOf("1"))
        assertSame(p, p.pruned(emptyList()))
    }
    @Test fun bellToggleRoundTrips() {
        val e = game("1")
        val auto = Preferences(teams = listOf(TeamRule("ΠΑΟΚ")))
        assertTrue(auto.bellOn(e))
        val muted = toggleReminder(e, auto)
        assertFalse(muted.bellOn(e))
        assertTrue(toggleReminder(e, muted).bellOn(e))
        val manualOnly = toggleReminder(e, Preferences())
        assertTrue(manualOnly.wantsReminder(e))
    }

    @Test fun backupRoundTripKeepsEverything() {
        val p = Preferences(teams = listOf(TeamRule("ΠΑΟΚ", "basket"), TeamRule("Άρης")), sports = setOf("f1"),
            competitions = setOf("euroleague"), manual = setOf("9"), muted = setOf("8"), favorites = setOf("7"),
            enabled = false, offsets = setOf(0, 45, 1440), quietEnabled = true, quietStart = 60, quietEnd = 420,
            includeShows = true, refreshHours = 12, theme = "amoled", palette = "violet", language = "en",
            hiddenChannels = setOf("Cosmote Sport 1"))
        assertEquals(p, PrefsCodec.importBackup(PrefsCodec.exportBackup(p, "0.3.0")))
    }
    @Test fun foreignOrBrokenBackupIsRejected() {
        assertNull(PrefsCodec.importBackup("{\"teams\":[]}"))
        assertNull(PrefsCodec.importBackup("not json"))
    }
    @Test fun invalidValuesFallBackToSafeDefaults() {
        val p = PrefsCodec.decode(org.json.JSONObject("""{"theme":"neon","palette":"x","language":"de",
            "offsets":[-5,5000],"refreshHours":2,"quietStart":9999,"teams":[{"name":"  "},{"name":"ΠΑΟΚ"}]}"""))
        assertEquals("dark", p.theme); assertEquals("mint", p.palette); assertEquals("el", p.language)
        assertEquals(setOf(30), p.offsets); assertEquals(6L, p.refreshHours); assertEquals(1439, p.quietStart)
        assertEquals(listOf("ΠΑΟΚ"), p.teams.map { it.name })
    }
    @Test fun oldPreferencesWithoutNewFieldsStillLoad() {
        val p = PrefsCodec.decode(org.json.JSONObject("""{"sports":["basket"],"theme":"light"}"""))
        assertEquals(setOf("basket"), p.sports); assertEquals("light", p.theme)
        assertEquals("el", p.language); assertTrue(p.hiddenChannels.isEmpty())
    }
    @Test fun feedNullsAreNotShownAsTheWordNull() {
        val e = FeedParser.parse("""[{"id":5,"scheduleDate":"2026-11-03 21:00:00","title":"Α - Β",
            "description":null,"channel":{"name":null},"tags":[{"name":"basket","displayName":null},null]}]""").single()
        assertEquals("", e.description)
        assertEquals("Κανάλι μη διαθέσιμο", e.channel)
        assertEquals("basket", e.sport)
        assertEquals("basket", e.tags.single().label)
    }
    @Test fun englishLabels() {
        val en = txtFor("en")
        assertEquals("Football", en.sport("podosfairo"))
        assertEquals("Ποδόσφαιρο", txtFor("el").sport("podosfairo"))
        assertEquals("Climbing", en.sport("climbing", "Climbing"))
        assertEquals("2 hours", en.offset(120)); assertEquals("45′", en.offset(45)); assertEquals("At start", en.offset(0))
        assertEquals("In 1h 5′", en.inMinutes(65)); assertEquals("Σε 5′", txtFor("el").inMinutes(5))
    }
}
