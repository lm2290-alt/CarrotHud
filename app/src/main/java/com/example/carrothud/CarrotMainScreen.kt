package com.example.carrothud

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

class CarrotMainScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val row = Row.Builder()
            .setTitle("CarrotHUD 연동 완료")
            .addText("안드로이드 오토 대시보드 서비스가 정상 실행 중입니다.")
            .build()

        val list = ItemList.Builder()
            .addItem(row)
            .build()

        return ListTemplate.Builder()
            .setTitle("CarrotHUD")
            .setSingleList(list)
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
