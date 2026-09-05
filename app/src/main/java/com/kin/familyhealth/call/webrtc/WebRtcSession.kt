package com.kin.familyhealth.call.webrtc

import com.kin.familyhealth.BuildConfig

import android.content.Context
import android.util.Log
import com.kin.familyhealth.core.SignalMessage
import com.kin.familyhealth.core.SignalType
import com.kin.familyhealth.core.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
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
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

private const val TAG = "WebRtcSession"

/**
 * Connection lifecycle exposed to the UI layer (CallActivity).
 */
/**
 * How long either side waits in CONNECTING before declaring NO_ANSWER. Without this a
 * caller whose wake-push never arrived would see "Calling..." forever and never learn
 * that help was NOT summoned.
 */
private const val CONNECT_TIMEOUT_MS = 30_000L

enum class CallConnectionState { CONNECTING, CONNECTED, DISCONNECTED, FAILED, NO_ANSWER }

/**
 * Thin wrapper around `org.webrtc` (io.github.webrtc-sdk:android:114.5735.10) that owns:
 *  - PeerConnectionFactory + a shared [EglBase] for hardware video rendering
 *  - Local camera capture (Camera2Enumerator, front camera by default, switchable)
 *  - Local mic capture (audio track)
 *  - The single PeerConnection for this call, driven by SDP offer/answer + ICE
 *  - Signaling I/O through the injected [core.SignalingClient] — this class never
 *    talks to Firestore/FCM directly, only through that interface.
 *
 * NOT thread-confined to any Android component: the caller (CallForegroundService /
 * CallSessionHolder) owns this instance's lifecycle and must call [dispose] exactly once.
 *
 * NAT traversal: only a public STUN server is configured below
 * (stun:stun.l.google.com:19302). That is enough for many home-network pairs but will
 * fail across symmetric NATs / some carrier-grade NATs. Production should add a TURN
 * server (e.g. coturn, Twilio, or Cloudflare Calls) to the ICE server list for reliable
 * connectivity — flagged here for the commander/QA, not addressed by this stub.
 *
 * @param appContext application Context (not an Activity — avoid leaking one).
 * @param signaling the SignalingClient implementation, injected by the commander at
 *   integration time (see [com.kin.familyhealth.call.CallSessionHolder]).
 * @param room the signaling room id (from CallLauncher extras).
 * @param peerUid the *other* party's uid — the `toUid` for every [SignalingClient.send] call.
 */
