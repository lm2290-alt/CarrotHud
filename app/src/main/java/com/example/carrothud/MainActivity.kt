package com.example.carrothud

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
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
    private lateinit var statusTextView: TextView
    private lateinit var layoutContainer: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        layoutContainer = FrameLayout(this)

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

        statusTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER
            text = "네트워크 대역 스캔 준비 중..."
        }

        layoutContainer.addView(webView)
        layoutContainer.addView(statusTextView)
        setContentView(layoutContainer)

        startCommaScan()
    }

    private fun startCommaScan() {
        CoroutineScope(Dispatchers.Main).launch {
            statusTextView.text = "활성 네트워크 분석 중..."
            val commaIp = findCommaDevice()

            if (commaIp != null) {
                val targetUrl = "http://$commaIp:7000"
                statusTextView.text = "콤마4 발견! ($targetUrl)"
                statusTextView.postDelayed({
                    statusTextView.visibility = View.GONE
                }, 800)
                webView.loadUrl(targetUrl)
            } else {
                statusTextView.text = "7000번 포트 응답 기기를 찾지 못했습니다.\n핫스팟 연결 상태를 확인해주세요."
            }
        }
    }

    private suspend fun findCommaDevice(): String? = withContext(Dispatchers.IO) {
        val subnets = getAllActiveSubnets()
        
        // 콤마4 및 일반적인 핫스팟 기본 대역들을 포함하여 전수 조사
        val defaultSubnets = listOf("192.168.43", "192.168.1", "192.168.0", "192.168.123", "10.0.0")
        val totalSubnets = (subnets + defaultSubnets).distinct()

        for (subnet in totalSubnets) {
            withContext(Dispatchers.Main) {
                statusTextView.text = "스캔 중: $subnet.1 ~ 254 (포트 7000)"
            }

            // 1부터 254까지 모든 IP를 코루틴으로 동시에 빠르게 스캔
            val deferreds = (1..254).map { host ->
                async(Dispatchers.IO) {
                    val targetIp = "$subnet.$host"
                    if (checkPort7000(targetIp)) targetIp else null
                }
            }

            val foundIp = deferreds.awaitAll().filterNotNull().firstOrNull()
            if (foundIp != null) {
                return@withContext foundIp
            }
        }

        return@withContext null
    }

    private fun getAllActiveSubnets(): List<String> {
        val subnets = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInterface = interfaces.nextElement()
                if (!netInterface.isUp || netInterface.isLoopback) continue

                val addresses = netInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
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

    private fun checkPort7000(ip: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 7000), 80)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}

