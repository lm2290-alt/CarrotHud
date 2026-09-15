package com.example.carrothud

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.webViewClient = WebViewClient()

        // 웹 자바스크립트 통신 인터페이스 등록
        webView.addJavascriptInterface(WebAppInterface(), "AndroidHUD")

        // 실제 CarrotHUD 웹 주소로 변경하세요
        val targetUrl = "https://your-carrothud-url.com" 
        webView.loadUrl(targetUrl)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun postHudData(speed: Int, destination: String, distance: String) {
            runOnUiThread {
                HudDataManager.updateData(speed, destination, distance)
            }
        }
    }
}
