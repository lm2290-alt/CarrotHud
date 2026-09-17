package com.example.carrothud

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
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
import kotlin.math.sin

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
        val leftBlinker: Boolean = false,
        val rightBlinker: Boolean = false,
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
    private val egoVehicleBitmap =
        BitmapFactory.decodeResource(carContext.resources, R.drawable.ego_niro_de_3d)
    private val leadVehicleBitmap =
        BitmapFactory.decodeResource(carContext.resources, R.drawable.lead_sedan_3d)

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

        c.f32() // steeringAngleDeg
        c.bool() // brakeHoldActive
        c.i16() // softHoldActive
        c.i16() // carrotCruise
        c.i16() // gearStep
        c.f32() // useLaneLineSpeed
        c.bool() // brakeLights
        c.bool() // leftBlindspot
        c.bool() // rightBlindspot
        c.i16() // leftLaneLine
        c.i16() // rightLaneLine
        c.u8() // gearShifter enum
        val leftBlinker = c.bool()
        val rightBlinker = c.bool()

        val speedMps =
            if (cluster > 0.01f) cluster else vEgo

        val speedKph = (speedMps * 3.6f).coerceIn(0f, 260f)
        val cruiseKph =
            if (cruise.isFinite() && cruise > 0f && cruise < 250f) cruise else 0f

        state = state.copy(
            speedKph = speedKph,
            // vCruiseCluster is already km/h. Multiplying by 3.6 caused 60 -> 216.
            cruiseKph = cruiseKph,
            leftBlinker = leftBlinker,
            rightBlinker = rightBlinker,
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

            drawRoadBackground(canvas, width, height)

            drawRoadMotion(canvas, width, height, s.speedKph)

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

    /** All geometry uses normalized screen coordinates, so wide OEM surfaces never stretch a bitmap. */
    private fun drawRoadBackground(canvas: Canvas, width: Float, height: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, 0f, height,
            Color.rgb(244, 245, 243), Color.rgb(205, 207, 203),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null

        // Soft distant landscape, drawn in screen-relative coordinates.
        paint.color = Color.rgb(218, 221, 216)
        canvas.drawPath(Path().apply {
            moveTo(0f, height * 0.33f)
            lineTo(width * 0.18f, height * 0.20f)
            lineTo(width * 0.34f, height * 0.31f)
            lineTo(width * 0.51f, height * 0.17f)
            lineTo(width * 0.72f, height * 0.32f)
            lineTo(width, height * 0.22f)
            lineTo(width, height * 0.43f)
            lineTo(0f, height * 0.43f)
            close()
        }, paint)

        // Tesla-like matte road plane. No bitmap scaling means no horizontal distortion.
        paint.shader = LinearGradient(
            0f, height * 0.25f, 0f, height,
            Color.rgb(226, 227, 224), Color.rgb(183, 187, 185),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(Path().apply {
            moveTo(width * 0.43f, height * 0.24f)
            lineTo(width * 0.57f, height * 0.24f)
            lineTo(width * 0.91f, height)
            lineTo(width * 0.09f, height)
            close()
        }, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(2f, width * 0.0022f)
        paint.color = Color.argb(155, 255, 255, 255)
        canvas.drawLine(width * 0.36f, height, width * 0.475f, height * 0.25f, paint)
        canvas.drawLine(width * 0.64f, height, width * 0.525f, height * 0.25f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawRoad(
        canvas: Canvas,
        width: Float,
        height: Float,
        s: HudState
    ) {
        val horizon = height * 0.18f
        val bottom = height * 1.03f
        val center = width * 0.5f

        if (s.pathX.size > 2 && s.pathY.size > 2) {
            drawDrivingCorridor(canvas, s.pathX, s.pathY, width, height, s.enabled)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(3f, width * 0.004f)
        paint.strokeCap = Paint.Cap.ROUND

        if (s.laneLines.isNotEmpty()) {
            s.laneLines.forEach { lane ->
                paint.color = Color.rgb(15, 112, 225)

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
            paint.color = Color.rgb(15, 112, 225)

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

        drawEgoCar(
            canvas,
            width,
            height,
            s
        )
    }

    /** Moving perspective markers provide continuous forward-motion depth at the live vehicle speed. */
    private fun drawRoadMotion(canvas: Canvas, width: Float, height: Float, speedKph: Float) {
        val now = SystemClock.uptimeMillis() / 1000f
        val speedFactor = (speedKph / 90f).coerceIn(0.08f, 1.8f)
        val phase = (now * speedFactor * 0.72f) % 1f

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(185, 255, 255, 255)
        for (lane in floatArrayOf(-0.24f, 0.24f)) {
            for (i in 0..7) {
                val t0 = ((i / 8f + phase) % 1f).coerceIn(0f, 1f)
                val t1 = (t0 + 0.055f + t0 * 0.065f).coerceAtMost(1f)
                val y0 = height * (0.27f + t0 * t0 * 0.79f)
                val y1 = height * (0.27f + t1 * t1 * 0.79f)
                val x0 = width * (0.5f + lane * t0)
                val x1 = width * (0.5f + lane * t1)
                paint.strokeWidth = max(2f, width * (0.0012f + t0 * 0.004f))
                canvas.drawLine(x0, y0, x1, y1, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawDrivingCorridor(
        canvas: Canvas,
        xs: FloatArray,
        ys: FloatArray,
        width: Float,
        height: Float,
        enabled: Boolean
    ) {
        val n = min(xs.size, ys.size)
        if (n < 2) return

        val left = mutableListOf<PointF>()
        val right = mutableListOf<PointF>()

        for (i in 0 until n) {
            val forward = xs[i]
            if (forward !in 0f..120f) continue
            projectWorld(forward, ys[i] - 1.25f, width, height)?.let(left::add)
            projectWorld(forward, ys[i] + 1.25f, width, height)?.let(right::add)
        }

        if (left.size < 2 || right.size < 2) return

        val corridor = Path().apply {
            moveTo(left.first().x, left.first().y)
            left.drop(1).forEach { lineTo(it.x, it.y) }
            right.asReversed().forEach { lineTo(it.x, it.y) }
            close()
        }

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            width * 0.5f,
            height * 0.88f,
            width * 0.5f,
            height * 0.28f,
            if (enabled) Color.argb(220, 77, 232, 95) else Color.argb(145, 120, 130, 136),
            if (enabled) Color.argb(70, 38, 183, 235) else Color.argb(25, 120, 130, 136),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(corridor, paint)
        paint.shader = null
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

            val point = projectWorld(forward, ys[i], width, height) ?: continue
            val screenX = point.x
            val screenY = point.y

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

    private fun projectWorld(
        forward: Float,
        lateral: Float,
        width: Float,
        height: Float
    ): PointF? {
        if (!forward.isFinite() || !lateral.isFinite() || forward !in 0f..120f) return null
        val depth = (forward / 120f).coerceIn(0f, 1f)
        val curvedDepth = kotlin.math.sqrt(depth)
        val screenY = height * 0.90f - curvedDepth * height * 0.70f
        val perspective = 1f - curvedDepth * 0.82f
        val screenX = width * 0.5f + lateral * width * 0.060f * perspective
        return PointF(screenX, screenY)
    }

    private fun drawEgoCar(
        canvas: Canvas,
        width: Float,
        height: Float,
        s: HudState
    ) {
        val cx = width * 0.5f
        val now = SystemClock.uptimeMillis()
        val roadPulse = (s.speedKph / 100f).coerceIn(0f, 1.2f)
        val bob = sin(now / 150.0).toFloat() * height * 0.0025f * roadPulse
        val sway = sin(now / 620.0).toFloat() * 0.65f * roadPulse
        val cy = height * 0.75f + bob
        val blinkOn = (SystemClock.uptimeMillis() / 450L) % 2L == 0L
        canvas.save()
        canvas.rotate(sway, cx, cy)
        drawTopDownCar(
            canvas,
            cx,
            cy,
            width * 0.115f,
            height * 0.31f,
            true,
            s.enabled,
            s.leftBlinker && blinkOn,
            s.rightBlinker && blinkOn
        )
        canvas.restore()
    }

    private fun drawTopDownCar(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        carW: Float,
        carH: Float,
        ego: Boolean,
        enabled: Boolean = false,
        leftBlinker: Boolean = false,
        rightBlinker: Boolean = false
    ) {
        val vehicleBitmap = if (ego) egoVehicleBitmap else leadVehicleBitmap
        if (vehicleBitmap != null) {
            // Width is always derived from source aspect ratio: never stretch on the Niro wide display.
            val drawnHeight = carH
            val drawnWidth = drawnHeight * vehicleBitmap.width / vehicleBitmap.height
            val destination = RectF(
                cx - drawnWidth / 2f,
                cy - drawnHeight / 2f,
                cx + drawnWidth / 2f,
                cy + drawnHeight / 2f
            )
            paint.alpha = if (ego) 255 else 230
            paint.colorFilter = null
            paint.setShadowLayer(drawnWidth * 0.20f, 0f, drawnWidth * 0.08f, Color.argb(75, 0, 0, 0))
            canvas.drawBitmap(vehicleBitmap, null, destination, paint)
            paint.clearShadowLayer()
            paint.colorFilter = null
            paint.alpha = 255
            if (leftBlinker || rightBlinker) {
                drawVehicleBlinkers(canvas, cx, cy, drawnWidth, drawnHeight, leftBlinker, rightBlinker)
            }
            return
        }

        paint.style = Paint.Style.FILL
        paint.setShadowLayer(carW * 0.22f, 0f, carW * 0.10f, Color.argb(90, 0, 0, 0))
        paint.color = if (ego) Color.rgb(27, 30, 32) else Color.rgb(132, 136, 137)
        canvas.drawRoundRect(
            RectF(cx - carW / 2f, cy - carH / 2f, cx + carW / 2f, cy + carH / 2f),
            carW * 0.30f,
            carW * 0.30f,
            paint
        )
        paint.clearShadowLayer()

        paint.color = if (ego) Color.rgb(73, 80, 84) else Color.rgb(191, 195, 194)
        canvas.drawRoundRect(
            RectF(cx - carW * 0.33f, cy - carH * 0.23f, cx + carW * 0.33f, cy + carH * 0.15f),
            carW * 0.14f,
            carW * 0.14f,
            paint
        )

        paint.color = if (enabled && ego) Color.rgb(68, 235, 106) else Color.rgb(210, 48, 43)
        val lightW = carW * 0.14f
        canvas.drawRoundRect(RectF(cx - carW * 0.34f, cy - carH * 0.43f, cx - carW * 0.34f + lightW, cy - carH * 0.39f), lightW, lightW, paint)
        canvas.drawRoundRect(RectF(cx + carW * 0.34f - lightW, cy - carH * 0.43f, cx + carW * 0.34f, cy - carH * 0.39f), lightW, lightW, paint)

    }

    private fun drawVehicleBlinkers(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        carW: Float,
        carH: Float,
        leftBlinker: Boolean,
        rightBlinker: Boolean
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 171, 24)
        paint.setShadowLayer(carW * 0.22f, 0f, 0f, Color.argb(210, 255, 171, 24))
        if (leftBlinker) {
            canvas.drawOval(
                RectF(cx - carW * 0.43f, cy + carH * 0.26f, cx - carW * 0.17f, cy + carH * 0.33f),
                paint
            )
        }
        if (rightBlinker) {
            canvas.drawOval(
                RectF(cx + carW * 0.17f, cy + carH * 0.26f, cx + carW * 0.43f, cy + carH * 0.33f),
                paint
            )
        }
        paint.clearShadowLayer()
    }

    private fun drawLead(
        canvas: Canvas,
        width: Float,
        height: Float,
        s: HudState
    ) {
        val d = s.leadDistance ?: return

        // Perspective correction: the previous mapping made a 29 m lead overlap the ego car.
        val visualDistance = (d * 1.75f).coerceIn(8f, 120f)
        val point = projectWorld(visualDistance, 0f, width, height) ?: return
        val normalized = (d / 120f).coerceIn(0.05f, 1f)
        val carH = height * (0.115f - normalized * 0.035f)
        val carW = carH * leadVehicleBitmap.width / leadVehicleBitmap.height
        val leadBob = sin(SystemClock.uptimeMillis() / 210.0).toFloat() * height * 0.0014f
        drawTopDownCar(canvas, point.x, point.y + leadBob, carW, carH, false)
    }

    private fun drawSpeed(
        canvas: Canvas,
        width: Float,
        height: Float,
        s: HudState
    ) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.rgb(25, 28, 30)
        paint.isFakeBoldText = true

        paint.textSize =
            max(54f, height * 0.19f)

        canvas.drawText(
            s.speedKph
                .coerceAtLeast(0f)
                .toInt()
                .toString(),
            width * 0.055f,
            height * 0.25f,
            paint
        )

        paint.isFakeBoldText = false
        paint.textSize =
            max(15f, height * 0.034f)

        paint.color = Color.rgb(91, 97, 99)

        canvas.drawText(
            "km/h",
            width * 0.058f,
            height * 0.30f,
            paint
        )

        if (s.cruiseKph > 0f) {
            paint.color =
                if (s.enabled)
                    Color.rgb(20, 150, 76)
                else
                    Color.rgb(91, 97, 99)

            paint.textSize =
                max(22f, height * 0.055f)

            canvas.drawText(
                "SET ${s.cruiseKph.toInt()}",
                width * 0.058f,
                height * 0.37f,
                paint
            )
        }

        s.leadDistance?.let { distance ->
            paint.textAlign = Paint.Align.RIGHT
            paint.isFakeBoldText = true
            paint.color = if (distance < 20f) Color.rgb(205, 47, 40) else Color.rgb(25, 28, 30)
            paint.textSize = max(30f, height * 0.075f)
            canvas.drawText("${distance.toInt()} m", width * 0.945f, height * 0.24f, paint)
            paint.isFakeBoldText = false
            paint.color = Color.rgb(91, 97, 99)
            paint.textSize = max(16f, height * 0.034f)
            val sign = if (s.leadRelSpeed > 0f) "+" else ""
            canvas.drawText("$sign${s.leadRelSpeed.toInt()} km/h", width * 0.945f, height * 0.30f, paint)
        }
    }

    private fun drawStatus(
        canvas: Canvas,
        width: Float,
        height: Float,
        s: HudState
    ) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize =
            max(18f, height * 0.040f)

        paint.color = if (s.connected) Color.rgb(20, 150, 76) else Color.rgb(205, 116, 34)

        canvas.drawText(
            if (s.connected)
                "GPS  ·  ONLINE"
            else
                "SEARCHING COMMA 4",
            width * 0.5f,
            height * 0.955f,
            paint
        )

        paint.isFakeBoldText = false
        paint.textSize =
            max(14f, height * 0.028f)

        paint.color = Color.rgb(105, 111, 113)

        s.commaIp?.let {
            canvas.drawText(
                it,
                width * 0.5f,
                height * 0.988f,
                paint
            )
        }
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
