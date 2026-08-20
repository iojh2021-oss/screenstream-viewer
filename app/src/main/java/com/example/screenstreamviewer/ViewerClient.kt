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
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
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
