/*
 * Gentle Connect - Senior-friendly communication application
 * Copyright (C) 2026 gentle-connection contributors
 *
 * Portions derived from Threema for Android:
 * Copyright (C) Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ch.heuscher.gentleconnect.voip

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Callbacks emitted by [PeerConnectionClient] during WebRTC peer connection lifecycle.
 */
interface PeerConnectionEvents {
    fun onLocalDescription(sdp: SessionDescription)
    fun onIceCandidate(candidate: IceCandidate)
    fun onIceCandidatesRemoved(candidates: Array<IceCandidate>)
    fun onIceConnected()
    fun onIceDisconnected()
    fun onPeerConnectionClosed()
    fun onPeerConnectionError(description: String)
}

/**
 * Manages WebRTC PeerConnection, ICE servers (built from TURN credentials), AudioTrack creation,
 * SDP offer/answer generation, and candidate handling for voice calls.
 */
class PeerConnectionClient(
    private val context: Context,
    private val events: PeerConnectionEvents
) {
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var isInitiator: Boolean = false

    companion object {
        private const val TAG = "PeerConnectionClient"
        private const val AUDIO_TRACK_ID = "ARDAMSa0"
        private const val AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation"
        private const val AUDIO_AUTO_GAIN_CONSTRAINT = "googAutoGainControl"
        private const val AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter"
        private const val AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression"

        private var factoryInitialized = false

        @Synchronized
        fun initializeFactory(context: Context) {
            if (factoryInitialized) return
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            factoryInitialized = true
        }
    }

    fun createPeerConnection(turnCredentials: TurnCredentials?, isInitiator: Boolean) {
        this.isInitiator = isInitiator
        initializeFactory(context)

        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        adm.release()

        val iceServers = mutableListOf<PeerConnection.IceServer>()
        turnCredentials?.allTurnUrls()?.forEach { url ->
            val builder = PeerConnection.IceServer.builder(url)
                .setUsername(turnCredentials.turnUsername)
                .setPassword(turnCredentials.turnPassword)
            iceServers.add(builder.createIceServer())
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pcObserver = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                events.onIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
                events.onIceCandidatesRemoved(candidates)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE Connection State: $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> events.onIceConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED -> events.onIceDisconnected()
                    PeerConnection.IceConnectionState.FAILED -> events.onPeerConnectionError("ICE connection failed")
                    else -> {}
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, pcObserver)
            ?: throw IllegalStateException("Failed to create PeerConnection")

        // Create local audio track
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "true"))
            mandatory.add(MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONSTRAINT, "true"))
            mandatory.add(MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "true"))
            mandatory.add(MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "true"))
        }

        audioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))
    }

    fun createOffer() {
        val sdpMediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        events.onLocalDescription(sdp)
                    }
                }, sdp)
            }
        }, sdpMediaConstraints)
    }

    fun createAnswer() {
        val sdpMediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        events.onLocalDescription(sdp)
                    }
                }, sdp)
            }
        }, sdpMediaConstraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        try {
            localAudioTrack?.dispose()
            localAudioTrack = null
            audioSource?.dispose()
            audioSource = null
            peerConnection?.dispose()
            peerConnection = null
            factory?.dispose()
            factory = null
            events.onPeerConnectionClosed()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PeerConnection", e)
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(reason: String) {
            Log.e(TAG, "SDP Create Failure: $reason")
        }
        override fun onSetFailure(reason: String) {
            Log.e(TAG, "SDP Set Failure: $reason")
        }
    }
}
