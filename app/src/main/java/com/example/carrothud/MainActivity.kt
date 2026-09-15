package com.example.carrothud

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var hudView: HudCanvasView
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    private val CARROT_WEBSOCKET_URL = "ws://192.168.43.1:8080" 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        hudView = HudCanvasView(this)
        setContentView(hudView)
        connectCarrotWebSocket()
    }

    private fun connectCarrotWebSocket() {
        val request = Request.Builder().url(CARROT_WEBSOCKET_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val speed = json.optInt("vEgo", 0)
                    val targetSpeed = json.optInt("vSet", 0)
                    val distance = json.optDouble("dRel", 0.0).toFloat()
                    val hasCar = json.optBoolean("hasCar", false)
                    val warning = json.optBoolean("warning", false)

                    hudView.updateHudData(speed, targetSpeed, distance, hasCar, warning)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                hudView.postDelayed({ connectCarrotWebSocket() }, 5000)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "App closed")
    }
}
