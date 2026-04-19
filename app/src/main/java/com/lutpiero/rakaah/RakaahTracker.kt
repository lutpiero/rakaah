package com.lutpiero.rakaah

data class PrayerState(
    val movement: Movement,
    val rakaahCount: Int
) {
    val movementName: String get() = movement.displayName
}

class RakaahTracker {
    private val movementCycle = listOf(
        Movement.QIYAM,
        Movement.RUKU,
        Movement.ITIDAL,
        Movement.SUJUD,
        Movement.JALSA,
        Movement.SUJUD,
        Movement.QIYAM
    )

    private var movementIndex = 0
    private var rakaahCount = 1

    fun currentState(): PrayerState = PrayerState(
        movement = movementCycle[movementIndex],
        rakaahCount = rakaahCount
    )

    /** Returns the [Movement] that would result from calling [nextState], without advancing. */
    fun peekNextMovement(): Movement {
        val nextIndex = (movementIndex + 1) % movementCycle.size
        return movementCycle[nextIndex]
    }

    fun nextState(): PrayerState {
        movementIndex = (movementIndex + 1) % movementCycle.size
        if (movementIndex == 0) {
            rakaahCount++
        }
        return currentState()
    }

    fun reset(): PrayerState {
        movementIndex = 0
        rakaahCount = 1
        return currentState()
    }
}
