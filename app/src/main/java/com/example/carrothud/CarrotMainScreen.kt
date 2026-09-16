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

    @Volatile
    private var renderW = 0

    @Volatile
    private var renderH = 0

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
        }
    }

    override fun onSurfaceAvailable(
        container: SurfaceContainer
    ) {
        surfaceContainer = container
        isRendering = true
        renderW = 0
        renderH = 0

        CoroutineScope(Dispatchers.Main).launch {
            initWebView()
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
        renderW = 0
        renderH = 0
    }

    override fun onClick(x: Float, y: Float) {
        val wv = webView ?: return

        touchX = x
        touchY = y
        touchUntil =
            SystemClock.uptimeMillis() + 1000

        val sw = renderW
        val sh = renderH

        if (sw <= 0 || sh <= 0) return

        val wx =
            x / (sw / WEB_W.toFloat())

        val wy =
            y / (sh / WEB_H.toFloat())

        if (
            wx !in 0f..WEB_W.toFloat() ||
            wy !in 0f..WEB_H.toFloat()
        ) return

        wv.post {
            val now =
                SystemClock.uptimeMillis()

            val down = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_DOWN,
                wx,
                wy,
                0
            )

            val up = MotionEvent.obtain(
                now,
                now + 60,
                MotionEvent.ACTION_UP,
                wx,
                wy,
                0
            )

            try {
                wv.dispatchTouchEvent(down)
                wv.dispatchTouchEvent(up)

                val js =
                    "(function(){" +
                    "var x=${wx.toInt()}," +
                    "y=${wy.toInt()};" +
                    "var e=document.elementFromPoint(x,y);" +
                    "if(!e)return;" +
                    "var t=e.closest(" +
                    "'button,a,input,[role=\"button\"],[onclick]'" +
                    ")||e;" +
                    "try{" +
                    "t.dispatchEvent(new PointerEvent(" +
                    "'pointerdown',{bubbles:true,clientX:x,clientY:y}));" +
                    "t.dispatchEvent(new PointerEvent(" +
                    "'pointerup',{bubbles:true,clientX:x,clientY:y}));" +
                    "t.dispatchEvent(new MouseEvent(" +
                    "'mousedown',{bubbles:true,clientX:x,clientY:y}));" +
                    "t.dispatchEvent(new MouseEvent(" +
                    "'mouseup',{bubbles:true,clientX:x,clientY:y}));" +
                    "t.dispatchEvent(new MouseEvent(" +
                    "'click',{bubbles:true,clientX:x,clientY:y}));" +
                    "}catch(z){if(t.click)t.click();}" +
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

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
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
                        super.onPageFinished(
                            view,
                            url
                        )

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

    private fun fixDisplay(view: WebView?) {
        val js =
            "(function(){" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){" +
            "m=document.createElement('meta');" +
            "m.name='viewport';" +
            "document.head.appendChild(m);" +
            "}" +
            "m.content=" +
            "'width=1280,height=720," +
            "initial-scale=1,maximum-scale=1,user-scalable=no';" +
            "var s=document.createElement('style');" +
            "s.id='aaFixedLayout';" +
            "s.textContent=" +
            "'html,body{" +
            "width:1280px!important;" +
            "height:720px!important;" +
            "min-width:1280px!important;" +
            "max-width:1280px!important;" +
            "min-height:720px!important;" +
            "max-height:720px!important;" +
            "margin:0!important;" +
            "padding:0!important;" +
            "overflow:hidden!important;" +
            "-webkit-text-size-adjust:100%!important;" +
            "}' +" +
            "'*,*::before,*::after{" +
            "animation-duration:0s!important;" +
            "transition-duration:0s!important;" +
            "}';" +
            "document.head.appendChild(s);" +
            "})();"

        view?.evaluateJavascript(js, null)
    }

    private fun autoStartVision(view: WebView?) {
        val js =
            "(function(){" +
            "var n=0;" +
            "var z=setInterval(function(){" +
            "n++;" +
            "var a=document.getElementsByTagName('*');" +
            "for(var i=0;i<a.length;i++){" +
            "var e=a[i];" +
            "var t=(e.innerText||e.textContent||'').trim();" +
            "if(t.indexOf('당근 비전 시작')!==-1||" +
            "t.indexOf('비전 시작')!==-1)e.click();" +
            "}" +
            "if(n>20)clearInterval(z);" +
            "},500);" +
            "})();"

        view?.evaluateJavascript(js, null)
    }

    private fun installVideoMirror(view: WebView?) {
        val js =
            "(function(){" +
            "if(window.__carrotAaMirror)return;" +
            "window.__carrotAaMirror=true;" +

            "function start(){" +
            "var v=document.getElementById('carrotRoadVideo');" +
            "if(!v){setTimeout(start,500);return;}" +

            "var p=v.parentElement;" +
            "if(!p){setTimeout(start,500);return;}" +

            "if(document.getElementById(" +
            "'carrotAaVideoMirror'))return;" +

            "var c=document.createElement('canvas');" +
            "c.id='carrotAaVideoMirror';" +
            "c.style.position='absolute';" +
            "c.style.left='0';" +
            "c.style.top='0';" +
            "c.style.width='100%';" +
            "c.style.height='100%';" +
            "c.style.zIndex='0';" +
            "c.style.pointerEvents='none';" +

            "p.insertBefore(c,v);" +
            "v.style.visibility='hidden';" +

            "var ctx=c.getContext('2d',{alpha:false});" +

            "function draw(){" +
            "if(v.videoWidth>0&&v.videoHeight>0){" +

            "var cw=p.clientWidth||1280;" +
            "var ch=p.clientHeight||720;" +

            "var q=Math.min(2," +
            "Math.max(1,v.videoWidth/cw));" +

            "var bw=Math.round(cw*q);" +
            "var bh=Math.round(ch*q);" +

            "if(c.width!==bw)c.width=bw;" +
            "if(c.height!==bh)c.height=bh;" +

            "var vw=v.videoWidth;" +
            "var vh=v.videoHeight;" +

            "var scale=Math.max(" +
            "bw/vw,bh/vh);" +

            "var dw=vw*scale;" +
            "var dh=vh*scale;" +
            "var dx=(bw-dw)/2;" +
            "var dy=(bh-dh)/2;" +

            "try{" +
            "ctx.imageSmoothingEnabled=true;" +
            "ctx.imageSmoothingQuality='high';" +
            "ctx.fillStyle='#000';" +
            "ctx.fillRect(0,0,bw,bh);" +
            "ctx.drawImage(v,dx,dy,dw,dh);" +
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

                if (!surface.isValid) {
                    return@withContext
                }

                var canvas:
                    android.graphics.Canvas? = null

                try {
                    canvas =
                        surface.lockCanvas(null)

                    if (canvas != null) {

                        canvas.drawColor(Color.BLACK)

                        renderW = canvas.width
                        renderH = canvas.height

                        val sx =
                            renderW /
                                WEB_W.toFloat()

                        val sy =
                            renderH /
                                WEB_H.toFloat()

                        canvas.save()
                        canvas.scale(sx, sy)
                        wv.draw(canvas)
                        canvas.restore()

                        if (
                            SystemClock.uptimeMillis() <
                            touchUntil
                        ) {
                            val p =
                                Paint().apply {
                                    color = Color.RED
                                    isAntiAlias = true
                                }

                            canvas.drawCircle(
                                touchX,
                                touchY,
                                28f,
                                p
                            )
                        }
                    }

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

    private fun drawMessage(message: String) {
        val c = surfaceContainer ?: return
        val surface = c.surface ?: return

        if (!surface.isValid) return

        var canvas:
            android.graphics.Canvas? = null

        try {
            canvas = surface.lockCanvas(null)

            canvas?.let {
                it.drawColor(Color.BLACK)

                val p =
                    Paint().apply {
                        color = Color.WHITE
                        textSize = 32f
                        textAlign =
                            Paint.Align.CENTER
                        isAntiAlias = true
                    }

                it.drawText(
                    message,
                    it.width / 2f,
                    it.height / 2f,
                    p
                )
            }

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

            val jobs =
                (2..254).map { i ->
                    async(Dispatchers.IO) {
                        val ip = "$subnet.$i"

                        if (
                            isPortOpen(
                                ip,
                                7000,
                                250
                            )
                        ) ip else null
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

    private fun getLocalSubnets():
        List<String> {

        val result =
            mutableListOf<String>()

        runCatching {
            val interfaces =
                Collections.list(
                    NetworkInterface
                        .getNetworkInterfaces()
                )

            for (network in interfaces) {
                for (
                    address in
                    Collections.list(
                        network.inetAddresses
                    )
                ) {
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

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
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
