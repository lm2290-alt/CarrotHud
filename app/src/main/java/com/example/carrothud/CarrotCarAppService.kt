package com.example.carrothud

import android.content.Intent
import android.os.Environment
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class CarrotCarAppService : CarAppService() {

    override fun onCreate() {
        super.onCreate()

        // 미처리 예외(Crash) 발생 시 [다운로드] 폴더의 carrot_crash.txt 로 저장
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stringWriter = StringWriter()
                throwable.printStackTrace(PrintWriter(stringWriter))
                val crashLog = stringWriter.toString()

                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val logFile = File(downloadDir, "carrot_crash.txt")
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
