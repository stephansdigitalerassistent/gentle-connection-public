package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.RemoteSettingsMapper
import ch.heuscher.gentleconnect.model.BluetoothAttribution
import ch.heuscher.gentleconnect.model.BluetoothAttributionKind
import ch.heuscher.gentleconnect.model.BluetoothSighting
import ch.heuscher.gentleconnect.model.KnownBluetoothDevice
import ch.heuscher.gentleconnect.model.LocatedWith
import ch.heuscher.gentleconnect.model.RemoteSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Bluetooth proximity attribution ("Bei wem & wo") logic,
 * known devices filtering/sorting, and RemoteSettingsMapper integration.
 */
class LocatedWithTest {

    @Test
    fun `normaliseKey trims and lowercases MAC address`() {
        assertEquals("aa:bb:cc:11:22:33", LocatedWith.normaliseKey("  AA:BB:CC:11:22:33  "))
        assertEquals("aabbcc112233", LocatedWith.normaliseKey("AABBCC112233"))
        assertEquals("", LocatedWith.normaliseKey(null))
        assertEquals("", LocatedWith.normaliseKey("   "))
    }

    @Test
    fun `parseKind parses valid kinds case-insensitively and returns null for unknown or blank`() {
        assertEquals(BluetoothAttributionKind.PLACE, LocatedWith.parseKind("PLACE"))
        assertEquals(BluetoothAttributionKind.PLACE, LocatedWith.parseKind("place"))
        assertEquals(BluetoothAttributionKind.PLACE, LocatedWith.parseKind("  Place  "))
        assertEquals(BluetoothAttributionKind.PERSON, LocatedWith.parseKind("person"))
        assertEquals(BluetoothAttributionKind.OWN_DEVICE, LocatedWith.parseKind("OWN_DEVICE"))
        assertEquals(BluetoothAttributionKind.OWN_DEVICE, LocatedWith.parseKind("own_device"))

        assertNull(LocatedWith.parseKind(null))
        assertNull(LocatedWith.parseKind(""))
        assertNull(LocatedWith.parseKind("   "))
        assertNull(LocatedWith.parseKind("CAR"))
        assertNull(LocatedWith.parseKind("INVALID"))
    }

    @Test
    fun `cleanAttributions drops blank keys or labels and deduplicates by key`() {
        val raw = listOf(
            BluetoothAttribution("  AA:BB:CC:11:22:33  ", BluetoothAttributionKind.PLACE, "  Zuhause  "),
            BluetoothAttribution("", BluetoothAttributionKind.PERSON, "Simon"),
            BluetoothAttribution("AA:BB:CC:11:22:33", BluetoothAttributionKind.PLACE, "Duplicate"),
            BluetoothAttribution("bb:cc:dd:22:33:44", BluetoothAttributionKind.PERSON, "   "),
            BluetoothAttribution("CC:DD:EE:33:44:55", BluetoothAttributionKind.OWN_DEVICE, "Ihre Uhr")
        )

        val cleaned = LocatedWith.cleanAttributions(raw)
        assertEquals(2, cleaned.size)
        assertEquals("aa:bb:cc:11:22:33", cleaned[0].key)
        assertEquals("Zuhause", cleaned[0].label)
        assertEquals(BluetoothAttributionKind.PLACE, cleaned[0].kind)

        assertEquals("cc:dd:ee:33:44:55", cleaned[1].key)
        assertEquals("Ihre Uhr", cleaned[1].label)
        assertEquals(BluetoothAttributionKind.OWN_DEVICE, cleaned[1].kind)
    }

    @Test
    fun `resolve returns null when no sightings or attributions are empty`() {
        val now = 1000L
        assertNull(LocatedWith.resolve(emptyList(), emptyList(), now))
        assertNull(
            LocatedWith.resolve(
                sightings = listOf(BluetoothSighting("aa:bb:cc:11:22:33", rssi = -60)),
                attributions = emptyList(),
                at = now
            )
        )
        assertNull(
            LocatedWith.resolve(
                sightings = emptyList(),
                attributions = listOf(
                    BluetoothAttribution("aa:bb:cc:11:22:33", BluetoothAttributionKind.PLACE, "Zuhause")
                ),
                at = now
            )
        )
    }

