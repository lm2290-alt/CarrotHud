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

class CarrotMainScreen(carContext: CarContext) :
    Screen(carContext), SurfaceCallback {

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
                    override fun onStopNavigation() {}
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
     * WebView 내부 DOM 좌표로 변환해서 클릭한다.
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
                                'button, a, input, select, textarea, ' +
                                '[role="button"], [onclick], .btn, .button'
                            ) || el;

                        try {

                            if (window.PointerEvent) {

                                target.dispatchEvent(
                                    new PointerEvent(
                                        'pointerdown',
                                        {
                                            bubbles: true,
                                            cancelable: true,
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
                                            cancelable: true,
                                            clientX: cssX,
                                            clientY: cssY,
                                            pointerType: 'touch'
                                        }
                                    )
                                );
                            }

                        } catch (ignore) {}

                        if (
                            typeof target.click === 'function'
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

                    } catch (e) {

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
             * Surface에 직접 그릴 것이므로
             * SOFTWARE 렌더링 사용
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
                 * 폰 화면과 최대한 같은 viewport 사용
                 */
                useWideViewPort = true
                loadWithOverviewMode = true

                /*
                 * Android Auto에서
                 * 글자 크기가 임의로 변하는 현상 억제
                 */
                textZoom = 100

                layoutAlgorithm =
                    WebSettings.LayoutAlgorithm.NORMAL

                /*
                 * WebView 자체 확대/축소 방지
                 */
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)

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
                         * Android WebView가
                         * 화면 크기에 따라 글자를 자동 확대하는 것 방지
                         */
                        val viewportScript = """
                            (function() {

                                try {

                                    document.documentElement
                                        .style
                                        .setProperty(
                                            '-webkit-text-size-adjust',
                                            '100%',
                                            'important'
                                        );

                                    if (document.body) {

                                        document.body
                                            .style
                                            .setProperty(
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

                                        document.head
                                            .appendChild(
                                                viewport
                                            );
                                    }

                                    if (viewport) {

                                        var content =
                                            viewport.getAttribute(
                                                'content'
                                            ) || '';

                                        if (
                                            !/initial-scale\s*=/
                                                .test(content)
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
                                            !/maximum-scale\s*=/
                                                .test(content)
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
                                            !/user-scalable\s*=/
                                                .test(content)
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

                                } catch (e) {}

                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(
                            viewportScript,
                            null
                        )

                        /*
                         * 당근 비전 시작 버튼 자동 클릭
                         */
                        val autoStartScript = """
                            (function() {

                                var attempts = 0;

                                var autoClicker =
                                    setInterval(
                                        function() {

                                            attempts++;

                                            var allElements =
                                                document
                                                    .getElementsByTagName(
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

                                                        el
                                                            .parentElement
                                                            .click();
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

    /*
     * ============================
     * 콤마4 연결
     * ============================
     *
     * 여기부터 탐색 방식은
     * 9d65662 원본 방식 유지
     */
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

                saveCustomLog(
                    "Comma found: $visionUrl"
                )

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
     * WebView → Android Auto Surface
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
                    if (container.width > 0) {
                        container.width
                    } else {
                        1280
                    }

                val height =
                    if (container.height > 0) {
                        container.height
                    } else {
                        720
                    }

                /*
                 * Surface 크기가 바뀔 때만
                 * WebView layout 다시 계산
                 */
                if (
                    width != lastWidth ||
                    height != lastHeight
                ) {

                    lastWidth = width
                    lastHeight = height

                    wv.measure(
                        View.MeasureSpec
                            .makeMeasureSpec(
                                width,
                                View.MeasureSpec.EXACTLY
                            ),
                        View.MeasureSpec
                            .makeMeasureSpec(
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

                var canvas:
                    android.graphics.Canvas? =
                    null

                try {

                    canvas =
                        surface.lockCanvas(
                            null
                        )

                    if (canvas != null) {

                        wv.draw(
                            canvas
                        )
                    }

                } catch (t: Throwable) {

                    saveCustomLog(
                        "Render Loop Crash: " +
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

            /*
             * 약 30 FPS
             */
            delay(33)
        }
    }

    private fun drawMessage(
        message: String
    ) {

        synchronized(
            renderLock
        ) {

            val container =
                surfaceContainer
                    ?: return

            val surface =
                container.surface
                    ?: return

            if (
                !surface.isValid ||
                !isRendering
            ) {
                return
            }

            var canvas:
                android.graphics.Canvas? =
                null

            try {

                canvas =
                    surface.lockCanvas(
                        null
                    )

                if (canvas != null) {

                    val targetWidth =
                        if (
                            container.width > 0
                        ) {
                            container.width
                        } else {
                            canvas.width
                        }

                    val targetHeight =
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

                            textSize =
                                32f

                            textAlign =
                                Paint.Align.CENTER

                            isAntiAlias =
                                true
                        }

                    canvas.drawText(
                        message,
                        targetWidth / 2f,
                        targetHeight / 2f,
                        paint
                    )
                }

            } catch (t: Throwable) {

                saveCustomLog(
                    "Message Canvas Crash: " +
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
                "${java.util.Date()}: $msg\n"
            )
        }
    }

    /*
     * =====================================================
     * 아래 콤마 탐색 코드는 9d65662 원본 방식 유지
     * =====================================================
     */
    private suspend fun findCommaDeviceIp():
        String? =
        coroutineScope {

            val localSubnets =
                getLocalSubnets()

            val candidateSubnets =
                (
                    localSubnets +
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

            for (
                subnet in candidateSubnets
            ) {

                val tasks =
                    (2..254).map { i ->

                        async(
                            Dispatchers.IO
                        ) {

                            val testIp =
                                "$subnet.$i"

                            if (
                                isPortOpen(
                                    testIp,
                                    7000,
                                    250
                                )
                            ) {

                                testIp

                            } else {

                                null
                            }
                        }
                    }

                val foundIp =
                    tasks
                        .awaitAll()
                        .firstOrNull {
                            it != null
                        }

                if (
                    foundIp != null
                ) {

                    return@coroutineScope
                        foundIp
                }
            }

            null
        }

    private fun getLocalSubnets():
        List<String> {

        val subnets =
            mutableListOf<String>()

        try {

            val interfaces =
                Collections.list(
                    NetworkInterface
                        .getNetworkInterfaces()
                )

            for (
                intf in interfaces
            ) {

                val addrs =
                    Collections.list(
                        intf.inetAddresses
                    )

                for (
                    addr in addrs
                ) {

                    if (
                        !addr.isLoopbackAddress &&
                        addr is java.net.Inet4Address
                    ) {

                        val hostAddress =
                            addr.hostAddress
                                ?: continue

                        val lastDot =
                            hostAddress
                                .lastIndexOf('.')

                        if (
                            lastDot > 0
                        ) {

                            subnets.add(
                                hostAddress
                                    .substring(
                                        0,
                                        lastDot
                                    )
                            )
                        }
                    }
                }
            }

        } catch (
            e: Exception
        ) {
            /*
             * 원본처럼 무시
             */
        }

        return subnets
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

                true
            }

        } catch (
            e: Exception
        ) {

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
