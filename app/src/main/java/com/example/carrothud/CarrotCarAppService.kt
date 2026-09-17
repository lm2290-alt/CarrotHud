package com.example.carrothud

import android.content.Intent
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

class CarrotCarAppService : CarAppService() {

    companion object {
        private const val TAG = "CarrotAA"
    }

    override fun onCreate() {
        super.onCreate()
        aaLog("CarrotCarAppService.onCreate")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stringWriter = StringWriter()
                throwable.printStackTrace(PrintWriter(stringWriter))
                val crashLog = stringWriter.toString()

                aaLog(
                    "UNCAUGHT thread=${thread.name} " +
                        "${throwable.javaClass.simpleName}: ${throwable.message}\n$crashLog"
                )
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun createHostValidator(): HostValidator {
        aaLog("createHostValidator -> ALLOW_ALL_HOSTS_VALIDATOR")
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        aaLog("onCreateSession")

        return object : Session() {
            override fun onCreateScreen(intent: Intent): CarrotMainScreen {
                aaLog("Session.onCreateScreen intent=$intent")

                val screen = CarrotMainScreen(carContext)

                // CarrotMainScreen �대��먯꽌 �ㅻⅨ Car API 珥덇린�붽� �ㅽ뙣�대룄
                // SurfaceCallback �깅줉源뚯� 媛숈씠 嫄대꼫�곗� �딅룄濡� �쒕퉬�ㅼ뿉��
                // �낅┰�곸쑝濡� Android Auto Surface �곌껐�� 蹂댁옣�쒕떎.
                runCatching {
                    carContext
                        .getCarService(AppManager::class.java)
                        .setSurfaceCallback(screen)
                }.onSuccess {
                    aaLog("AppManager.setSurfaceCallback OK")
                }.onFailure { e ->
                    aaLog(
                        "AppManager.setSurfaceCallback FAILED: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    )
                }

                aaLog("Session.onCreateScreen -> CarrotMainScreen")
                return screen
            }
        }
    }

    private fun aaLog(message: String) {
        Log.i(TAG, message)

        runCatching {
            File(filesDir, "carrot_aa_lifecycle.log")
                .appendText("${Date()}  $message\n")
        }
    }
}
