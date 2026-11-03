package gr.matchbell.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class OptimizationTest {
    private fun at(value: String): Instant = LocalDateTime.parse(value).atZone(Athens).toInstant()
    private val now = at("2026-10-07T12:00:00")
    private fun game(id: String = "1", start: Instant = at("2026-10-07T21:00:00")) =
        Broadcast(id, start, "ΠΑΟΚ - ΑΕΚ", "ERT", listOf(Tag("basket", "Μπάσκετ")))
    private val prefs = Preferences(sports = setOf("basket"))

    @Test fun oneHundredSimultaneousRemindersNeedOneAlarm() {
        val events = (1..100).map { game(it.toString()) }
        val batches = batchReminders(planReminders(events, prefs, now))
        assertEquals(1, batches.size)
        assertEquals(100, batches.single().tokens.size)
    }
    @Test fun twoOffsetsKeepTwoDistinctWakeups() {
        val batches = batchReminders(planReminders(listOf(game("1"), game("2")), prefs.copy(offsets = setOf(15, 30)), now))
        assertEquals(listOf(at("2026-10-07T20:30:00").toEpochMilli(), at("2026-10-07T20:45:00").toEpochMilli()), batches.map { it.atMillis })
        assertEquals(listOf(2, 2), batches.map { it.tokens.size })
    }
    @Test fun batchSignaturesSurviveSourceOrderingChanges() {
        val reminders = planReminders(listOf(game("1"), game("2")), prefs, now)
        val first = batchReminders(reminders).single()
        assertEquals(first, batchReminders(reminders.reversed()).single())
        assertEquals(AlarmPayload.encode(first.tokens), AlarmPayload.encode(batchReminders(reminders.reversed()).single().tokens))
    }
    @Test fun duplicateReminderCannotCreateDuplicatePayload() {
        val reminder = planReminders(listOf(game()), prefs, now).single()
        assertEquals(1, batchReminders(listOf(reminder, reminder)).single().tokens.size)
    }
    @Test fun unchangedPlanPerformsNoAlarmOperations() {
        val registry = mapOf("one" to "true|payload", "two" to "true|second")
        assertEquals(AlarmChanges(emptySet(), emptySet()), alarmChanges(registry, registry))
    }
    @Test fun changedStartRemovesOldTimeAndSchedulesNewTime() {
        assertEquals(AlarmChanges(setOf("old"), setOf("new")), alarmChanges(mapOf("old" to "x"), mapOf("new" to "y")))
    }
    @Test fun removingOneEventOnlyUpdatesItsSharedBucket() {
        assertEquals(AlarmChanges(emptySet(), setOf("shared")), alarmChanges(
            mapOf("shared" to "one,two", "unrelated" to "three"), mapOf("shared" to "one", "unrelated" to "three")))
    }
    @Test fun rebootRestoresEvenIdenticalRegistrations() {
        val registry = mapOf("one" to "true|payload", "two" to "true|second")
        assertEquals(registry.keys, alarmChanges(registry, registry, force = true).schedule)
    }
    @Test fun exactPermissionChangeReplacesAlarmMode() {
        assertEquals(setOf("one"), alarmChanges(mapOf("one" to "false|payload"), mapOf("one" to "true|payload")).schedule)
    }
    @Test fun pausingCancelsEveryScheduledAlarm() {
        assertEquals(AlarmChanges(setOf("one", "two"), emptySet()), alarmChanges(mapOf("one" to "a", "two" to "b"), emptyMap()))
    }
    @Test fun appearanceStarsAndRefreshIntervalDoNotAffectAlarms() {
        assertEquals(prefs.reminderSettings(), prefs.copy(theme = "light", favorites = setOf("1"), refreshHours = 12).reminderSettings())
        assertNotEquals(prefs.reminderSettings(), prefs.copy(offsets = setOf(60)).reminderSettings())
    }
    @Test fun noSelectionsOrPauseNeedsNoBackgroundDownload() {
        assertFalse(backgroundSyncNeeded(Preferences(), listOf(game()), now))
        assertFalse(backgroundSyncNeeded(prefs.copy(enabled = false), listOf(game()), now))
        assertFalse(backgroundSyncNeeded(prefs.copy(offsets = emptySet()), listOf(game()), now))
    }
    @Test fun followedTeamNeedsSyncEvenIfAbsentFromCurrentWeek() {
        assertTrue(backgroundSyncNeeded(Preferences(teams = listOf(TeamRule("ΠΑΟΚ"))), emptyList(), now))
        assertTrue(backgroundSyncNeeded(Preferences(competitions = setOf("euroleague")), emptyList(), now))
    }
    @Test fun manualOnlySyncStopsAfterEventExpiresOrIsMuted() {
        val manual = Preferences(manual = setOf("1"))
        assertTrue(backgroundSyncNeeded(manual, listOf(game()), now))
        assertFalse(backgroundSyncNeeded(manual, listOf(game(start = now.minusSeconds(1))), now))
        assertFalse(backgroundSyncNeeded(manual.copy(muted = setOf("1")), listOf(game()), now))
        assertFalse(backgroundSyncNeeded(manual, emptyList(), now))
    }
    @Test fun starsAloneDoNotEnableBackgroundWork() {
        assertFalse(backgroundSyncNeeded(Preferences(favorites = setOf("1")), listOf(game()), now))
    }
    @Test fun freshCacheSkipsNetworkUntilChosenInterval() {
        assertFalse(needsRefresh(now, now.plusSeconds(3 * 3600 - 1), 3))
        assertTrue(needsRefresh(now, now.plusSeconds(3 * 3600), 3))
        assertTrue(needsRefresh(null, now, 3))
    }
    @Test fun newAthensDayRefreshesEvenWhenUtcDateIsUnchanged() {
        val previous = at("2026-10-07T23:59:00")
        assertTrue(needsRefresh(previous, at("2026-10-08T00:01:00"), 12))
    }
    @Test fun clockMovingBackwardsCannotFreezeCache() {
        assertTrue(needsRefresh(now, now.minusSeconds(60), 12))
    }
    @Test fun payloadRoundTripRetainsSourceVersionAndMultipleOffsets() {
        val tokens = listOf(ReminderToken("id:/μετάδοση", 30, game().startsAt.toEpochMilli()), ReminderToken("2", 0, game().startsAt.toEpochMilli()))
        assertEquals(tokens, AlarmPayload.decode(AlarmPayload.encode(tokens)))
    }
    @Test fun corruptOrInvalidPayloadDoesNotDeliver() {
        assertTrue(AlarmPayload.decode("bad json").isEmpty())
        assertTrue(AlarmPayload.decode("[{\"id\":\"1\",\"minutes\":-1,\"start\":100}]").isEmpty())
    }
    @Test fun changingEventByCopyRebuildsSearchAndTeamIndexes() {
        val first = game()
        val updated = first.copy(title = "Ολυμπιακός - Άρης", channel = "Cosmote")
        assertTrue(teamMatches(TeamRule("ΠΑΟΚ"), first))
        assertFalse(teamMatches(TeamRule("ΠΑΟΚ"), updated))
        assertTrue(updated.searchText.contains("cosmote"))
        assertFalse(first.searchText.contains("cosmote"))
    }
    @Test fun rollingWindowRefillsWithoutReschedulingUnrelatedBuckets() {
        val events = (1..450).map { game(it.toString(), now.plusSeconds(7200 + it * 60L)) }
        val original = activeAlarmBatches(planReminders(events, prefs, now))
        assertEquals(400, original.size)
        val later = activeAlarmBatches(planReminders(events, prefs, Instant.ofEpochMilli(original.first().atMillis + 1)))
        assertEquals(400, later.size)
        fun registry(batches: List<AlarmBatch>) = batches.associate { it.key to AlarmPayload.encode(it.tokens) }
        val change = alarmChanges(registry(original), registry(later))
        assertEquals(setOf(original.first().key), change.cancel)
        assertEquals(setOf(later.last().key), change.schedule)
    }

}
