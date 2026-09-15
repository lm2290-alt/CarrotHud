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

        Toast.makeText(this, "연결된 네트워크 대역 감지 및 콤마4 검색 중...", Toast.LENGTH_SHORT).show()

        // 실시간 네트워크 감지 및 7000번 포트 탐색 시작
        CoroutineScope(Dispatchers.Main).launch {
            val commaIp = findCommaDevice()

            if (commaIp != null) {
                val targetUrl = "http://$commaIp:7000"
                Toast.makeText(this@MainActivity, "콤마4 연결 성공: $targetUrl", Toast.LENGTH_LONG).show()
                webView.loadUrl(targetUrl)
            } else {
                Toast.makeText(this@MainActivity, "7000번 포트 응답 기기를 찾지 못했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 1. 폰에 할당된 모든 IP 대역 감지 -> 2. 해당 대역 스캔 -> 3. 7000번 포트 열린 IP 반환
    private suspend fun findCommaDevice(): String? = withContext(Dispatchers.IO) {
        // IP 규칙 상관없이 현재 폰의 모든 활성 IPv4 서브넷 추출
        val subnets = getAllActiveSubnets()

        for (subnet in subnets) {
            // 1~254 전체 IP에 대해 7000번 포트 동시 스캔
            val deferreds = (1..254).map { host ->
                async(Dispatchers.IO) {
                    val targetIp = "$subnet.$host"
                    if (checkPort7000(targetIp)) targetIp else null
                }
            }

            val foundIp = deferreds.awaitAll().filterNotNull().firstOrNull()
            if (foundIp != null) return@withContext foundIp
        }

        return@withContext null
    }

    // 스마트폰의 모든 네트워크 인터페이스를 조회하여 현재 생성된 모든 IPv4 서브넷(AAA.BBB.CCC)을 자동 감지
    private fun getAllActiveSubnets(): List<String> {
        val subnets = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInterface = interfaces.nextElement()
                val addresses = netInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    // Loopback(127.0.0.1)만 제외하고 172.x, 10.x, 192.x 등 모든 IPv4 주소 수집
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        val lastDot = hostAddress.lastIndexOf('.')
                        if (lastDot != -1) {
                            val subnet = hostAddress.substring(0, lastDot)
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

    // 해당 IP의 7000번 포트 연결 시도 (타임아웃 200ms)
    private fun checkPort7000(ip: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 7000), 200)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}

