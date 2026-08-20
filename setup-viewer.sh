#!/bin/bash
# ScreenStream Viewer - Auto Setup Script for Termux
# Run this inside an empty cloned repo directory

set -e

echo "🚀 ScreenStream Viewer - Auto Setup"
echo "===================================="

mkdir -p app/src/main/java/com/example/screenstreamviewer
mkdir -p app/src/main/res/{values,drawable,mipmap-anydpi-v26}
mkdir -p .github/workflows
mkdir -p gradle/wrapper

# ============================================
# Gradle files
# ============================================

cat > settings.gradle.kts << 'EOF'
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ScreenStreamViewer"
include(":app")
EOF
echo "✅ settings.gradle.kts"

cat > build.gradle.kts << 'EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
EOF
echo "✅ build.gradle.kts"

cat > gradle.properties << 'EOF'
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
EOF
echo "✅ gradle.properties"

cat > gradle/wrapper/gradle-wrapper.properties << 'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF
echo "✅ gradle-wrapper.properties (Gradle 8.9)"

cat > .github/workflows/build.yml << 'EOF'
name: Build APK
on:
  push:
    branches: [main]
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: {distribution: temurin, java-version: '17', cache: gradle}
      - uses: android-actions/setup-android@v3
      - run: chmod +x ./gradlew
      - run: ./gradlew assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        if: success()
        with: {name: app-debug, path: app/build/outputs/apk/debug/app-debug.apk}
EOF
echo "✅ .github/workflows/build.yml"

# ============================================
# App-level Gradle
# ============================================

cat > app/build.gradle.kts << 'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.screenstreamviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screenstreamviewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("io.getstream:stream-webrtc-android:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
EOF
echo "✅ app/build.gradle.kts"

# ============================================
# Manifest
# ============================================

cat > app/src/main/AndroidManifest.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.ScreenStreamViewer"
        android:supportsRtl="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:configChanges="orientation|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
EOF
echo "✅ AndroidManifest.xml"

# ============================================
# Resources
# ============================================

cat > app/src/main/res/values/strings.xml << 'EOF'
<resources>
    <string name="app_name">ScreenStream Viewer</string>
</resources>
EOF
echo "✅ strings.xml"

cat > app/src/main/res/values/themes.xml << 'EOF'
<resources>
    <style name="Theme.ScreenStreamViewer" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="android:windowBackground">#0D0F14</item>
    </style>
</resources>
EOF
echo "✅ themes.xml"

cat > app/src/main/res/values/colors.xml << 'EOF'
<resources>
    <color name="ic_launcher_background">#0D0F14</color>
    <color name="gold">#C9A768</color>
</resources>
EOF
echo "✅ colors.xml"

cat > app/src/main/res/drawable/ic_launcher_foreground.xml << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#00000000" android:strokeColor="#C9A768" android:strokeWidth="5"
        android:pathData="M24,34 L84,34 A6,6 0 0 1 90,40 L90,70 A6,6 0 0 1 84,76 L24,76 A6,6 0 0 1 18,70 L18,40 A6,6 0 0 1 24,34 Z" />
</vector>
EOF
echo "✅ ic_launcher_foreground.xml"

cat > app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
EOF
echo "✅ ic_launcher.xml"

cp app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
echo "✅ ic_launcher_round.xml"

# ============================================
# Kotlin - MainActivity (UI + orchestration)
# ============================================

cat > app/src/main/java/com/example/screenstreamviewer/MainActivity.kt << 'KOTLIN_EOF'
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
KOTLIN_EOF
echo "✅ MainActivity.kt"

# ============================================
# Kotlin - ViewerClient (WebRTC + Signaling)
# ============================================

cat > app/src/main/java/com/example/screenstreamviewer/ViewerClient.kt << 'KOTLIN_EOF'
package com.example.screenstreamviewer

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.charset.StandardCharsets

class ViewerClient(
    private val context: android.content.Context,
    private val eglBase: EglBase,
    private val serverUrl: String,
    private val room: String,
    private val callback: Callback
) {
    interface Callback {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onRemoteVideo(track: VideoTrack)
    }

    private val httpClient = OkHttpClient()
    private var webSocket: WebSocket? = null
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    fun connect() {
        setupPeerConnectionFactory()
        connectSignaling()
    }

    private fun setupPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun connectSignaling() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                send(JSONObject().apply {
                    put("type", "join")
                    put("room", room)
                    put("role", "viewer")
                })
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "peer-joined" -> {
                        // phone joined, wait for offer
                    }
                    "offer" -> handleOffer(msg.getJSONObject("sdp"))
                    "ice-candidate" -> {
                        val c = msg.getJSONObject("candidate")
                        peerConnection?.addIceCandidate(
                            IceCandidate(c.getString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate"))
                        )
                    }
                    "peer-left" -> callback.onDisconnected("طرف مقابل قطع شد")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                callback.onDisconnected(t.message ?: "خطای اتصال")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                callback.onDisconnected("اتصال بسته شد")
            }
        })
    }

    private fun handleOffer(sdp: JSONObject) {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                send(JSONObject().apply {
                    put("type", "ice-candidate")
                    put("candidate", JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                })
            }

            override fun onAddStream(stream: MediaStream) {
                if (stream.videoTracks.isNotEmpty()) {
                    callback.onRemoteVideo(stream.videoTracks[0])
                }
            }

            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    callback.onConnected()
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED) {
                    callback.onDisconnected("اتصال WebRTC قطع شد")
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })

        val remoteDescription = SessionDescription(SessionDescription.Type.OFFER, sdp.getString("sdp"))
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                        send(JSONObject().apply {
                            put("type", "answer")
                            put("sdp", JSONObject().apply {
                                put("type", desc.type.canonicalForm())
                                put("sdp", desc.description)
                            })
                        })
                    }
                }, MediaConstraints())
            }
        }, remoteDescription)
    }

    fun sendTap(x: Float, y: Float) {
        sendControl(JSONObject().apply {
            put("type", "tap")
            put("x", x)
            put("y", y)
        })
    }

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        sendControl(JSONObject().apply {
            put("type", "swipe")
            put("x1", x1); put("y1", y1)
            put("x2", x2); put("y2", y2)
            put("duration", durationMs)
        })
    }

    private fun sendControl(json: JSONObject) {
        dataChannel?.send(DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(json.toString().toByteArray(StandardCharsets.UTF_8)), false
        ))
    }

    private fun send(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    fun close() {
        webSocket?.close(1000, "done")
        dataChannel?.close()
        peerConnection?.close()
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
KOTLIN_EOF
echo "✅ ViewerClient.kt"

echo ""
echo "===================================="
echo "✅ تمام فایل‌های Viewer ساخته شدند!"
echo ""
echo "حالا اجرا کن:"
echo "  git add ."
echo "  git commit -m 'Add ScreenStream Viewer app'"
echo "  git push -u origin main"
