package com.example.screenstreamviewer

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {

    private lateinit var eglBase: EglBase
    private lateinit var videoRenderer: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var connectionForm: LinearLayout
    private lateinit var rootLayout: FrameLayout
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
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(0xFF0D0F14.toInt())
        }

        videoRenderer = SurfaceViewRenderer(this)
        videoRenderer.init(eglBase.eglBaseContext, null)
        videoRenderer.setZOrderMediaOverlay(true)
        rootLayout.addView(videoRenderer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        connectionForm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        val title = TextView(this).apply {
            text = "ScreenStream Viewer"
            textSize = 22f
            setTextColor(0xFFC9A768.toInt())
            gravity = Gravity.CENTER
        }

        val serverInput = EditText(this).apply {
            hint = "آدرس سرور (wss://...)"
        }

        val roomInput = EditText(this).apply {
            hint = "کد اتاق"
        }

        val connectBtn = Button(this).apply {
            text = "اتصال به گوشی"
            setOnClickListener {
                val server = serverInput.text.toString().trim()
                val room = roomInput.text.toString().trim()
                if (server.isEmpty() || room.isEmpty()) {
                    Toast.makeText(this@MainActivity, "آدرس و کد اتاق را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                connect(server, room)
            }
        }

        statusText = TextView(this).apply {
            text = "منتظر اتصال..."
            gravity = Gravity.CENTER
            setTextColor(0xFFE9E2CF.toInt())
            setPadding(0, dp(16), 0, 0)
        }

        listOf(title, serverInput, roomInput, connectBtn, statusText).forEach {
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            p.topMargin = dp(12)
            connectionForm.addView(it, p)
        }

        rootLayout.addView(connectionForm, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        setContentView(rootLayout)
        setupTouchForwarding()
    }

    private fun connect(server: String, room: String) {
        statusText.text = "در حال اتصال..."
        client = ViewerClient(this, eglBase, server, room, object : ViewerClient.Callback {
            override fun onConnected() {
                runOnUiThread {
                    statusText.text = "متصل شد"
                    connectionForm.visibility = android.view.View.GONE
                }
            }

            override fun onDisconnected(reason: String) {
                runOnUiThread {
                    statusText.text = "اتصال قطع شد: $reason"
                    connectionForm.visibility = android.view.View.VISIBLE
                }
            }

            override fun onRemoteVideo(track: org.webrtc.VideoTrack) {
                runOnUiThread {
                    track.addSink(videoRenderer)
                }
            }
        })
        client?.connect()
    }

    private fun setupTouchForwarding() {
        videoRenderer.setOnTouchListener { view, event ->
            val xNorm = event.x / view.width
            val yNorm = event.y / view.height
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchDownX = xNorm
                    lastTouchDownY = yNorm
                    lastTouchDownTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - lastTouchDownTime
                    val dx = kotlin.math.abs(xNorm - lastTouchDownX)
                    val dy = kotlin.math.abs(yNorm - lastTouchDownY)
                    if (dx < 0.02 && dy < 0.02) {
                        client?.sendTap(xNorm, yNorm)
                    } else {
                        client?.sendSwipe(lastTouchDownX, lastTouchDownY, xNorm, yNorm, duration)
                    }
                }
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.close()
        videoRenderer.release()
    }
}
