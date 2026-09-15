package com.example.carrothud

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CarrotMainScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // 화면 생명주기에 맞춰 매니저 등록/해제
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                HudDataManager.registerScreen(this@CarrotMainScreen)
            }

            override fun onStop(owner: LifecycleOwner) {
                HudDataManager.unregisterScreen()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val data = HudDataManager.currentData

        val row = Row.Builder()
            .setTitle("현재 속도: ${data.speed} km/h")
            .addText("목적지: ${data.destination} (남은 거리: ${data.distance})")
            .build()

        val list = ItemList.Builder()
            .addItem(row)
            .build()

        return ListTemplate.Builder()
            .setTitle("CarrotHUD 실시간 주행정보")
            .setSingleList(list)
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
