package com.example.carrothud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.*
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections

class CarrotMainScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var surfaceContainer: SurfaceContainer? = null
    @Volatile private var isRendering = false
    private var streamJob: Job? = null
    private val renderLock = Any()
    private var lastMessage: String = "콤마4 탐색 중..."
    @Volatile private var currentBitmap: Bitmap? = null

    init {
        runCatching {
            val navigationManager = carContext.getCarService(NavigationManager::class.java)
            navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() {}
            })

            carContext.getCarService(androidx.car.app.AppManager::class.java)
                .setSurfaceCallback(this)
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        synchronized(renderLock) {
            this.surfaceContainer = surfaceContainer
            isRendering = true
        }
        startStreamingPipeline()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        synchronized(renderLock) {
            isRendering = false
            streamJob?.cancel()
            this.surfaceContainer = null
            currentBitmap?.recycle()
            currentBitmap = null
        }
    }

    private fun startStreamingPipeline() {
        streamJob?.cancel()
        streamJob = CoroutineScope(Dispatchers.IO).launch {
            updateMessage("콤마4 탐색 중...")
            val commaIp = findCommaDeviceIp()

            if (commaIp == null) {
                updateMessage("콤마4(포트 7000) 탐색 실패\n재시도 중...")
                delay(3000)
                if (isRendering) startStreamingPipeline()
                return@launch
            }

            updateMessage("스트리밍 연결 중: $commaIp")
            runMjpegStream(commaIp)
        }
    }

    private suspend fun runMjpegStream(ip: String) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        try {
            val url = URL("http://$ip:7000")
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 5000
                connect()
            }
            inputStream = connection.inputStream
            updateMessage("스트리밍 연결 성공")

            val buffer = ByteArray(16384)
            val bos = ByteArrayOutputStream()

            while (isRendering) {
                val available = inputStream.available()
                val chunk = if (available > 0) {
                    val b = ByteArray(minOf(available, buffer.size))
                    inputStream.read(b)
                } else {
                    inputStream.read(buffer)
                }

                if (chunk == -1) break
                bos.write(buffer, 0, chunk)

                val bytes = bos.toByteArray()
                var startIndex = -1
                var endIndex = -1

                for (i in 0 until bytes.size - 1) {
                    if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xFF) == 0xD8) {
                        startIndex = i
                        break
                    }
                }

                if (startIndex != -1) {
                    for (i in startIndex + 2 until bytes.size - 1) {
                        if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xFF) == 0xD9) {
                            endIndex = i + 2
                            break
                        }
                    }
                }

                if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, startIndex, endIndex - startIndex)
                        if (bitmap != null) {
                            synchronized(renderLock) {
                                currentBitmap?.recycle()
                                currentBitmap = bitmap
                            }
                        }
                    } catch (e: Exception) {
                        // 디코딩 에러 무시
                    }

                    bos.reset()
                    if (endIndex < bytes.size) {
                        bos.write(bytes, endIndex, bytes.size - endIndex)
                    }
                } else if (bytes.size > 1024 * 1024) {
                    bos.reset()
                }

                renderFrame()
                delay(10)
            }
        } catch (e: Exception) {
            updateMessage("스트리밍 끊김: ${e.localizedMessage}\n재연결 중...")
            delay(2000)
            if (isRendering) {
                runMjpegStream(ip)
            }
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
            try { connection?.disconnect() } catch (e: Exception) {}
        }
    }

    private fun updateMessage(msg: String) {
        lastMessage = msg
        renderFrame()
    }

    private fun renderFrame() {
        synchronized(renderLock) {
            val container = surfaceContainer ?: return
            val surface = container.surface ?: return
            if (!surface.isValid || !isRendering) return

            var canvas: Canvas? = null
            try {
                canvas = surface.lockCanvas(null)
                if (canvas != null) {
                    val width = if (container.width > 0) container.width else canvas.width
                    val height = if (container.height > 0) container.height else canvas.height

                    canvas.drawColor(Color.BLACK)

                    val bmp = currentBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        val srcRect = android.graphics.Rect(0, 0, bmp.width, bmp.height)
                        val destRect = android.graphics.Rect(0, 0, width, height)
                        canvas.drawBitmap(bmp, srcRect, destRect, null)
                    } else {
                        val paint = Paint().apply {
                            color = Color.WHITE
                            textSize = 32f
                            textAlign = Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        val lines = lastMessage.split("\n")
                        val startY = height / 2f - (lines.size * 20f)
                        lines.forEachIndexed { index, line ->
                            canvas.drawText(line, width / 2f, startY + (index * 40f), paint)
                        }
                    }
                }
            } catch (t: Throwable) {
                // 렌더링 예외 무시
            } finally {
                if (canvas != null) {
                    runCatching { surface.unlockCanvasAndPost(canvas) }
                }
            }
        }
    }

    private suspend fun findCommaDeviceIp(): String? = coroutineScope {
        val localSubnets = getLocalSubnets()
        val candidateSubnets = (localSubnets + listOf(
            "192.168.43", "192.168.42", "192.168.137", "192.168.225",
            "172.20.10", "10.42.0", "192.168.0", "192.168.1", "192.168.8"
        )).distinct()

        for (subnet in candidateSubnets) {
            val tasks = (2..254).map { i ->
                async(Dispatchers.IO) {
                    val testIp = "$subnet.$i"
                    if (isPortOpen(testIp, 7000, 200)) testIp else null
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
        } catch (e: Exception) {}
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
            .setRoutingInfo(
                RoutingInfo.Builder()
                    .setLoading(false)
                    .build()
            )
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("재시도")
                            .setOnClickListener {
                                startStreamingPipeline()
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
