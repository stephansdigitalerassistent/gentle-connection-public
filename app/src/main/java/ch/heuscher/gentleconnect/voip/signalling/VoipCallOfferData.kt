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

class VoipCallOfferData : VoipCallData<VoipCallOfferData>() {

    var offerData: OfferData? = null
    var features: FeatureList = FeatureList()

    class OfferData {
        var sdpType: String? = null
        var sdp: String? = null

        fun setSdpType(type: String): OfferData {
            this.sdpType = type
            return this
        }

        fun setSdp(sdp: String?): OfferData {
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
            fun parse(o: JSONObject): OfferData {
                try {
                    val offerData = OfferData()
                    offerData.sdpType = getStringOrNull(o, KEY_SDP_TYPE)
                    if (offerData.sdpType == null) {
                        throw BadMessageException("TM060: sdpType missing")
                    } else if (offerData.sdpType == "answer" || offerData.sdpType == "pranswer") {
                        throw BadMessageException("TM060: sdpType cannot be answer or pranswer")
                    }

                    offerData.sdp = getStringOrNull(o, KEY_SDP)
                    if (offerData.sdp == null && offerData.sdpType != "rollback") {
                        throw BadMessageException("TM060: sdp may only be null if sdpType=rollback")
                    }

                    return offerData
                } catch (e: Exception) {
                    if (e is BadMessageException) throw e
                    throw BadMessageException("TM060", e)
                }
            }

            private const val KEY_SDP_TYPE = "sdpType"
            private const val KEY_SDP = "sdp"
        }
    }

    fun setOfferData(data: OfferData): VoipCallOfferData {
        this.offerData = data
        return this
    }

    fun addFeature(feature: CallFeature): VoipCallOfferData {
        features.addFeature(feature)
        return this
    }

    @Throws(Exception::class)
    fun write(bos: ByteArrayOutputStream) {
        bos.write(generateString().toByteArray(StandardCharsets.UTF_8))
    }

    @Throws(BadMessageException::class)
    fun generateString(): String {
        val o = buildJsonObject()
        val data = offerData ?: throw BadMessageException("TM060: Missing offer data")
        try {
            o.put(KEY_OFFER, data.toJSON())
            if (!features.isEmpty) {
                o.put(KEY_FEATURES, features.toJSON())
            }
        } catch (e: JSONException) {
            throw BadMessageException("TM060", e)
        }
        return o.toString()
    }

    companion object {
        private const val KEY_OFFER = "offer"
        private const val KEY_FEATURES = "features"

        @Throws(BadMessageException::class)
        fun parse(jsonObjectString: String): VoipCallOfferData {
            val o: JSONObject = try {
                JSONObject(jsonObjectString)
            } catch (e: JSONException) {
                throw BadMessageException("TM060: Invalid JSON", e)
            }

            val offerData = VoipCallOfferData()

            try {
                val callId = getLongOrThrow(o, KEY_CALL_ID)
                if (callId != null) {
                    offerData.setCallId(callId)
                }
            } catch (e: Exception) {
                throw BadMessageException("TM060: Invalid Call ID", e)
            }

            try {
                val offerObj = o.getJSONObject(KEY_OFFER)
                offerData.offerData = OfferData.parse(offerObj)
            } catch (e: Exception) {
                if (e is BadMessageException) throw e
                throw BadMessageException("TM060: Offer parse failed", e)
            }

            try {
                val featureObj = o.optJSONObject(KEY_FEATURES)
                if (featureObj != null) {
                    offerData.features = FeatureList.parse(featureObj)
                }
            } catch (e: Exception) {
                throw BadMessageException("TM060: Feature list parse failed", e)
            }

            return offerData
        }
    }
}
