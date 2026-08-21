package com.example.screenstreamviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var eglBase: EglBase
    private lateinit var videoRenderer: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var connectionForm: LinearLayout
    private lateinit var contentPanel: LinearLayout
    private lateinit var serverInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var setupLinkText: TextView
    private var client: ViewerClient? = null
    private var lastTouchDownX = 0f
    private var lastTouchDownY = 0f
    private var lastTouchDownTime = 0L

    companion object {
        private const val PREFS = "viewer"
        private const val SERVER_KEY = "server"
        private const val ROOM_KEY = "room"
        private const val DEFAULT_SERVER = "wss://YOUR-SIGNALING-SERVER"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eglBase = EglBase.create()
        buildUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(8, 11, 18)) }
        videoRenderer = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setZOrderMediaOverlay(true)
            setEnableHardwareScaler(true)
        }
        root.addView(videoRenderer, FrameLayout.LayoutParams(-1, -1))

        contentPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            background = roundedBackground(0xF0141A25.toInt(), dp(24))
        }

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val viewerTab = Button(this).apply { text = "Viewer"; isAllCaps = false }
        val apkTab = Button(this).apply { text = "APK"; isAllCaps = false }
        tabs.addView(viewerTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabs.addView(apkTab, LinearLayout.LayoutParams(0, dp(48), 1f))

        connectionForm = buildViewerTab(prefs)
        val apkForm = buildApkTab(prefs)
        contentPanel.addView(tabs)
        contentPanel.addView(connectionForm, LinearLayout.LayoutParams(-1, 0, 1f))

        viewerTab.setOnClickListener { showTab(connectionForm, viewerTab, apkTab) }
        apkTab.setOnClickListener { showTab(apkForm, apkTab, viewerTab) }

        root.addView(contentPanel, FrameLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        setContentView(root)
        setupTouchForwarding()
    }

    private fun buildViewerTab(prefs: android.content.SharedPreferences): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val title = TextView(this@MainActivity).apply {
                text = "ScreenStream"
                textSize = 30f
                setTextColor(0xFFF5F7FA.toInt())
                gravity = Gravity.CENTER
            }
            val subtitle = TextView(this@MainActivity).apply {
                text = "Remote screen viewer"
                textSize = 14f
                setTextColor(0xFF9AA6B6.toInt())
                gravity = Gravity.CENTER
            }
            serverInput = input("wss://server.example.com").apply { setText(prefs.getString(SERVER_KEY, "")) }
            roomInput = input("Room code").apply { setText(prefs.getString(ROOM_KEY, "")) }
            val connectBtn = Button(this@MainActivity).apply {
                text = "Connect"
                isAllCaps = false
                setOnClickListener {
                    val server = serverInput.text.toString().trim()
                    val room = roomInput.text.toString().trim()
                    if (!server.startsWith("ws://") && !server.startsWith("wss://")) {
                        Toast.makeText(this@MainActivity, "Enter a valid WebSocket server", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                    }
                    if (room.isEmpty()) {
                        roomInput.setText(generateRoom())
                    }
                    val finalRoom = roomInput.text.toString().trim()
                    prefs.edit().putString(SERVER_KEY, server).putString(ROOM_KEY, finalRoom).apply()
                    connect(server, finalRoom)
                }
            }
            statusText = TextView(this@MainActivity).apply {
                text = "Ready to connect"
                textSize = 13f
                setTextColor(0xFF9AA6B6.toInt())
                gravity = Gravity.CENTER
            }
            listOf(title, subtitle, serverInput, roomInput, connectBtn, statusText).forEachIndexed { index, view ->
                val p = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
                p.topMargin = if (index == 0) 0 else dp(12)
                addView(view, p)
            }
        }
    }

    private fun buildApkTab(prefs: android.content.SharedPreferences): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(16), dp(4), 0)
            val title = TextView(this@MainActivity).apply {
                text = "Streamer APK setup"
                textSize = 24f
                setTextColor(0xFFF5F7FA.toInt())
                gravity = Gravity.CENTER
            }
            val info = TextView(this@MainActivity).apply {
                text = "Generate a room and a one-tap setup link for the ScreenStream Sender APK. The link fills the server and room automatically after installation."
                textSize = 13f
                setTextColor(0xFF9AA6B6.toInt())
                gravity = Gravity.CENTER
            }
            val server = input("Signaling server (wss://…)").apply { setText(prefs.getString(SERVER_KEY, "")) }
            val room = input("Room code (leave empty to generate)")
            val generate = Button(this@MainActivity).apply {
                text = "Generate setup"
                isAllCaps = false
                setOnClickListener {
                    val serverValue = server.text.toString().trim()
                    if (!serverValue.startsWith("ws://") && !serverValue.startsWith("wss://")) {
                        Toast.makeText(this@MainActivity, "Enter a valid WebSocket server", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                    }
                    val roomValue = room.text.toString().trim().ifBlank { generateRoom() }
                    room.setText(roomValue)
                    serverInput.setText(serverValue)
                    roomInput.setText(roomValue)
                    prefs.edit().putString(SERVER_KEY, serverValue).putString(ROOM_KEY, roomValue).apply()
                    val link = "screenstream://connect?server=${Uri.encode(serverValue)}&room=${Uri.encode(roomValue)}"
                    setupLinkText.text = link
                    setupLinkText.visibility = View.VISIBLE
                }
            }
            setupLinkText = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(0xFFB9C5D6.toInt())
                setTextIsSelectable(true)
                visibility = View.GONE
            }
            val copy = Button(this@MainActivity).apply {
                text = "Copy setup link"
                isAllCaps = false
                setOnClickListener {
                    val link = setupLinkText.text.toString()
                    if (link.isBlank()) return@setOnClickListener
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ScreenStream setup", link))
                    Toast.makeText(this@MainActivity, "Setup link copied", Toast.LENGTH_SHORT).show()
                }
            }
            val apk = Button(this@MainActivity).apply {
                text = "Get latest Sender APK build"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/iojh2021-oss/screenstream-android/actions")))
                }
            }
            listOf(title, info, server, room, generate, setupLinkText, copy, apk).forEachIndexed { index, view ->
                val p = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
                p.topMargin = if (index == 0) 0 else dp(10)
                addView(view, p)
            }
        }
    }

    private fun showTab(target: View, active: Button, inactive: Button) {
        contentPanel.removeViews(2, contentPanel.childCount - 2)
        contentPanel.addView(target, LinearLayout.LayoutParams(-1, 0, 1f))
        active.alpha = 1f
        inactive.alpha = 0.55f
    }

    private fun generateRoom(): String = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()

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
                MotionEvent.ACTION_DOWN -> { lastTouchDownX = x; lastTouchDownY = y; lastTouchDownTime = System.currentTimeMillis() }
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
