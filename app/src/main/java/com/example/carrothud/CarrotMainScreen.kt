package com.example.carrothud

import android.graphics.Canvas
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

class CarrotMainScreen(
    carContext: CarContext
) : Screen(carContext), SurfaceCallback {

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

            val navigationManager =
                carContext.getCarService(NavigationManager::class.java)

            navigationManager.setNavigationManagerCallback(
                object : NavigationManagerCallback {
                    override fun onStopNavigation() {
                    }
                }
            )

            carContext
                .getCarService(androidx.car.app.AppManager::class.java)
                .setSurfaceCallback(this)

        }.onFailure { e ->

            saveCustomLog(
                "Init Exception: ${e.localizedMessage}"
            )
        }
    }

    override fun onSurfaceAvailable(
        surfaceContainer: SurfaceContainer
    ) {

        synchronized(renderLock) {

            this.surfaceContainer = surfaceContainer
            isRendering = true
        }

        CoroutineScope(Dispatchers.Main).launch {

            initWebView()

            startStreamingPipeline()
        }
    }

    override fun onSurfaceDestroyed(
        surfaceContainer: SurfaceContainer
    ) {

        synchronized(renderLock) {

            isRendering = false

            streamJob?.cancel()

            this.surfaceContainer = null
        }
    }

    /*
     * Android Auto 화면 터치
     *
     * SurfaceCallback으로 들어온 좌표를
     * WebView의 실제 웹페이지 좌표로 변환한 다음
     * 해당 HTML 요소를 클릭한다.
     */
    override fun onClick(x: Float, y: Float) {

        val wv = webView ?: return

        wv.post {

            if (wv.width <= 0 || wv.height <= 0) {
                return@post
            }

            val viewWidth = wv.width.toFloat()
            val viewHeight = wv.height.toFloat()

            val js = """
                (function() {
                    try {

                        var surfaceX = $x;
                        var surfaceY = $y;

                        var cssWidth =
                            window.innerWidth ||
                            document.documentElement.clientWidth ||
                            1;

                        var cssHeight =
                            window.innerHeight ||
                            document.documentElement.clientHeight ||
                            1;

                        var cssX =
                            surfaceX * cssWidth / $viewWidth;

                        var cssY =
                            surfaceY * cssHeight / $viewHeight;

                        var el =
                            document.elementFromPoint(
                                cssX,
                                cssY
                            );

                        if (!el) {
                            return "NO_ELEMENT";
                        }

                        var target =
                            el.closest(
                                'button, a, input, select, textarea, [role="button"], [onclick], .btn, .button'
                            ) || el;

                        try {

                            if (window.PointerEvent) {

                                target.dispatchEvent(
                                    new PointerEvent(
                                        'pointerdown',
                                        {
                                            bubbles: true,
                                            clientX: cssX,
                                            clientY: cssY,
                                            pointerType: 'touch'
                                        }
                                    )
                                );

                                target.dispatchEvent(
                                    new PointerEvent(
                                        'pointerup',
                                        {
                                            bubbles: true,
                                            clientX: cssX,
                                            clientY: cssY,
                                            pointerType: 'touch'
                                        }
                                    )
                                );
                            }

                        } catch(ignore) {
                        }

                        if (
                            typeof target.click ===
                            'function'
                        ) {

                            target.click();

                        } else {

                            target.dispatchEvent(
                                new MouseEvent(
                                    'click',
                                    {
                                        bubbles: true,
                                        cancelable: true,
                                        clientX: cssX,
                                        clientY: cssY
                                    }
                                )
                            );
                        }

                        return (
                            target.tagName +
                            ":" +
                            (
                                target.innerText ||
                                target.textContent ||
                                ""
                            )
                            .trim()
                            .slice(0, 50)
                        );

                    } catch(e) {

                        return "JS_ERROR:" + e;
                    }
                })();
            """.trimIndent()

            wv.evaluateJavascript(js) { result ->

                saveCustomLog(
                    "AutoTouch x=$x y=$y result=$result"
                )
            }
        }
    }

    private fun initWebView() {

        if (webView != null) {
            return
        }

        webView = WebView(carContext).apply {

            /*
             * Surface Canvas에 직접 그리기 때문에
             * software layer 사용
             */
            setLayerType(
                View.LAYER_TYPE_SOFTWARE,
                null
            )

            settings.apply {

                javaScriptEnabled = true

                domStorageEnabled = true

                mediaPlaybackRequiresUserGesture = false

                /*
                 * 휴대폰 WebView와 최대한 같은
                 * viewport 동작 사용
                 */
                useWideViewPort = true

                loadWithOverviewMode = true

                /*
                 * Android Auto 쪽에서
                 * 글씨를 임의로 확대하지 않도록 고정
                 */
                textZoom = 100

                layoutAlgorithm =
                    WebSettings.LayoutAlgorithm.NORMAL

                /*
                 * WebView 자체 확대/축소 방지
                 */
                setSupportZoom(false)

                builtInZoomControls = false

                displayZoomControls = false

                /*
                 * comma 페이지가 HTTP이므로 허용
                 */
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

                        /*
                         * 핵심:
                         *
                         * Android Auto에서 WebView가
                         * 작은 글씨를 자동 확대하는 것을 막는다.
                         *
                         * 화면 자체를 1280x720 같은
                         * 별도 HUD 레이아웃으로 만드는 게 아니라
                         * 실제 Surface 크기를 그대로 사용한다.
                         */
                        val displayFixScript = """
                            (function() {

                                try {

                                    document.documentElement.style.setProperty(
                                        '-webkit-text-size-adjust',
                                        '100%',
                                        'important'
                                    );

                                    if (document.body) {

                                        document.body.style.setProperty(
                                            '-webkit-text-size-adjust',
                                            '100%',
                                            'important'
                                        );
                                    }

                                    var viewport =
                                        document.querySelector(
                                            'meta[name="viewport"]'
                                        );

                                    if (
                                        !viewport &&
                                        document.head
                                    ) {

                                        viewport =
                                            document.createElement(
                                                'meta'
                                            );

                                        viewport.name =
                                            'viewport';

                                        document.head.appendChild(
                                            viewport
                                        );
                                    }

                                    if (viewport) {

                                        var content =
                                            viewport.getAttribute(
                                                'content'
                                            ) || '';

                                        if (
                                            !/initial-scale\s*=/.test(
                                                content
                                            )
                                        ) {

                                            content +=
                                                (
                                                    content
                                                    ? ','
                                                    : ''
                                                ) +
                                                'initial-scale=1';
                                        }

                                        if (
                                            !/maximum-scale\s*=/.test(
                                                content
                                            )
                                        ) {

                                            content +=
                                                (
                                                    content
                                                    ? ','
                                                    : ''
                                                ) +
                                                'maximum-scale=1';
                                        }

                                        if (
                                            !/user-scalable\s*=/.test(
                                                content
                                            )
                                        ) {

                                            content +=
                                                (
                                                    content
                                                    ? ','
                                                    : ''
                                                ) +
                                                'user-scalable=no';
                                        }

                                        viewport.setAttribute(
                                            'content',
                                            content
                                        );
                                    }

                                } catch(e) {
                                }

                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(
                            displayFixScript,
                            null
                        )

                        /*
                         * 기존 당근비전 자동 시작 기능
                         */
                        val autoStartScript = """
                            (function() {

                                var attempts = 0;

                                var autoClicker =
                                    setInterval(
                                        function() {

                                            attempts++;

                                            var allElements =
                                                document.getElementsByTagName(
                                                    '*'
                                                );

                                            for (
                                                var i = 0;
                                                i < allElements.length;
                                                i++
                                            ) {

                                                var el =
                                                    allElements[i];

                                                var txt =
                                                    (
                                                        el.innerText ||
                                                        el.textContent ||
                                                        ''
                                                    ).trim();

                                                if (
                                                    txt.indexOf(
                                                        '당근 비전 시작'
                                                    ) !== -1 ||
                                                    txt.indexOf(
                                                        '비전 시작'
                                                    ) !== -1
                                                ) {

                                                    el.click();

                                                    if (
                                                        el.parentElement
                                                    ) {

                                                        el.parentElement.click();
                                                    }
                                                }
                                            }

                                            if (
                                                attempts > 20
                                            ) {

                                                clearInterval(
                                                    autoClicker
                                                );
                                            }

                                        },
                                        500
                                    );

                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(
                            autoStartScript,
                            null
                        )
                    }
                }
        }
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

                drawMessage(
                    "영상 스트리밍 연결 중..."
                )

                val visionUrl =
                    "http://$commaIp:7000"

                withContext(
                    Dispatchers.Main
                ) {

                    webView?.loadUrl(
                        visionUrl
                    )
                }

                startSurfaceRenderLoop()
            }
    }

    /*
     * WebView를 Android Auto Surface에
     * 그대로 그린다.
     *
     * 여기서 별도의 고정 해상도를 만들지 않는다.
     */
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

                val width =
                    if (container.width > 0)
                        container.width
                    else
                        1280

                val height =
                    if (container.height > 0)
                        container.height
                    else
                        720

                /*
                 * Surface 크기가 바뀔 때만
                 * WebView 재배치
                 */
                if (
                    width != lastWidth ||
                    height != lastHeight
                ) {

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

                var canvas: Canvas? = null

                try {

                    canvas =
                        surface.lockCanvas(
                            null
                        )

                    canvas?.let {

                        /*
                         * 검은 배경으로 한번 정리 후
                         * WebView를 그대로 그림
                         */
                        it.drawColor(
                            Color.BLACK
                        )

                        wv.draw(
                            it
                        )
                    }

                } catch (t: Throwable) {

                    saveCustomLog(
                        "Render Loop Crash: ${t.localizedMessage}"
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

            /*
             * 약 30fps
             */
            delay(33)
        }
    }

    private suspend fun drawMessage(
        message: String
    ) {

        withContext(
            Dispatchers.Main
        ) {

            val container =
                surfaceContainer
                    ?: return@withContext

            val surface =
                container.surface
                    ?: return@withContext

            if (!surface.isValid) {
                return@withContext
            }

            var canvas: Canvas? = null

            try {

                canvas =
                    surface.lockCanvas(
                        null
                    )

                canvas?.let {

                    it.drawColor(
                        Color.BLACK
                    )

                    val paint =
                        Paint(
                            Paint.ANTI_ALIAS_FLAG
                        ).apply {

                            color =
                                Color.WHITE

                            textSize =
                                42f
                        }

                    it.drawText(
                        message,
                        50f,
                        100f,
                        paint
                    )
                }

            } catch (e: Exception) {

                saveCustomLog(
                    "drawMessage: ${e.localizedMessage}"
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
    }

    /*
     * comma 장치 탐색
     */
    private fun findCommaDeviceIp(): String? {

        /*
         * 흔히 사용되는 주소를 먼저 확인
         */
        val preferredIps =
            listOf(
                "192.168.43.1",
                "192.168.0.1",
                "192.168.1.1"
            )

        for (ip in preferredIps) {

            if (
                isPortOpen(
                    ip,
                    7000,
                    150
                )
            ) {

                return ip
            }
        }

        /*
         * 현재 연결된 네트워크의 /24 탐색
         */
        val subnets =
            getLocalSubnets()

        for (subnet in subnets) {

            for (i in 1..254) {

                if (!isRendering) {
                    return null
                }

                val ip =
                    "$subnet.$i"

                if (
                    isPortOpen(
                        ip,
                        7000,
                        60
                    )
                ) {

                    return ip
                }
            }
        }

        return null
    }

    private fun getLocalSubnets(): List<String> {

        val result =
            mutableSetOf<String>()

        try {

            val interfaces =
                NetworkInterface
                    .getNetworkInterfaces()

            while (
                interfaces.hasMoreElements()
            ) {

                val networkInterface =
                    interfaces.nextElement()

                if (
                    !networkInterface.isUp ||
                    networkInterface.isLoopback
                ) {

                    continue
                }

                val addresses =
                    networkInterface
                        .inetAddresses

                while (
                    addresses.hasMoreElements()
                ) {

                    val address =
                        addresses.nextElement()

                    val host =
                        address.hostAddress
                            ?: continue

                    if (
                        host.contains(":")
                    ) {

                        continue
                    }

                    val parts =
                        host.split(".")

                    if (
                        parts.size == 4
                    ) {

                        result.add(
                            "${parts[0]}.${parts[1]}.${parts[2]}"
                        )
                    }
                }
            }

        } catch (e: Exception) {

            saveCustomLog(
                "Subnet Error: ${e.localizedMessage}"
            )
        }

        return result.toList()
    }

    private fun isPortOpen(
        ip: String,
        port: Int,
        timeout: Int
    ): Boolean {

        var socket: Socket? = null

        return try {

            socket = Socket()

            socket.connect(
                InetSocketAddress(
                    ip,
                    port
                ),
                timeout
            )

            true

        } catch (_: Exception) {

            false

        } finally {

            runCatching {
                socket?.close()
            }
        }
    }

    private fun saveCustomLog(
        message: String
    ) {

        runCatching {

            val file =
                File(
                    carContext.filesDir,
                    "carrot_auto_log.txt"
                )

            file.appendText(
                "${System.currentTimeMillis()} : $message\n"
            )
        }
    }

    override fun onGetTemplate(): Template {

        return NavigationTemplate.Builder()

            .setActionStrip(

                ActionStrip.Builder()

                    .addAction(

                        Action.Builder()

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
