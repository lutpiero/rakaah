package com.lutpiero.rakaah

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val tracker = RakaahTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val movementText = findViewById<TextView>(R.id.movementName)
        val rakaahText = findViewById<TextView>(R.id.rakaahCount)
        val nextButton = findViewById<Button>(R.id.nextMovementButton)
        val resetButton = findViewById<Button>(R.id.resetButton)

        renderState(movementText, rakaahText, tracker.currentState())

        nextButton.setOnClickListener {
            renderState(movementText, rakaahText, tracker.nextState())
        }

        resetButton.setOnClickListener {
            renderState(movementText, rakaahText, tracker.reset())
        }
    }

    private fun renderState(
        movementText: TextView,
        rakaahText: TextView,
        state: PrayerState
    ) {
        movementText.text = state.movementName
        rakaahText.text = getString(R.string.rakaah_count_format, state.rakaahCount)
    }
}
