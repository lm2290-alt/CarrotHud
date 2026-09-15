package com.example.carrothud

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = WebViewClient()

        scanAndLoadCommaVision()
    }

    private fun scanAndLoadCommaVision() {
        Toast.makeText(this, "콤마4 (포트 7000) 탐색 중...", Toast.LENGTH_SHORT).show()

        scanJob?.cancel()
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            val commaIp = findCommaDeviceIp()
            withContext(Dispatchers.Main) {
                if (commaIp != null) {
                    val visionUrl = "http://$commaIp:7000"
                    Toast.makeText(this@MainActivity, "콤마4 연결 성공: $visionUrl", Toast.LENGTH_SHORT).show()
                    webView.loadUrl(visionUrl)
                } else {
                    Toast.makeText(this@MainActivity, "콤마4 미발견 (재탐색 중)", Toast.LENGTH_SHORT).show()
                    delay(2000)
                    scanAndLoadCommaVision()
                }
            }
        }
    }

    private suspend fun findCommaDeviceIp(): String? = coroutineScope {
        val localSubnets = getLocalSubnets()
        val candidateSubnets = (localSubnets + listOf(
            "192.168.43", "192.168.42", "192.168.137", "192.168.225",
            "172.20.10", "10.42.0", "192.168.0", "192.168.1", "192.168.8"
        )).distinct()

        for (subnet in candidateSubnets) {
            val tasks = (2..254).map { i ->
                async(Dispatchers.IO) {
                    val testIp = "$subnet.$i"
                    if (isPortOpen(testIp, 7000, 250)) testIp else null
                }
            }
            val foundIp = tasks.awaitAll().firstOrNull { it != null }
            if (foundIp != null) return@coroutineScope foundIp
        }
        null
    }

    private fun getLocalSubnets(): List<String> {
        val subnets = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        val lastDot = hostAddress.lastIndexOf('.')
                        if (lastDot > 0) {
                            subnets.add(hostAddress.substring(0, lastDot))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return subnets
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
    }
}
