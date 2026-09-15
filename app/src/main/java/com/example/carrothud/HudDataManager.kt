package com.example.carrothud

import androidx.car.app.Screen

data class HudData(
    val speed: Int = 0,
    val destination: String = "목적지 없음",
    val distance: String = "0 km"
)

object HudDataManager {
    var currentData = HudData()
        private set

    private var activeScreen: Screen? = null

    fun registerScreen(screen: Screen) {
        activeScreen = screen
    }

    fun unregisterScreen() {
        activeScreen = null
    }

    fun updateData(speed: Int, destination: String, distance: String) {
        currentData = HudData(speed, destination, distance)
        // 안드로이드 오토 화면에 재렌더링 요청
        activeScreen?.invalidate()
    }
}
