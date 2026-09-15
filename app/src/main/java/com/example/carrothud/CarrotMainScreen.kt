package com.example.carrothud

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
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
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid) return

        val width = container.width
        val height = container.height
        if (width <= 0 || height <= 0) return

        val activity = HudDataManager.activity

        // Activity가 없거나 화면이 꺼졌을 때 오토가 튕기지 않도록 대기 텍스트 출력
        if (activity == null || activity.isFinishing || activity.isDestroyed || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            drawFallbackText(surface, width, height, "휴대폰 CarrotHUD 앱을 켜주세요")
            return
        }

        val decorView = activity.window?.decorView
        if (decorView == null || !decorView.isAttachedToWindow) {
            drawFallbackText(surface, width, height, "화면 연결 대기 중...")
            return
        }

        if (cachedBitmap == null || cachedBitmap?.width != width || cachedBitmap?.height != height) {
            recycleBitmap()
            cachedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        val bitmap = cachedBitmap ?: return

        try {
            PixelCopy.request(
                activity.window,
                bitmap,
                { copyResult ->
                    if (isRendering && surface.isValid) {
                        if (copyResult == PixelCopy.SUCCESS) {
                            renderBitmapToSurface(surface, bitmap)
                        } else {
                            drawFallbackText(surface, width, height, "휴대폰 화면을 켜두세요")
                        }
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            drawFallbackText(surface, width, height, "실시간 화면 수신 중...")
        }
    }

    private fun renderBitmapToSurface(surface: android.view.Surface, bitmap: Bitmap) {
        runCatching {
            val canvas = surface.lockCanvas(null) ?: return@runCatching
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawFallbackText(surface: android.view.Surface, width: Int, height: Int, text: String) {
        runCatching {
            if (!surface.isValid) return@runCatching
            val canvas = surface.lockCanvas(null) ?: return@runCatching
            canvas.drawColor(Color.BLACK)

            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 36f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText(text, width / 2f, height / 2f, paint)
            surface.unlockCanvasAndPost(canvas)
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
