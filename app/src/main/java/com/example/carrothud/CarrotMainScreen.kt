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

    private val renderRunnable = object : Runnable {
        override fun run() {
            if (isRendering) {
                drawWebViewToSurface()
                handler.postDelayed(this, 100) // 10 FPS로 안정화
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
                val w = if (webView.width > 0) webView.width else width
                val h = if (webView.height > 0) webView.height else height

                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val bitmapCanvas = Canvas(bitmap)
                webView.draw(bitmapCanvas)

                if (surface.isValid) {
                    val canvas = surface.lockCanvas(null)
                    if (canvas != null) {
                        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                        canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                        surface.unlockCanvasAndPost(canvas)
                        if (scaledBitmap != bitmap) scaledBitmap.recycle()
                    }
                }
                bitmap.recycle()
            } catch (e: Exception) {
                e.printStackTrace() // 예외 발생 시 안드로이드 오토 튕김 방지
            }
        }
    }

    override fun onGetTemplate(): Template {
        return try {
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
        } catch (e: Exception) {
            NavigationTemplate.Builder().build()
        }
    }
}
