package com.example.carrothud

import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class CarrotMainScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var surfaceContainer: SurfaceContainer? = null

    @Volatile
    private var isRendering = false

    private var streamJob: Job? = null
    private var webView: WebView? = null

    private val renderLock = Any()

    private var lastWidth = -1
    private var lastHeight = -1

    init {
        runCatching {
            val nm = carContext.getCarService(NavigationManager::class.java)

            nm.setNavigationManagerCallback(
                object : NavigationManagerCallback {
                    override fun onStopNavigation() {}
                }
            )

            carContext
                .getCarService(androidx.car.app.AppManager::class.java)
                .setSurfaceCallback(this)

        }.onFailure {
            saveCustomLog("Init: ${it.localizedMessage}")
        }
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        synchronized(renderLock) {
            surfaceContainer = container
            isRendering = true
        }

        CoroutineScope(Dispatchers.Main).launch {
            initWebView()
            startStreamingPipeline()
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        synchronized(renderLock) {
            isRendering = false
            streamJob?.cancel()
            streamJob = null
            surfaceContainer = null
        }
    }

    /*
     * Android Auto 클릭을 WebView로 전달
     */
    override fun onClick(x: Float, y: Float) {
        val wv = webView ?: return
        val container = surfaceContainer ?: return

        val sw = container.width
        val sh = container.height

        if (sw <= 0 || sh <= 0) return

        wv.post {
            if (wv.width <= 0 || wv.height <= 0) {
                return@post
            }

            val webX = x * wv.width / sw.toFloat()
            val webY = y * wv.height / sh.toFloat()

            val jsX = webX.toInt()
            val jsY = webY.toInt()

            val script =
                "(function(){" +
                "var e=document.elementFromPoint($jsX,$jsY);" +
                "if(!e)return 'NONE';" +
                "var t=e.closest('button,a,input,[role=\"button\"],[onclick]')||e;" +
                "t.click();" +
                "return t.tagName;" +
                "})();"

            wv.evaluateJavascript(script) { result ->
                saveCustomLog(
                    "CLICK x=$x y=$y web=$webX,$webY result=$result"
                )
            }
        }
    }

    private fun initWebView() {
        if (webView != null) return

        webView = WebView(carContext).apply {

            setLayerType(View.LAYER_TYPE_SOFTWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false

                useWideViewPort = true
                loadWithOverviewMode = true

                textZoom = 100

                layoutAlgorithm =
                    WebSettings.LayoutAlgorithm.NORMAL

                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false

                mixedContentMode =
                    WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            webViewClient = object : WebViewClient() {

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(view, url)

                    applyDisplayFix(view)
                    autoStartVision(view)
                }
            }
        }
    }

    /*
     * 글자 자동 확대 방지
     *
     * 일부러 JavaScript를 한 줄 문자열로 구성.
     * 이전처럼 """ 문자열이 잘려 컴파일이 깨지는 것을 방지한다.
     */
    private fun applyDisplayFix(view: WebView?) {
        val script =
            "(function(){" +
            "document.documentElement.style.webkitTextSizeAdjust='100%';" +
            "if(document.body)document.body.style.webkitTextSizeAdjust='100%';" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){" +
            "m=document.createElement('meta');" +
            "m.name='viewport';" +
            "document.head.appendChild(m);" +
            "}" +
            "m.content='width=1280,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';" +
            "return 'OK';" +
            "})();"

        view?.evaluateJavascript(script) { result ->
            saveCustomLog("DisplayFix=$result")
        }
    }

    /*
     * 당근 비전 시작 버튼 자동 클릭
     */
    private fun autoStartVision(view: WebView?) {
        val script =
            "(function(){" +
            "var n=0;" +
            "var timer=setInterval(function(){" +
            "n++;" +
            "var a=document.getElementsByTagName('*');" +
            "for(var i=0;i<a.length;i++){" +
            "var e=a[i];" +
            "var t=(e.innerText||e.textContent||'').trim();" +
            "if(t.indexOf('당근 비전 시작')!==-1||t.indexOf('비전 시작')!==-1){" +
            "e.click();" +
            "}" +
            "}" +
            "if(n>20)clearInterval(timer);" +
            "},500);" +
            "})();"

        view?.evaluateJavascript(script, null)
    }

    private fun startStreamingPipeline() {
        streamJob?.cancel()

        streamJob = CoroutineScope(Dispatchers.IO).launch {

            drawMessage("콤마4 탐색 중...")

            val commaIp = findCommaDeviceIp()

            if (commaIp == null) {
                drawMessage("콤마4(포트 7000)를 찾지 못함")

                delay(2000)

                if (isRendering) {
                    startStreamingPipeline()
                }

                return@launch
            }

            saveCustomLog("Comma=$commaIp")

            drawMessage("영상 스트리밍 연결 중...")

            val url = "http://$commaIp:7000"

            withContext(Dispatchers.Main) {
                webView?.loadUrl(url)
            }

            startSurfaceRenderLoop()
        }
    }

    private suspend fun startSurfaceRenderLoop() {
        while (isRendering) {

            withContext(Dispatchers.Main) {

                val container =
                    surfaceContainer ?: return@withContext

                val surface =
                    container.surface ?: return@withContext

                val wv =
                    webView ?: return@withContext

                if (!surface.isValid || !isRendering) {
                    return@withContext
                }

                val width =
                    if (container.width > 0) container.width else 1280

                val height =
                    if (container.height > 0) container.height else 720

                if (width != lastWidth || height != lastHeight) {

                    lastWidth = width
                    lastHeight = height

                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(
                            width,
                            View.MeasureSpec.EXACTLY
                        ),
                        View.MeasureSpec.makeMeasureSpec(
                            height,
                            View.MeasureSpec.EXACTLY
                        )
                    )

                    wv.layout(
                        0,
                        0,
                        width,
                        height
                    )
                }

                var canvas: android.graphics.Canvas? = null

                try {
                    canvas = surface.lockCanvas(null)

                    if (canvas != null) {
                        canvas.drawColor(Color.BLACK)
                        wv.draw(canvas)
                    }

                } catch (t: Throwable) {

                    saveCustomLog(
                        "Render: ${t.localizedMessage}"
                    )

                } finally {

                    if (canvas != null) {
                        runCatching {
                            surface.unlockCanvasAndPost(canvas)
                        }
                    }
                }
            }

            delay(33)
        }
    }

    private fun drawMessage(message: String) {
        synchronized(renderLock) {

            val container =
                surfaceContainer ?: return

            val surface =
                container.surface ?: return

            if (!surface.isValid || !isRendering) return

            var canvas: android.graphics.Canvas? = null

            try {
                canvas = surface.lockCanvas(null)

                if (canvas != null) {

                    val width =
                        if (container.width > 0) {
                            container.width
                        } else {
                            canvas.width
                        }

                    val height =
                        if (container.height > 0) {
                            container.height
                        } else {
                            canvas.height
                        }

                    canvas.drawColor(Color.BLACK)

                    val paint = Paint().apply {
                        color = Color.WHITE
                        textSize = 32f
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }

                    canvas.drawText(
                        message,
                        width / 2f,
                        height / 2f,
                        paint
                    )
                }

            } catch (t: Throwable) {

                saveCustomLog(
                    "Message: ${t.localizedMessage}"
                )

            } finally {

                if (canvas != null) {
                    runCatching {
                        surface.unlockCanvasAndPost(canvas)
                    }
                }
            }
        }
    }

    private fun saveCustomLog(msg: String) {
        runCatching {
            val file =
                File(
                    carContext.filesDir,
                    "carrot_crash.txt"
                )

            file.appendText(
                "${java.util.Date()}: $msg\n"
            )
        }
    }

    private suspend fun findCommaDeviceIp(): String? =
        coroutineScope {

            val candidateSubnets =
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

            for (subnet in candidateSubnets) {

                val tasks =
                    (2..254).map { i ->

                        async(Dispatchers.IO) {

                            val ip = "$subnet.$i"

                            if (
                                isPortOpen(
                                    ip,
                                    7000,
                                    250
                                )
                            ) {
                                ip
                            } else {
                                null
                            }
                        }
                    }

                val found =
                    tasks
                        .awaitAll()
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
        val result = mutableListOf<String>()

        try {
            val interfaces =
                Collections.list(
                    NetworkInterface.getNetworkInterfaces()
                )

            for (networkInterface in interfaces) {

                val addresses =
                    Collections.list(
                        networkInterface.inetAddresses
                    )

                for (address in addresses) {

                    if (
                        !address.isLoopbackAddress &&
                        address is java.net.Inet4Address
                    ) {
                        val host =
                            address.hostAddress ?: continue

                        val dot =
                            host.lastIndexOf('.')

                        if (dot > 0) {
                            result.add(
                                host.substring(0, dot)
                            )
                        }
                    }
                }
            }

        } catch (e: Exception) {

            saveCustomLog(
                "Subnet: ${e.localizedMessage}"
            )
        }

        return result.distinct()
    }

    private fun isPortOpen(
        ip: String,
        port: Int,
        timeoutMs: Int
    ): Boolean {
        return try {

            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(ip, port),
                    timeoutMs
                )
            }

            true

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
