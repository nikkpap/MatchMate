package gr.matchbell.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NotificationLookTest {
    private fun e(title: String, vararg tags: Pair<String, String>) =
        Broadcast("1", Instant.parse("2026-11-05T19:00:00Z"), title, "ΕΡΤ1", tags.map { Tag(it.first, it.second) })
    @Test fun emojiPerSport() {
        assertEquals("⚽", sportEmoji("podosfairo")); assertEquals("🏀", sportEmoji("basket"))
        assertEquals("🎾", sportEmoji("tennis")); assertEquals("🏐", sportEmoji("volley"))
        assertEquals("🤾", sportEmoji("handball")); assertEquals("🤽", sportEmoji("polo"))
        assertEquals("🏎️", sportEmoji("f1")); assertEquals("🏍️", sportEmoji("motogp"))
        assertEquals("⛳", sportEmoji("gkolf", "Γκολφ")); assertEquals("🏆", sportEmoji("climbing", "Αναρρίχηση"))
    }
    @Test fun f1BroadcastUsesCarNotFlag() {
        val f1 = e("Formula 1 Αγώνας", "mixanokinitos-athlitismos" to "Μηχανοκίνητα")
        assertEquals("🏎️", sportEmoji(f1.series ?: f1.sport, f1.sportLabel))
    }
    @Test fun matchTitleSpacesTheTeams() {
        assertEquals("ΠΑΟΚ – Ρεάλ Μαδρίτης", matchTitle(e("ΠΑΟΚ - Ρεάλ Μαδρίτης", "podosfairo" to "Ποδόσφαιρο")))
        assertEquals("WTA Finals", matchTitle(e("WTA Finals", "tennis" to "Τένις")))
    }
}
