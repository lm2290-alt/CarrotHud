package com.example.carrothud

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class CarrotCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        // 1.4.0 정식 라이브러리에 맞춘 디버그 샘플 호스트 허용 설정
        return HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    override fun onCreateSession(): Session {
        return CarrotCarSession()
    }
}
