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

/**
 * Base class for JSON data in VoIP messages.
 * ZERO ANDROID IMPORTS allowed in this package.
 */
abstract class VoipCallData<T : VoipCallData<T>> {
    var callId: Long? = null
        private set

    fun getCallIdOrDefault(defaultValue: Long): Long {
        return callId ?: defaultValue
    }

    @Suppress("UNCHECKED_CAST")
    fun setCallId(id: Long): T {
        require(id >= 0) { "callId must be positive, but was $id" }
        require(id < (1L shl 32)) { "callId must fit in an unsigned 32bit integer, but was $id" }
        this.callId = id
        return this as T
    }

    protected open fun buildJsonObject(): JSONObject {
        val o = JSONObject()
        val currentCallId = callId
        if (currentCallId != null) {
            try {
                o.put(KEY_CALL_ID, currentCallId)
            } catch (e: JSONException) {
                throw RuntimeException("Call to JSONObject.put failed", e)
            }
        }
        return o
    }

    companion object {
        protected const val KEY_CALL_ID = "callId"

        fun getLongOrThrow(o: JSONObject, key: String): Long? {
            if (o.isNull(key)) return null
            val value = o.opt(key)
            if (value is Number) {
                val doubleVal = value.toDouble()
                if (doubleVal > Long.MAX_VALUE || doubleVal < Long.MIN_VALUE) {
                    throw RuntimeException("Number at key \"$key\" out of range")
                }
                return value.toLong()
            } else {
                throw RuntimeException("Invalid object type for key $key")
            }
        }

        fun getStringOrNull(o: JSONObject, key: String): String? {
            if (o.isNull(key)) return null
            val value = o.opt(key)
            return if (value is String) value else null
        }

        fun getIntegerOrNull(o: JSONObject, key: String): Int? {
            if (o.isNull(key)) return null
            val value = o.opt(key)
            return if (value is Int) value else null
        }
    }
}
