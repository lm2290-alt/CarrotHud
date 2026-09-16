package com.example.carrothud

import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.car.app.AppManager
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
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class CarrotMainScreen(
    carContext: CarContext
) : Screen(carContext), SurfaceCallback {

    companion object {
        private const val WEB_W = 1280
        private const val WEB_H = 720
    }

    private var surfaceContainer:
        SurfaceContainer? = null

    private var webView: WebView? = null
    private var streamJob: Job? = null

    @Volatile
    private var rendering = false

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
            carContext.getCarService(
                NavigationManager::class.java
            ).setNavigationManagerCallback(
                object :
                    NavigationManagerCallback {
                    override fun onStopNavigation() {}
                }
            )

            carContext.getCarService(
                AppManager::class.java
            ).setSurfaceCallback(this)
        }
    }

    override fun onSurfaceAvailable(
        container: SurfaceContainer
    ) {
        surfaceContainer = container
        rendering = true

        CoroutineScope(
            Dispatchers.Main
        ).launch {
            initWebView()
            startPipeline()
        }
    }

    override fun onSurfaceDestroyed(
        container: SurfaceContainer
    ) {
        rendering = false
        streamJob?.cancel()
        streamJob = null
        surfaceContainer = null
    }

    override fun onClick(
        x: Float,
        y: Float
    ) {
        val wv = webView ?: return
        val sw = renderW
        val sh = renderH

        touchX = x
        touchY = y
        touchUntil =
            SystemClock.uptimeMillis() + 700

        if (sw <= 0 || sh <= 0) return

        val wx =
            x * WEB_W / sw.toFloat()

        val wy =
            y * WEB_H / sh.toFloat()

        if (
            wx < 0 ||
            wy < 0 ||
            wx > WEB_W ||
            wy > WEB_H
        ) return

        val ix = wx.toInt()
        val iy = wy.toInt()

        wv.post {
            val js =
                """
                (function(){
                  var x=$ix,y=$iy;
                  var e=document.elementFromPoint(x,y);
                  if(!e)return;

                  var t=e.closest(
                    'button,a,input,label,[role="button"],[onclick]'
                  )||e;

                  function fire(name,Type){
                    try{
                      t.dispatchEvent(
                        new Type(name,{
                          bubbles:true,
                          cancelable:true,
                          composed:true,
                          clientX:x,
                          clientY:y,
                          screenX:x,
                          screenY:y,
                          button:0,
                          buttons:
                            (name.indexOf('down')>=0 ? 1 : 0),
                          pointerId:1,
                          pointerType:'touch',
                          isPrimary:true
                        })
                      );
                    }catch(z){}
                  }

                  if(window.PointerEvent){
                    fire('pointerdown',PointerEvent);
                  }

                  fire('mousedown',MouseEvent);

                  if(window.PointerEvent){
                    fire('pointerup',PointerEvent);
                  }

                  fire('mouseup',MouseEvent);
                  fire('click',MouseEvent);

                  try{
                    if(
                      t.tagName==='BUTTON' ||
                      t.tagName==='A'
                    ) t.click();
                  }catch(z){}
                })();
                """.trimIndent()

            wv.evaluateJavascript(js, null)
        }
    }

    private fun initWebView() {
        if (webView != null) {
            webView?.onResume()
            webView?.resumeTimers()
            return
        }

        webView =
            WebView(carContext).apply {

                setLayerType(
                    View.LAYER_TYPE_HARDWARE,
                    null
                )

                setRendererPriorityPolicy(
                    WebView.RENDERER_PRIORITY_IMPORTANT,
                    false
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true

                    mediaPlaybackRequiresUserGesture =
                        false

                    useWideViewPort = true
                    loadWithOverviewMode = false
                    textZoom = 100

                    layoutAlgorithm =
                        WebSettings
                            .LayoutAlgorithm.NORMAL

                    setSupportZoom(false)

                    builtInZoomControls =
                        false

                    displayZoomControls =
                        false

                    mixedContentMode =
                        WebSettings
                            .MIXED_CONTENT_ALWAYS_ALLOW
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

                            view?.onResume()
                            view?.resumeTimers()

                            fixLayout(view)

                            startVisionOnce(view)

                            installVideoMirror(view)
                        }
                    }
            }

        layoutWebView()

        webView?.onResume()
        webView?.resumeTimers()
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

    private fun fixLayout(
        view: WebView?
    ) {
        val js =
            """
            (function(){
              if(document.getElementById(
                'carrotAaFixed'
              ))return;

              var m=document.querySelector(
                'meta[name="viewport"]'
              );

              if(!m){
                m=document.createElement('meta');
                m.name='viewport';
                document.head.appendChild(m);
              }

              m.content=
                'width=1280,height=720,'+
                'initial-scale=1,'+
                'maximum-scale=1,'+
                'user-scalable=no';

              var s=document.createElement(
                'style'
              );

              s.id='carrotAaFixed';

              s.textContent=
                'html,body{'+
                'width:1280px!important;'+
                'height:720px!important;'+
                'min-width:1280px!important;'+
                'max-width:1280px!important;'+
                'min-height:720px!important;'+
                'max-height:720px!important;'+
                'margin:0!important;'+
                'padding:0!important;'+
                'overflow:hidden!important;'+
                '-webkit-text-size-adjust:100%!important;'+
                '}';

              document.head.appendChild(s);
              window.scrollTo(0,0);
            })();
            """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun startVisionOnce(
        view: WebView?
    ) {
        val js =
            """
            (function(){
              if(window.__carrotAaStarted)return;
              window.__carrotAaStarted=true;

              var tries=0;

              var timer=setInterval(function(){
                tries++;

                var nodes=document.querySelectorAll(
                  'button,[role="button"]'
                );

                for(var i=0;i<nodes.length;i++){
                  var t=(
                    nodes[i].innerText||
                    nodes[i].textContent||
                    ''
                  ).trim();

                  if(
                    t==='당근 비전 시작' ||
                    t==='비전 시작'
                  ){
                    clearInterval(timer);
                    nodes[i].click();
                    return;
                  }
                }

                if(tries>=20){
                  clearInterval(timer);
                }
              },250);
            })();
            """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun installVideoMirror(
        view: WebView?
    ) {
        val js =
            """
            (function(){
              if(window.__carrotAaMirror)return;
              window.__carrotAaMirror=true;

              function start(){
                var v=document.getElementById(
                  'carrotRoadVideo'
                );

                if(!v){
                  setTimeout(start,300);
                  return;
                }

                var p=v.parentElement;

                if(!p){
                  setTimeout(start,300);
                  return;
                }

                var old=document.getElementById(
                  'carrotAaVideoMirror'
                );

                if(old)return;

                var c=document.createElement(
                  'canvas'
                );

                c.id='carrotAaVideoMirror';

                c.style.position='absolute';
                c.style.left='0';
                c.style.top='0';
                c.style.width='100%';
                c.style.height='100%';
                c.style.zIndex='0';
                c.style.pointerEvents='none';
                c.style.background='#000';

                p.insertBefore(c,v);

                v.style.visibility='hidden';

                var ctx=c.getContext(
                  '2d',
                  {alpha:false}
                );

                function draw(){
                  if(
                    v.videoWidth>0 &&
                    v.videoHeight>0
                  ){
                    var cw=
                      p.clientWidth||1280;

                    var ch=
                      p.clientHeight||720;

                    var q=Math.min(
                      2,
                      Math.max(
                        1,
                        v.videoWidth/cw
                      )
                    );

                    var bw=
                      Math.round(cw*q);

                    var bh=
                      Math.round(ch*q);

                    if(c.width!==bw)
                      c.width=bw;

                    if(c.height!==bh)
                      c.height=bh;

                    var vw=v.videoWidth;
                    var vh=v.videoHeight;

                    var scale=Math.max(
                      bw/vw,
                      bh/vh
                    );

                    var dw=vw*scale;
                    var dh=vh*scale;

                    var dx=(bw-dw)/2;
                    var dy=(bh-dh)/2;

                    try{
                      ctx.imageSmoothingEnabled=true;
                      ctx.imageSmoothingQuality='high';

                      ctx.fillStyle='#000';

                      ctx.fillRect(
                        0,0,bw,bh
                      );

                      ctx.drawImage(
                        v,
                        dx,
                        dy,
                        dw,
                        dh
                      );
                    }catch(e){}
                  }

                  requestAnimationFrame(draw);
                }

                draw();
              }

              start();
            })();
            """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun startPipeline() {
        streamJob?.cancel()

        streamJob =
            CoroutineScope(
                Dispatchers.IO
            ).launch {

                drawMessage(
                    "콤마4 탐색 중..."
                )

                val ip =
                    findCommaDeviceIp()

                if (ip == null) {
                    drawMessage(
                        "콤마4를 찾지 못함"
                    )

                    delay(2000)

                    if (rendering) {
                        startPipeline()
                    }

                    return@launch
                }

                withContext(
                    Dispatchers.Main
                ) {
                    layoutWebView()

                    webView?.onResume()
                    webView?.resumeTimers()

                    webView?.loadUrl(
                        "http://$ip:7000"
                    )
                }

                renderLoop()
            }
    }

    private suspend fun renderLoop() {
        while (rendering) {

            withContext(
                Dispatchers.Main
            ) {
                val surface =
                    surfaceContainer
                        ?.surface
                        ?: return@withContext

                val wv =
                    webView
                        ?: return@withContext

                if (!surface.isValid) {
                    return@withContext
                }

                var canvas:
                    android.graphics.Canvas? =
                    null

                try {
                    canvas =
                        surface.lockCanvas(null)

                    canvas?.let {
                        it.drawColor(Color.BLACK)

                        renderW = it.width
                        renderH = it.height

                        val sx =
                            it.width /
                                WEB_W.toFloat()

                        val sy =
                            it.height /
                                WEB_H.toFloat()

                        it.save()
                        it.scale(sx, sy)
                        wv.draw(it)
                        it.restore()

                        if (
                            SystemClock.uptimeMillis()
                            < touchUntil
                        ) {
                            val p =
                                Paint().apply {
                                    color = Color.RED
                                    isAntiAlias = true
                                }

                            it.drawCircle(
                                touchX,
                                touchY,
                                24f,
                                p
                            )
                        }
                    }

                } finally {
                    canvas?.let {
                        runCatching {
                            surface
                                .unlockCanvasAndPost(it)
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
        val surface =
            surfaceContainer?.surface
                ?: return

        if (!surface.isValid) return

        var canvas:
            android.graphics.Canvas? = null

        try {
            canvas =
                surface.lockCanvas(null)

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
            canvas?.let {
                runCatching {
                    surface
                        .unlockCanvasAndPost(it)
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
                        val ip="$subnet.$i"

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

    override fun onGetTemplate():
        Template {

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
                                startPipeline()
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