class WebRtcSession(
    private val appContext: Context,
    private val signaling: SignalingClient,
    private val room: String,
    private val peerUid: String,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var incomingJob: Job? = null

    /** Shared EGL context — pass eglBase.eglBaseContext to any SurfaceViewRenderer.init(). */
    val eglBase: EglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    // ICE candidates can arrive from Firestore before setRemoteDescription completes
    // (it's async). Queue them and flush once the remote description is in place,
    // otherwise libwebrtc may silently drop them and the call never connects.
    @Volatile private var remoteDescriptionSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private var isFrontCamera = true
    private var disposed = false

    /** True once [dispose] has run; a disposed session must never be reused for a call. */
    val isDisposed: Boolean get() = disposed

    private val _connectionState = MutableStateFlow(CallConnectionState.CONNECTING)
    val connectionState: StateFlow<CallConnectionState> = _connectionState

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    private val _cameraEnabled = MutableStateFlow(true)
    val cameraEnabled: StateFlow<Boolean> = _cameraEnabled

    private val _remoteHangup = MutableStateFlow(false)
    val remoteHangup: StateFlow<Boolean> = _remoteHangup

    /**
     * Must be called once, on the main thread, before [attachLocalRenderer]/[startAsCaller]/
     * [startAsCallee]. Sets up the PeerConnectionFactory, capturers and tracks, and starts
     * listening for incoming signals. Requires that CAMERA/RECORD_AUDIO permissions are
     * already granted and that the caller is already inside a running foreground service
     * (background camera access is blocked by the OS — see ARCHITECTURE.md).
     */
    fun initialize() {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        setUpLocalMedia()
        listenForSignals()
    }

    private fun setUpLocalMedia() {
        val f = factory ?: return

        // --- Camera / local video track ---
        val enumerator = Camera2Enumerator(appContext)
        val frontName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val backName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        val chosenName = frontName ?: backName ?: enumerator.deviceNames.firstOrNull()
        isFrontCamera = chosenName != null && chosenName == frontName

        val capturer = chosenName?.let { enumerator.createCapturer(it, null) }
        videoCapturer = capturer

        val source = f.createVideoSource(capturer?.isScreencast ?: false)
        videoSource = source

        if (capturer != null) {
            val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            surfaceTextureHelper = helper
            capturer.initialize(helper, appContext, source.capturerObserver)
            runCatching { capturer.startCapture(1280, 720, 30) }
                .onFailure { Log.w(TAG, "startCapture failed", it) }
        } else {
            Log.w(TAG, "No camera found on device; call will be audio-only")
        }

        localVideoTrack = f.createVideoTrack("kin_v0", source).apply { setEnabled(true) }

        // --- Mic / local audio track ---
        val aSource = f.createAudioSource(MediaConstraints())
        audioSource = aSource
        localAudioTrack = f.createAudioTrack("kin_a0", aSource).apply { setEnabled(true) }

        // --- PeerConnection ---
        // STUN handles direct peer-to-peer on friendly networks; TURN relays the
        // media when both peers are behind symmetric NAT (typical on mobile data),
        // so an emergency call still connects on cellular. TURN creds come from
        // BuildConfig (set via gradle.properties) — empty means STUN-only.
        val iceServers = buildList {
            add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            val turnUrl = BuildConfig.TURN_URL
            if (turnUrl.isNotBlank()) {
                add(
                    PeerConnection.IceServer.builder(turnUrl)
                        .setUsername(BuildConfig.TURN_USERNAME)
                        .setPassword(BuildConfig.TURN_CREDENTIAL)
                        .createIceServer()
                )
            }
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = f.createPeerConnection(rtcConfig, pcObserver)
        peerConnection?.let { pc ->
            pc.addTrack(localVideoTrack, listOf("kin_stream"))
            pc.addTrack(localAudioTrack, listOf("kin_stream"))
        }

        localRenderer?.let { localVideoTrack?.addSink(it) }
    }

    /** Attach the local preview (PiP) renderer. Safe to call before or after [initialize]. */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setMirror(isFrontCamera)
        renderer.setEnableHardwareScaler(true)
        localRenderer = renderer
        localVideoTrack?.addSink(renderer)
    }

    /** Attach the full-screen remote renderer. Safe to call before or after [initialize]. */
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        remoteRenderer = renderer
    }

    /** Caller side: create and send an SDP offer. Call after [initialize]. */
    fun startAsCaller() {
        startConnectWatchdog()
        val pc = peerConnection ?: return
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                scope.launch {
                    signaling.send(
                        peerUid,
                        SignalMessage(type = SignalType.OFFER, fromUid = "", room = room, sdp = desc.description)
                    )
                }
            }
        }, MediaConstraints())
    }

    /** Callee side: nothing to send up-front — just wait for the OFFER via [listenForSignals]. */
    fun startAsCallee() {
        // initialize() already started listenForSignals(); the OFFER drives onOffer().
        startConnectWatchdog()
    }

    /**
     * If we are still CONNECTING after [CONNECT_TIMEOUT_MS], flip to NO_ANSWER so the UI
     * can tell the person plainly that the call did not go through and offer a fallback.
     * A connected/failed/hung-up call leaves CONNECTING first, so this is a no-op then.
     */
    private fun startConnectWatchdog() {
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            // Atomic compare-and-set: onIceConnectionChange writes CONNECTED from a WebRTC
            // thread, so a plain read-then-write could mislabel a just-connected call.
            if (!disposed) {
                _connectionState.compareAndSet(CallConnectionState.CONNECTING, CallConnectionState.NO_ANSWER)
            }
        }
    }

    private fun listenForSignals() {
        incomingJob = scope.launch {
            signaling.incoming(room).collect { message -> handleSignal(message) }
        }
    }

    private fun handleSignal(message: SignalMessage) {
        when (message.type) {
            SignalType.OFFER -> message.sdp?.let(::onRemoteOffer)
            SignalType.ANSWER -> message.sdp?.let(::onRemoteAnswer)
            SignalType.ICE -> message.candidate?.let(::onRemoteIceCandidate)
            SignalType.HANGUP -> {
                _remoteHangup.value = true
                _connectionState.value = CallConnectionState.DISCONNECTED
            }
        }
    }

    private fun onRemoteOffer(sdp: String) {
        val pc = peerConnection ?: return
        // Correct WebRTC order: apply the remote OFFER first, and only once that has
        // succeeded create the ANSWER and flush any early ICE candidates.
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                onRemoteDescriptionSet()
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        pc.setLocalDescription(SdpObserverAdapter(), desc)
                        scope.launch {
                            signaling.send(
                                peerUid,
                                SignalMessage(type = SignalType.ANSWER, fromUid = "", room = room, sdp = desc.description)
                            )
                        }
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun onRemoteAnswer(sdp: String) {
        peerConnection?.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() { onRemoteDescriptionSet() }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    /** Marks the remote description as applied and flushes queued ICE candidates. */
    private fun onRemoteDescriptionSet() {
        remoteDescriptionSet = true
        val queued = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
        queued.forEach { peerConnection?.addIceCandidate(it) }
    }

    private fun onRemoteIceCandidate(candidateJson: String) {
        runCatching {
            val json = JSONObject(candidateJson)
            IceCandidate(
                json.optString("sdpMid"),
                json.optInt("sdpMLineIndex"),
                json.optString("candidate")
            )
        }.onSuccess { candidate ->
            if (remoteDescriptionSet) {
                peerConnection?.addIceCandidate(candidate)
            } else {
                synchronized(pendingIce) { pendingIce.add(candidate) }
            }
        }.onFailure { Log.w(TAG, "bad ICE candidate payload", it) }
    }

    private fun encodeIceCandidate(candidate: IceCandidate): String =
        JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }.toString()

    /** Toggle mic mute; returns the new muted state. */
    fun toggleMute(): Boolean {
        val newMuted = !_muted.value
        localAudioTrack?.setEnabled(!newMuted)
        _muted.value = newMuted
        return newMuted
    }

    /** Toggle local camera on/off (keeps the call audio-only, does not end it). */
    fun toggleCamera(): Boolean {
        val newEnabled = !_cameraEnabled.value
        localVideoTrack?.setEnabled(newEnabled)
        _cameraEnabled.value = newEnabled
        return newEnabled
    }

    /** Flip between front/back camera. */
    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCameraNow: Boolean) {
                isFrontCamera = isFrontCameraNow
                localRenderer?.setMirror(isFrontCameraNow)
            }

            override fun onCameraSwitchError(error: String?) {
                Log.w(TAG, "switchCamera failed: $error")
            }
        })
    }

    /** End the call: notify the peer, then release all resources. Safe to call multiple times. */
    fun hangUp() {
        if (disposed) return
        scope.launch {
            runCatching {
                signaling.send(peerUid, SignalMessage(type = SignalType.HANGUP, fromUid = "", room = room))
            }
        }
        dispose()
    }

    /** Release camera/mic/peer-connection resources. Does NOT notify the peer — use [hangUp] for that. */
    fun dispose() {
        if (disposed) return
        disposed = true
        incomingJob?.cancel()
        // Tear down the whole scope so no straggling signaling.send() coroutine outlives
        // the session (they'd otherwise no-op against a closed room and leak).
        scope.cancel()
        signaling.close(room)

        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        localVideoTrack?.dispose()
        videoSource?.dispose()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        factory?.dispose()
        // Detach the renderer fields BEFORE releasing them so a late PeerConnection.Observer
        // callback (native thread) can never addSink() onto an already-released renderer.
        val lr = localRenderer
        val rr = remoteRenderer
        localRenderer = null
        remoteRenderer = null
        lr?.release()
        rr?.release()
        eglBase.release()
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> _connectionState.value = CallConnectionState.CONNECTED
                PeerConnection.IceConnectionState.DISCONNECTED -> _connectionState.value = CallConnectionState.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED -> _connectionState.value = CallConnectionState.FAILED
                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                signaling.send(
                    peerUid,
                    SignalMessage(
                        type = SignalType.ICE,
                        fromUid = "",
                        room = room,
                        candidate = encodeIceCandidate(candidate)
                    )
                )
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {
            // Legacy Plan B callback; kept for older/alt code paths. Unified Plan delivers
            // remote video via onAddTrack/onTrack below.
            if (disposed) return
            val renderer = remoteRenderer ?: return
            stream?.videoTracks?.firstOrNull()?.addSink(renderer)
        }

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            if (disposed) return
            val track = receiver?.track()
            if (track is VideoTrack) {
                remoteRenderer?.let { track.addSink(it) }
            }
        }
    }

    /** SdpObserver with every callback defaulted to no-op except the one(s) a call site overrides. */
    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "SDP create failure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.w(TAG, "SDP set failure: $error")
        }
    }
}
