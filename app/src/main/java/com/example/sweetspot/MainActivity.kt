package com.example.sweetspot

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            text = """
                SweetSpot

                Audio tuning service

                Initializing...
            """.trimIndent()

            textSize = 28f
            gravity = Gravity.CENTER

            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)

            setPadding(48, 48, 48, 48)
        }

        setContentView(status)
    }
}