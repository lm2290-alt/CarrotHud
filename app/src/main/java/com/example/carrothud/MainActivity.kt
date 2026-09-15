package com.example.carrothud

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var sidebar = document.querySelector('.sidebar') || document.querySelector('#sidebar');
                            if (sidebar) sidebar.style.display = 'none';
                        })();
                        """.trimIndent(), null
                    )
                }
            }
        }

        setContentView(webView)

        Toast.makeText(this, "핫스팟 연결된 콤마4 탐색 중...", Toast.LENGTH_SHORT).show()

        // 비동기 스캔 시작
        CoroutineScope(Dispatchers.Main).launch {
            val commaIp = autoDiscoverCommaIp()

            if (commaIp != null) {
                val targetUrl = "http://$commaIp:7000"
                Toast.makeText(this@MainActivity, "콤마4 발견! 접속: $targetUrl", Toast.LENGTH_LONG).show()
                webView.loadUrl(targetUrl)
            } else {
                Toast.makeText(this@MainActivity, "콤마4를 찾을 수 없습니다. 핫스팟 연결을 확인하세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 스마트폰에 연결된 모든 네트워크 대역에서 7000번 포트가 열린 콤마4 IP 자동 탐색
    private suspend fun autoDiscoverCommaIp(): String? = withContext(Dispatchers.IO) {
        val subnets = getAllSubnets()

        // 추출된 각 서브넷 대역별로 1~254 병렬 스캔
        for (subnet in subnets) {
            val deferreds = (2..254).map { host ->
                async(Dispatchers.IO) {
                    val testIp = "$subnet.$host"
                    if (isPortOpen(testIp, 7000, 120)) testIp else null
                }
            }
            val foundIp = deferreds.awaitAll().filterNotNull().firstOrNull()
            if (foundIp != null) return@withContext foundIp
        }

        // 스마트폰 핫스팟 대표 대역들 추가 백업 스캔 (192.168.43, 192.168.49, 172.20.10)
        val fallbackSubnets = listOf("192.168.43", "192.168.49", "172.20.10")
        for (subnet in fallbackSubnets) {
            if (subnets.contains(subnet)) continue
            val deferreds = (2..254).map { host ->
                async(Dispatchers.IO) {
                    val testIp = "$subnet.$host"
                    if (isPortOpen(testIp, 7000, 120)) testIp else null
                }
            }
            val foundIp = deferreds.awaitAll().filterNotNull().firstOrNull()
            if (foundIp != null) return@withContext foundIp
        }

        return@withContext null
    }

    // 폰의 모든 활성 네트워크 인터페이스(핫스팟, Wi-Fi 등)의 IPv4 서브넷 대역 추출
    private fun getAllSubnets(): List<String> {
        val subnets = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        if (hostAddress.contains(".")) {
                            val subnet = hostAddress.substring(0, hostAddress.lastIndexOf("."))
                            if (!subnets.contains(subnet)) {
                                subnets.add(subnet)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return subnets
    }

    // 특정 IP의 7000번 포트 소켓 오픈 여부 확인
    private fun isPortOpen(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeout)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}

