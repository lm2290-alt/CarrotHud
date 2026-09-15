package com.example.carrothud

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
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
                try {
                    drawScreenToSurface()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
                handler.postDelayed(this, 100) // 10 FPS
            }
        }
    }

    init {
        try {
            carContext.getCarService(androidx.car.app.AppManager::class.java)
                .setSurfaceCallback(this)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = surfaceContainer
        isRendering = true
        handler.removeCallbacks(renderRunnable)
        handler.post(renderRunnable)
    }

    override fun onSurfaceVisible(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = surfaceContainer
        isRendering = true
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        isRendering = false
        this.surfaceContainer = null
        handler.removeCallbacks(renderRunnable)
        recycleBitmap()
    }

    private fun recycleBitmap() {
        try {
            cachedBitmap?.recycle()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
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

        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            drawMessageOnSurface(surface, width, height, "스마트폰에서 CarrotHUD 앱을 실행해 주세요")
            return
        }

        val window = try { activity.window } catch (t: Throwable) { null }
        if (window == null) {
            drawMessageOnSurface(surface, width, height, "화면 연결 준비 중...")
            return
        }

        if (cachedBitmap == null || cachedBitmap?.width != width || cachedBitmap?.height != height) {
            recycleBitmap()
            try {
                cachedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                return
            }
        }

        val bitmap = cachedBitmap ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PixelCopy.request(
                    window,
                    bitmap,
                    { copyResult ->
                        try {
                            if (isRendering && surface.isValid) {
                                if (copyResult == PixelCopy.SUCCESS) {
                                    copyBitmapToSurface(surface, bitmap, width, height)
                                } else {
                                    drawMessageOnSurface(surface, width, height, "스마트폰 화면을 켜주세요")
                                }
                            }
                        } catch (t: Throwable) {
                            t.printStackTrace()
                        }
                    },
                    handler
                )
            } catch (t: Throwable) {
                drawMessageOnSurface(surface, width, height, "화면 캡처 중...")
            }
        } else {
            drawMessageOnSurface(surface, width, height, "지원되지 않는 안드로이드 버전입니다")
        }
    }

    private fun copyBitmapToSurface(surface: Surface, bitmap: Bitmap, destWidth: Int, destHeight: Int) {
        var canvas: android.graphics.Canvas? = null
        try {
            if (!surface.isValid) return
            canvas = surface.lockCanvas(null)
            if (canvas != null) {
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val destRect = Rect(0, 0, destWidth, destHeight)
                canvas.drawBitmap(bitmap, srcRect, destRect, null)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }

    private fun drawMessageOnSurface(surface: Surface, width: Int, height: Int, message: String) {
        var canvas: android.graphics.Canvas? = null
        try {
            if (!surface.isValid) return
            canvas = surface.lockCanvas(null)
            if (canvas != null) {
                canvas.drawColor(Color.BLACK)
                val paint = Paint().apply {
                    color = Color.WHITE
                    textSize = 40f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                canvas.drawText(message, width / 2f, height / 2f, paint)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
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
        } catch (t: Throwable) {
            PaneTemplate.Builder(
                Pane.Builder()
                    .addRow(Row.Builder().setTitle("CarrotHUD 실행 중").build())
                    .build()
            ).setTitle("CarrotHUD").build()
        }
    }
}
