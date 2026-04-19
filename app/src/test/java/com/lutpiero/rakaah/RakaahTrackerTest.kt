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

    @Test
    fun `peekNextMovementName returns next without advancing state`() {
        val tracker = RakaahTracker()

        val next = tracker.peekNextMovementName()

        assertEquals("Ruku", next)
        assertEquals("Qiyam", tracker.currentState().movementName)
    }

    @Test
    fun `full movement cycle peek matches nextState sequence`() {
        val tracker = RakaahTracker()
        // After each peek we advance; the 7th peek (index=6) still returns "Qiyam" (wraps to 0)
        val expectedPeeks = listOf("Ruku", "I'tidal", "Sujud", "Jalsa", "Sujud", "Qiyam", "Qiyam")

        expectedPeeks.forEach { expected ->
            assertEquals(expected, tracker.peekNextMovementName())
            tracker.nextState()
        }
    }
}

class MovementToPhysicalPoseTest {

    @Test
    fun `Qiyam maps to STANDING`() {
        assertEquals(PhysicalPose.STANDING, movementToPhysicalPose("Qiyam"))
    }

    @Test
    fun `Itidal maps to STANDING`() {
        assertEquals(PhysicalPose.STANDING, movementToPhysicalPose("I'tidal"))
    }

    @Test
    fun `Ruku maps to BOWING`() {
        assertEquals(PhysicalPose.BOWING, movementToPhysicalPose("Ruku"))
    }

    @Test
    fun `Sujud maps to PROSTRATING`() {
        assertEquals(PhysicalPose.PROSTRATING, movementToPhysicalPose("Sujud"))
    }

    @Test
    fun `Jalsa maps to SITTING`() {
        assertEquals(PhysicalPose.SITTING, movementToPhysicalPose("Jalsa"))
    }
}
