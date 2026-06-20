package com.utopia.racechronobridge

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = "RaceChrono Bridge"
            gravity = Gravity.CENTER
            textSize = 22f
        }

        setContentView(textView)
    }
}
