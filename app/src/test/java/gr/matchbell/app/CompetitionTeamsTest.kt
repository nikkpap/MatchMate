package gr.matchbell.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class CompetitionTeamsTest {
    private fun at(v: String): Instant = LocalDateTime.parse(v).atZone(Athens).toInstant()
    private fun ucl(id: String, title: String) = Broadcast(id, at("2026-11-04T22:00:00"), title, "Cosmote Sport 2",
        listOf(Tag("podosfairo", "Ποδόσφαιρο"), Tag("ucl", "UEFA Champions League 2026-27")))
    private val paok = ucl("1", "ΠΑΟΚ - Ρεάλ Μαδρίτης")
    private val aek = ucl("2", "Μπάγερν - ΑΕΚ")
    private val other = ucl("3", "Λίβερπουλ - Ίντερ")
    private val key = "uefa champions league"

    @Test fun competitionWithoutTeamFilterKeepsEveryMatch() {
        val p = Preferences(competitions = setOf(key))
        assertTrue(listOf(paok, aek, other).all { p.wantsReminder(it) })
    }
    @Test fun onlyChosenTeamsOfTheCompetition() {
        val p = Preferences(competitions = setOf(key), competitionTeams = mapOf(key to setOf("ΠΑΟΚ", "ΑΕΚ")))
        assertTrue(p.wantsReminder(paok)); assertTrue(p.wantsReminder(aek)); assertFalse(p.wantsReminder(other))
    }
    @Test fun aliasesWorkInsideACompetition() {
        val p = Preferences(competitions = setOf(key), competitionTeams = mapOf(key to setOf("Ρεάλ Μαδρίτης")))
        assertTrue(p.wantsReminder(ucl("4", "Real Madrid - Λίβερπουλ")))
    }
    @Test fun teamNamesComeFromTitle() {
        assertEquals(listOf("ΠΑΟΚ", "Ρεάλ Μαδρίτης"), paok.teamNames)
        assertTrue(ucl("5", "WTA 250 2026").teamNames.isEmpty())
    }
    @Test fun filterChangesAlarmsAndSurvivesBackup() {
        val p = Preferences(competitions = setOf(key))
        val q = p.copy(competitionTeams = mapOf(key to setOf("ΠΑΟΚ")))
        assertNotEquals(p.reminderSettings(), q.reminderSettings())
        assertEquals(q, PrefsCodec.importBackup(PrefsCodec.exportBackup(q, "x")))
    }
}
