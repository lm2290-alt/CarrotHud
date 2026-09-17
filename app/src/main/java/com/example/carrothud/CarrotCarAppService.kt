package com.example.carrothud

import android.content.Intent
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class CarrotCarAppService : CarAppService() {

    companion object {
        private const val TAG = "CarrotAA"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CarrotCarAppService.onCreate")
    }

    override fun createHostValidator(): HostValidator {
        Log.i(TAG, "createHostValidator")
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        Log.i(TAG, "onCreateSession")

        return object : Session() {
            override fun onCreateScreen(intent: Intent): CarrotMainScreen {
                Log.i(TAG, "Session.onCreateScreen")
                return CarrotMainScreen(carContext)
            }
        }
    }
}
