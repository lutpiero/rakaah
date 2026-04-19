package com.lutpiero.rakaah

import org.junit.Assert.assertEquals
import org.junit.Test

class RakaahTrackerTest {

    @Test
    fun `starts from first movement and rakaah one`() {
        val tracker = RakaahTracker()

        assertEquals("Qiyam", tracker.currentState().movementName)
        assertEquals(1, tracker.currentState().rakaahCount)
    }

    @Test
    fun `increments rakaah after one full movement cycle`() {
        val tracker = RakaahTracker()

        repeat(7) {
            tracker.nextState()
        }

        assertEquals("Qiyam", tracker.currentState().movementName)
        assertEquals(2, tracker.currentState().rakaahCount)
    }

    @Test
    fun `reset returns to initial state`() {
        val tracker = RakaahTracker()
        repeat(3) { tracker.nextState() }

        val resetState = tracker.reset()

        assertEquals("Qiyam", resetState.movementName)
        assertEquals(1, resetState.rakaahCount)
    }
}
