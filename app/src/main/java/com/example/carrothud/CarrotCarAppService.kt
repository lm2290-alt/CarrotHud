package com.example.carrothud

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class CarrotCarAppService : CarAppService() {

    override fun onCreate() {
        super.onCreate()

        // 앱 튕김 발생 시 /Android/data/com.example.carrothud/files/carrot_crash.txt 파일로 에러 로그 자동 저장
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stringWriter = StringWriter()
                throwable.printStackTrace(PrintWriter(stringWriter))
                val crashLog = stringWriter.toString()

                val logFile = File(getExternalFilesDir(null), "carrot_crash.txt")
                logFile.writeText("=== Crash Time: ${java.util.Date()} ===\n\n$crashLog")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent) = CarrotMainScreen(carContext)
        }
    }
}
