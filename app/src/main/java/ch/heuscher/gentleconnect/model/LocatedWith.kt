package ch.heuscher.gentleconnect.model

/**
 * Kind of proximity attribution configured by a caregiver for a Bluetooth device.
 */
enum class BluetoothAttributionKind {
    PLACE,
    PERSON,
    OWN_DEVICE
}

/**
 * Proximity attribution connecting a Bluetooth device key (normalised MAC address)
 * to a semantic place, person or own peripheral.
 */
data class BluetoothAttribution(
    val key: String,
    val kind: BluetoothAttributionKind,
    val label: String
)

/**
 * A single Bluetooth device seen during a heartbeat sample or currently connected.
 */
data class BluetoothSighting(
    val key: String,
    val name: String = "",
    val rssi: Int? = null,
    val bonded: Boolean = false,
    val at: Long = 0L
)

/**
 * A Bluetooth peripheral seen recently or bonded, offered on the caregiver portal
 * for proximity attribution.
 */
data class KnownBluetoothDevice(
    val key: String,
    val name: String,
    val bonded: Boolean = false,
    val sightings: Int = 1,
    val lastSeenAt: Long = 0L
)

/**
 * Summary of Bluetooth proximity attribution resolved for a heartbeat.
 */
data class LocatedWithSummary(
    val place: String? = null,
    val people: List<String> = emptyList(),
    val ownDevices: List<String> = emptyList(),
    val at: Long,
    val placeAt: Long = at
)

/**
 * Rules and resolution logic for the "Located with" ("Bei wem & wo") Bluetooth proximity feature.
 *
 * All operations here are pure and independent of Android framework classes so they can be
 * thoroughly tested on the JVM.
 */
object LocatedWith {

    /**
     * Minimum received signal strength in dBm to claim a [BluetoothAttributionKind.PLACE].
     * Sightings weaker than this are too distant (e.g. neighbours across the corridor).
     * Devices with null RSSI (bonded/connected devices without a measured RSSI) are allowed.
     */
    const val MIN_PLACE_RSSI = -85

    /**
     * Minimum sightings required for an unbonded peripheral before offering it in known devices.
     * Prevents a single passing car or commuter on the street from cluttering the portal.
     */
    const val MIN_UNBONDED_SIGHTINGS = 2

    /** Maximum devices published to the caregiver portal. */
    const val MAX_KNOWN_DEVICES = 15

    /** A device seen more than 30 days ago is forgotten unless bonded or already attributed. */
    const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /** Normalises a Bluetooth key (trimmed, lowercased MAC address). */
    fun normaliseKey(raw: String?): String = raw?.trim()?.lowercase().orEmpty()

    /**
     * Canonicalises a device name by trimming, stripping a leading "le-tr " or "le-" prefix
     * (case-insensitively), collapsing internal whitespace, and lowercasing.
     */
    fun canonicalDeviceName(raw: String?): String {
        if (raw == null) return ""
        var trimmed = raw.trim()
        val lower = trimmed.lowercase()
        if (lower.startsWith("le-tr ")) {
            trimmed = trimmed.substring(6).trim()
        } else if (lower.startsWith("le-")) {
            trimmed = trimmed.substring(3).trim()
        }
        return trimmed.replace(Regex("\\s+"), " ").lowercase()
    }

    /**
     * Returns true if two device names refer to the same physical device, accounting for
     * BLE prefixes and Android's 19-char truncation on bonded names.
     */
    fun sameDeviceName(a: String?, b: String?): Boolean {
        val ca = canonicalDeviceName(a)
        val cb = canonicalDeviceName(b)
        if (ca.isEmpty() || cb.isEmpty()) return false
        if (ca == cb) return true
        return minOf(ca.length, cb.length) >= 12 && (ca.startsWith(cb) || cb.startsWith(ca))
    }

