package com.example.carrothud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections

class CarrotMainScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var surfaceContainer: SurfaceContainer? = null
    @Volatile private var isRendering = false
    private var streamJob: Job? = null

    init {
        runCatching {
            carContext.getCarService(androidx.car.app.AppManager::class.java)
                .setSurfaceCallback(this)
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = surfaceContainer
        isRendering = true
        startStreamingPipeline()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        isRendering = false
        streamJob?.cancel()
        this.surfaceContainer = null
    }

    private fun startStreamingPipeline() {
        streamJob?.cancel()
        streamJob = CoroutineScope(Dispatchers.IO).launch {
            drawMessage("콤마4 탐색 중...")
            val commaIp = findCommaDeviceIp()

            if (commaIp == null) {
                drawMessage("콤마4(포트 7000)를 찾지 못함")
                delay(2000)
                startStreamingPipeline()
                return@launch
            }

            drawMessage("영상 스트리밍 연결 중...")
            connectAndStream("http://$commaIp:7000")
        }
    }

    private suspend fun connectAndStream(urlStr: String) {
        while (isRendering) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection()
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                val inputStream = conn.getInputStream()

                val buffer = ByteArrayOutputStream()
                val readBuffer = ByteArray(8192)
                var inJpeg = false
                var lastByte = -1

                while (isRendering) {
                    val bytesRead = inputStream.read(readBuffer)
                    if (bytesRead == -1) break

                    for (i in 0 until bytesRead) {
                        val currentByte = readBuffer[i].toInt() and 0xFF

                        if (!inJpeg && lastByte == 0xFF && currentByte == 0xD8) {
                            buffer.reset()
                            buffer.write(0xFF)
                            buffer.write(0xD8)
                            inJpeg = true
                        } else if (inJpeg) {
                            buffer.write(currentByte)
                            if (lastByte == 0xFF && currentByte == 0xD9) {
                                inJpeg = false
                                val bytes = buffer.toByteArray()
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    drawBitmapToSurface(bitmap)
                                }
                            }
                        }
                        lastByte = currentByte
                    }
                }
            } catch (e: Exception) {
                saveCustomLog("Stream Exception: ${e.localizedMessage}")
                drawMessage("영상 재연결 중...")
                delay(2000)
            }
        }
    }

    private fun drawBitmapToSurface(bitmap: Bitmap) {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid || !isRendering) return

        var canvas: android.graphics.Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            if (canvas != null) {
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val destRect = Rect(0, 0, container.width, container.height)
                canvas.drawBitmap(bitmap, srcRect, destRect, null)
            }
        } catch (t: Throwable) {
            saveCustomLog("Draw Canvas Crash: ${t.localizedMessage}")
        } finally {
            if (canvas != null) {
                runCatching { surface.unlockCanvasAndPost(canvas) }
            }
        }
    }

    private fun drawMessage(message: String) {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid) return

        var canvas: android.graphics.Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            if (canvas != null) {
                canvas.drawColor(Color.BLACK)
                val paint = Paint().apply {
                    color = Color.WHITE
                    textSize = 32f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                canvas.drawText(message, container.width / 2f, container.height / 2f, paint)
            }
        } catch (t: Throwable) {
            saveCustomLog("Message Canvas Crash: ${t.localizedMessage}")
        } finally {
            if (canvas != null) {
                runCatching { surface.unlockCanvasAndPost(canvas) }
            }
        }
    }

    private fun saveCustomLog(msg: String) {
        runCatching {
            val file = File(carContext.getExternalFilesDir(null), "carrot_crash.txt")
            file.appendText("${java.util.Date()}: $msg\n")
        }
    }

    private suspend fun findCommaDeviceIp(): String? = coroutineScope {
        val localSubnets = getLocalSubnets()
        val candidateSubnets = (localSubnets + listOf("192.168.43", "192.168.12", "172.20.10", "192.168.0", "192.168.1")).distinct()

        for (subnet in candidateSubnets) {
            val tasks = (2..50).map { i ->
                async(Dispatchers.IO) {
                    val testIp = "$subnet.$i"
                    if (isPortOpen(testIp, 7000, 150)) testIp else null
                }
            }
            val foundIp = tasks.awaitAll().firstOrNull { it != null }
            if (foundIp != null) return@coroutineScope foundIp
        }
        null
    }

    private fun getLocalSubnets(): List<String> {
        val subnets = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        val lastDot = hostAddress.lastIndexOf('.')
                        if (lastDot > 0) {
                            subnets.add(hostAddress.substring(0, lastDot))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 무시
        }
        return subnets
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("재시도")
                            .setOnClickListener { startStreamingPipeline() }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
