package com.tahsin.app.data.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakTest {

    private val today = 20_000L

    @Test
    fun `no activity means no streak`() {
        val streak = UserRepository.computeStreak(emptyList(), today)
        assertEquals(0, streak.currentStreak)
        assertEquals(0, streak.longestStreak)
        assertFalse(streak.activeToday)
    }

    @Test
    fun `consecutive days ending today count`() {
        val streak = UserRepository.computeStreak(listOf(today - 2, today - 1, today), today)
        assertEquals(3, streak.currentStreak)
        assertTrue(streak.activeToday)
    }

    @Test
    fun `an untouched today does not break a streak that ran through yesterday`() {
        val streak = UserRepository.computeStreak(listOf(today - 3, today - 2, today - 1), today)
        assertEquals(3, streak.currentStreak)
        assertFalse(streak.activeToday)
    }

    @Test
    fun `a gap of two days ends the streak`() {
        val streak = UserRepository.computeStreak(listOf(today - 5, today - 4, today - 3), today)
        assertEquals(0, streak.currentStreak)
        assertEquals(3, streak.longestStreak)
    }

    @Test
    fun `longest streak survives later gaps`() {
        val days = listOf(today - 20, today - 19, today - 18, today - 17, today - 1, today)
        val streak = UserRepository.computeStreak(days, today)
        assertEquals(2, streak.currentStreak)
        assertEquals(4, streak.longestStreak)
    }

    @Test
    fun `a single day today is a one day streak`() {
        val streak = UserRepository.computeStreak(listOf(today), today)
        assertEquals(1, streak.currentStreak)
        assertEquals(1, streak.longestStreak)
    }
}