    /** Parses attribution kind case-insensitively, returning null for blank or unknown strings. */
    fun parseKind(raw: String?): BluetoothAttributionKind? {
        val clean = raw?.trim() ?: return null
        return BluetoothAttributionKind.entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }
    }

    /**
     * Normalises keys, trims labels, drops blank entries, and deduplicates keeping the first entry per key.
     */
    fun cleanAttributions(raw: Collection<BluetoothAttribution>?): List<BluetoothAttribution> {
        if (raw == null) return emptyList()
        val seenKeys = mutableSetOf<String>()
        val result = mutableListOf<BluetoothAttribution>()
        for (attr in raw) {
            val key = normaliseKey(attr.key)
            val label = attr.label.trim()
            if (key.isBlank() || label.isBlank()) continue
            if (seenKeys.add(key)) {
                result.add(attr.copy(key = key, label = label))
            }
        }
        return result
    }

    /**
     * Resolves the current proximity attribution summary from active sightings and caregiver attributions.
     *
     * If an attribution cannot be matched directly by key (e.g. when a BLE peripheral rotates its MAC),
     * it falls back to matching by device name against [knownDevices].
     */
    fun resolve(
        sightings: List<BluetoothSighting>,
        attributions: List<BluetoothAttribution>,
        at: Long,
        knownDevices: List<KnownBluetoothDevice> = emptyList()
    ): LocatedWithSummary? {
        val cleanedAttributions = cleanAttributions(attributions)
        if (cleanedAttributions.isEmpty() || sightings.isEmpty()) return null

        val sightingsByKey = mutableMapOf<String, BluetoothSighting>()
        for (s in sightings) {
            val key = normaliseKey(s.key)
            if (key.isEmpty()) continue
            val existing = sightingsByKey[key]
            if (existing == null) {
                sightingsByKey[key] = s
            } else {
                val rExisting = existing.rssi ?: -80
                val rNew = s.rssi ?: -80
                val replace = when {
                    rNew > rExisting -> true
                    rNew < rExisting -> false
                    s.bonded && !existing.bonded -> true
                    else -> false
                }
                if (replace) {
                    sightingsByKey[key] = s.copy(bonded = existing.bonded || s.bonded)
                } else if (s.bonded && !existing.bonded) {
                    sightingsByKey[key] = existing.copy(bonded = true)
                }
            }
        }

        val knownNameByKey = knownDevices
            .associate { normaliseKey(it.key) to it.name.trim() }
            .filterValues { it.isNotBlank() }

        val matchedSightingByAttr = cleanedAttributions.associateWith { attr ->
            val direct = sightingsByKey[attr.key]
            if (direct != null) {
                direct
            } else {
                val attrDeviceName = knownNameByKey[attr.key]
                if (!attrDeviceName.isNullOrBlank()) {
                    sightingsByKey.values
                        .filter { sameDeviceName(it.name, attrDeviceName) }
                        .maxWithOrNull(
                            Comparator { s1, s2 ->
                                val r1 = s1.rssi ?: -80
                                val r2 = s2.rssi ?: -80
                                val r = r1.compareTo(r2)
                                if (r != 0) return@Comparator r
                                s1.bonded.compareTo(s2.bonded)
                            }
                        )
                } else null
            }
        }

        data class PlaceCandidate(
            val label: String,
            val effectiveRssi: Int,
            val bonded: Boolean,
            val index: Int
        )

        val placeCandidates = mutableListOf<PlaceCandidate>()
        for ((index, attr) in cleanedAttributions.withIndex()) {
            if (attr.kind != BluetoothAttributionKind.PLACE) continue
            val sighting = matchedSightingByAttr[attr] ?: continue
            if (sighting.rssi != null && sighting.rssi < MIN_PLACE_RSSI) continue
            placeCandidates.add(
                PlaceCandidate(
                    label = attr.label,
                    effectiveRssi = sighting.rssi ?: -80,
                    bonded = sighting.bonded,
                    index = index
                )
            )
        }

        val bestPlace = placeCandidates.maxWithOrNull(
            Comparator { a, b ->
                val r = a.effectiveRssi.compareTo(b.effectiveRssi)
                if (r != 0) return@Comparator r
                val bond = a.bonded.compareTo(b.bonded)
                if (bond != 0) return@Comparator bond
                b.index.compareTo(a.index)
            }
        )?.label

        val people = cleanedAttributions
            .filter { it.kind == BluetoothAttributionKind.PERSON && matchedSightingByAttr[it] != null }
            .map { it.label }
            .distinct()

        val ownDevices = cleanedAttributions
            .filter { it.kind == BluetoothAttributionKind.OWN_DEVICE && matchedSightingByAttr[it] != null }
            .map { it.label }
            .distinct()

        if (bestPlace == null && people.isEmpty() && ownDevices.isEmpty()) {
            return null
        }

        return LocatedWithSummary(
            place = bestPlace,
            people = people,
            ownDevices = ownDevices,
            at = at,
            placeAt = at
        )
    }

    /**
     * Merges a newly resolved summary with the previous summary so that a heartbeat which only
     * sees a worn peripheral ([LocatedWithSummary.ownDevices]) or a person does not erase the last
     * known [LocatedWithSummary.place], preserving when that place was last seen in [LocatedWithSummary.placeAt].
     */
    fun merge(previous: LocatedWithSummary?, current: LocatedWithSummary?): LocatedWithSummary? {
        if (current == null) return previous
        if (previous == null) return current
        if (current.place != null) {
            return current.copy(placeAt = current.at)
        }
        return if (previous.place != null) {
            current.copy(place = previous.place, placeAt = previous.placeAt)
        } else {
            current
        }
    }

    /**
     * Filters, merges and sorts known devices to publish to the caregiver portal.
     */
    fun knownDevices(
        devices: List<KnownBluetoothDevice>,
        attributions: List<BluetoothAttribution>,
        now: Long
    ): List<KnownBluetoothDevice> {
        val cleanAttributed = cleanAttributions(attributions)
        val attributedKeys = cleanAttributed.map { it.key }.toSet()

        val cleanedDevices = devices.mapNotNull { d ->
            val k = normaliseKey(d.key)
            if (k.isBlank()) null
            else d.copy(key = k, name = d.name.trim())
        }

        val mergedByKey = mutableMapOf<String, KnownBluetoothDevice>()
        for (d in cleanedDevices) {
            val existing = mergedByKey[d.key]
            if (existing == null) {
                mergedByKey[d.key] = d
            } else {
                mergedByKey[d.key] = existing.copy(
                    name = existing.name.ifBlank { d.name },
                    bonded = existing.bonded || d.bonded,
                    sightings = maxOf(existing.sightings, d.sightings),
                    lastSeenAt = maxOf(existing.lastSeenAt, d.lastSeenAt)
                )
            }
        }

        val eligible = mergedByKey.values.filter { device ->
            device.key in attributedKeys ||
                (device.bonded && device.name.isNotBlank()) ||
                (device.name.isNotBlank() &&
                    device.sightings >= MIN_UNBONDED_SIGHTINGS &&
                    (now <= 0L || now - device.lastSeenAt <= RECENT_WINDOW_MS))
        }

        val bondedNames = eligible.filter { it.bonded && it.name.isNotBlank() }.map { it.name }

        // Drop unbonded devices matching any bonded device name (e.g. rotating BLE advertisement)
        val nonBondedDuplicatesRemoved = eligible.filterNot { device ->
            !device.bonded && device.key !in attributedKeys && bondedNames.any { sameDeviceName(it, device.name) }
        }

        // Deduplicate unbonded unattributed devices sharing the same canonical name (e.g. multiple lamps)
        val retained = mutableListOf<KnownBluetoothDevice>()
        val unbondedUnattributedByCanonical = mutableMapOf<String, MutableList<KnownBluetoothDevice>>()

        for (device in nonBondedDuplicatesRemoved) {
            if (device.bonded || device.key in attributedKeys) {
                retained.add(device)
            } else {
                val cName = canonicalDeviceName(device.name)
                if (cName.isEmpty()) {
                    retained.add(device)
                } else {
                    unbondedUnattributedByCanonical.getOrPut(cName) { mutableListOf() }.add(device)
                }
            }
        }

        for (group in unbondedUnattributedByCanonical.values) {
            val best = group.maxWithOrNull(
                compareBy<KnownBluetoothDevice> { it.sightings }
                    .thenBy { it.lastSeenAt }
            )!!
            retained.add(
                best.copy(
                    sightings = group.maxOf { it.sightings },
                    lastSeenAt = group.maxOf { it.lastSeenAt }
                )
            )
        }

        val sorted = retained.sortedWith(
            compareByDescending<KnownBluetoothDevice> { it.key in attributedKeys }
                .thenByDescending { it.bonded }
                .thenByDescending { it.sightings }
                .thenByDescending { it.lastSeenAt }
                .thenBy { it.name.lowercase() }
        )

        val top = sorted.take(MAX_KNOWN_DEVICES)
        val attributedFromDevices = sorted.filter { it.key in attributedKeys }
        return (top + attributedFromDevices).distinctBy { it.key }
    }
}
