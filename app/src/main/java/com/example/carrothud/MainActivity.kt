package com.example.carrothud

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

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
        }
        webView.webViewClient = WebViewClient()

        // 핫스팟에 연결된 콤마4(7000 포트) 자동 스캔 시작
        scanAndLoadCommaVision()
    }

    private fun scanAndLoadCommaVision() {
        Toast.makeText(this, "콤마4 (당근비전 7000포트) 스캔 중...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val commaIp = findCommaDeviceIp()
            withContext(Dispatchers.Main) {
                if (commaIp != null) {
                    val visionUrl = "http://$commaIp:7000"
                    Toast.makeText(this@MainActivity, "콤마4 연결 성공: $visionUrl", Toast.LENGTH_SHORT).show()
                    webView.loadUrl(visionUrl)
                } else {
                    Toast.makeText(this@MainActivity, "핫스팟에 연결된 콤마4를 찾지 못했습니다.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 핫스팟 대역(192.168.43.2 ~ 254) 비동기 병렬 스캔
    private suspend fun findCommaDeviceIp(): String? = coroutineScope {
        val subnet = "192.168.43"
        val tasks = (2..254).map { i ->
            async(Dispatchers.IO) {
                val testIp = "$subnet.$i"
                if (isPortOpen(testIp, 7000, 200)) testIp else null
            }
        }
        val results = tasks.awaitAll()
        results.firstOrNull { it != null }
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
