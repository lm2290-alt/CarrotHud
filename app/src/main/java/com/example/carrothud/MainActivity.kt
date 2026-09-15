package com.example.carrothud

import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var speedTextView: TextView
    private lateinit var statusTextView: TextView
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(30, 30, 30, 30)
        }

        speedTextView = TextView(this).apply {
            textSize = 70f
            text = "0"
            setTextColor(Color.GREEN)
            gravity = Gravity.CENTER
        }

        statusTextView = TextView(this).apply {
            textSize = 24f
            text = "CONNECTING..."
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(50, 0, 0, 0)
        }

        layout.addView(speedTextView)
        layout.addView(statusTextView)
        setContentView(layout)

        connectWebSocket()
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url("ws://10.239.225.61:7000").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    statusTextView.text = "CONNECTED"
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val speed = if (json.has("speed")) json.optInt("speed", 0) else json.optInt("vEgo", 0)
                    
                    runOnUiThread {
                        speedTextView.text = speed.toString()
                        statusTextView.text = "OK\n10.239.225.61"
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusTextView.text = "PARSE ERR"
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    statusTextView.text = "DISCONNECTED"
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "App destroyed")
    }
}

