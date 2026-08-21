package com.example.screenstreamviewer

import android.content.Context
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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class ViewerClient(
    private val context: Context,
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
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var closed = false

    fun connect() {
        if (closed) return
        if (peerConnectionFactory == null) setupPeerConnectionFactory()
        connectSignaling()
    }

    private fun setupPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
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
                send(JSONObject().apply { put("type", "join"); put("room", room); put("role", "viewer") })
            }
            override fun onMessage(ws: WebSocket, text: String) {
                runCatching {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "offer" -> handleOffer(msg.getJSONObject("sdp"))
                        "ice-candidate" -> handleIceCandidate(msg.getJSONObject("candidate"))
                        "peer-left" -> callback.onDisconnected("Remote device disconnected")
                        "error" -> callback.onDisconnected(msg.optString("message", "Signaling server error"))
                    }
                }.onFailure { callback.onDisconnected("Invalid signaling message") }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!closed) callback.onDisconnected(t.message ?: "Signaling connection failed")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!closed) callback.onDisconnected(reason.ifBlank { "Signaling connection closed" })
            }
        })
    }

    private fun handleIceCandidate(c: JSONObject) {
        val mid = c.optString("sdpMid", null)
        val index = c.optInt("sdpMLineIndex", -1)
        val candidate = c.optString("candidate", "")
        if (mid == null || index < 0 || candidate.isBlank()) return
        val iceCandidate = IceCandidate(mid, index, candidate)
        if (!remoteDescriptionSet) pendingIceCandidates += iceCandidate
        else peerConnection?.addIceCandidate(iceCandidate)
    }

    private fun handleOffer(sdp: JSONObject) {
        val factory = peerConnectionFactory ?: return
        peerConnection?.close()
        pendingIceCandidates.clear()
        remoteDescriptionSet = false
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )
        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
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
            override fun onAddStream(stream: MediaStream) { stream.videoTracks.firstOrNull()?.let(callback::onRemoteVideo) }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) { (transceiver.receiver.track() as? VideoTrack)?.let(callback::onRemoteVideo) }
            override fun onDataChannel(channel: DataChannel) { dataChannel = channel }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> callback.onConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> callback.onDisconnected("WebRTC connection lost")
                    else -> Unit
                }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                if (state == PeerConnection.PeerConnectionState.CONNECTED) callback.onConnected()
                if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.DISCONNECTED) callback.onDisconnected("Peer connection lost")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })

        val description = SessionDescription(SessionDescription.Type.OFFER, sdp.getString("sdp"))
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                pendingIceCandidates.clear()
                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetFailure(error: String?) { callback.onDisconnected(error ?: "Failed to set local description") }
                        }, desc)
                        send(JSONObject().apply {
                            put("type", "answer")
                            put("sdp", JSONObject().apply { put("type", desc.type.canonicalForm()); put("sdp", desc.description) })
                        })
                    }
                    override fun onCreateFailure(error: String?) { callback.onDisconnected(error ?: "Failed to create answer") }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) { callback.onDisconnected(error ?: "Invalid remote offer") }
        }, description)
    }

    fun sendTap(x: Float, y: Float) = sendControl(JSONObject().apply { put("type", "tap"); put("x", x); put("y", y) })

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = sendControl(JSONObject().apply {
        put("type", "swipe"); put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2); put("duration", durationMs)
    })

    private fun sendControl(json: JSONObject) {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(json.toString().toByteArray(StandardCharsets.UTF_8)), false))
    }

    private fun send(json: JSONObject) { webSocket?.send(json.toString()) }

    fun close() {
        closed = true
        webSocket?.close(1000, "done")
        webSocket = null
        dataChannel?.close(); dataChannel = null
        peerConnection?.close(); peerConnection = null
        peerConnectionFactory?.dispose(); peerConnectionFactory = null
        pendingIceCandidates.clear()
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
