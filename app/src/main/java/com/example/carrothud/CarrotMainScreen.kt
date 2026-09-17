package com.example.carrothud

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class CarrotMainScreen(
    carContext: CarContext
) : Screen(carContext), SurfaceCallback {

    companion object {
        private const val TAG = "CarrotAA"
        private const val MAX_POINTS = 512
        private const val MAX_LANES = 8
    }

    data class HudState(
        val speedKph: Float = 0f,
        val cruiseKph: Float = 0f,
        val enabled: Boolean = false,
        val leadDistance: Float? = null,
        val leadRelSpeed: Float = 0f,
        val pathX: FloatArray = floatArrayOf(),
        val pathY: FloatArray = floatArrayOf(),
        val laneLines: List<Pair<FloatArray, FloatArray>> = emptyList(),
        val connected: Boolean = false,
        val commaIp: String? = null
    )

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private var surfaceContainer: SurfaceContainer? = null

    @Volatile
    private var rendering = false

    @Volatile
    private var state = HudState()

    private var renderJob: Job? = null
    private var connectJob: Job? = null

    private var webSocket: WebSocket? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        runCatching {
            carContext
                .getCarService(AppManager::class.java)
                .setSurfaceCallback(this)
        }.onFailure { e ->
            android.util.Log.e(
                "CarrotAA",
                "setSurfaceCallback failed",
                e
            )
        }
    }

    override fun onSurfaceAvailable(
        surfaceContainer: SurfaceContainer
    ) {
        this.surfaceContainer = surfaceContainer
        rendering = true

        startRenderer()
        startConnection()
    }

    override fun onSurfaceDestroyed(
        surfaceContainer: SurfaceContainer
    ) {
        rendering = false

        renderJob?.cancel()
        connectJob?.cancel()

        webSocket?.close(1000, "surface destroyed")
        webSocket = null

        this.surfaceContainer = null
    }

    private fun startRenderer() {
        renderJob?.cancel()

        renderJob = scope.launch {
            while (rendering && isActive) {
                drawCluster()
                delay(16)
            }
        }
    }

    private fun startConnection() {
        connectJob?.cancel()

        connectJob = scope.launch {
            while (rendering && isActive) {
                state = state.copy(
                    connected = false,
                    commaIp = null
                )

                val ip = findCommaIp()

                if (ip == null) {
                    delay(2000)
                    continue
                }

                state = state.copy(commaIp = ip)

                connectWebSocket(ip)

                while (
                    rendering &&
                    isActive &&
                    webSocket != null
                ) {
                    delay(1000)
                }

                delay(1500)
            }
        }
    }

    private fun connectWebSocket(ip: String) {
        webSocket?.cancel()

        val services = listOf(
            "carState",
            "controlsState",
            "selfdriveState",
            "modelV2",
            "radarState"
        ).joinToString(",")

        val url =
            "ws://$ip:7000/ws/compact_state" +
            "?services=$services"

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    state = state.copy(
                        connected = true,
                        commaIp = ip
                    )
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    // 서버 hello JSON.
                    // 실제 주행 데이터는 binary frame으로 들어온다.
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString
                ) {
                    try {
                        decodePacket(bytes.toByteArray())
                    } catch (t: Throwable) {
                        Log.w(TAG, "compact_state decode failed", t)
                    }
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    state = state.copy(
                        connected = false
                    )

                    if (this@CarrotMainScreen.webSocket === webSocket) {
                        this@CarrotMainScreen.webSocket = null
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    state = state.copy(
                        connected = false
                    )

                    if (this@CarrotMainScreen.webSocket === webSocket) {
                        this@CarrotMainScreen.webSocket = null
                    }
                }
            }
        )
    }

    private fun decodePacket(data: ByteArray) {
        if (data.size < 4) return

        if (
            data[0] == 'C'.code.toByte() &&
            data[1] == 'V'.code.toByte() &&
            data[2] == 'S'.code.toByte() &&
            data[3] == '1'.code.toByte()
        ) {
            decodeFrame(data)
            return
        }

        if (
            data[0] == 'C'.code.toByte() &&
            data[1] == 'V'.code.toByte() &&
            data[2] == 'B'.code.toByte() &&
            data[3] == '1'.code.toByte()
        ) {
            decodeBatch(data)
        }
    }

    private fun decodeBatch(data: ByteArray) {
        val c = Cursor(data)

        c.skip(4)

        val count = c.u16()

        repeat(count) {
            val length = c.u32().toInt()

            if (
                length <= 0 ||
                c.remaining() < length
            ) return

            val frame = c.bytes(length)

            decodeFrame(frame)
        }
    }

    private fun decodeFrame(data: ByteArray) {
        if (data.size < 8) return

        val c = Cursor(data)

        c.skip(4)

        val serviceId = c.u8()

        c.u8()
        c.u16()

        when (serviceId) {
            1 -> decodeCarState(c)
            2 -> decodeControlsState(c)
            6 -> decodeSelfdriveState(c)
            9 -> decodeModelV2(c)
            13 -> decodeRadarState(c)
        }
    }

    private fun decodeCarState(c: Cursor) {
        val vEgo = c.f32()
        c.f32()

        val cluster = c.f32()
        val cruise = c.f32()

        val speedMps =
            if (cluster > 0.01f) cluster else vEgo

        val speedKph = (speedMps * 3.6f).coerceIn(0f, 260f)
        val cruiseKph =
            if (cruise.isFinite() && cruise > 0f && cruise < 250f) cruise else 0f

        state = state.copy(
            speedKph = speedKph,
            // vCruiseCluster is already km/h. Multiplying by 3.6 caused 60 -> 216.
            cruiseKph = cruiseKph,
            connected = true
        )
    }

    private fun decodeControlsState(c: Cursor) {
        val enabled = c.bool()

        val cruise = c.f32()

        val cruiseKph =
            if (cruise.isFinite() && cruise > 0f && cruise < 250f) cruise else 0f

        state = state.copy(
            enabled = enabled,
            // controlsState.vCruiseCluster is also km/h; 255 means unset.
            cruiseKph = cruiseKph
        )
    }

    private fun decodeSelfdriveState(c: Cursor) {
        val enabled = c.bool()

        state = state.copy(
            enabled = enabled
        )
    }

    private fun decodeModelV2(c: Cursor) {
        c.u32()
        c.u32()

        val position = readXyz(c)

        readVelocity(c)

        val laneCount = c.u8()

        if (laneCount > MAX_LANES) {
            error("invalid lane line count: $laneCount")
        }

        val lanes = ArrayList<Pair<FloatArray, FloatArray>>()

        repeat(laneCount) {
            val xyz = readXyz(c)

            lanes.add(
                xyz.first to xyz.second
            )
        }

        // laneLineProbs follows the complete laneLines struct list on the wire.
        val laneProbs = c.f32List()
        val visibleLanes = lanes.filterIndexed { index, lane ->
            (index == 1 || index == 2) &&
                laneProbs.getOrElse(index) { 0f } >= 0.30f &&
                lane.first.size >= 2 && lane.second.size >= 2
        }

        state = state.copy(
            pathX = position.first,
            pathY = position.second,
            laneLines = visibleLanes
        )
    }

    private fun decodeRadarState(c: Cursor) {
        if (c.remaining() < 4) return

        val dRel = c.f32()
        c.f32()
        val vRel = c.f32()

        // aRel, vLead, aLead, dPath, vLat, vLeadK, aLeadK
        repeat(7) { c.f32() }
        c.bool() // fcw
        val status = c.bool()

        state = state.copy(
            leadDistance =
                if (status && dRel.isFinite() && dRel in 0.1f..300f)
                    dRel
                else
                    null,
            leadRelSpeed = if (vRel.isFinite()) vRel.coerceIn(-100f, 100f) else 0f
        )
    }

    private fun readVelocity(
        c: Cursor
    ): FloatArray {
        return c.i16CmList()
    }

    private fun readXyz(
        c: Cursor
    ): Pair<FloatArray, FloatArray> {
        val x = c.u16CmList()
        val y = c.i16MmList()

        c.i16MmList()

        return x to y
    }

    private fun drawCluster() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return

        if (
            !rendering ||
            !surface.isValid
        ) return

        var canvas: Canvas? = null

        try {
            canvas = surface.lockCanvas(null)

            val width =
                if (container.width > 0)
                    container.width.toFloat()
                else
                    canvas.width.toFloat()

            val height =
                if (container.height > 0)
                    container.height.toFloat()
                else
                    canvas.height.toFloat()

            val s = state

            canvas.drawColor(Color.rgb(4, 6, 8))

            drawRoad(
                canvas,
                width,
                height,
                s
            )

            drawLead(
                canvas,
                width,
                height,
                s
            )

            drawSpeed(
                canvas,
                width,
                height,
                s
            )

            drawStatus(
                canvas,
                width,
                height,
                s
            )

        } catch (t: Throwable) {
            Log.e(TAG, "HUD renderer failed", t)
        } finally {
            if (canvas != null) {
                runCatching {
                    surface.unlockCanvasAndPost(canvas)
                }
            }
        }
    }

    private fun drawRoad(
        canvas: Canvas,
        width: Float,
        height: Float,
        s: HudState
    ) {
        val horizon = height * 0.23f
        val bottom = height * 1.02f
        val center = width * 0.5f

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(12, 15, 18)

        val road = Path().apply {
            moveTo(center - width * 0.055f, horizon)
            lineTo(center + width * 0.055f, horizon)
            lineTo(center + width * 0.42f, bottom)
            lineTo(center - width * 0.42f, bottom)
            close()
        }

        canvas.drawPath(road, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(2f, width * 0.003f)
        paint.strokeCap = Paint.Cap.ROUND

        if (s.laneLines.isNotEmpty()) {
            s.laneLines.forEachIndexed { index, lane ->
                paint.color =
                    if (index == 1 || index == 2)
                        Color.WHITE
                    else
                        Color.rgb(100, 110, 120)

                drawWorldLine(
                    canvas,
                    lane.first,
                    lane.second,
                    width,
                    height,
                    paint
                )
            }
        } else {
            paint.color = Color.rgb(110, 120, 130)

            drawFallbackLane(
                canvas,
                center - width * 0.16f,
                center - width * 0.025f,
                horizon,
                bottom,
                paint
            )

            drawFallbackLane(
                canvas,
                center + width * 0.16f,
                center + width * 0.025f,
                horizon,
                bottom,
                paint
            )
        }

        if (
            s.pathX.size > 2 &&
            s.pathY.size > 2
        ) {
            paint.color =
                if (s.enabled)
                    Color.rgb(40, 220, 110)
                else
                    Color.rgb(90, 100, 110)

            paint.strokeWidth =
                max(5f, width * 0.010f)

            drawWorldLine(
                canvas,
                s.pathX,
                s.pathY,
                width,
                height,
                paint
            )
        }

        drawEgoCar(
            canvas,
            width,
            height,
            s.enabled
        )
    }

    private fun drawFallbackLane(
        canvas: Canvas,
        bottomX: Float,
        topX: Float,
        topY: Float,
        bottomY: Float,
        p: Paint
    ) {
        canvas.drawLine(
            bottomX,
            bottomY,
            topX,
            topY,
            p
        )
    }

    private fun drawWorldLine(
        canvas: Canvas,
        xs: FloatArray,
        ys: FloatArray,
        width: Float,
        height: Float,
        p: Paint
    ) {
        val n = min(xs.size, ys.size)

        if (n < 2) return

        val path = Path()
        var started = false

        for (i in 0 until n) {
            val forward = xs[i]

            if (
                forward < 0f ||
                forward > 120f
            ) continue

            val depth =
                (forward / 120f)
                    .coerceIn(0f, 1f)

            val screenY =
                height * 0.90f -
                depth * height * 0.66f

            val perspective =
                1f - depth * 0.80f

            val screenX =
                width * 0.5f +
                ys[i] *
                width *
                0.060f *
                perspective

            if (!started) {
                path.moveTo(
                    screenX,
                    screenY
                )
                started = true
            } else {
                path.lineTo(
                    screenX,
                    screenY
                )
            }
        }

        if (started) {
            canvas.drawPath(path, p)
        }
    }

    private fun drawEgoCar(
        canvas: Canvas,
        width: Float,
        height: Float,
        enabled: Boolean
    ) {
        val cx = width * 0.5f
        val cy = height * 0.82f

        val carW = width * 0.055f
        val carH = height * 0.095f

        paint.style = Paint.Style.FILL
        paint.color =
            if (enabled)
                Color.rgb(38, 220, 105)
            else
                Color.rgb(210, 215, 220)

        canvas.drawRoundRect(
            cx - carW / 2f,
            cy - carH / 2f,
            cx + carW / 2f,
            cy + carH / 2f,
            carW * 0.25f,
            carW * 0.25f,
            paint
        )

        paint.color = Color.rgb(25, 30, 34)

        canvas.drawRoundRect(
            cx - carW * 0.28f,
            cy - carH * 0.25f,
            cx + carW * 0.28f,
            cy + carH * 0.05f,
            carW * 0.10f,
            carW * 0.10f,
            paint
        )
    }

    private fun drawLead(
        canvas: Canvas,
        width: Float,
        height: Float,
        s: HudState
    ) {
        val d = s.leadDistance ?: return

        val normalized =
            (d / 120f)
                .coerceIn(0.05f, 1f)

        val y =
            height * 0.76f -
            normalized * height * 0.48f

        val size =
            width *
            (0.055f - normalized * 0.025f)

        val cx = width * 0.5f

        paint.style = Paint.Style.FILL
        paint.color =
            if (d < 20f)
                Color.rgb(255, 90, 70)
            else
                Color.rgb(255, 180, 45)

        canvas.drawRoundRect(
            cx - size,
            y - size * 0.45f,
            cx + size,
            y + size * 0.45f,
            size * 0.2f,
            size * 0.2f,
            paint
        )

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize =
            max(18f, width * 0.022f)

        canvas.drawText(
            "${d.toInt()}m",
            cx,
            y - size * 0.75f,
            paint
        )
    }

    private fun drawSpeed(
        canvas: Canvas,
        width: Float,
        height: Float,
        s: HudState
    ) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.isFakeBoldText = true

        paint.textSize =
            max(54f, height * 0.19f)

        canvas.drawText(
            s.speedKph
                .coerceAtLeast(0f)
                .toInt()
                .toString(),
            width * 0.5f,
            height * 0.19f,
            paint
        )

        paint.isFakeBoldText = false
        paint.textSize =
            max(15f, height * 0.034f)

        paint.color = Color.rgb(170, 180, 188)

        canvas.drawText(
            "km/h",
            width * 0.5f,
            height * 0.235f,
            paint
        )

        if (s.cruiseKph > 0f) {
            paint.color =
                if (s.enabled)
                    Color.rgb(50, 230, 120)
                else
                    Color.rgb(180, 190, 195)

            paint.textSize =
                max(22f, height * 0.055f)

            canvas.drawText(
                "SET ${s.cruiseKph.toInt()}",
                width * 0.5f,
                height * 0.30f,
                paint
            )
        }
    }

    private fun drawStatus(
        canvas: Canvas,
        width: Float,
        height: Float,
        s: HudState
    ) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.isFakeBoldText = true
        paint.textSize =
            max(18f, height * 0.040f)

        paint.color =
            if (s.connected)
                Color.rgb(50, 225, 115)
            else
                Color.rgb(255, 165, 55)

        canvas.drawText(
            if (s.connected)
                "CARROT CONNECTED"
            else
                "SEARCHING COMMA 4",
            width * 0.035f,
            height * 0.075f,
            paint
        )

        paint.isFakeBoldText = false
        paint.textSize =
            max(14f, height * 0.028f)

        paint.color = Color.rgb(140, 150, 160)

        s.commaIp?.let {
            canvas.drawText(
                it,
                width * 0.035f,
                height * 0.115f,
                paint
            )
        }

        paint.textAlign = Paint.Align.RIGHT

        paint.color =
            if (s.enabled)
                Color.rgb(50, 230, 120)
            else
                Color.rgb(150, 160, 168)

        paint.textSize =
            max(22f, height * 0.050f)

        canvas.drawText(
            if (s.enabled)
                "OPENPILOT ACTIVE"
            else
                "OPENPILOT",
            width * 0.965f,
            height * 0.075f,
            paint
        )
    }

    private suspend fun findCommaIp(): String? =
        coroutineScope {

            val subnets =
                (
                    getLocalSubnets() +
                    listOf(
                        "192.168.43",
                        "192.168.42",
                        "192.168.137",
                        "192.168.225",
                        "172.20.10",
                        "10.42.0",
                        "192.168.0",
                        "192.168.1",
                        "192.168.8"
                    )
                ).distinct()

            for (subnet in subnets) {
                val jobs =
                    (2..254).map { host ->
                        async(Dispatchers.IO) {
                            val ip =
                                "$subnet.$host"

                            if (
                                isPortOpen(
                                    ip,
                                    7000,
                                    180
                                )
                            ) ip
                            else null
                        }
                    }

                val found =
                    jobs.awaitAll()
                        .firstOrNull {
                            it != null
                        }

                if (found != null) {
                    return@coroutineScope found
                }
            }

            null
        }

    private fun getLocalSubnets(): List<String> {
        val result =
            mutableListOf<String>()

        runCatching {
            val interfaces =
                Collections.list(
                    NetworkInterface
                        .getNetworkInterfaces()
                )

            for (network in interfaces) {
                val addresses =
                    Collections.list(
                        network.inetAddresses
                    )

                for (address in addresses) {
                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress
                    ) {
                        val host =
                            address.hostAddress
                                ?: continue

                        val dot =
                            host.lastIndexOf('.')

                        if (dot > 0) {
                            result.add(
                                host.substring(
                                    0,
                                    dot
                                )
                            )
                        }
                    }
                }
            }
        }

        return result
    }

    private fun isPortOpen(
        ip: String,
        port: Int,
        timeout: Int
    ): Boolean {
        return try {
            Socket().use {
                it.connect(
                    InetSocketAddress(
                        ip,
                        port
                    ),
                    timeout
                )
            }

            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("재연결")
                            .setOnClickListener {
                                webSocket?.cancel()
                                webSocket = null
                                startConnection()
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private class Cursor(
        private val data: ByteArray
    ) {
        private val buffer =
            ByteBuffer.wrap(data)
                .order(ByteOrder.LITTLE_ENDIAN)

        fun remaining(): Int =
            buffer.remaining()

        fun skip(n: Int) {
            if (buffer.remaining() < n) {
                throw IllegalStateException(
                    "compact frame truncated"
                )
            }

            buffer.position(
                buffer.position() + n
            )
        }

        fun bytes(n: Int): ByteArray {
            if (remaining() < n) {
                throw IllegalStateException(
                    "compact frame truncated"
                )
            }

            return ByteArray(n).also {
                buffer.get(it)
            }
        }

        fun u8(): Int =
            buffer.get().toInt() and 0xFF

        fun bool(): Boolean =
            u8() != 0

        fun u16(): Int =
            buffer.short.toInt() and 0xFFFF

        fun u32(): Long =
            buffer.int.toLong() and 0xFFFFFFFFL

        fun f32(): Float =
            buffer.float

        fun i16(): Int =
            buffer.short.toInt()

        fun text(): String {
            val n = u16()

            return bytes(n)
                .toString(Charsets.UTF_8)
        }

        fun u16CmList(): FloatArray {
            val n = u16()

            requireList(n, 2)

            return FloatArray(n) {
                u16() / 100f
            }
        }

        fun i16CmList(): FloatArray {
            val n = u16()

            requireList(n, 2)

            return FloatArray(n) {
                i16() / 100f
            }
        }

        fun i16MmList(): FloatArray {
            val n = u16()

            requireList(n, 2)

            return FloatArray(n) {
                i16() / 1000f
            }
        }

        fun f32List(): FloatArray {
            val n = u16()

            requireList(n, 4)

            return FloatArray(n) { f32() }
        }

        private fun requireList(n: Int, bytesPerItem: Int) {
            if (n > MAX_POINTS || n > remaining() / bytesPerItem) {
                throw IllegalStateException("invalid compact list length: $n")
            }
        }
    }
}
