package gr.matchbell.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/** Samples copied from the real SPORT24 feed (03/11/2026). */
class GenderTest {
    private fun at(v: String): Instant = LocalDateTime.parse(v).atZone(Athens).toInstant()
    private fun e(id: String, title: String, vararg tags: Pair<String, String>) =
        Broadcast(id, at("2026-11-05T19:00:00"), title, "ΕΡΤ2 ΣΠΟΡ", tags.map { Tag(it.first, it.second) })

    private val womenHandball = e("1", "Πανόραμα - ΠΑΟΚ", "handball" to "Χάντμπολ", "a1" to "Πρωτάθλημα Α1 Γυναικών")
    private val womenVolley = e("2", "ΑΕΚ - Α. Ο. Θήρας", "volley" to "Βόλεϊ", "v" to "Α1 Γυναικών Volley League 2026-27")
    private val womenBasket = e("3", "Αθηναϊκός Qualco - Κιμπίρκστις Β.", "basket" to "Μπάσκετ", "el" to "Ευρωλίγκα Γυναικών 2026/27")
    private val womenUcl = e("4", "Μπαρτσελόνα - Άρσεναλ", "podosfairo" to "Ποδόσφαιρο", "ucl" to "UEFA Champions League Γυναικών 2026-27")
    private val wta = e("5", "WTA 250 2026", "tennis" to "Τένις", "r" to "Χονγκ Κονγκ, Προημιτελικοί")
    private val menVolley = e("6", "Ολυμπιακός - Φλοίσβος", "volley" to "Βόλεϊ", "v" to "Volley League Ανδρών 2026-27")
    private val menBasket = e("7", "ΠΑΟΚ - Ρόμα", "basket" to "Μπάσκετ", "ec" to "EuroCup 2026/27")
    private val atp = e("8", "ATP Masters 1000 2026", "tennis" to "Τένις", "r" to "Παρίσι (Κυρίως Ταμπλό)")
    private val menUcl = e("9", "ΠΑΟΚ - Ρεάλ", "podosfairo" to "Ποδόσφαιρο", "ucl" to "UEFA Champions League 2026-27")
    private val show = e("10", "Women in sport", "show" to "Εκπομπή")

    @Test fun realFeedTitlesAreClassified() {
        listOf(womenHandball, womenVolley, womenBasket, womenUcl, wta).forEach { assertTrue(it.title, it.isWomen) }
        listOf(menVolley, menBasket, atp, menUcl).forEach { assertFalse(it.title, it.isWomen) }
        assertFalse("shows have no gender", show.isWomen)
    }
    @Test fun descriptionDoesNotTriggerWomen() {
        val d = menBasket.copy(description = "Περιγραφή: Γ. Δούσκας. Η ομάδα γυναικών του συλλόγου…")
        assertFalse(d.isWomen)
    }
    @Test fun womenAndMenCompetitionsStayApart() {
        assertNotEquals(womenUcl.competitions.single().key, menUcl.competitions.single().key)
        // A women's event whose tag lacks the word gets "(Γυναικών)" added to its competition.
        val wtaRound = wta.competitions.single()
        assertEquals("Χονγκ Κονγκ, Προημιτελικοί (Γυναικών)", wtaRound.label)
        assertEquals("Ευρωλίγκα Γυναικών", womenBasket.competitions.single().label)
    }
    @Test fun teamGenderSeparatesPaokMenAndWomen() {
        assertTrue(teamMatches(TeamRule("ΠΑΟΚ"), womenHandball))
        assertTrue(teamMatches(TeamRule("ΠΑΟΚ"), menBasket))
        assertFalse(teamMatches(TeamRule("ΠΑΟΚ", gender = "men"), womenHandball))
        assertTrue(teamMatches(TeamRule("ΠΑΟΚ", gender = "men"), menBasket))
        assertTrue(teamMatches(TeamRule("ΠΑΟΚ", gender = "women"), womenHandball))
        assertFalse(teamMatches(TeamRule("ΠΑΟΚ", gender = "women"), menBasket))
        assertEquals("παοκ|all", TeamRule("ΠΑΟΚ").key) // unchanged from 0.3.0
        assertNotEquals(TeamRule("ΠΑΟΚ", gender = "men").key, TeamRule("ΠΑΟΚ", gender = "women").key)
    }
    @Test fun sportGenderSelection() {
        val womenOnly = Preferences(sports = setOf(sportSelection("volley", "women")))
        assertTrue(womenOnly.wantsReminder(womenVolley))
        assertFalse(womenOnly.wantsReminder(menVolley))
        val all = Preferences(sports = setOf("volley"))
        assertTrue(all.wantsReminder(womenVolley) && all.wantsReminder(menVolley))
        val menTennis = Preferences(sports = setOf("tennis@men"))
        assertTrue(menTennis.wantsReminder(atp)); assertFalse(menTennis.wantsReminder(wta))
        assertEquals("women", womenOnly.sportGender("volley")); assertNull(womenOnly.sportGender("basket"))
    }
    @Test fun genderedTeamsSurviveBackup() {
        val p = Preferences(teams = listOf(TeamRule("ΠΑΟΚ", "handball", "women"), TeamRule("ΑΕΚ")), sports = setOf("basket@men"))
        assertEquals(p, PrefsCodec.importBackup(PrefsCodec.exportBackup(p, "x")))
    }
    @Test fun formulaOneGreekSpelling() {
        assertEquals("f1", e("11", "Φόρμουλα 1: Αγώνας", "mixanokinitos-athlitismos" to "Μηχανοκίνητα").series)
    }
}
