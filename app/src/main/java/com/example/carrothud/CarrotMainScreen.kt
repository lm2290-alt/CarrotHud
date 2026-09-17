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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CarrotMainScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    companion object {
        private const val TAG = "CarrotAA"
        private const val MAX_POINTS = 512
        private const val MAX_LANES = 8
        private const val MAX_FORWARD = 140f
        private const val MAX_LATERAL = 15f
        private const val LANE_PROB_MIN = 0.30f
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var state = HudState()

    @Volatile
    private var rendering = false

    private var surfaceContainer: SurfaceContainer? = null
    private var renderJob: Job? = null
    private var connectJob: Job? = null
    private var webSocket: WebSocket? = null

    init {
        runCatching {
            carContext
                .getCarService(AppManager::class.java)
                .setSurfaceCallback(this)
        }.onFailure {
            Log.e(TAG, "setSurfaceCallback failed", it)
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = surfaceContainer
        rendering = true

        startRenderer()
        startConnection()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        rendering = false

        renderJob?.cancel()
        connectJob?.cancel()

        webSocket?.close(1000, "surface destroyed")
        webSocket = null

        state = HudState()
        this.surfaceContainer = null
    }

    private fun startRenderer() {
        renderJob?.cancel()

        renderJob = scope.launch {
            while (rendering && isActive) {
                drawCluster()

                // Android Auto HUD에는 30fps면 충분.
                // Surface lock 경합과 발열도 감소.
                delay(33)
            }
        }
    }

    private fun startConnection() {
        connectJob?.cancel()

        connectJob = scope.launch {
            while (rendering && isActive) {

                state = HudState()

                val ip = findCommaIp()

                if (ip == null) {
                    delay(2000)
                    continue
                }

                state = state.copy(
                    commaIp = ip
                )

                connectWebSocket(ip)

                while (
                    rendering &&
                    isActive &&
                    webSocket != null
                ) {
                    delay(500)
                }

                delay(1000)
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

        val request = Request.Builder()
            .url(
                "ws://$ip:7000/ws/compact_state" +
                    "?services=$services"
            )
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
                ) = Unit

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString
                ) {
                    try {
                        decodePacket(
                            bytes.toByteArray()
                        )
                    } catch (t: Throwable) {
                        Log.w(
                            TAG,
                            "compact_state decode failed: ${t.message}"
                        )
                    }
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    if (
                        this@CarrotMainScreen.webSocket === webSocket
                    ) {
                        this@CarrotMainScreen.webSocket = null
                    }

                    val ipNow = state.commaIp

                    state = HudState(
                        commaIp = ipNow
                    )
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    if (
                        this@CarrotMainScreen.webSocket === webSocket
                    ) {
                        this@CarrotMainScreen.webSocket = null
                    }

                    val ipNow = state.commaIp

                    state = HudState(
                        commaIp = ipNow
                    )
                }
            }
        )
    }

    private fun decodePacket(data: ByteArray) {

        if (data.size < 4) {
            return
        }

        when {

            magic(
                data,
                'C',
                'V',
                'S',
                '1'
            ) -> {
                decodeFrame(data)
            }

            magic(
                data,
                'C',
                'V',
                'B',
                '1'
            ) -> {
                decodeBatch(data)
            }
        }
    }

    private fun magic(
        data: ByteArray,
        a: Char,
        b: Char,
        c: Char,
        d: Char
    ): Boolean {

        return data.size >= 4 &&

            data[0] == a.code.toByte() &&
            data[1] == b.code.toByte() &&
            data[2] == c.code.toByte() &&
            data[3] == d.code.toByte()
    }

    private fun decodeBatch(
        data: ByteArray
    ) {

        val c = Cursor(data)

        c.skip(4)

        val count = c.u16()

        if (count > 512) {
            error(
                "bad batch count=$count"
            )
        }

        repeat(count) {

            val length = c.u32()

            if (
                length <= 0 ||
                length >
                Int.MAX_VALUE.toLong()
            ) {
                error(
                    "bad frame length=$length"
                )
            }

            decodeFrame(
                c.bytes(
                    length.toInt()
                )
            )
        }
    }

    private fun decodeFrame(
        data: ByteArray
    ) {

        if (
            data.size < 8 ||
            !magic(
                data,
                'C',
                'V',
                'S',
                '1'
            )
        ) {
            return
        }

        val c = Cursor(data)

        c.skip(4)

        val serviceId =
            c.u8()

        // flags
        c.u8()

        // sequence
        c.u16()

        when (serviceId) {

            1 -> {
                decodeCarState(c)
            }

            2 -> {
                decodeControlsState(c)
            }

            6 -> {
                state =
                    state.copy(
                        enabled =
                            c.bool()
                    )
            }

            9 -> {
                decodeModelV2(c)
            }

            13 -> {
                decodeRadarState(c)
            }
        }
    }

    private fun decodeCarState(
        c: Cursor
    ) {

        // vEgo
        val vEgo =
            c.f32()

        // aEgo
        c.f32()

        // vEgoCluster
        val vEgoCluster =
            c.f32()

        // vCruiseCluster
        //
        // 중요:
        // 이 값은 이미 km/h.
        // ×3.6 하면 60 -> 216이 된다.
        val vCruiseCluster =
            c.f32()

        val speedMps =
            when {

                vEgoCluster.isFinite() &&
                    vEgoCluster >= 0f -> {
                    vEgoCluster
                }

                vEgo.isFinite() &&
                    vEgo >= 0f -> {
                    vEgo
                }

                else -> {
                    return
                }
            }

        val old =
            state

        val speedKph =
            (
                speedMps *
                    3.6f
                )
                .coerceIn(
                    0f,
                    260f
                )

        // 255 = V_CRUISE_UNSET
        val cruiseKph =
            if (
                vCruiseCluster
                    .isFinite() &&
                vCruiseCluster > 0f &&
                vCruiseCluster < 250f
            ) {
                vCruiseCluster
            } else {
                0f
            }

        state =
            old.copy(

                speedKph =
                    smooth(
                        old.speedKph,
                        speedKph,
                        0.55f
                    ),

                cruiseKph =
                    cruiseKph,

                connected =
                    true
            )
    }

    private fun decodeControlsState(
        c: Cursor
    ) {

        val enabled =
            c.bool()

        // 이것도 이미 km/h
        val vCruiseCluster =
            c.f32()

        val old =
            state

        val cruiseKph =
            if (
                vCruiseCluster
                    .isFinite() &&
                vCruiseCluster > 0f &&
                vCruiseCluster < 250f
            ) {
                vCruiseCluster
            } else {
                0f
            }

        state =
            old.copy(
                enabled =
                    enabled,
                cruiseKph =
                    cruiseKph
            )
    }

    private fun decodeModelV2(
        c: Cursor
    ) {

        // frameId
        c.u32()

        // frameIdExtra
        c.u32()

        // predicted path
        val position =
            readXyz(c)

        // velocity.x
        c.i16CmList(
