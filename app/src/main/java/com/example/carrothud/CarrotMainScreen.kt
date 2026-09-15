package com.example.carrothud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

class CarrotMainScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var surfaceContainer: SurfaceContainer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRendering = false

    private val renderRunnable = object : Runnable {
        override fun run() {
            if (isRendering) {
                drawWebViewToSurface()
                handler.postDelayed(this, 50) // 안정적인 20 FPS 주기
            }
        }
    }

    init {
        try {
            carContext.getCarService(androidx.car.app.AppManager::class.java)
                .setSurfaceCallback(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = surfaceContainer
        isRendering = true
        handler.removeCallbacks(renderRunnable)
        handler.post(renderRunnable)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        isRendering = false
        this.surfaceContainer = null
        handler.removeCallbacks(renderRunnable)
    }

    private fun drawWebViewToSurface() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        val webView = HudDataManager.webView ?: return

        if (!surface.isValid) return

        val width = container.width
        val height = container.height
        if (width <= 0 || height <= 0) return

        webView.post {
            if (!isRendering || surfaceContainer == null) return@post

            try {
                // 웹뷰 레이아웃 크기 맞춤
                if (webView.width != width || webView.height != height) {
                    webView.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, width, height)
                }

                // 비트맵 안전 캡처 후 차량 화면에 출력 (크래시 완전 방지)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val bitmapCanvas = Canvas(bitmap)
                webView.draw(bitmapCanvas)

                if (surface.isValid) {
                    val canvas = surface.lockCanvas(null)
                    if (canvas != null) {
                        canvas.drawBitmap(bitmap, 0f, 0f, null)
                        surface.unlockCanvasAndPost(canvas)
                    }
                }
                bitmap.recycle()
            } catch (e: Exception) {
                // 서페이스 해제 직후 렌더링 예외 시 오토 강제종료 방지
                e.printStackTrace()
            }
        }
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder().build()
    }
}
