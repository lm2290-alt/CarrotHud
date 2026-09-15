package com.example.carrothud

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this).apply {
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
                    // 페이지 로드가 끝나면 사이드바나 메뉴 요소를 숨기고 전체 화면으로 맞추는 자바스크립트 실행
                    // (대시보드 구조에 맞춰 불필요한 요소를 숨기는 스크립트입니다)
                    view?.evaluateJavascript(
                        """
                        (function() {
                            // 사이드바나 메뉴 영역이 있다면 여기서 숨길 수 있습니다.
                            // 예: document.querySelector('.sidebar-class-name')?.style.display = 'none';
                        })();
                        """.trimIndent(), null
                    )
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("http://10.239.225.61:7000")
    }
}
