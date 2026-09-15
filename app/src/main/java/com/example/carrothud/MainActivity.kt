package com.example.carrothud

import android.annotation.SuppressLint
import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.math.BigInteger
import java.net.InetAddress
import java.nio.ByteOrder

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
                    // 사이드바 및 불필요한 UI 숨김 처리
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

        // 자동 감지된 IP로 접속 시도
        val targetIp = getGatewayIp()
        val targetUrl = "http://$targetIp:7000"

        Toast.makeText(this, "접속 주소: $targetUrl", Toast.LENGTH_SHORT).show()
        webView.loadUrl(targetUrl)
    }

    // 핫스팟/Wi-Fi 네트워크의 게이트웨이 IP(본체 IP 대역) 자동 추출 함수
    private fun getGatewayIp(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo: DhcpInfo = wifiManager.dhcpInfo
            var gateway = dhcpInfo.gateway

            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                gateway = Integer.reverseBytes(gateway)
            }

            val gatewayBytes = BigInteger.valueOf(gateway.toLong()).toByteArray()
            val ipAddress = InetAddress.getByAddress(gatewayBytes)
            
            val ipString = ipAddress.hostAddress
            if (ipString.isNullOrEmpty() || ipString == "0.0.0.0") {
                "10.239.225.61" // 감지 실패 시 기본값
            } else {
                ipString
            }
        } catch (e: Exception) {
            "10.239.225.61" // 예외 발생 시 기존 IP로 대체
        }
    }
}

