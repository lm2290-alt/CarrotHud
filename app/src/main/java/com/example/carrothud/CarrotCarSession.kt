package com.example.carrothud

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class CarrotCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return CarrotMainScreen(carContext)
    }
}
