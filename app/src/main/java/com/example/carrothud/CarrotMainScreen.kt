package com.example.carrothud

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
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

    private var cachedBitmap: Bitmap? = null

    private val renderRunnable = object : Runnable {
        override fun run() {
            if (isRendering) {
                drawScreenToSurface()
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
    }

    private fun drawScreenToSurface() {
        val activity = HudDataManager.activity ?: return
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return

        if (!surface.isValid) return

        val width = container.width
        val height = container.height
        if (width <= 0 || height <= 0) return

        if (cachedBitmap == null || cachedBitmap?.width != width || cachedBitmap?.height != height) {
            recycleBitmap()
            cachedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        val bitmap = cachedBitmap ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PixelCopy.request(
                    activity.window,
                    bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS && isRendering && surface.isValid) {
                            try {
                                val canvas = surface.lockCanvas(null)
                                if (canvas != null) {
                                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                                    surface.unlockCanvasAndPost(canvas)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                    handler
                )
            } catch (e: Exception) {
                e.printStackTrace()
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
