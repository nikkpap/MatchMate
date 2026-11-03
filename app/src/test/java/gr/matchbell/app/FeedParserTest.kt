package gr.matchbell.app

import org.junit.Assert.*
import org.junit.Test

class FeedParserTest {
    private val sample = """[
        {"id":132325,"scheduleDate":"2026-10-06 20:30:00","title":"Μπόσνα - ΠΑΟΚ",
        "channel":{"name":"Novasports Prime"},"tags":[{"name":"basket","displayName":"Μπάσκετ"},
        {"name":"EuroCup-2026/27","displayName":"EuroCup 2026/27"}],"isLive":false},
        {"id":132468,"scheduleDate":"2026-10-04 10:00:00","title":"Formula 1 Αγώνας",
        "channel":{"name":"ΑΝΤ1+"},"tags":[{"name":"mixanokinitos-athlitismos","displayName":"Μηχανοκίνητα"}],"isLive":false}
    ]"""
    @Test fun realShapeParsesAndSortsWithoutUsingLiveFlag() {
        val events = FeedParser.parse(sample)
        assertEquals(2, events.size)
        assertEquals("132468", events.first().id)
        assertEquals("f1", events.first().series)
        assertEquals("2026-10-06T17:30:00Z", events.last().startsAt.toString())
        assertEquals("eurocup", events.last().competitions.first().key)
        assertTrue(Preferences(teams = listOf(TeamRule("ΠΑΟΚ", "basket"))).wantsReminder(events.last()))
    }
    @Test fun missingChannelAndTagsAreAllowed() {
        val events = FeedParser.parse("""[{"id":1,"scheduleDate":"2026-10-05 21:00:00","title":"Αγώνας"}]""")
        assertEquals("other", events.single().sport)
        assertEquals("Κανάλι μη διαθέσιμο", events.single().channel)
    }
    @Test fun newlyIntroducedSportKeepsItsOwnFilter() {
        val events = FeedParser.parse("""[{"id":1,"scheduleDate":"2026-10-05 21:00:00","title":"Αγώνας",
            "tags":[{"name":"climbing","displayName":"Αναρρίχηση"},{"name":"world-cup","displayName":"World Cup 2026-27"}]}]""")
        assertEquals("climbing", events.single().sport)
        assertEquals("Αναρρίχηση", events.single().sportLabel)
        assertEquals(listOf("world cup"), events.single().competitions.map { it.key })
        assertTrue(Preferences(sports = setOf("climbing")).wantsReminder(events.single()))
    }
    @Test fun duplicatesDoNotCreateDuplicateReminders() {
        val row = """{"id":1,"scheduleDate":"2026-10-05 21:00:00","title":"Αγώνας"}"""
        assertEquals(1, FeedParser.parse("[$row,$row]").size)
    }
    @Test(expected = Exception::class) fun changedResponseShapeIsRejected() { FeedParser.parse("""{"events":[]}""") }
    @Test(expected = Exception::class) fun malformedRowRejectsWholeRefresh() {
        FeedParser.parse("""[{"id":1,"scheduleDate":"not-a-date","title":"Αγώνας"}]""")
    }
    @Test fun emptyResponseIsValid() { assertTrue(FeedParser.parse("[]").isEmpty()) }
}
