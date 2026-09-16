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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
     * Android Auto 화면을 누르면 SurfaceCallback을 통해
     * 전달된 좌표를 WebView의 웹 페이지 좌표로 변환한 뒤
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

            val script = """
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

                        var element =
                            document.elementFromPoint(
                                cssX,
                                cssY
                            );

                        if (!element) {
                            return "NO_ELEMENT";
                        }

                        var target =
                            element.closest(
                                'button, a, input, select, textarea, ' +
                                '[role="button"], [onclick], .btn, .button'
                            ) || element;

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
                        } catch (ignore) {
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

                    } catch (e) {
                        return "JS_ERROR:" + e;
                    }
                })();
            """.trimIndent()

            wv.evaluateJavascript(script) { result ->
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
             * software rendering 사용.
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
                 * 휴대폰 WebView와 최대한 동일한
                 * viewport 동작을 사용한다.
                 */
                useWideViewPort = true
                loadWithOverviewMode = true

                /*
                 * Android Auto에서 WebView가
                 * 글자를 임의로 확대하지 않도록 고정.
                 */
                textZoom = 100

                layoutAlgorithm =
                    WebSettings.LayoutAlgorithm.NORMAL

                /*
                 * WebView 자체 확대 기능은 사용하지 않는다.
                 */
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

                        /*
                         * Android Auto WebView에서
                         * text autosizing / viewport 차이로
                         * 연비 등의 글씨 크기가 달라지는 것을
                         * 최대한 방지한다.
                         */
                        val displayFixScript = """
                            (function() {
                                try {

                                    document.documentElement.style
                                        .setProperty(
                                            '-webkit-text-size-adjust',
                                            '100%',
                                            'important'
                                        );

                                    if (document.body) {
                                        document.body.style
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
                                            !/initial-scale\s*=/
                                                .test(content)
                                        ) {
                                            content +=
                                                (content ? ',' : '') +
                                                'initial-scale=1';
                                        }

                                        if (
                                            !/maximum-scale\s*=/
                                                .test(content)
                                        ) {
                                            content +=
                                                (content ? ',' : '') +
                                                'maximum-scale=1';
                                        }

                                        if (
                                            !/user-scalable\s*=/
                                                .test(content)
                                        ) {
                                            content +=
                                                (content ? ',' : '') +
                                                'user-scalable=no';
                                        }

                                        viewport.setAttribute(
                                            'content',
                                            content
                                        );
                                    }

                                } catch (e) {
                                }
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(
                            displayFixScript,
                            null
                        )

                        /*
                         * 기존 당근 비전 자동 시작 기능.
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
                                                        el.parentElement
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
                    "Vision URL: $visionUrl"
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

                /*
                 * Android Auto가 실제 제공한
                 * Surface 크기를 그대로 사용한다.
                 *
                 * 고정 1280x720 UI로 변환하지 않는다.
                 */
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

                    saveCustomLog(
                        "Surface size: ${width}x${height}"
                    )
                }

                var canvas: Canvas? = null

                try {

                    canvas =
                        surface.lockCanvas(
                            null
                        )

                    if (canvas != null) {
                        wv.draw(canvas)
                    }

                } catch (t: Throwable) {

                    saveCustomLog(
                        "Render Loop Crash: " +
                        "${t.localizedMessage}"
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
             * 약 30fps
             */
            delay(33)
        }
    }

    private fun drawMessage(
        message: String
    ) {

        val container =
            surfaceContainer ?: return

        val surface =
            container.surface ?: return

        if (!surface.isValid) {
            return
        }

        var canvas: Canvas? = null

        try {

            canvas =
                surface.lockCanvas(
                    null
                )

            canvas.drawColor(
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

            canvas.drawText(
                message,
                50f,
                100f,
                paint
            )

        } catch (e: Exception) {

            saveCustomLog(
                "drawMessage error: " +
                "${e.localizedMessage}"
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

    private fun findCommaDeviceIp(): String? {

        /*
         * 먼저 현재 Android 기기가 연결된
         * 네트워크 인터페이스에서 IPv4 주소를 찾는다.
         */
        val subnets =
            getLocalSubnets()

        saveCustomLog(
            "Local subnets: $subnets"
        )

        /*
         * 자주 사용되는 comma 주소를 먼저 확인.
         */
        val preferredHosts =
            listOf(
                "192.168.43.1",
                "192.168.0.1",
                "192.168.1.1"
            )

        for (host in preferredHosts) {

            if (isPortOpen(host, 7000)) {

                saveCustomLog(
                    "Comma found: $host"
                )

                return host
            }
        }

        /*
         * 현재 연결된 /24 네트워크를 검색.
         */
        for (subnet in subnets) {

            for (i in 1..254) {

                if (!isRendering) {
                    return null
                }

                val host =
                    "$subnet.$i"

                if (
                    isPortOpen(
                        host,
                        7000,
                        35
                    )
                ) {

                    saveCustomLog(
                        "Comma found: $host"
                    )

                    return host
                }
            }
        }

        return null
    }

    private fun getLocalSubnets():
        List<String> {

        val result =
            mutableListOf<String>()

        try {

            val interfaces =
                NetworkInterface
                    .getNetworkInterfaces()

            while (
                interfaces.hasMoreElements()
            ) {

                val network =
                    interfaces.nextElement()

                if (
                    !network.isUp ||
                    network.isLoopback
                ) {
                    continue
                }

                val addresses =
                    network.inetAddresses

                while (
                    addresses.hasMoreElements()
                ) {

                    val address =
                        addresses.nextElement()

                    val host =
                        address.hostAddress
                            ?: continue

                    /*
                     * IPv4만 사용.
                     */
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

                        val subnet =
                            "${parts[0]}." +
                            "${parts[1]}." +
                            "${parts[2]}"

                        if (
                            !result.contains(
                                subnet
                            )
                        ) {
                            result.add(
                                subnet
                            )
                        }
                    }
                }
            }

        } catch (e: Exception) {

            saveCustomLog(
                "Subnet error: " +
                "${e.localizedMessage}"
            )
        }

        return result
    }

    private fun isPortOpen(
        host: String,
        port: Int,
        timeout: Int = 100
    ): Boolean {

        var socket: Socket? = null

        return try {

            socket =
                Socket()

            socket.connect(
                InetSocketAddress(
                    host,
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

            val formatter =
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.getDefault()
                )

            val line =
                "${formatter.format(Date())} " +
                "$message\n"

            val directory =
                carContext
                    .getExternalFilesDir(
                        null
                    )
                    ?: return@runCatching

            val logFile =
                File(
                    directory,
                    "carrot_hud.log"
                )

            logFile.appendText(
                line
            )
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
