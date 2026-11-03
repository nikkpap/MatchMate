package gr.matchbell.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class ProfileTest {
    private fun at(v: String): Instant = LocalDateTime.parse(v).atZone(Athens).toInstant()
    private fun e(id: String, title: String, vararg tags: Pair<String, String>) =
        Broadcast(id, at("2026-11-05T19:00:00"), title, "ΕΡΤ2 ΣΠΟΡ", tags.map { Tag(it.first, it.second) })
    private val menFootball = e("1", "ΠΑΟΚ - Ρεάλ", "podosfairo" to "Ποδόσφαιρο", "ucl" to "UEFA Champions League 2026-27")
    private val womenFootball = e("2", "ΠΑΟΚ - Άρσεναλ", "podosfairo" to "Ποδόσφαιρο", "ucl" to "UEFA Champions League Γυναικών 2026-27")
    private val menBasket = e("3", "ΠΑΟΚ - Ρόμα", "basket" to "Μπάσκετ", "ec" to "EuroCup 2026/27")
    private val womenHandball = e("4", "Πανόραμα - ΠΑΟΚ", "handball" to "Χάντμπολ", "a1" to "Πρωτάθλημα Α1 Γυναικών")
    private val events = listOf(menFootball, womenFootball, menBasket, womenHandball)
    private val now = at("2026-11-05T10:00:00")

    @Test fun menFootballProfileFiltersAnOldAllRule() {
        // ΠΑΟΚ was followed before the profile existed ("all" sports, "all" genders).
        val p = Preferences(teams = listOf(TeamRule("ΠΑΟΚ")), scopeGender = "men", scopeSports = setOf("podosfairo"))
        assertEquals(listOf("1"), events.filter { p.wantsReminder(it) }.map { it.id })
        assertEquals(listOf("1"), planReminders(events, p, now).map { it.event.id })
    }
    @Test fun noProfileKeepsOldBehaviour() {
        assertEquals(4, events.count { Preferences(teams = listOf(TeamRule("ΠΑΟΚ"))).wantsReminder(it) })
    }
    @Test fun manualBellIgnoresProfile() {
        val p = Preferences(scopeGender = "men", scopeSports = setOf("podosfairo"), manual = setOf("4"))
        assertTrue(p.wantsReminder(womenHandball))
    }
    @Test fun profileChangesReschedulingButViewDoesNot() {
        val p = Preferences(sports = setOf("podosfairo"))
        assertNotEquals(p.reminderSettings(), p.copy(scopeGender = "men").reminderSettings())
        assertNotEquals(p.reminderSettings(), p.copy(scopeSports = setOf("basket")).reminderSettings())
        assertEquals(p.reminderSettings(), p.copy(programmeView = "mine").reminderSettings())
    }
    @Test fun profileIsSavedAndRestored() {
        val p = Preferences(scopeGender = "men", scopeSports = setOf("podosfairo"), programmeView = "mine")
        assertEquals(p, PrefsCodec.decode(org.json.JSONObject(PrefsCodec.encode(p).toString())))
    }
}
