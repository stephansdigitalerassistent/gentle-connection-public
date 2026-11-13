/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2017-2025 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.heuscher.gentleconnect.voip.signalling

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class VoipCallAnswerData : VoipCallData<VoipCallAnswerData>() {

    var action: Byte? = null
    var rejectReason: Byte? = null
    var answerData: AnswerData? = null
    var features: FeatureList = FeatureList()

    object Action {
        const val REJECT: Byte = 0
        const val ACCEPT: Byte = 1
    }

    object RejectReason {
        const val UNKNOWN: Byte = 0
        const val BUSY: Byte = 1
        const val TIMEOUT: Byte = 2
        const val REJECTED: Byte = 3
        const val DISABLED: Byte = 4
        const val OFF_HOURS: Byte = 5
    }

    class AnswerData {
        var sdpType: String? = null
        var sdp: String? = null

        fun setSdpType(type: String): AnswerData {
            this.sdpType = type
            return this
        }

        fun setSdp(sdp: String?): AnswerData {
            this.sdp = sdp
            return this
        }

        fun toJSON(): JSONObject {
            val o = JSONObject()
            o.put("sdpType", sdpType)
            o.put("sdp", if (sdp == null) JSONObject.NULL else sdp)
            return o
        }

        companion object {
            @Throws(BadMessageException::class)
            fun parse(o: JSONObject): AnswerData {
                try {
                    val data = AnswerData()
                    data.sdpType = getStringOrNull(o, KEY_SDP_TYPE)
                    if (data.sdpType == null) {
                        throw BadMessageException("TM061: sdpType missing")
                    } else if (data.sdpType == "offer") {
                        throw BadMessageException("TM061: sdpType cannot be offer")
                    }

                    data.sdp = getStringOrNull(o, KEY_SDP)
                    if (data.sdp == null && data.sdpType != "rollback") {
                        throw BadMessageException("TM061: sdp may only be null if sdpType=rollback")
                    }

                    return data
                } catch (e: Exception) {
                    if (e is BadMessageException) throw e
                    throw BadMessageException("TM061", e)
                }
            }

            private const val KEY_SDP_TYPE = "sdpType"
            private const val KEY_SDP = "sdp"
        }
    }

    fun setAction(act: Byte): VoipCallAnswerData {
        this.action = act
        return this
    }

    fun setAnswerData(data: AnswerData?): VoipCallAnswerData {
        this.answerData = data
        return this
    }

    fun setRejectReason(reason: Byte): VoipCallAnswerData {
        this.rejectReason = reason
        return this
    }

    fun addFeature(feature: CallFeature): VoipCallAnswerData {
        features.addFeature(feature)
        return this
    }

    @Throws(Exception::class)
    fun write(bos: ByteArrayOutputStream) {
        bos.write(generateString().toByteArray(StandardCharsets.UTF_8))
    }

    @Throws(BadMessageException::class)
    fun generateString(): String {
        val currentAction = action ?: throw BadMessageException("TM061: No action set")
        when (currentAction) {
            Action.ACCEPT -> {
                if (answerData == null) throw BadMessageException("TM061: Accept message must contain answer data")
                if (rejectReason != null) throw BadMessageException("TM061: Accept message must not contain reject reason")
            }
            Action.REJECT -> {
                if (rejectReason == null) throw BadMessageException("TM061: Reject message must contain reject reason")
                if (answerData != null) throw BadMessageException("TM061: Reject message must not contain answer data")
            }
            else -> throw BadMessageException("TM061: Invalid action")
        }

        val o = buildJsonObject()
        try {
            o.put(KEY_ACTION, currentAction.toInt())
            if (currentAction == Action.ACCEPT) {
                o.put(KEY_ANSWER, answerData?.toJSON())
            } else if (currentAction == Action.REJECT) {
                o.put(KEY_REJECT_REASON, rejectReason?.toInt())
            }

            if (!features.isEmpty) {
                o.put(KEY_FEATURES, features.toJSON())
            }
        } catch (e: JSONException) {
            throw BadMessageException("TM061", e)
        }

        return o.toString()
    }

    companion object {
        private const val KEY_ACTION = "action"
        private const val KEY_ANSWER = "answer"
        private const val KEY_FEATURES = "features"
        private const val KEY_REJECT_REASON = "rejectReason"

        @Throws(BadMessageException::class)
        fun parse(jsonObjectString: String): VoipCallAnswerData {
            val o: JSONObject = try {
                JSONObject(jsonObjectString)
            } catch (e: JSONException) {
                throw BadMessageException("TM061: Invalid JSON", e)
            }

            val data = VoipCallAnswerData()

            try {
                val callId = getLongOrThrow(o, KEY_CALL_ID)
                if (callId != null) {
                    data.setCallId(callId)
                }
            } catch (e: Exception) {
                throw BadMessageException("TM061: Invalid Call ID", e)
            }

            try {
                data.action = o.getInt(KEY_ACTION).toByte()
            } catch (e: Exception) {
                throw BadMessageException("TM061: Action missing or invalid", e)
            }

            if (data.action == Action.ACCEPT) {
                try {
                    val answerObj = o.getJSONObject(KEY_ANSWER)
                    data.answerData = AnswerData.parse(answerObj)
                } catch (e: Exception) {
                    if (e is BadMessageException) throw e
                    throw BadMessageException("TM061: Answer parse failed", e)
                }
            } else if (data.action == Action.REJECT) {
                try {
                    data.rejectReason = o.getInt(KEY_REJECT_REASON).toByte()
                } catch (e: Exception) {
                    throw BadMessageException("TM061: Reject reason parse failed", e)
                }
            }

            try {
                val featureObj = o.optJSONObject(KEY_FEATURES)
                if (featureObj != null) {
                    data.features = FeatureList.parse(featureObj)
                }
            } catch (e: Exception) {
                throw BadMessageException("TM061: Feature list parse failed", e)
            }

            return data
        }
    }
}