    @Test
    fun `resolve returns null when no sightings match any attribution`() {
        val now = 1000L
        val sightings = listOf(BluetoothSighting("11:22:33:44:55:66", rssi = -50))
        val attributions = listOf(
            BluetoothAttribution("aa:bb:cc:11:22:33", BluetoothAttributionKind.PLACE, "Zuhause")
        )
        assertNull(LocatedWith.resolve(sightings, attributions, now))
    }

    @Test
    fun `resolve picks strongest RSSI when multiple PLACE devices are seen`() {
        val now = 1000L
        val attributions = listOf(
            BluetoothAttribution("dev:kitchen", BluetoothAttributionKind.PLACE, "Küche"),
            BluetoothAttribution("dev:livingroom", BluetoothAttributionKind.PLACE, "Wohnzimmer")
        )
        // Living room device has stronger RSSI (-55 dBm beats -75 dBm)
        val sightings = listOf(
            BluetoothSighting("dev:kitchen", rssi = -75),
            BluetoothSighting("dev:livingroom", rssi = -55)
        )

        val summary = LocatedWith.resolve(sightings, attributions, now)
        assertNotNull(summary)
        assertEquals("Wohnzimmer", summary!!.place)
        assertTrue(summary.people.isEmpty())
        assertTrue(summary.ownDevices.isEmpty())
        assertEquals(now, summary.at)
    }

    @Test
    fun `resolve ignores PLACE sighting weaker than MIN_PLACE_RSSI but allows null RSSI for bonded device`() {
        val now = 1000L
        val attributions = listOf(
            BluetoothAttribution("dev:distant", BluetoothAttributionKind.PLACE, "Nachbar"),
            BluetoothAttribution("dev:car", BluetoothAttributionKind.PLACE, "Auto")
        )

        // dev:distant has -86 dBm (< -85 threshold), so it must be ignored
        val weakSightings = listOf(BluetoothSighting("dev:distant", rssi = -86))
        assertNull(LocatedWith.resolve(weakSightings, attributions, now))

        // Boundary test: -85 dBm is kept
        val boundarySightings = listOf(BluetoothSighting("dev:distant", rssi = -85))
        assertEquals("Nachbar", LocatedWith.resolve(boundarySightings, attributions, now)?.place)

        // Bonded connected device with null RSSI is accepted as fallback
        val bondedNullRssi = listOf(BluetoothSighting("dev:car", rssi = null, bonded = true))
        val carSummary = LocatedWith.resolve(bondedNullRssi, attributions, now)
        assertNotNull(carSummary)
        assertEquals("Auto", carSummary!!.place)
    }

    @Test
    fun `resolve preserves caregiver order and deduplicates for PERSON attributions`() {
        val now = 1000L
        val attributions = listOf(
            BluetoothAttribution("phone:simon", BluetoothAttributionKind.PERSON, "Simon"),
            BluetoothAttribution("watch:simon", BluetoothAttributionKind.PERSON, "Simon"),
            BluetoothAttribution("phone:anna", BluetoothAttributionKind.PERSON, "Anna")
        )

        val sightings = listOf(
            BluetoothSighting("watch:simon", rssi = -60),
            BluetoothSighting("phone:anna", rssi = -70),
            BluetoothSighting("phone:simon", rssi = -65)
        )

        val summary = LocatedWith.resolve(sightings, attributions, now)
        assertNotNull(summary)
        assertNull(summary!!.place)
        assertEquals(listOf("Simon", "Anna"), summary.people)
        assertTrue(summary.ownDevices.isEmpty())
    }

    @Test
    fun `resolve includes OWN_DEVICE attributions`() {
        val now = 1000L
        val attributions = listOf(
            BluetoothAttribution("watch:mama", BluetoothAttributionKind.OWN_DEVICE, "Ihre Uhr"),
            BluetoothAttribution("tv:livingroom", BluetoothAttributionKind.PLACE, "Wohnzimmer"),
            BluetoothAttribution("phone:laura", BluetoothAttributionKind.PERSON, "Laura")
        )

        val sightings = listOf(
            BluetoothSighting("watch:mama", rssi = -50, bonded = true),
            BluetoothSighting("tv:livingroom", rssi = -65),
            BluetoothSighting("phone:laura", rssi = -72)
        )

        val summary = LocatedWith.resolve(sightings, attributions, now)
        assertNotNull(summary)
        assertEquals("Wohnzimmer", summary!!.place)
        assertEquals(listOf("Laura"), summary.people)
        assertEquals(listOf("Ihre Uhr"), summary.ownDevices)
        assertEquals(now, summary.at)
    }

