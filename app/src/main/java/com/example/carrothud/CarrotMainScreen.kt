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
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class CarrotMainScreen(carContext: CarContext) :
    Screen(carContext), SurfaceCallback {

    companion object {
        private const val WEB_WIDTH = 1280
        private const val WEB_HEIGHT = 720
    }

    private var surfaceContainer: SurfaceContainer? = null

    @Volatile
    private var isRendering = false

    private var streamJob: Job? = null
    private var webView: WebView? = null

    private val renderLock = Any()
    private var webViewSized = false

    init {
        runCatching {
            val nm = carContext.getCarService(
                NavigationManager::class.java
            )

            nm.setNavigationManagerCallback(
                object : NavigationManagerCallback {
                    override fun onStopNavigation() {}
                }
            )

            carContext
                .getCarService(
                    androidx.car.app.AppManager::class.java
                )
                .setSurfaceCallback(this)

        }.onFailure {
            saveCustomLog("Init: ${it.localizedMessage}")
        }
    }

    override fun onSurfaceAvailable(
        container: SurfaceContainer
    ) {
        synchronized(renderLock) {
            surfaceContainer = container
            isRendering = true
        }

        CoroutineScope(Dispatchers.Main).launch {
            initWebView()
            sizeWebView()
            startStreamingPipeline()
        }
    }

    override fun onSurfaceDestroyed(
        container: SurfaceContainer
    ) {
        synchronized(renderLock) {
            isRendering = false
            streamJob?.cancel()
            streamJob = null
            surfaceContainer = null
        }
    }

    override fun onClick(x: Float, y: Float) {
        val wv = webView ?: return
        val container = surfaceContainer ?: return

        val sw = container.width
        val sh = container.height

        if (sw <= 0 || sh <= 0) return

        wv.post {
            val scale = minOf(
                sw / WEB_WIDTH.toFloat(),
                sh / WEB_HEIGHT.toFloat()
            )

            if (scale <= 0f) return@post

            val dx =
                (sw - WEB_WIDTH * scale) / 2f

            val dy =
                (sh - WEB_HEIGHT * scale) / 2f

            val webX = (x - dx) / scale
            val webY = (y - dy) / scale

            if (
                webX < 0f ||
                webX > WEB_WIDTH ||
                webY < 0f ||
                webY > WEB_HEIGHT
            ) {
                return@post
            }

            val jsX = webX.toInt()
            val jsY = webY.toInt()

            val script =
                "(function(){" +
                "var e=document.elementFromPoint(" +
                jsX + "," + jsY + ");" +
                "if(!e)return 'NONE';" +
                "var t=e.closest(" +
                "'button,a,input,[role=\"button\"],[onclick]'" +
                ")||e;" +
                "t.click();" +
                "return t.tagName;" +
                "})();"

            wv.evaluateJavascript(script) { result ->
                saveCustomLog(
                    "CLICK x=$x y=$y " +
                    "web=$webX,$webY result=$result"
                )
            }
        }
    }

    private fun initWebView() {
        if (webView != null) return

        webView = WebView(carContext).apply {

            setLayerType(
                View.LAYER_TYPE_HARDWARE,
                null
            )

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

            webViewClient =
                object : WebViewClient() {

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?
                    ) {
                        super.onPageFinished(
                            view,
                            url
                        )

                        applyDisplayFix(view)
                        autoStartVision(view)
                        installVideoMirror(view)
                    }
                }
        }

        sizeWebView()
    }

    private fun sizeWebView() {
        val wv = webView ?: return

        if (
            webViewSized &&
            wv.width == WEB_WIDTH &&
            wv.height == WEB_HEIGHT
        ) {
            return
        }

        wv.measure(
            View.MeasureSpec.makeMeasureSpec(
                WEB_WIDTH,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(
                WEB_HEIGHT,
                View.MeasureSpec.EXACTLY
            )
        )

        wv.layout(
            0,
            0,
            WEB_WIDTH,
            WEB_HEIGHT
        )

        webViewSized = true
    }

    private fun applyDisplayFix(
        view: WebView?
    ) {
        val script =
            "(function(){" +
            "document.documentElement.style." +
            "webkitTextSizeAdjust='100%';" +
            "if(document.body)" +
            "document.body.style." +
            "webkitTextSizeAdjust='100%';" +
            "var m=document.querySelector(" +
            "'meta[name=\"viewport\"]');" +
            "if(!m){" +
            "m=document.createElement('meta');" +
            "m.name='viewport';" +
            "document.head.appendChild(m);" +
            "}" +
            "m.content='width=1280," +
            "initial-scale=1.0," +
            "maximum-scale=1.0," +
            "user-scalable=no';" +
            "return 'OK';" +
            "})();"

        view?.evaluateJavascript(script) {
            result ->
            saveCustomLog(
                "DisplayFix=$result"
            )
        }
    }

    private fun autoStartVision(
        view: WebView?
    ) {
        val script =
            "(function(){" +
            "var n=0;" +
            "var timer=setInterval(function(){" +
            "n++;" +
            "var a=document.getElementsByTagName('*');" +
            "for(var i=0;i<a.length;i++){" +
            "var e=a[i];" +
            "var t=(e.innerText||" +
            "e.textContent||'').trim();" +
            "if(t.indexOf('당근 비전 시작')" +
            "!==-1||" +
            "t.indexOf('비전 시작')!==-1){" +
            "e.click();" +
            "}" +
            "}" +
            "if(n>20)clearInterval(timer);" +
            "},500);" +
            "})();"

        view?.evaluateJavascript(
            script,
            null
        )
    }

    private fun installVideoMirror(
        view: WebView?
    ) {
        val script =
            "(function(){" +
            "if(window.__carrotAaMirror)return;" +
            "window.__carrotAaMirror=true;" +

            "function start(){" +
            "var v=document.getElementById(" +
            "'carrotRoadVideo');" +

            "if(!v){" +
            "setTimeout(start,500);" +
            "return;" +
            "}" +

            "var old=document.getElementById(" +
            "'carrotAaVideoMirror');" +
            "if(old)return;" +

            "var p=v.parentElement;" +
            "if(!p){" +
            "setTimeout(start,500);" +
            "return;" +
            "}" +

            "var c=document.createElement('canvas');" +
            "c.id='carrotAaVideoMirror';" +

            "c.style.position='absolute';" +
            "c.style.left='0';" +
            "c.style.top='0';" +
            "c.style.width='100%';" +
            "c.style.height='100%';" +
            "c.style.zIndex='0';" +
            "c.style.pointerEvents='none';" +
            "c.style.background='#000';" +

            "p.insertBefore(c,v);" +

            "v.style.visibility='hidden';" +

            "var ctx=c.getContext(" +
            "'2d',{alpha:false});" +

            "function draw(){" +
            "if(v.videoWidth>0&&" +
            "v.videoHeight>0){" +

            "var cw=p.clientWidth||1280;" +
            "var ch=p.clientHeight||720;" +

            "if(c.width!==cw)c.width=cw;" +
            "if(c.height!==ch)c.height=ch;" +

            "var vw=v.videoWidth;" +
            "var vh=v.videoHeight;" +

            "var s=Math.max(" +
            "cw/vw,ch/vh);" +

            "var dw=vw*s;" +
            "var dh=vh*s;" +
            "var dx=(cw-dw)/2;" +
            "var dy=(ch-dh)/2;" +

            "try{" +
            "ctx.fillStyle='#000';" +
            "ctx.fillRect(0,0,cw,ch);" +
            "ctx.drawImage(" +
            "v,dx,dy,dw,dh);" +
            "}catch(e){}" +
            "}" +

            "requestAnimationFrame(draw);" +
            "}" +

            "draw();" +
            "}" +

            "start();" +
            "})();"

        view?.evaluateJavascript(
            script,
            null
        )
    }

    private fun startStreamingPipeline() {
        streamJob?.cancel()

        streamJob =
            CoroutineScope(
                Dispatchers.IO
            ).launch {

                drawMessage(
                    "콤마4 탐색 중..."
                )

                val commaIp =
                    findCommaDeviceIp()

                if (commaIp == null) {
                    drawMessage(
                        "콤마4(포트 7000)를 찾지 못함"
                    )

                    delay(2000)

                    if (isRendering) {
                        startStreamingPipeline()
                    }

                    return@launch
                }

                saveCustomLog(
                    "Comma=$commaIp"
                )

                drawMessage(
                    "영상 스트리밍 연결 중..."
                )

                val url =
                    "http://$commaIp:7000"

                withContext(
                    Dispatchers.Main
                ) {
                    sizeWebView()
                    webView?.loadUrl(url)
                }

                startSurfaceRenderLoop()
            }
    }

    private suspend fun startSurfaceRenderLoop() {
        while (isRendering) {

            withContext(
                Dispatchers.Main
            ) {
                val container =
                    surfaceContainer
                        ?: return@withContext

                val surface =
                    container.surface
                        ?: return@withContext

                val wv =
                    webView
                        ?: return@withContext

                if (
                    !surface.isValid ||
                    !isRendering
                ) {
                    return@withContext
                }

                sizeWebView()

                var canvas:
                    android.graphics.Canvas? =
                    null

                try {
                    canvas =
                        surface.lockCanvas(null)

                    if (canvas != null) {
                        canvas.drawColor(
                            Color.BLACK
                        )

                        val scale =
                            minOf(
                                canvas.width /
                                    WEB_WIDTH.toFloat(),
                                canvas.height /
                                    WEB_HEIGHT.toFloat()
                            )

                        val dx =
                            (
                                canvas.width -
                                WEB_WIDTH * scale
                            ) / 2f

                        val dy =
                            (
                                canvas.height -
                                WEB_HEIGHT * scale
                            ) / 2f

                        canvas.save()

                        canvas.translate(
                            dx,
                            dy
                        )

                        canvas.scale(
                            scale,
                            scale
                        )

                        wv.draw(canvas)

                        canvas.restore()
                    }

                } catch (t: Throwable) {
                    saveCustomLog(
                        "Render: " +
                        t.localizedMessage
                    )

                } finally {
                    if (canvas != null) {
                        runCatching {
                            surface
                                .unlockCanvasAndPost(
                                    canvas
                                )
                        }
                    }
                }
            }

            delay(33)
        }
    }

    private fun drawMessage(
        message: String
    ) {
        synchronized(renderLock) {

            val container =
                surfaceContainer ?: return

            val surface =
                container.surface ?: return

            if (
                !surface.isValid ||
                !isRendering
            ) return

            var canvas:
                android.graphics.Canvas? =
                null

            try {
                canvas =
                    surface.lockCanvas(null)

                if (canvas != null) {

                    val width =
                        if (
                            container.width > 0
                        ) {
                            container.width
                        } else {
                            canvas.width
                        }

                    val height =
                        if (
                            container.height > 0
                        ) {
                            container.height
                        } else {
                            canvas.height
                        }

                    canvas.drawColor(
                        Color.BLACK
                    )

                    val paint =
                        Paint().apply {
                            color =
                                Color.WHITE
                            textSize = 32f
                            textAlign =
                                Paint.Align.CENTER
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
                    "Message: " +
                    t.localizedMessage
                )

            } finally {
                if (canvas != null) {
                    runCatching {
                        surface
                            .unlockCanvasAndPost(
                                canvas
                            )
                    }
                }
            }
        }
    }

    private fun saveCustomLog(
        msg: String
    ) {
        runCatching {
            val file =
                File(
                    carContext.filesDir,
                    "carrot_crash.txt"
                )

            file.appendText(
                "${java.util.Date()}: " +
                "$msg\n"
            )
        }
    }

    private suspend fun findCommaDeviceIp():
        String? =
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
                        "192.168.8",
                        "10.142.142"
                    )
                ).distinct()

            for (
                subnet in candidateSubnets
            ) {
                val tasks =
                    (2..254).map { i ->

                        async(
                            Dispatchers.IO
                        ) {
                            val ip =
                                "$subnet.$i"

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

    private fun getLocalSubnets():
        List<String> {

        val result =
            mutableListOf<String>()

        try {
            val interfaces =
                Collections.list(
                    NetworkInterface
                        .getNetworkInterfaces()
                )

            for (
                networkInterface in interfaces
            ) {
                val addresses =
                    Collections.list(
                        networkInterface
                            .inetAddresses
                    )

                for (
                    address in addresses
                ) {
                    if (
                        !address
                            .isLoopbackAddress &&
                        address is Inet4Address
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

        } catch (e: Exception) {
            saveCustomLog(
                "Subnet: " +
                e.localizedMessage
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
                    InetSocketAddress(
                        ip,
                        port
                    ),
                    timeoutMs
                )
            }

            true

        } catch (e: Exception) {
            false
        }
    }

    override fun onGetTemplate():
        Template {

        return NavigationTemplate
            .Builder()
            .setActionStrip(
                ActionStrip
                    .Builder()
                    .addAction(
                        Action
                            .Builder()
                            .setTitle(
                                "재시도"
                            )
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
