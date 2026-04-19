package com.lutpiero.rakaah

data class PrayerState(
    val movementName: String,
    val rakaahCount: Int
)

class RakaahTracker {
    private val movementCycle = listOf(
        "Qiyam",
        "Ruku",
        "I'tidal",
        "Sujud",
        "Jalsa",
        "Sujud",
        "Qiyam"
    )

    private var movementIndex = 0
    private var rakaahCount = 1

    fun currentState(): PrayerState = PrayerState(
        movementName = movementCycle[movementIndex],
        rakaahCount = rakaahCount
    )

    /** Returns the movement name that would result from calling [nextState]. */
    fun peekNextMovementName(): String {
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