    @Test
    fun `knownDevices drops unnamed or 1-sighting passer-by unbonded devices`() {
        val now = 10_000_000L
        val devices = listOf(
            KnownBluetoothDevice("dev:passerby", name = "BMW Audio", bonded = false, sightings = 1, lastSeenAt = now),
            KnownBluetoothDevice("dev:unnamed", name = "", bonded = false, sightings = 5, lastSeenAt = now),
            KnownBluetoothDevice("dev:regular", name = "Neighbour TV", bonded = false, sightings = 2, lastSeenAt = now),
            KnownBluetoothDevice("dev:bonded", name = "VW Bluetooth", bonded = true, sightings = 1, lastSeenAt = now)
        )

        val known = LocatedWith.knownDevices(devices, emptyList(), now)
        val keys = known.map { it.key }
        assertFalse(keys.contains("dev:passerby"))
        assertFalse(keys.contains("dev:unnamed"))
        assertTrue(keys.contains("dev:regular"))
        assertTrue(keys.contains("dev:bonded"))
    }

    @Test
    fun `knownDevices drops unbonded devices older than 30 days unless attributed`() {
        val now = LocatedWith.RECENT_WINDOW_MS * 2
        val oldTimestamp = now - LocatedWith.RECENT_WINDOW_MS - 1000L // 30 days + 1 sec ago
        val devices = listOf(
            KnownBluetoothDevice("dev:old_unbonded", name = "Old Speaker", bonded = false, sightings = 10, lastSeenAt = oldTimestamp),
            KnownBluetoothDevice("dev:old_attributed", name = "Old Base", bonded = false, sightings = 10, lastSeenAt = oldTimestamp)
        )

        val attributions = listOf(
            BluetoothAttribution("dev:old_attributed", BluetoothAttributionKind.PLACE, "Zuhause")
        )

        val known = LocatedWith.knownDevices(devices, attributions, now)
        val keys = known.map { it.key }
        assertFalse("Old unbonded un-attributed device must be dropped", keys.contains("dev:old_unbonded"))
        assertTrue("Already-attributed device must never be dropped", keys.contains("dev:old_attributed"))
    }

    @Test
    fun `knownDevices sorts attributed first then bonded then sightings then lastSeenAt`() {
        val now = 5_000_000L
        val devices = listOf(
            KnownBluetoothDevice("dev:unattributed_bonded", name = "B Headset", bonded = true, sightings = 5, lastSeenAt = now),
            KnownBluetoothDevice("dev:unbonded_frequent", name = "A Smart TV", bonded = false, sightings = 100, lastSeenAt = now),
            KnownBluetoothDevice("dev:attributed_unbonded", name = "Z Speaker", bonded = false, sightings = 2, lastSeenAt = now)
        )

        val attributions = listOf(
            BluetoothAttribution("dev:attributed_unbonded", BluetoothAttributionKind.PLACE, "Zuhause")
        )

        val known = LocatedWith.knownDevices(devices, attributions, now)
        // 1st: attributed device
        assertEquals("dev:attributed_unbonded", known[0].key)
        // 2nd: bonded device
        assertEquals("dev:unattributed_bonded", known[1].key)
        // 3rd: frequent unbonded device
        assertEquals("dev:unbonded_frequent", known[2].key)
    }

