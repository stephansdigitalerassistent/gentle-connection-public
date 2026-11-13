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

interface CallFeature {
    val name: String
    val params: JSONObject?
}

class VideoFeature : CallFeature {
    override val name: String get() = "video"
    override val params: JSONObject? get() = null
}

class UnknownCallFeature(
    override val name: String,
    override val params: JSONObject?
) : CallFeature

class FeatureList(val list: MutableList<CallFeature> = mutableListOf()) {

    fun addFeature(feature: CallFeature): FeatureList {
        list.add(feature)
        return this
    }

    fun hasFeature(name: String): Boolean {
        return list.any { it.name == name }
    }

    val isEmpty: Boolean get() = list.isEmpty()
    val size: Int get() = list.size

    fun toJSON(): JSONObject {
        val featureMap = JSONObject()
        for (feature in list) {
            val params = feature.params
            try {
                if (params == null) {
                    featureMap.put(feature.name, JSONObject.NULL)
                } else {
                    featureMap.put(feature.name, params)
                }
            } catch (e: JSONException) {
                throw RuntimeException("Call to JSONObject.put failed", e)
            }
        }
        return featureMap
    }

    companion object {
        fun parse(obj: JSONObject): FeatureList {
            val features = mutableListOf<CallFeature>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (key) {
                    "video" -> features.add(VideoFeature())
                    else -> features.add(UnknownCallFeature(key, obj.optJSONObject(key)))
                }
            }
            return FeatureList(features)
        }
    }
}
