package com.example.carrothud

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var scanJob: Job? = null
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode =
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.setRendererPriorityPolicy(
            WebView.RENDERER_PRIORITY_IMPORTANT,
            false
        )

        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(view, url)

                    view?.onResume()
                    view?.resumeTimers()
                }
            }

        scanAndLoadCommaVision()
    }

    override fun onResume() {
        super.onResume()

        if (::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
        }

        super.onPause()
    }

    private fun scanAndLoadCommaVision() {
        if (loaded) return

        scanJob?.cancel()

        Toast.makeText(
            this,
            "콤마4 탐색 중...",
            Toast.LENGTH_SHORT
        ).show()

        scanJob =
            CoroutineScope(Dispatchers.IO).launch {

                val ip = findCommaDeviceIp()

                withContext(Dispatchers.Main) {
                    if (ip != null) {
                        loaded = true

                        webView.onResume()
                        webView.resumeTimers()

                        webView.loadUrl(
                            "http://$ip:7000"
                        )

                    } else {
                        delay(2000)
                        scanAndLoadCommaVision()
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
                    .firstOrNull { it != null }

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
                                host.substring(0, dot)
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
                    InetSocketAddress(ip, port),
                    timeout
                )
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
