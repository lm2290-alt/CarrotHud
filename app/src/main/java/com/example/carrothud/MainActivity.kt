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

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()

        // JS -> Kotlin 통신 인터페이스 등록 ("AndroidHUD")
        webView.addJavascriptInterface(WebAppInterface(), "AndroidHUD")

        // 동작 테스트용 HTML 로드 (실제 서비스 시에는 innerHTML 대신 외부 URL 입력 가능)
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
            <body style="font-size:18px; text-align:center; padding-top:40px;">
                <h2>CarrotHUD 웹 컨트롤러</h2>
                <button onclick="sendData(60, '인천시청', '3.5km')" style="font-size:18px; padding:12px; margin:10px;">60 km/h 전송</button><br>
                <button onclick="sendData(100, '서울역', '25.0km')" style="font-size:18px; padding:12px; margin:10px;">100 km/h 전송</button>

                <script>
                    function sendData(speed, dest, dist) {
                        if (window.AndroidHUD) {
                            window.AndroidHUD.postHudData(speed, dest, dist);
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, sampleHtml, "text/html", "UTF-8", null)
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
