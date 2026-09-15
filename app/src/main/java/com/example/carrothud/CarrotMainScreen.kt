package com.example.carrothud

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
            drawWebViewToSurface()
            if (isRendering) {
                handler.postDelayed(this, 33)
            }
        }
    }

    init {
        carContext.getCarService(androidx.car.app.AppManager::class.java)
            .setSurfaceCallback(this)
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = surfaceContainer
        isRendering = true
        handler.post(renderRunnable)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = null
        isRendering = false
        handler.removeCallbacks(renderRunnable)
    }

    private fun drawWebViewToSurface() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        val webView = HudDataManager.webView ?: return

        if (!surface.isValid) return

        try {
            val width = container.width
            val height = container.height
            if (width <= 0 || height <= 0) return

            webView.post {
                try {
                    webView.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, width, height)

                    val canvas = surface.lockCanvas(null)
                    webView.draw(canvas)
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder().build()
    }
}