    @Test
    fun `RemoteSettingsMapper parses bluetoothAttributions and ignores malformed entries`() {
        val map = mapOf(
            "bluetoothAttributions" to listOf(
                mapOf("key" to "AA:BB:CC:11:22:33", "kind" to "PLACE", "label" to "Zuhause"),
                mapOf("key" to "BB:CC:DD:22:33:44", "kind" to "person", "label" to "Simon"),
                mapOf("key" to "CC:DD:EE:33:44:55", "kind" to "INVALID_KIND", "label" to "Something"),
                mapOf("key" to "", "kind" to "PLACE", "label" to "No Key"),
                "not a map entry"
            )
        )

        val settings = RemoteSettingsMapper.mapToRemoteSettings(map)
        assertEquals(2, settings.bluetoothAttributions.size)
        assertEquals("aa:bb:cc:11:22:33", settings.bluetoothAttributions[0].key)
        assertEquals(BluetoothAttributionKind.PLACE, settings.bluetoothAttributions[0].kind)
        assertEquals("Zuhause", settings.bluetoothAttributions[0].label)

        assertEquals("bb:cc:dd:22:33:44", settings.bluetoothAttributions[1].key)
        assertEquals(BluetoothAttributionKind.PERSON, settings.bluetoothAttributions[1].kind)
        assertEquals("Simon", settings.bluetoothAttributions[1].label)
    }

    @Test
    fun `remoteSettingsToMap never writes bluetoothAttributions back to Firestore`() {
        val settings = RemoteSettings(
            bluetoothAttributions = listOf(
                BluetoothAttribution("aa:bb:cc:11:22:33", BluetoothAttributionKind.PLACE, "Zuhause")
            )
        )

        val map = RemoteSettingsMapper.remoteSettingsToMap(settings)
        assertFalse("Firestore map must not contain bluetoothAttributions", map.containsKey("bluetoothAttributions"))
    }

    @Test
    fun `sameDeviceName matches full name, LE prefix, and 19-char truncated names`() {
        val bonded = "Bose QuietComfort 35"
        val bleAdv = "LE-Bose QuietComfort 35"
        val truncated = "LE-Bose QuietComfor"

        assertTrue(LocatedWith.sameDeviceName(bonded, bleAdv))
        assertTrue(LocatedWith.sameDeviceName(bleAdv, bonded))
        assertTrue(LocatedWith.sameDeviceName(bonded, truncated))
        assertTrue(LocatedWith.sameDeviceName(truncated, bonded))
        assertTrue(LocatedWith.sameDeviceName(bleAdv, truncated))

        // Truncated LE-TR prefix support
        assertTrue(LocatedWith.sameDeviceName("LE-TR Bose QuietComfort 35", bonded))

        // Unrelated names must not match
        assertFalse(LocatedWith.sameDeviceName(bonded, "Sony WH-1000XM4"))
        assertFalse(LocatedWith.sameDeviceName(bonded, "Bose SoundLink"))
        assertFalse(LocatedWith.sameDeviceName("Living Room", "Living Room TV"))
        assertFalse(LocatedWith.sameDeviceName(null, bonded))
        assertFalse(LocatedWith.sameDeviceName("", bleAdv))
    }

    @Test
    fun `resolve matches BLE sighting with rotating MAC to bonded attribution via knownDevices name fallback`() {
        val now = 1000L
        val bondedKey = "04:52:c7:c4:cf:fb"
        val rotatingBleKey = "59:f6:23:a4:1a:bd"

        val attributions = listOf(
            BluetoothAttribution(bondedKey, BluetoothAttributionKind.PERSON, "Simon")
        )

        val known = listOf(
            KnownBluetoothDevice(
                key = bondedKey,
                name = "Bose QuietComfort 35",
                bonded = true,
                sightings = 10,
                lastSeenAt = now
            )
        )

        // Sighting comes from BLE scan with rotating MAC and LE- prefix
        val sightings = listOf(
            BluetoothSighting(
                key = rotatingBleKey,
                name = "LE-Bose QuietComfort 35",
                rssi = -65,
                bonded = false,
                at = now
            )
        )

        // When knownDevices is passed, resolve should match by device name
        val summary = LocatedWith.resolve(sightings, attributions, now, known)
        assertNotNull(summary)
        assertEquals(listOf("Simon"), summary!!.people)
        assertNull(summary.place)
        assertTrue(summary.ownDevices.isEmpty())

        // Without knownDevices, resolve fails because keys do not match
        assertNull(LocatedWith.resolve(sightings, attributions, now))
    }

