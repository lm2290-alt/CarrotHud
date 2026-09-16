package com.example.carrothud

import android.graphics.Color
import android.graphics.Paint
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

    private var container: SurfaceContainer? = null
    private var webView: WebView? = null
    private var job: Job? = null

    @Volatile
    private var rendering = false

    @Volatile
    private var surfaceW = 0

    @Volatile
    private var surfaceH = 0

    init {
        runCatching {
            carContext.getCarService(
                NavigationManager::class.java
            ).setNavigationManagerCallback(
                object : NavigationManagerCallback {
                    override fun onStopNavigation() {}
                }
            )

            carContext.getCarService(
                AppManager::class.java
            ).setSurfaceCallback(this)
        }
    }

    override fun onSurfaceAvailable(
        surfaceContainer: SurfaceContainer
    ) {
        container = surfaceContainer
        rendering = true

        CoroutineScope(Dispatchers.Main).launch {
            createWebView()
            start()
        }
    }

    override fun onSurfaceDestroyed(
        surfaceContainer: SurfaceContainer
    ) {
        rendering = false
        job?.cancel()
        job = null
        container = null
    }

    override fun onClick(x: Float, y: Float) {
        val wv = webView ?: return
        val sw = surfaceW
        val sh = surfaceH

        if (sw <= 0 || sh <= 0) return

        val wx =
            x * WEB_W.toFloat() / sw.toFloat()

        val wy =
            y * WEB_H.toFloat() / sh.toFloat()

        val js = """
            (function(){
                var x=${wx.toInt()};
                var y=${wy.toInt()};

                var e=document.elementFromPoint(x,y);
                if(!e)return;

                var t=e.closest(
                    'button,a,[role="button"],input,label,[onclick]'
                );

                if(!t){
                    var p=e.parentElement;

                    for(
                        var i=0;
                        i<5 && p;
                        i++,p=p.parentElement
                    ){
                        if(
                            p.tagName==='BUTTON' ||
                            p.tagName==='A' ||
                            p.getAttribute('role')==='button' ||
                            typeof p.onclick==='function'
                        ){
                            t=p;
                            break;
                        }
                    }
                }

                if(!t)t=e;

                try{
                    t.focus({
                        preventScroll:true
                    });
                }catch(z){}

                try{
                    t.click();
                }catch(z){}
            })();
        """.trimIndent()

        wv.evaluateJavascript(js, null)
    }

    private fun createWebView() {
        if (webView != null) return

        webView = WebView(carContext).apply {

            setLayerType(
                View.LAYER_TYPE_HARDWARE,
                null
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
                    WebSettings.LayoutAlgorithm.NORMAL

                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false

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

                        fixPage(view)
                        restoreAutoStart(view)
                        installMirror(view)
                    }
                }
        }

        measureWebView()
    }

    private fun measureWebView() {
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

    private fun fixPage(view: WebView?) {
        val js = """
            (function(){
                var meta=document.querySelector(
                    'meta[name="viewport"]'
                );

                if(!meta){
                    meta=document.createElement('meta');
                    meta.name='viewport';
                    document.head.appendChild(meta);
                }

                meta.content=
                    'width=1280,height=720,'+
                    'initial-scale=1,'+
                    'maximum-scale=1,'+
                    'user-scalable=no';

                var old=document.getElementById(
                    'carrotHudFixedStyle'
                );

                if(old)old.remove();

                var s=document.createElement('style');
                s.id='carrotHudFixedStyle';

                s.textContent=`
                    html,body{
                        width:1280px !important;
                        height:720px !important;
                        min-width:1280px !important;
                        max-width:1280px !important;
                        min-height:720px !important;
                        max-height:720px !important;
                        margin:0 !important;
                        padding:0 !important;
                        overflow:hidden !important;
                        -webkit-text-size-adjust:100% !important;
                    }

                    #carrotStage,
                    .carrot-stage{
                        transition:none !important;
                        animation:none !important;
                    }
                `;

                document.head.appendChild(s);
                window.scrollTo(0,0);
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun restoreAutoStart(view: WebView?) {
        val js = """
            (function(){
                if(window.__carrotHudAutoStart)return;
                window.__carrotHudAutoStart=true;

                var attempts=0;

                var timer=setInterval(function(){
                    attempts++;

                    var all=
                        document.getElementsByTagName('*');

                    for(var i=0;i<all.length;i++){
                        var el=all[i];

                        var txt=(
                            el.innerText ||
                            el.textContent ||
                            ''
                        ).trim();

                        if(
                            txt.indexOf(
                                '당근 비전 시작'
                            )!==-1 ||
                            txt.indexOf(
                                '비전 시작'
                            )!==-1
                        ){
                            var button=
                                el.closest('button');

                            if(button){
                                button.click();
                                clearInterval(timer);
                                return;
                            }

                            if(
                                el.classList &&
                                el.classList.contains(
                                    'vision-start-overlay'
                                )
                            ){
                                var b=
                                    el.querySelector('button');

                                if(b){
                                    b.click();
                                    clearInterval(timer);
                                    return;
                                }
                            }
                        }
                    }

                    if(attempts>=20){
                        clearInterval(timer);
                    }
                },500);
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun installMirror(view: WebView?) {
        val js = """
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

                    var c=document.getElementById(
                        'carrotAaVideoMirror'
                    );

                    if(!c){
                        c=document.createElement(
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
                    }

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

                            if(c.width!==bw)c.width=bw;
                            if(c.height!==bh)c.height=bh;

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
                                ctx.imageSmoothingEnabled=
                                    true;

                                ctx.imageSmoothingQuality=
                                    'high';

                                ctx.fillStyle='#000';

                                ctx.fillRect(
                                    0,
                                    0,
                                    bw,
                                    bh
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

    private fun start() {
        job?.cancel()

        job =
            CoroutineScope(
                Dispatchers.IO
            ).launch {

                drawMessage(
                    "콤마4 탐색 중..."
                )

                val ip=findCommaIp()

                if(ip==null){
                    drawMessage(
                        "콤마4를 찾지 못함"
                    )

                    delay(2000)

                    if(rendering)start()

                    return@launch
                }

                withContext(
                    Dispatchers.Main
                ){
                    measureWebView()

                    webView?.loadUrl(
                        "http://$ip:7000"
                    )
                }

                renderLoop()
            }
    }

    private suspend fun renderLoop() {
        while(rendering){

            withContext(
                Dispatchers.Main
            ){
                val surface=
                    container?.surface
                        ?:return@withContext

                val wv=
                    webView
                        ?:return@withContext

                if(!surface.isValid){
                    return@withContext
                }

                var canvas:
                    android.graphics.Canvas?=null

                try{
                    canvas=
                        surface.lockCanvas(null)

                    canvas?.let{
                        it.drawColor(Color.BLACK)

                        surfaceW=it.width
                        surfaceH=it.height

                        val sx=
                            it.width.toFloat() /
                            WEB_W.toFloat()

                        val sy=
                            it.height.toFloat() /
                            WEB_H.toFloat()

                        it.save()
                        it.scale(sx,sy)

                        wv.draw(it)

                        it.restore()
                    }

                }finally{
                    canvas?.let{
                        runCatching{
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
        message:String
    ){
        val surface=
            container?.surface ?:return

        if(!surface.isValid)return

        var canvas:
            android.graphics.Canvas?=null

        try{
            canvas=
                surface.lockCanvas(null)

            canvas?.let{
                it.drawColor(Color.BLACK)

                val p=
                    Paint().apply{
                        color=Color.WHITE
                        textSize=32f

                        textAlign=
                            Paint.Align.CENTER

                        isAntiAlias=true
                    }

                it.drawText(
                    message,
                    it.width/2f,
                    it.height/2f,
                    p
                )
            }

        }finally{
            canvas?.let{
                runCatching{
                    surface
                        .unlockCanvasAndPost(it)
                }
            }
        }
    }

    private suspend fun findCommaIp():
        String?=coroutineScope{

        val subnets=
            (
                localSubnets()+
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

        for(subnet in subnets){

            val tasks=
                (2..254).map{i->
                    async(Dispatchers.IO){
                        val ip="$subnet.$i"

                        if(
                            portOpen(
                                ip,
                                7000,
                                250
                            )
                        )ip else null
                    }
                }

            val found=
                tasks.awaitAll()
                    .firstOrNull{
                        it!=null
                    }

            if(found!=null){
                return@coroutineScope found
            }
        }

        return@coroutineScope null
    }

    private fun localSubnets():
        List<String>{

        val result=
            mutableListOf<String>()

        runCatching{
            val interfaces=
                Collections.list(
                    NetworkInterface
                        .getNetworkInterfaces()
                )

            for(network in interfaces){

                val addresses=
                    Collections.list(
                        network.inetAddresses
                    )

                for(address in addresses){

                    if(
                        !address.isLoopbackAddress &&
                        address is Inet4Address
                    ){
                        val host=
                            address.hostAddress
                                ?:continue

                        val dot=
                            host.lastIndexOf('.')

                        if(dot>0){
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

    private fun portOpen(
        ip:String,
        port:Int,
        timeout:Int
    ):Boolean{
        return try{
            Socket().use{
                it.connect(
                    InetSocketAddress(
                        ip,
                        port
                    ),
                    timeout
                )
            }

            true
        }catch(_:Exception){
            false
        }
    }

    override fun getTemplate(): Template {
        val strip=
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("Retry")
                        .setOnClickListener{
                            start()
                        }
                        .build()
                )
                .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setPanModeListener{}
            .build()
    }
}
