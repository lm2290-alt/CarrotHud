package com.example.carrothud

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class CarrotCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        // 개발/디버그용: 모든 호스트 접속 허용
        return HostValidator.ALLOW_ALL_HOSTS_DEBUG
    }

    override fun onCreateSession(): Session {
        return CarrotCarSession()
    }
}
