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

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class VoipICECandidatesData : VoipCallData<VoipICECandidatesData>() {

    var isRemoved: Boolean = false
    var candidates: Array<Candidate>? = null

    class Candidate(
        var candidate: String? = null,
        var sdpMid: String? = null,
        var sdpMLineIndex: Int? = null,
        var ufrag: String? = null
    ) {
        fun toJSON(): JSONObject {
            val o = JSONObject()
            o.put(KEY_CANDIDATE, candidate)
            o.put(KEY_SDP_MID, if (sdpMid == null) JSONObject.NULL else sdpMid)
            o.put(KEY_SDP_M_LINE_INDEX, if (sdpMLineIndex == null) JSONObject.NULL else sdpMLineIndex)
            o.put(KEY_UFRAG, if (ufrag == null) JSONObject.NULL else ufrag)
            return o
        }

        companion object {
            @Throws(BadMessageException::class)
            fun parse(o: JSONObject): Candidate {
                try {
                    val candStr = getStringOrNull(o, KEY_CANDIDATE)
                        ?: throw BadMessageException("TM062: candidate required")
                    val candidate = Candidate()
                    candidate.candidate = candStr
                    candidate.sdpMid = getStringOrNull(o, KEY_SDP_MID)
                    candidate.sdpMLineIndex = getIntegerOrNull(o, KEY_SDP_M_LINE_INDEX)
                    candidate.ufrag = getStringOrNull(o, KEY_UFRAG)
                    return candidate
                } catch (e: Exception) {
                    if (e is BadMessageException) throw e
                    throw BadMessageException("TM062", e)
                }
            }

            private const val KEY_CANDIDATE = "candidate"
            private const val KEY_SDP_MID = "sdpMid"
            private const val KEY_SDP_M_LINE_INDEX = "sdpMLineIndex"
            private const val KEY_UFRAG = "ufrag"
        }
    }

    fun setCandidates(candList: Array<Candidate>): VoipICECandidatesData {
        this.candidates = candList
        return this
    }

    @Throws(Exception::class)
    fun write(bos: ByteArrayOutputStream) {
        bos.write(generateString().toByteArray(StandardCharsets.UTF_8))
    }

    @Throws(Exception::class)
    fun generateString(): String {
        val o = buildJsonObject()
        try {
            o.put(KEY_REMOVED, isRemoved)
            val candidateArray = JSONArray()
            candidates?.forEach { candidate ->
                candidateArray.put(candidate.toJSON())
            }
            o.put(KEY_CANDIDATES, candidateArray)
        } catch (e: Exception) {
            throw BadMessageException("TM062", e)
        }
        return o.toString()
    }

    companion object {
        private const val KEY_REMOVED = "removed"
        private const val KEY_CANDIDATES = "candidates"

        @Throws(BadMessageException::class)
        fun parse(jsonObjectString: String): VoipICECandidatesData {
            val o: JSONObject = try {
                JSONObject(jsonObjectString)
            } catch (e: JSONException) {
                throw BadMessageException("TM062: Invalid JSON", e)
            }

            val data = VoipICECandidatesData()

            try {
                val callId = getLongOrThrow(o, KEY_CALL_ID)
                if (callId != null) {
                    data.setCallId(callId)
                }
            } catch (e: Exception) {
                throw BadMessageException("TM062: Invalid Call ID", e)
            }

            try {
                data.isRemoved = o.optBoolean(KEY_REMOVED, false)
                val candidateArray = o.getJSONArray(KEY_CANDIDATES)
                if (candidateArray.length() == 0) {
                    throw BadMessageException("TM062: candidates cannot be empty")
                }
                val arr = Array(candidateArray.length()) { i ->
                    Candidate.parse(candidateArray.getJSONObject(i))
                }
                data.candidates = arr
            } catch (e: Exception) {
                if (e is BadMessageException) throw e
                throw BadMessageException("TM062: Candidates parse failed", e)
            }

            return data
        }
    }
}