    @Test
    fun `knownDevices deduplicates multiple unbonded entries with same name and omits unbonded matching bonded name`() {
        val now = 10_000_000L
        val bondedKey = "04:52:c7:c4:cf:fb"

        val devices = listOf(
            // Bonded headphone
            KnownBluetoothDevice(bondedKey, name = "Bose QuietComfort 35", bonded = true, sightings = 1, lastSeenAt = now),
            // Unbonded BLE advertisement of the same headphone (e.g. rotating MAC)
            KnownBluetoothDevice("59:f6:23:a4:1a:bd", name = "LE-Bose QuietComfort 35", bonded = false, sightings = 5, lastSeenAt = now),
            // Two identical unbonded lamps in the same room
            KnownBluetoothDevice("aa:11:22:33:44:01", name = "Mito sospeso", bonded = false, sightings = 3, lastSeenAt = now - 100),
            KnownBluetoothDevice("aa:11:22:33:44:02", name = "Mito sospeso", bonded = false, sightings = 7, lastSeenAt = now)
        )

        val known = LocatedWith.knownDevices(devices, emptyList(), now)

        // 1. Unbonded "LE-Bose QuietComfort 35" must be omitted because "Bose QuietComfort 35" is bonded
        assertFalse(known.any { it.key == "59:f6:23:a4:1a:bd" })
        assertTrue(known.any { it.key == bondedKey })

        // 2. The two unbonded "Mito sospeso" lamps must be deduplicated to a single entry with highest sightings
        val mitoDevices = known.filter { LocatedWith.canonicalDeviceName(it.name) == "mito sospeso" }
        assertEquals(1, mitoDevices.size)
        assertEquals(7, mitoDevices[0].sightings)
    }

    @Test
    fun `merge preserves previous place and placeAt when current beat only sees ownDevices or people`() {
        val t1 = 1_000_000L
        val t2 = 1_900_000L
        val t3 = 2_800_000L

        val firstBeat = LocatedWith.resolve(
            sightings = listOf(
                BluetoothSighting("88:11:96:cc:8a:49", name = "H Home", rssi = null, bonded = true, at = t1),
                BluetoothSighting("50:04:b8:0b:e7:0f", name = "HUAWEI WATCH 2 0311", rssi = null, bonded = true, at = t1)
            ),
            attributions = listOf(
                BluetoothAttribution("88:11:96:cc:8a:49", BluetoothAttributionKind.PLACE, "Zuhause"),
                BluetoothAttribution("50:04:b8:0b:e7:0f", BluetoothAttributionKind.OWN_DEVICE, "Ihre Uhr")
            ),
            at = t1
        )
        assertNotNull(firstBeat)
        assertEquals("Zuhause", firstBeat!!.place)
        assertEquals(t1, firstBeat.placeAt)

        // Second beat: speaker went to sleep, only watch is seen
        val secondBeat = LocatedWith.resolve(
            sightings = listOf(
                BluetoothSighting("50:04:b8:0b:e7:0f", name = "HUAWEI WATCH 2 0311", rssi = null, bonded = true, at = t2)
            ),
            attributions = listOf(
                BluetoothAttribution("88:11:96:cc:8a:49", BluetoothAttributionKind.PLACE, "Zuhause"),
                BluetoothAttribution("50:04:b8:0b:e7:0f", BluetoothAttributionKind.OWN_DEVICE, "Ihre Uhr")
            ),
            at = t2
        )
        val merged = LocatedWith.merge(firstBeat, secondBeat)
        assertNotNull(merged)
        assertEquals("Zuhause", merged!!.place)
        assertEquals(t1, merged.placeAt)
        assertEquals(listOf("Ihre Uhr"), merged.ownDevices)
        assertEquals(t2, merged.at)

        // Third beat: a new place is seen -> place and placeAt update to the new beat
        val thirdBeat = LocatedWith.resolve(
            sightings = listOf(
                BluetoothSighting("aa:bb:cc:dd:ee:ff", name = "Praxis", rssi = -60, bonded = false, at = t3)
            ),
            attributions = listOf(
                BluetoothAttribution("aa:bb:cc:dd:ee:ff", BluetoothAttributionKind.PLACE, "Arztpraxis")
            ),
            at = t3
        )
        val mergedThird = LocatedWith.merge(merged, thirdBeat)
        assertNotNull(mergedThird)
        assertEquals("Arztpraxis", mergedThird!!.place)
        assertEquals(t3, mergedThird.placeAt)
        assertEquals(t3, mergedThird.at)
    }
}
