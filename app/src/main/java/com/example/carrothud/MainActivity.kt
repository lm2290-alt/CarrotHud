package com.example.carrothud

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
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
import java.net.InetSocketAddress
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

        Toast.makeText(this, "본체 IP 탐색 중...", Toast.LENGTH_SHORT).show()

        // lifecycleScope 대신 기본 CoroutineScope(Dispatchers.Main) 사용
        CoroutineScope(Dispatchers.Main).launch {
            val detectedIp = scanNetworkForDevice()
            val targetUrl = "http://$detectedIp:7000"
            
            Toast.makeText(this@MainActivity, "접속: $targetUrl", Toast.LENGTH_SHORT).show()
            webView.loadUrl(targetUrl)
        }
    }

    private suspend fun scanNetworkForDevice(): String = withContext(Dispatchers.IO) {
        val myIp = getLocalIpAddress()
        val subnetBase = if (myIp.contains(".")) {
            myIp.substring(0, myIp.lastIndexOf("."))
        } else {
            "192.168.43" // 실패 시 기본 핫스팟 대역
        }

        val deferreds = (2..50).map { i ->
            async(Dispatchers.IO) {
                val testIp = "$subnetBase.$i"
                if (checkPort(testIp, 7000, 150)) testIp else null
            }
        }

        val results = deferreds.awaitAll().filterNotNull()

        if (results.isNotEmpty()) {
            results[0]
        } else {
            "10.239.225.61"
        }
    }

    private fun checkPort(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeout)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.dhcpInfo.gateway
            String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            "192.168.43.1"
        }
    }
}

