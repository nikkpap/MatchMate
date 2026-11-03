package gr.matchbell.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class RulesTest {
    private fun at(value: String): Instant = LocalDateTime.parse(value).atZone(Athens).toInstant()
    private fun game(id: String = "101", title: String = "ΠΑΟΚ - Ολυμπιακός", sport: String = "podosfairo",
                     date: String = "2026-10-05T21:00:00") = Broadcast(id, at(date), title, "Novasports Prime",
        listOf(Tag(sport, SportLabels[sport] ?: sport), Tag("league", "Stoiximan Super League 2026-27")))
    @Test fun accentsAndFinalSigmaAreNormalized() {
        assertEquals(normalized("Παναθηναϊκός"), normalized("ΠΑΝΑΘΗΝΑΙΚΟΣ"))
        assertEquals("αεκ betsson", normalized("ΑΕΚ – Betsson"))
    }
    @Test fun teamMatchesEitherSideAndSportScope() {
        assertTrue(teamMatches(TeamRule("ΠΑΟΚ", "podosfairo"), game()))
        assertTrue(teamMatches(TeamRule("ΠΑΟΚ"), game(title = "ΑΕΚ – ΠΑΟΚ")))
        assertFalse(teamMatches(TeamRule("ΠΑΟΚ", "basket"), game()))
    }
    @Test fun reserveTeamDoesNotMatchSeniorTeam() {
        assertFalse(teamMatches(TeamRule("ΠΑΟΚ"), game(title = "ΠΑΟΚ Β - Ηρακλής")))
        assertTrue(teamMatches(TeamRule("ΠΑΟΚ Β"), game(title = "ΠΑΟΚ Β - Ηρακλής")))
    }
    @Test fun aliasesWorkButSubstringDoesNot() {
        assertTrue(teamMatches(TeamRule("Παναθηναϊκός"), game(title = "Παναθηναϊκός AKTOR - ΑΕΚ")))
        assertFalse(teamMatches(TeamRule("Άρης"), game(title = "Παρί Σεν Ζερμέν - Ρεάλ Μαδρίτης")))
    }
    @Test fun selectionsAreAdditive() {
        val prefs = Preferences(teams = listOf(TeamRule("ΠΑΟΚ")), sports = setOf("tennis"), competitions = setOf("euroleague"))
        assertTrue(prefs.interested(game()))
        assertTrue(prefs.interested(game(title = "ATP Tokyo", sport = "tennis")))
        val basketball = game(title = "Μπαρτσελόνα - Ρεάλ", sport = "basket").copy(tags = listOf(Tag("basket", "Μπάσκετ"), Tag("EuroLeague-2026/27", "EuroLeague 2026/27")))
        assertTrue(prefs.interested(basketball))
        assertFalse(prefs.interested(game(title = "Άρης - ΑΕΚ")))
    }
    @Test fun competitionSurvivesSeasonChange() {
        assertEquals("euroleague", competitionKey("EuroLeague 2026/27"))
        assertEquals(competitionKey("EuroLeague 2026/27"), competitionKey("EuroLeague 2027-28"))
        assertEquals("Greek Superbet League 2", competitionLabel("Greek Superbet League 2 2026 - 27"))
    }
    @Test fun f1IsIndependentOfOtherMotorsports() {
        val prefs = Preferences(sports = setOf("f1"))
        assertTrue(prefs.interested(game(title = "Formula 1 Αγώνας", sport = "mixanokinitos-athlitismos")))
        assertFalse(prefs.interested(game(title = "MotoGP Αγώνας", sport = "mixanokinitos-athlitismos")))
    }
    @Test fun manualReminderWorksWithoutFollowingAnything() {
        assertTrue(Preferences(manual = setOf("101")).wantsReminder(game()))
    }
    @Test fun mutedMatchOverridesAutomaticAndManualSelections() {
        assertFalse(Preferences(sports = setOf("podosfairo"), manual = setOf("101"), muted = setOf("101")).wantsReminder(game()))
    }
    @Test fun globalPauseStopsEveryReminder() {
        assertTrue(planReminders(listOf(game()), Preferences(manual = setOf("101"), enabled = false), at("2026-10-05T18:00:00")).isEmpty())
    }
    @Test fun favoriteAloneDoesNotCreateReminder() {
        assertFalse(Preferences(favorites = setOf("101")).wantsReminder(game()))
    }
    @Test fun twoOffsetsProduceTwoCorrectInstants() {
        val planned = planReminders(listOf(game()), Preferences(manual = setOf("101"), offsets = setOf(15, 60)), at("2026-10-05T19:00:00"))
        assertEquals(listOf(at("2026-10-05T20:00:00"), at("2026-10-05T20:45:00")), planned.map { it.at })
        assertEquals(2, planned.map { it.key }.distinct().size)
    }
    @Test fun missedOffsetIsNotSentImmediately() {
        val planned = planReminders(listOf(game()), Preferences(manual = setOf("101"), offsets = setOf(15, 60)), at("2026-10-05T20:10:00"))
        assertEquals(listOf(15), planned.map { it.minutes })
    }
    @Test fun pastBroadcastIsNeverScheduled() {
        assertTrue(planReminders(listOf(game()), Preferences(manual = setOf("101")), at("2026-10-05T22:00:00")).isEmpty())
    }
    @Test fun zeroOffsetSchedulesAtBroadcastStart() {
        val reminders = planReminders(listOf(game()), Preferences(manual = setOf("101"), offsets = setOf(0)), at("2026-10-05T20:55:00"))
        assertEquals(game().startsAt, reminders.single().at)
    }
    @Test fun quietHoursSpanMidnightAndRespectBoundaries() {
        val prefs = Preferences(quietEnabled = true, quietStart = 1380, quietEnd = 480)
        assertTrue(inQuietHours(at("2026-10-05T23:00:00"), prefs))
        assertTrue(inQuietHours(at("2026-10-06T07:59:00"), prefs))
        assertFalse(inQuietHours(at("2026-10-06T08:00:00"), prefs))
        assertFalse(inQuietHours(at("2026-10-05T22:59:00"), prefs))
    }
    @Test fun daytimeQuietHoursAndEqualTimes() {
        val p = Preferences(quietEnabled = true, quietStart = 900, quietEnd = 1020)
        assertTrue(inQuietHours(at("2026-10-05T16:00:00"), p))
        assertFalse(inQuietHours(at("2026-10-05T18:00:00"), p))
        assertFalse(inQuietHours(at("2026-10-05T16:00:00"), p.copy(quietEnd = 900)))
    }
    @Test fun quietHoursOmitRatherThanDelay() {
        val p = Preferences(manual = setOf("101"), offsets = setOf(30), quietEnabled = true, quietStart = 1200, quietEnd = 480)
        assertTrue(planReminders(listOf(game()), p, at("2026-10-05T18:00:00")).isEmpty())
    }
    @Test fun offsetCrossesMidnightAndDstUsingInstants() {
        val e = game(date = "2026-10-26T00:15:00")
        val planned = planReminders(listOf(e), Preferences(manual = setOf("101"), offsets = setOf(60)), at("2026-10-25T20:00:00"))
        assertEquals(at("2026-10-25T23:15:00"), planned.single().at)
        assertEquals("2026-10-25T22:15:00Z", e.startsAt.toString())
    }
    @Test fun movedBroadcastKeepsAlarmSlotButChangesDeliveryKey() {
        val first = Reminder(game(), 30, game().startsAt.minusSeconds(1800))
        val moved = Reminder(game(date = "2026-10-05T22:00:00"), 30, at("2026-10-05T21:30:00"))
        assertEquals(first.key, moved.key)
        assertNotEquals(first.deliveryKey, moved.deliveryKey)
    }
    @Test fun removedBroadcastProducesNoReminder() {
        assertTrue(planReminders(emptyList(), Preferences(manual = setOf("101")), Instant.now()).isEmpty())
    }
    @Test fun showsAreExcludedUnlessExplicitlyRequested() {
        val e = game(title = "Formula 1 Show", sport = "show")
        assertEquals("f1", e.series)
        assertFalse(Preferences(sports = setOf("f1")).wantsReminder(e))
        assertTrue(Preferences(sports = setOf("f1"), includeShows = true).wantsReminder(e))
        assertFalse(Preferences(teams = listOf(TeamRule("ΠΑΟΚ"))).wantsReminder(e))
        assertTrue(Preferences(manual = setOf("101")).wantsReminder(e))
    }
}
