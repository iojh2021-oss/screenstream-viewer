package com.example.screenstreamviewer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {
    private lateinit var eglBase: EglBase
    private lateinit var videoRenderer: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var connectionForm: LinearLayout
    private lateinit var rootLayout: FrameLayout
    private lateinit var serverInput: EditText
    private lateinit var roomInput: EditText
    private var client: ViewerClient? = null
    private var lastTouchDownX = 0f
    private var lastTouchDownY = 0f
    private var lastTouchDownTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eglBase = EglBase.create()
        buildUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.rgb(8, 11, 18)) }
        videoRenderer = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setZOrderMediaOverlay(true)
            setEnableHardwareScaler(true)
        }
        rootLayout.addView(videoRenderer, FrameLayout.LayoutParams(-1, -1))

        connectionForm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24))
            background = roundedBackground(0xF0141A25.toInt(), dp(24))
        }

        val title = TextView(this).apply {
            text = "ScreenStream"
            textSize = 30f
            setTextColor(0xFFF5F7FA.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Remote screen viewer"
            textSize = 14f
            setTextColor(0xFF9AA6B6.toInt())
            gravity = Gravity.CENTER
        }
        serverInput = input("wss://server.example.com")
        roomInput = input("Room code")
        val connectBtn = Button(this).apply {
            text = "Connect"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener {
                val server = serverInput.text.toString().trim()
                val room = roomInput.text.toString().trim()
                if (server.isEmpty() || room.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Enter the server and room code", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                connect(server, room)
            }
        }
        statusText = TextView(this).apply {
            text = "Ready to connect"
            textSize = 13f
            setTextColor(0xFF9AA6B6.toInt())
            gravity = Gravity.CENTER
        }

        listOf(title, subtitle, serverInput, roomInput, connectBtn, statusText).forEachIndexed { index, view ->
            val p = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.topMargin = if (index == 0) 0 else dp(12)
            connectionForm.addView(view, p)
        }
        rootLayout.addView(connectionForm, FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        setContentView(rootLayout)
        setupTouchForwarding()
    }

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setSingleLine(true)
        setTextColor(0xFFF5F7FA.toInt())
        setHintTextColor(0xFF687487.toInt())
        setPadding(dp(16), dp(4), dp(16), dp(4))
        background = roundedBackground(0xFF202938.toInt(), dp(14))
    }

    private fun roundedBackground(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun connect(server: String, room: String) {
        client?.close()
        statusText.text = "Connecting…"
        connectionForm.visibility = View.VISIBLE
        client = ViewerClient(this, eglBase, server, room, object : ViewerClient.Callback {
            override fun onConnected() = runOnUiThread {
                statusText.text = "Connected"
                connectionForm.visibility = View.GONE
            }
            override fun onDisconnected(reason: String) = runOnUiThread {
                statusText.text = "Disconnected: $reason"
                connectionForm.visibility = View.VISIBLE
            }
            override fun onRemoteVideo(track: org.webrtc.VideoTrack) = runOnUiThread {
                track.addSink(videoRenderer)
            }
        })
        client?.connect()
    }

    private fun setupTouchForwarding() {
        videoRenderer.setOnTouchListener { view, event ->
            if (view.width <= 0 || view.height <= 0) return@setOnTouchListener true
            val x = (event.x / view.width).coerceIn(0f, 1f)
            val y = (event.y / view.height).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchDownX = x; lastTouchDownY = y; lastTouchDownTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - lastTouchDownTime
                    val dx = kotlin.math.abs(x - lastTouchDownX)
                    val dy = kotlin.math.abs(y - lastTouchDownY)
                    if (dx < 0.02f && dy < 0.02f) client?.sendTap(x, y)
                    else client?.sendSwipe(lastTouchDownX, lastTouchDownY, x, y, duration.coerceAtMost(5000L))
                }
                MotionEvent.ACTION_CANCEL -> return@setOnTouchListener true
            }
            true
        }
    }

    override fun onDestroy() {
        client?.close()
        videoRenderer.release()
        eglBase.release()
        super.onDestroy()
    }
}
