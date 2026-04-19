package com.lutpiero.rakaah

import org.junit.Assert.assertEquals
import org.junit.Test

class RakaahTrackerTest {

    @Test
    fun `starts from first movement and rakaah one`() {
        val tracker = RakaahTracker()

        assertEquals(Movement.QIYAM, tracker.currentState().movement)
        assertEquals(1, tracker.currentState().rakaahCount)
    }

    @Test
    fun `increments rakaah after one full movement cycle`() {
        val tracker = RakaahTracker()

        repeat(7) {
            tracker.nextState()
        }

        assertEquals(Movement.QIYAM, tracker.currentState().movement)
        assertEquals(2, tracker.currentState().rakaahCount)
    }

    @Test
    fun `reset returns to initial state`() {
        val tracker = RakaahTracker()
        repeat(3) { tracker.nextState() }

        val resetState = tracker.reset()

        assertEquals(Movement.QIYAM, resetState.movement)
        assertEquals(1, resetState.rakaahCount)
    }

    @Test
    fun `peekNextMovement returns next without advancing state`() {
        val tracker = RakaahTracker()

        val next = tracker.peekNextMovement()

        assertEquals(Movement.RUKU, next)
        assertEquals(Movement.QIYAM, tracker.currentState().movement)
    }

    @Test
    fun `full movement cycle peek matches nextState sequence`() {
        val tracker = RakaahTracker()
        // After each peek we advance; at index=6 peek wraps back to index=0 (QIYAM)
        val expectedPeeks = listOf(
            Movement.RUKU, Movement.ITIDAL, Movement.SUJUD,
            Movement.JALSA, Movement.SUJUD, Movement.QIYAM, Movement.QIYAM
        )

        expectedPeeks.forEach { expected ->
            assertEquals(expected, tracker.peekNextMovement())
            tracker.nextState()
        }
    }

    @Test
    fun `movementName on PrayerState returns enum display name`() {
        val state = PrayerState(Movement.ITIDAL, 1)
        assertEquals("I'tidal", state.movementName)
    }
}

class MovementToPhysicalPoseTest {

    @Test
    fun `QIYAM maps to STANDING`() {
        assertEquals(PhysicalPose.STANDING, movementToPhysicalPose(Movement.QIYAM))
    }

    @Test
    fun `ITIDAL maps to STANDING`() {
        assertEquals(PhysicalPose.STANDING, movementToPhysicalPose(Movement.ITIDAL))
    }

    @Test
    fun `RUKU maps to BOWING`() {
        assertEquals(PhysicalPose.BOWING, movementToPhysicalPose(Movement.RUKU))
    }

    @Test
    fun `SUJUD maps to PROSTRATING`() {
        assertEquals(PhysicalPose.PROSTRATING, movementToPhysicalPose(Movement.SUJUD))
    }

    @Test
    fun `JALSA maps to SITTING`() {
        assertEquals(PhysicalPose.SITTING, movementToPhysicalPose(Movement.JALSA))
    }
}
