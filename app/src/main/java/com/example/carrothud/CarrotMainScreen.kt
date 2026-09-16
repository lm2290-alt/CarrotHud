package com.example.carrothud

import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
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
        private const val WEB_W = 1280
        private const val WEB_H = 720
    }

    private var surfaceContainer: SurfaceContainer? = null

    @Volatile
    private var isRendering = false

    private var streamJob: Job? = null
    private var webView: WebView? = null

    private var surfaceW = 0
    private var surfaceH = 0

    @Volatile
    private var touchX = -1f

    @Volatile
    private var touchY = -1f

    @Volatile
    private var touchUntil = 0L

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

            carContext.getCarService(
                androidx.car.app.AppManager::class.java
            ).setSurfaceCallback(this)
        }.onFailure {
            log("Init=${it.localizedMessage}")
        }
    }

    override fun onSurfaceAvailable(
        container: SurfaceContainer
    ) {
        surfaceContainer = container
        isRendering = true

        surfaceW = 0
        surfaceH = 0

        log(
            "Surface=${container.width}x${container.height}"
        )

        CoroutineScope(Dispatchers.Main).launch {
            initWebView()
            layoutWebView()
            startStreamingPipeline()
        }
    }

    override fun onSurfaceDestroyed(
        container: SurfaceContainer
    ) {
        isRendering = false
        streamJob?.cancel()
        streamJob = null
        surfaceContainer = null
        surfaceW = 0
        surfaceH = 0
    }

    override fun onClick(
        x: Float,
        y: Float
    ) {
        val wv = webView ?: return

        touchX = x
        touchY = y
        touchUntil =
            SystemClock.uptimeMillis() + 1000L

        log("CLICK=$x,$y")

        val sw = surfaceW
        val sh = surfaceH

        if (sw <= 0 || sh <= 0) return

        val sx = sw / WEB_W.toFloat()
        val sy = sh / WEB_H.toFloat()

        if (sx <= 0f || sy <= 0f) return

        val wx = x / sx
        val wy = y / sy

        if (
            wx < 0f ||
            wx > WEB_W ||
            wy < 0f ||
            wy > WEB_H
        ) return

        wv.post {
            val now =
                SystemClock.uptimeMillis()

            val down =
                MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_DOWN,
                    wx,
                    wy,
                    0
                )

            val up =
                MotionEvent.obtain(
                    now,
                    now + 70L,
                    MotionEvent.ACTION_UP,
                    wx,
                    wy,
                    0
                )

            try {
                wv.dispatchTouchEvent(down)
                wv.dispatchTouchEvent(up)

                /*
                 * 일반 MotionEvent로 안 먹는 요소를 위한
                 * click() 보조 경로.
                 */
                val ix = wx.toInt()
                val iy = wy.toInt()

                val js =
                    "(function(){" +
                    "var e=document.elementFromPoint($ix,$iy);" +
                    "if(!e)return;" +
                    "var t=e.closest(" +
                    "'button,a,input,[role=\"button\"],[onclick]'" +
                    ")||e;" +
                    "if(t&&t.click)t.click();" +
                    "})();"

                wv.evaluateJavascript(js, null)

            } finally {
                down.recycle()
                up.recycle()
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

            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true

                mediaPlaybackRequiresUserGesture =
                    false

                useWideViewPort = true
                loadWithOverviewMode = false
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
                        super.onPageFinished(view, url)

                        fixDisplay(view)
                        autoStartVision(view)
                        installVideoMirror(view)
                    }
                }
        }

        layoutWebView()
    }

    private fun layoutWebView() {
        val wv = webView ?: return

        if (
            wv.width == WEB_W &&
            wv.height == WEB_H
        ) return

        wv.measure(
            View.MeasureSpec.makeMeasureSpec(
                WEB_W,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(
                WEB_H,
                View.MeasureSpec.EXACTLY
            )
        )

        wv.layout(
            0,
            0,
            WEB_W,
            WEB_H
        )
    }

    /*
     * 당근 웹 UI의 논리 화면은 계속 1280x720.
     * 화질 개선 때문에 UI 크기를 바꾸지 않는다.
     */
    private fun fixDisplay(
        view: WebView?
    ) {
        val js =
            "(function(){" +
            "if(window.__aaLayoutFix)return;" +
            "window.__aaLayoutFix=true;" +

            "function fix(){" +
            "var d=document.documentElement;" +
            "d.style.webkitTextSizeAdjust='100%';" +
            "d.style.width='1280px';" +
            "d.style.height='720px';" +
            "d.style.minWidth='1280px';" +
            "d.style.maxWidth='1280px';" +
            "d.style.minHeight='720px';" +
            "d.style.maxHeight='720px';" +
            "d.style.margin='0';" +
            "d.style.padding='0';" +
            "d.style.overflow='hidden';" +

            "if(document.body){" +
            "var b=document.body;" +
            "b.style.webkitTextSizeAdjust='100%';" +
            "b.style.width='1280px';" +
            "b.style.height='720px';" +
            "b.style.minWidth='1280px';" +
            "b.style.maxWidth='1280px';" +
            "b.style.minHeight='720px';" +
            "b.style.maxHeight='720px';" +
            "b.style.margin='0';" +
            "b.style.padding='0';" +
            "b.style.overflow='hidden';" +
            "}" +

            "var m=document.querySelector(" +
            "'meta[name=\"viewport\"]');" +

            "if(!m){" +
            "m=document.createElement('meta');" +
            "m.name='viewport';" +
            "document.head.appendChild(m);" +
            "}" +

            "m.content=" +
            "'width=1280,height=720," +
            "initial-scale=1," +
            "minimum-scale=1," +
            "maximum-scale=1," +
            "user-scalable=no';" +

            "window.scrollTo(0,0);" +
            "}" +

            "fix();" +

            /*
             * 웹쪽 코드가 나중에 viewport/style을
             * 변경하는 경우 다시 원상복구.
             */
            "setInterval(fix,1000);" +
            "})();"

        view?.evaluateJavascript(js, null)
    }

    private fun autoStartVision(
        view: WebView?
    ) {
        val js =
            "(function(){" +
            "var n=0;" +
            "var z=setInterval(function(){" +
            "n++;" +

            "var a=document.getElementsByTagName('*');" +

            "for(var i=0;i<a.length;i++){" +
            "var e=a[i];" +
            "var t=(e.innerText||e.textContent||'').trim();" +

            "if(" +
            "t.indexOf('당근 비전 시작')!==-1||" +
            "t.indexOf('비전 시작')!==-1" +
            "){" +
            "e.click();" +
            "}" +
            "}" +

            "if(n>20)clearInterval(z);" +
            "},500);" +
            "})();"

        view?.evaluateJavascript(js, null)
    }

    /*
     * 성공한 카메라 방식 유지.
     *
     * 차이점:
     * CSS 크기는 그대로 두고
     * canvas 내부 픽셀만 고밀도로 만든다.
     */
    private fun installVideoMirror(
        view: WebView?
    ) {
        val js =
            "(function(){" +
            "if(window.__carrotAaMirror)return;" +
            "window.__carrotAaMirror=true;" +

            "function start(){" +
            "var v=document.getElementById('carrotRoadVideo');" +

            "if(!v){" +
            "setTimeout(start,500);" +
            "return;" +
            "}" +

            "if(document.getElementById(" +
            "'carrotAaVideoMirror'))return;" +

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

            "if(v.videoWidth>0&&v.videoHeight>0){" +

            "var cw=p.clientWidth||1280;" +
            "var ch=p.clientHeight||720;" +

            /*
             * 최대 2배.
             * 단 원본 비디오보다 터무니없이
             * 큰 canvas를 만들지는 않는다.
             */
            "var q=2;" +

            "var bw=Math.round(cw*q);" +
            "var bh=Math.round(ch*q);" +

            "var maxW=Math.max(v.videoWidth,cw);" +
            "var maxH=Math.max(v.videoHeight,ch);" +

            "bw=Math.min(bw,maxW);" +
            "bh=Math.min(bh,maxH);" +

            "bw=Math.max(bw,Math.round(cw));" +
            "bh=Math.max(bh,Math.round(ch));" +

            "if(c.width!==bw)c.width=bw;" +
            "if(c.height!==bh)c.height=bh;" +

            "var vw=v.videoWidth;" +
            "var vh=v.videoHeight;" +

            /*
             * 실제 canvas pixel 기준 cover.
             */
            "var s=Math.max(bw/vw,bh/vh);" +

            "var dw=vw*s;" +
            "var dh=vh*s;" +
            "var dx=(bw-dw)/2;" +
            "var dy=(bh-dh)/2;" +

            "try{" +
            "ctx.imageSmoothingEnabled=true;" +
            "ctx.imageSmoothingQuality='high';" +

            "ctx.fillStyle='#000';" +
            "ctx.fillRect(0,0,bw,bh);" +

            "ctx.drawImage(" +
            "v,dx,dy,dw,dh" +
            ");" +

            "}catch(e){}" +
            "}" +

            "requestAnimationFrame(draw);" +
            "}" +

            "draw();" +
            "}" +

            "start();" +
            "})();"

        view?.evaluateJavascript(js, null)
    }

    private fun startStreamingPipeline() {
        streamJob?.cancel()

        streamJob =
            CoroutineScope(Dispatchers.IO).launch {

                drawMessage("콤마4 탐색 중...")

                val ip = findCommaDeviceIp()

                if (ip == null) {
                    drawMessage(
                        "콤마4(포트 7000)를 찾지 못함"
                    )

                    delay(2000)

                    if (isRendering) {
                        startStreamingPipeline()
                    }

                    return@launch
                }

                log("Comma=$ip")

                drawMessage(
                    "영상 스트리밍 연결 중..."
                )

                withContext(Dispatchers.Main) {
                    layoutWebView()

                    webView?.loadUrl(
                        "http://$ip:7000"
                    )
                }

                startRenderLoop()
            }
    }

    private suspend fun startRenderLoop() {
        while (isRendering) {

            withContext(Dispatchers.Main) {

                val c =
                    surfaceContainer
                        ?: return@withContext

                val surface =
                    c.surface
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

                layoutWebView()

                var canvas:
                    android.graphics.Canvas? = null

                try {
                    canvas =
                        surface.lockCanvas(null)

                    if (canvas != null) {

                        canvas.drawColor(Color.BLACK)

                        /*
                         * 첫 정상 프레임에서만
                         * Android Auto Surface 크기를 잡는다.
                         */
                        if (
                            surfaceW <= 0 ||
                            surfaceH <= 0
                        ) {
                            surfaceW = canvas.width
                            surfaceH = canvas.height

                            log(
                                "RenderSurface=" +
                                "${surfaceW}x${surfaceH}"
                            )
                        }

                        val sx =
                            surfaceW /
                                WEB_W.toFloat()

                        val sy =
                            surfaceH /
                                WEB_H.toFloat()

                        canvas.save()

                        /*
                         * 잘라내지 않고 Surface에 맞춤.
                         * 1280x720 UI 전체가 항상 남는다.
                         */
                        canvas.scale(sx, sy)

                        wv.draw(canvas)

                        canvas.restore()

                        /*
                         * SurfaceCallback.onClick이
                         * 실제로 들어오면 빨간점.
                         */
                        if (
                            SystemClock.uptimeMillis() <
                            touchUntil
                        ) {
                            val paint =
                                Paint().apply {
                                    color = Color.RED
                                    style =
                                        Paint.Style.FILL
                                    isAntiAlias = true
                                }

                            canvas.drawCircle(
                                touchX,
                                touchY,
                                28f,
                                paint
                            )
                        }
                    }

                } catch (e: Throwable) {
                    log(
                        "Render=${e.localizedMessage}"
                    )

                } finally {
                    if (canvas != null) {
                        runCatching {
                            surface.unlockCanvasAndPost(
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
        val c = surfaceContainer ?: return
        val surface = c.surface ?: return

        if (
            !surface.isValid ||
            !isRendering
        ) return

        var canvas:
            android.graphics.Canvas? = null

        try {
            canvas = surface.lockCanvas(null)

            if (canvas != null) {
                canvas.drawColor(Color.BLACK)

                val paint =
                    Paint().apply {
                        color = Color.WHITE
                        textSize = 32f
                        textAlign =
                            Paint.Align.CENTER
                        isAntiAlias = true
                    }

                canvas.drawText(
                    message,
                    canvas.width / 2f,
                    canvas.height / 2f,
                    paint
                )
            }

        } catch (e: Throwable) {
            log(
                "Message=${e.localizedMessage}"
            )

        } finally {
            if (canvas != null) {
                runCatching {
                    surface.unlockCanvasAndPost(
                        canvas
                    )
                }
            }
        }
    }

    private suspend fun findCommaDeviceIp():
        String? = coroutineScope {

        val subnets =
            (
                getLocalSubnets() +
                listOf(
                    "10.142.142",
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
                tasks.awaitAll()
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

            for (network in interfaces) {

                val addresses =
                    Collections.list(
                        network.inetAddresses
                    )

                for (address in addresses) {

                    if (
                        !address.isLoopbackAddress &&
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
            log(
                "Subnet=${e.localizedMessage}"
            )
        }

        return result.distinct()
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

        } catch (_: Exception) {
            false
        }
    }

    private fun log(
        message: String
    ) {
        runCatching {
            File(
                carContext.filesDir,
                "carrot_crash.txt"
            ).appendText(
                "${java.util.Date()} $message\n"
            )
        }
    }

    override fun onGetTemplate(): Template {

        val mapActions =
            ActionStrip.Builder()
                .addAction(Action.PAN)
                .build()

        val actions =
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

        return NavigationTemplate.Builder()
            .setMapActionStrip(mapActions)
            .setActionStrip(actions)
            .build()
    }
    }
