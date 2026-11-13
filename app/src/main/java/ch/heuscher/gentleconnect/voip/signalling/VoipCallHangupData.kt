/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2020-2025 Threema GmbH
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

class VoipCallHangupData : VoipCallData<VoipCallHangupData>() {

    @Throws(Exception::class)
    fun write(bos: ByteArrayOutputStream) {
        bos.write(generateString().toByteArray(StandardCharsets.UTF_8))
    }

    fun generateString(): String {
        return buildJsonObject().toString()
    }

    companion object {
        @Throws(BadMessageException::class)
        fun parse(jsonObjectString: String): VoipCallHangupData {
            val trimmed = jsonObjectString.trim()
            val o: JSONObject = if (trimmed.isEmpty()) {
                JSONObject()
            } else {
                try {
                    JSONObject(trimmed)
                } catch (e: JSONException) {
                    throw BadMessageException("TM063: Invalid JSON", e)
                }
            }

            val data = VoipCallHangupData()
            try {
                val callId = getLongOrThrow(o, KEY_CALL_ID)
                if (callId != null) {
                    data.setCallId(callId)
                }
            } catch (e: Exception) {
                throw BadMessageException("TM063: Invalid Call ID", e)
            }

            return data
        }
    }
}
