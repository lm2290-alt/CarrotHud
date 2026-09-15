package com.example.carrothud

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.CarContext
import androidx.car.app.model.*
import androidx.car.app.validation.HostValidator

class CarrotCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_VALIDATORS_FOR_DEBUG
    }

    override fun onCreateSession(): Session {
        return CarrotCarSession()
    }
}

class CarrotCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return CarrotMainScreen(carContext)
    }
}

class CarrotMainScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return PaneTemplate.Builder(
            Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle("CarrotHUD 웹뷰 연동")
                        .addText("콤마 7000번 포트 탐색 및 연결 대기 중...")
                        .build()
                )
                .build()
        ).setTitle("CarrotHUD").build()
    }
}

