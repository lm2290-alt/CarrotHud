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
                carContext.getCarService(
                    NavigationManager::class.java
                )

            navigationManager.setNavigationManagerCallback(
                object : NavigationManagerCallback {
                    override fun onStopNavigation() {
                    }
                }
            )

            carContext
                .getCarService(
                    androidx.car.app.AppManager::class.java
                )
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
            streamJob = null

            this.surfaceContainer = null
        }
    }

    /*
     * ============================================================
     * Android Auto Surface Touch -> WebView
     * ============================================================
     */
    override fun onClick(
        x: Float,
        y: Float
    ) {
        val wv =
            webView ?: return

        val container =
            surfaceContainer ?: return

        val surfaceWidth =
            if (container.width > 0) {
                container.width.toFloat()
            } else {
                return
            }

        val surfaceHeight =
            if (container.height > 0) {
                container.height.toFloat()
            } else {
                return
            }

        wv.post {
            if (
                wv.width <= 0 ||
                wv.height <= 0
            ) {
                return@post
            }

            /*
             * Android Auto Surface 좌표를
             * WebView 좌표로 변환
             */
            val webX =
                x * wv.width.toFloat() /
                    surfaceWidth

            val webY =
                y * wv.height.toFloat() /
                    surfaceHeight

            val script = """
                (function() {
                    try {
                        var viewWidth =
                            window.innerWidth ||
                            document.documentElement.clientWidth ||
                            1280;

                        var viewHeight =
                            window.innerHeight ||
                            document.documentElement.clientHeight ||
                            720;

                        var nativeWidth =
                            ${wv.width.toFloat()};

                        var nativeHeight =
                            ${wv.height.toFloat()};

                        var nativeX =
                            $webX;

                        var nativeY =
                            $webY;

                        var cssX =
                            nativeX *
                            viewWidth /
                            nativeWidth;

                        var cssY =
                            nativeY *
                            viewHeight /
                            nativeHeight;

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
                                'button,' +
                                'a,' +
                                'input,' +
                                'select,' +
                                'textarea,' +
                                '[role="button"],' +
                                '[onclick],' +
                                '.btn,' +
                                '.button'
                            ) || element;

                        try {
                            target.dispatchEvent(
                                new MouseEvent(
                                    'mousedown',
                                    {
                                        bubbles: true,
                                        cancelable: true,
                                        clientX: cssX,
                                        clientY: cssY
                                    }
                                )
                            );

                            target.dispatchEvent(
                                new MouseEvent(
                                    'mouseup',
                                    {
                                        bubbles: true,
                                        cancelable: true,
                                        clientX: cssX,
                                        clientY: cssY
                                    }
                                )
                            );
                        } catch (ignore) {
                        }

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
                            "CLICK:" +
                            target.tagName +
                            ":" +
                            (
                                target.innerText ||
                                target.textContent ||
                                ""
                            )
                            .trim()
                            .substring(0, 40)
                        );

                    } catch (e) {
                        return "ERROR:" + e;
                    }
                })();
            """.trimIndent()

            wv.evaluateJavascript(
                script
            ) { result ->

                saveCustomLog(
                    "AA_CLICK " +
                        "surface=($x,$y) " +
                        "web=($webX,$webY) " +
                        "result=$result"
                )
            }
        }
    }

    /*
     * ============================================================
     * WebView
     * ============================================================
     */
    private fun initWebView() {

        if (webView != null) {
            return
        }

        webView =
            WebView(carContext).apply {

                setLayerType(
                    View.LAYER_TYPE_SOFTWARE,
                    null
                )

                settings.apply {

                    javaScriptEnabled = true
                    domStorageEnabled = true

                    mediaPlaybackRequiresUserGesture =
                        false

                    /*
                     * PC/폰 레이아웃에 최대한 가깝게 유지
                     */
                    useWideViewPort = true
                    loadWithOverviewMode = true

                    /*
                     * Android WebView 글자 자동 확대 방지
                     */
                    textZoom = 100

                    layoutAlgorithm =
                        WebSettings.LayoutAlgorithm.NORMAL

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

                            applyViewportFix(
                                view
                            )

                            startCarrotVision(
                                view
                            )
                        }
                    }
            }
    }

    /*
     * ============================================================
     * Web page viewport / text autosize fix
     * ============================================================
     */
    private fun applyViewportFix(
        view: WebView?
    ) {

        val script = """
            (function() {
                try {
                    document.documentElement
                        .style
                        .setProperty(
                            '-
