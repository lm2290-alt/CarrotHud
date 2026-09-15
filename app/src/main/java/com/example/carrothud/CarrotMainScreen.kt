package com.example.carrothud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

class CarrotMainScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var surfaceContainer: SurfaceContainer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRendering = false

    // 메모리 누수 방지용 캐시 버퍼
    private var cachedBitmap: Bitmap? = null
    private var cachedCanvas: Canvas? = null

    private val renderRunnable = object : Runnable {
        override fun run() {
            if (isRendering) {
                drawWebViewToSurface()
                handler.postDelayed(this, 100) // 10 FPS
            }
        }
    }

    init {
        runCatching {
            carContext.getCarService(androidx.car.app.AppManager::class.java)
                .setSurfaceCallback(this)
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
        recycleBitmap()
    }

    private fun recycleBitmap() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedCanvas = null
    }

    private fun drawWebViewToSurface() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        val webView = HudDataManager.webView ?: return

        if (!surface.isValid) return

        val width = container.width
        val height = container.height
        if (width <= 0 || height <= 0) return

        handler.post {
            if (!isRendering || surfaceContainer == null || !surface.isValid) return@post

            try {
                // 비트맵 재사용 (메모리 튕김 완벽 차단)
                if (cachedBitmap == null || cachedBitmap?.width != width || cachedBitmap?.height != height) {
                    recycleBitmap()
                    cachedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    cachedCanvas = Canvas(cachedBitmap!!)
                }

                val bitmap = cachedBitmap ?: return@post
                val canvasObj = cachedCanvas ?: return@post

                webView.draw(canvasObj)

                if (surface.isValid) {
                    val surfaceCanvas = surface.lockCanvas(null)
                    if (surfaceCanvas != null) {
                        surfaceCanvas.drawBitmap(bitmap, 0f, 0f, null)
                        surface.unlockCanvasAndPost(surfaceCanvas)
                    }
                }
            } catch (t: Throwable) {
                // 어떤 예외/오류가 발생해도 안드로이드 오토 튕김 방지
                t.printStackTrace()
            }
        }
    }

    override fun onGetTemplate(): Template {
        return runCatching {
            NavigationTemplate.Builder()
                .setActionStrip(
                    ActionStrip.Builder()
                        .addAction(
                            Action.Builder()
                                .setTitle("새로고침")
                                .setOnClickListener { invalidate() }
                                .build()
                        )
                        .build()
                )
                .build()
        }.getOrElse {
            NavigationTemplate.Builder().build()
        }
    }
}
