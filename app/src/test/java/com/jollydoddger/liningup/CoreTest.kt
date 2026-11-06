package com.jollydoddger.liningup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CoreTest {
    private val london = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, london).toInstant().toEpochMilli()

    @Test fun dayRollsOverAtTwoNotMidnight() {
        assertEquals("2026-11-06", Days.key(at(2026, 11, 6, 20), london))
        assertEquals("2026-11-06", Days.key(at(2026, 11, 7, 1, 59), london))
        assertEquals("2026-11-07", Days.key(at(2026, 11, 7, 2, 0), london))
    }

    @Test fun nextReminderIsTonightOrTomorrow() {
        assertEquals(at(2026, 11, 6, 20), Days.nextAt(at(2026, 11, 6, 9), 20, 0, london))
        assertEquals(at(2026, 11, 7, 20), Days.nextAt(at(2026, 11, 6, 20), 20, 0, london))
        assertEquals(at(2026, 11, 7, 20), Days.nextAt(at(2026, 11, 6, 21), 20, 0, london))
    }

    @Test fun nextReminderSurvivesTheClocksChanging() {
        // 25 Oct 2026 is the night the UK clocks go back.
        assertEquals(at(2026, 10, 25, 20), Days.nextAt(at(2026, 10, 24, 21), 20, 0, london))
    }

    @Test fun wireRoundTrips() {
        for (s in listOf(
            Status("me", "🦊", "2026-11-06", true, Choice.TONGUE, 123L),
            Status("me", "🦊", "2026-11-06", true, null, 124L),
            Status("me", "🐻", "2026-11-06", false, null, 125L),
            Status("me", "🐻", "2026-11-06", null, null, 126L),
        )) assertEquals(s, Status.parse(s.wire()))
    }

    @Test fun parseRefusesNonsense() {
        assertNull(Status.parse(null))
        assertNull(Status.parse("hello"))
        assertNull(Status.parse("v2\ta\tb\tc\t1\t-\t1"))
        assertNull(Status.parse("v1\ta\t🦊\t2026-11-06\t7\t-\t1"))
    }

    @Test fun aRefusalCarriesNoChoice() {
        val s = Status.parse("v1\ta\t🦊\t2026-11-06\t0\tEGGPLANT\t1")!!
        assertNull(s.what)
        assertEquals("❌", s.emojis)
    }

    @Test fun sealedMessagesOpenOnlyWithTheSameCode() {
        val code = Pairing.newCode()
        val a = Couple(code)
        val b = Couple(Pairing.pretty(code).lowercase())
        assertEquals(a.topic, b.topic)
        val sealed = a.seal("✅🍆")
        assertNotEquals("✅🍆", sealed)
        assertFalse(sealed.contains("🍆"))
        assertEquals("✅🍆", b.open(sealed))
        assertNull(Couple(Pairing.newCode()).open(sealed))
        assertNull(a.open("not sealed at all"))
    }

    @Test fun twoSealsOfTheSameThingDiffer() {
        val c = Couple(Pairing.newCode())
        assertNotEquals(c.seal("x"), c.seal("x"))
    }

    @Test fun topicDoesNotRevealTheCode() {
        val code = Pairing.newCode()
        val c = Couple(code)
        assertFalse(c.topic.uppercase().contains(code.take(6)))
        assertTrue(c.topic.matches(Regex("lu-[0-9a-f]{32}")))
    }

    @Test fun pastedCodesAreCleanedUp() {
        val code = Pairing.newCode()
        assertEquals(code, Pairing.normalise("  " + Pairing.pretty(code).lowercase() + " \n"))
        assertEquals(24, Pairing.pretty(code).length)
        assertEquals(code, Pairing.normalise("Lining up 🌙 " + Pairing.pretty(code)))
        assertEquals(code, Pairing.normalise("lining up\n" + Pairing.pretty(code).replace("-", " ").lowercase()))
        assertTrue(Pairing.isValid(Pairing.pretty(code)))
        assertFalse(Pairing.isValid("ABCD"))
    }

    @Test fun partnerIsTheNewestTapFromSomebodyElseToday() {
        val day = "2026-11-06"
        val all = listOf(
            Status("me", "🦊", day, true, null, 50),
            Status("them", "🐻", day, false, null, 10),
            Status("them", "🐻", day, true, Choice.EGGPLANT, 30),
            Status("them", "🐻", "2026-11-05", true, null, 99),
        )
        assertEquals(30L, Statuses.partner(all, "me", day)!!.at)
        assertEquals(50L, Statuses.mine(all, "me", day)!!.at)
        assertNull(Statuses.partner(all, "me", "2026-11-07"))
    }

    @Test fun takingItBackHidesIt() {
        val day = "2026-11-06"
        val all = listOf(
            Status("them", "🐻", day, true, null, 10),
            Status("them", "🐻", day, null, null, 20),
        )
        assertEquals("", Statuses.partner(all, "me", day)!!.emojis)
    }

    @Test fun matchNeedsBothTicksToday() {
        val d = "2026-11-06"
        val yes = Status("a", "", d, true, null, 1)
        val yes2 = Status("b", "", d, true, Choice.TONGUE, 2)
        val no = Status("b", "", d, false, null, 2)
        assertTrue(Statuses.isMatch(yes, yes2, d))
        assertFalse(Statuses.isMatch(yes, no, d))
        assertFalse(Statuses.isMatch(yes, null, d))
        assertFalse(Statuses.isMatch(yes, yes2, "2026-11-07"))
    }

    @Test fun republishOnlyTodaysAndOnlyWhenStale() {
        val d = "2026-11-06"
        val s = Status("a", "", d, true, null, 0)
        val h = 60 * 60 * 1000L
        assertFalse(Statuses.shouldRepublish(s, d, lastPublishedAt = 0, nowMs = h))
        assertTrue(Statuses.shouldRepublish(s, d, lastPublishedAt = 0, nowMs = 3 * h))
        assertFalse(Statuses.shouldRepublish(s, "2026-11-07", 0, 9 * h))
        assertFalse(Statuses.shouldRepublish(null, d, 0, 9 * h))
    }
}
