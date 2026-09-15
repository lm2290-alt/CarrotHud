package com.example.carrothud

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var speedTextView: TextView
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        speedTextView = TextView(this).apply {
            textSize = 80f
            text = "0"
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }

        val rootLayout = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(speedTextView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        setContentView(rootLayout)

        connectWebSocket()
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url("ws://192.168.43.1:8080").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val speed = json.optInt("speed", 0)
                    runOnUiThread {
                        speedTextView.text = speed.toString()
                    }
                } catch (e: Exception) {
                    // 예외 무시
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "App destroyed")
    }
}

