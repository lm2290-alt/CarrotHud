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
        private const val RENDER_DELAY = 40L
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
                const x=${wx.toInt()};
                const y=${wy.toInt()};

                const selector=
                    'button,'+
                    'a,'+
                    'input,'+
                    'label,'+
                    '[role="button"],'+
                    '[onclick]';

                const e=
                    document.elementFromPoint(x,y);

                let target=
                    e ? e.closest(selector) : null;

                if(!target){
                    const all=
                        document.querySelectorAll(
                            selector
                        );

                    let best=null;
                    let bestDist=25;

                    for(const el of all){
                        const r=
                            el.getBoundingClientRect();

                        if(
                            r.width<=0 ||
                            r.height<=0
                        )continue;

                        const cx=
                            Math.max(
                                r.left,
                                Math.min(x,r.right)
                            );

                        const cy=
                            Math.max(
                                r.top,
                                Math.min(y,r.bottom)
                            );

                        const dx=x-cx;
                        const dy=y-cy;

                        const d=
                            Math.sqrt(
                                dx*dx+dy*dy
                            );

                        if(d<bestDist){
                            bestDist=d;
                            best=el;
                        }
                    }

                    target=best;
                }

                if(!target)return;

                try{
                    target.focus({
                        preventScroll:true
                    });
                }catch(e){}

                try{
                    target.click();
                }catch(e){}
            })();
        """.trimIndent()

        wv.evaluateJavascript(js, null)
    }

    private fun createWebView() {
        if (webView != null) return

        webView =
            WebView(carContext).apply {

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

    private fun fixPage(
        view: WebView?
    ) {
        val js = """
            (function(){
                let meta=
                    document.querySelector(
                        'meta[name="viewport"]'
                    );

                if(!meta){
                    meta=
                        document.createElement(
                            'meta'
                        );

                    meta.name='viewport';

                    document.head.appendChild(
                        meta
                    );
                }

                meta.content=
                    'width=1280,'+
                    'height=720,'+
                    'initial-scale=1,'+
                    'minimum-scale=1,'+
                    'maximum-scale=1,'+
                    'user-scalable=no';

                const old=
                    document.getElementById(
                        'carrotHudFixedStyle'
                    );

                if(old)old.remove();

                const s=
                    document.createElement(
                        'style'
                    );

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

                        -webkit-text-size-adjust:
                            100% !important;
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

        view?.evaluateJavascript(
            js,
            null
        )
    }

    private fun restoreAutoStart(
        view: WebView?
    ) {
        val js = """
            (function(){
                if(
                    window.__carrotHudAutoStart
                )return;

                window.__carrotHudAutoStart=true;

                let attempts=0;

                const timer=
                    setInterval(function(){

                    attempts++;

                    const all=
                        document
                        .getElementsByTagName('*');

                    for(
                        let i=0;
                        i<all.length;
                        i++
                    ){
                        const el=all[i];

                        const txt=(
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
                            const button=
                                el.closest(
                                    'button'
                                );

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
                                const b=
                                    el.querySelector(
                                        'button'
                                    );

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

        view?.evaluateJavascript(
            js,
            null
        )
    }

    private fun installMirror(
        view: WebView?
    ) {
        val js = """
            (function(){
                if(
                    window.__carrotAaMirror
                )return;

                window.__carrotAaMirror=true;

                function start(){
                    const v=
                        document.getElementById(
                            'carrotRoadVideo'
                        );

                    if(!v){
                        setTimeout(
                            start,
                            300
                        );
                        return;
                    }

                    const p=v.parentElement;

                    if(!p){
                        setTimeout(
                            start,
                            300
                        );
                        return;
                    }

                    let c=
                        document.getElementById(
                            'carrotAaVideoMirror'
                        );

                    if(!c){
                        c=
                            document.createElement(
                                'canvas'
                            );

                        c.id=
                            'carrotAaVideoMirror';

                        c.style.position='absolute';
                        c.style.left='0';
                        c.style.top='0';

                        c.style.width='100%';
                        c.style.height='100%';

                        c.style.zIndex='0';

                        c.style.pointerEvents=
                            'none';

                        c.style.background='#000';

                        p.insertBefore(c,v);
                    }

                    v.style.visibility='hidden';

                    const ctx=
                        c.getContext(
                            '2d',
                            {
                                alpha:false,
                                desynchronized:true
                            }
                        );

                    let last=0;

                    function draw(now){

                        if(
                            now-last>=40 &&
                            v.videoWidth>0 &&
                            v.videoHeight>0
                        ){
                            last=now;

                            const cw=
                                p.clientWidth ||
                                1280;

                            const ch=
                                p.clientHeight ||
                                720;

                            /*
                             * 720p CSS 화면은 유지.
                             * 원본 영상이 더 크면
                             * 최대 1.5배 backing canvas.
                             */
                            const ratio=
                                Math.min(
                                    1.5,
                                    Math.max(
                                        1,
                                        v.videoWidth/cw,
                                        v.videoHeight/ch
                                    )
                                );

                            const bw=
                                Math.round(
                                    cw*ratio
                                );

                            const bh=
                                Math.round(
                                    ch*ratio
                                );

                            if(c.width!==bw){
                                c.width=bw;
                            }

                            if(c.height!==bh){
                                c.height=bh;
                            }

                            const vw=
                                v.videoWidth;

                            const vh=
                                v.videoHeight;

                            const scale=
                                Math.max(
                                    bw/vw,
                                    bh/vh
                                );

                            const dw=
                                vw*scale;

                            const dh=
                                vh*scale;

                            const dx=
                                (bw-dw)/2;

                            const dy=
                                (bh-dh)/2;

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

                        requestAnimationFrame(
                            draw
                        );
                    }

                    requestAnimationFrame(
                        draw
                    );
                }

                start();
            })();
        """.trimIndent()

        view?.evaluateJavascript(
            js,
            null
        )
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

                val ip=
                    findCommaIp()

                if(ip==null){

                    drawMessage(
                        "콤마4를 찾지 못함"
                    )

                    delay(2000)

                    if(rendering){
                        start()
                    }

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
                    android.graphics.Canvas?=
                    null

                try{
                    canvas=
                        surface.lockCanvas(
                            null
                        )

                    canvas?.let {

                        it.drawColor(
                            Color.BLACK
                        )

                        surfaceW=
                            it.width

                        surfaceH=
                            it.height

                        val sx=
                            it.width.toFloat() /
                            WEB_W.toFloat()

                        val sy=
                            it.height.toFloat() /
                            WEB_H.toFloat()

                        it.save()

                        it.scale(
                            sx,
                            sy
                        )

                        wv.draw(it)

                        it.restore()
                    }

                }finally{
                    canvas?.let {
                        runCatching {
                            surface
                                .unlockCanvasAndPost(
                                    it
                                )
                        }
                    }
                }
            }

            delay(
                RENDER_DELAY
            )
        }
    }

    private fun drawMessage(
        message:String
    ){
        val surface=
            container?.surface
                ?:return

        if(!surface.isValid){
            return
        }

        var canvas:
            android.graphics.Canvas?=
            null

        try{
            canvas=
                surface.lockCanvas(
                    null
                )

            canvas?.let {

                it.drawColor(
                    Color.BLACK
                )

                val p=
                    Paint().apply {
                        color=
                            Color.WHITE

                        textSize=
                            32f

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
            canvas?.let {
                runCatching {
                    surface
                        .unlockCanvasAndPost(
                            it
                        )
                }
            }
        }
    }

    private suspend fun findCommaIp():
        String? = coroutineScope {

        val subnets=
            (
                localSubnets() +
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
                (2..254).map { i ->

                    async(
                        Dispatchers.IO
                    ){
                        val ip="$subnet.$i"

                        if(
                            portOpen(
                                ip,
                                7000,
                                250
                            )
                        ){
                            ip
                        }else{
                            null
                        }
                    }
                }

            val found=
                tasks.awaitAll()
                    .firstOrNull {
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

        runCatching {

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

        }catch(_:Exception){
            false
        }
    }

    override fun onGetTemplate():
        Template{

        return NavigationTemplate.Builder()
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.PAN
                    )
                    .build()
            )
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(
                                "재시도"
                            )
                            .setOnClickListener {
                                start()
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
