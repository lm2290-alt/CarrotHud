package com.example.carrothud

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        Toast.makeText(this, "콤마4 (7000포트) 동적 IP 탐색 중...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val commaIp = findCommaDeviceIp()
            withContext(Dispatchers.Main) {
                if (commaIp != null) {
                    val visionUrl = "http://$commaIp:7000"
                    Toast.makeText(this@MainActivity, "콤마4 연결 성공: $visionUrl", Toast.LENGTH_SHORT).show()
                    webView.loadUrl(visionUrl)
                } else {
                    Toast.makeText(this@MainActivity, "핫스팟에 연결된 콤마4를 찾지 못했습니다. (핫스팟 연결 확인 필요)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun findCommaDeviceIp(): String? = coroutineScope {
        // 현재 기기의 핫스팟/네트워크 대역 자동 추출 + 기본 대역 후보
        val localSubnets = getLocalSubnets()
        val candidateSubnets = (localSubnets + listOf("192.168.43", "192.168.12", "172.20.10", "192.168.0", "192.168.1")).distinct()

        for (subnet in candidateSubnets) {
            val tasks = (2..254).map { i ->
                async(Dispatchers.IO) {
                    val testIp = "$subnet.$i"
                    if (isPortOpen(testIp, 7000, 300)) testIp else null
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
}
