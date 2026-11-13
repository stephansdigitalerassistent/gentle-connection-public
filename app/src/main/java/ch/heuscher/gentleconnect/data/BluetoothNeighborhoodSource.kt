package ch.heuscher.gentleconnect.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ch.heuscher.gentleconnect.model.BluetoothSighting
import ch.heuscher.gentleconnect.model.KnownBluetoothDevice
import ch.heuscher.gentleconnect.model.LocatedWith
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Samples nearby Bluetooth devices and bonded peripherals on each heartbeat
 * for "Located with" ("Bei wem & wo") proximity attribution.
 *
 * Runs a brief, low-power 4-second BLE scan and checks currently connected profiles
 * and bonded devices. Sightings are recorded in private preferences so named peripherals
 * seen across heartbeats can be offered to the caregiver on the portal.
 */
class BluetoothNeighborhoodSource(private val context: Context) {

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Whether the app has permission to query bonded devices and names. */
    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Whether the app has permission to run a BLE scan. */
    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            val hasBt = context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val hasFine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            hasBt && (hasFine || hasCoarse)
        }
    }

    /**
     * Samples nearby and connected Bluetooth peripherals during a 4-second window.
     * Updates persistent sighting history and returns all sightings detected.
     */
    @SuppressLint("MissingPermission")
    suspend fun sampleNow(): List<BluetoothSighting> {
        val currentAdapter = adapter ?: return emptyList()
        val isEnabled = if (hasConnectPermission()) {
            try {
                currentAdapter.isEnabled
            } catch (e: SecurityException) {
                false
            } catch (e: Exception) {
                false
            }
        } else false

        if (!isEnabled) return emptyList()

        val sightings = ConcurrentHashMap<String, BluetoothSighting>()
        val now = System.currentTimeMillis()

        // 1. Bonded devices & active profile connections
        val bondedDevices: Set<BluetoothDevice> = if (hasConnectPermission()) {
            try {
                currentAdapter.bondedDevices.orEmpty()
            } catch (e: SecurityException) {
                emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        } else emptySet()

        val bondedKeys = bondedDevices.mapNotNull {
            val addr = runCatching { it.address }.getOrNull()
            LocatedWith.normaliseKey(addr).ifEmpty { null }
        }.toSet()

        val connectedDevices = mutableListOf<BluetoothDevice>()
        if (hasConnectPermission()) {
            try {
                bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)?.let { connectedDevices.addAll(it) }
            } catch (e: SecurityException) {
            } catch (e: Exception) {
            }
            try {
                bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT_SERVER)?.let { connectedDevices.addAll(it) }
            } catch (e: SecurityException) {
            } catch (e: Exception) {
            }
            for (dev in bondedDevices) {
                if (isDeviceConnected(dev)) {
                    connectedDevices.add(dev)
                }
            }
        }

        for (dev in connectedDevices) {
            val rawAddr = runCatching { dev.address }.getOrNull() ?: continue
            val key = LocatedWith.normaliseKey(rawAddr)
            if (key.isEmpty()) continue
            val name = if (hasConnectPermission()) {
                try {
                    dev.name?.trim().orEmpty()
                } catch (e: SecurityException) {
                    ""
                } catch (e: Exception) {
                    ""
                }
            } else ""
            val isBonded = key in bondedKeys || (try { dev.bondState == BluetoothDevice.BOND_BONDED } catch (e: Exception) { false })
            sightings[key] = BluetoothSighting(
                key = key,
                name = name,
                rssi = null,
                bonded = isBonded,
                at = now
            )
        }

        // 2. Bounded 4-second BLE scan
        if (hasScanPermission()) {
            val scanner = currentAdapter.bluetoothLeScanner
            if (scanner != null) {
                val scanSettings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build()

                fun recordResult(result: ScanResult) {
                    val rawAddress = runCatching { result.device?.address }.getOrNull() ?: return
                    val key = LocatedWith.normaliseKey(rawAddress)
                    if (key.isEmpty()) return
                    val name = extractDeviceName(result, hasConnectPermission())
                    val isBonded = key in bondedKeys || (try { result.device?.bondState == BluetoothDevice.BOND_BONDED } catch (e: Exception) { false })
                    val rssi = result.rssi
                    val scanAt = System.currentTimeMillis()

                    sightings.compute(key) { _, existing ->
                        if (existing == null) {
                            BluetoothSighting(
                                key = key,
                                name = name,
                                rssi = rssi,
                                bonded = isBonded,
                                at = scanAt
                            )
                        } else {
                            val bestName = existing.name.ifBlank { name }
                            val bestRssi = if (existing.rssi == null) rssi else maxOf(existing.rssi, rssi)
                            existing.copy(
                                name = bestName,
                                rssi = bestRssi,
                                bonded = existing.bonded || isBonded,
                                at = maxOf(existing.at, scanAt)
                            )
                        }
                    }
                }

                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult?) {
                        result?.let { recordResult(it) }
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                        results?.forEach { recordResult(it) }
                    }

                    override fun onScanFailed(errorCode: Int) {
                    }
                }

                val scanStarted = try {
                    scanner.startScan(null, scanSettings, callback)
                    true
                } catch (e: SecurityException) {
                    false
                } catch (e: Exception) {
                    false
                }

                if (scanStarted) {
                    try {
                        delay(SCAN_DURATION_MS)
                    } finally {
                        try {
                            scanner.stopScan(callback)
                        } catch (e: SecurityException) {
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        }

        val currentSightings = sightings.values.toList()
        updateHistory(currentSightings)
        return currentSightings
    }

    /**
     * Returns the persisted device sighting history merged with currently bonded peripherals.
     * Guarantees all paired devices with a name are available for attribution even before a BLE scan.
     */
    @SuppressLint("MissingPermission")
    fun knownHistory(): List<KnownBluetoothDevice> {
        val history = loadHistory().toMutableMap()
        if (hasConnectPermission()) {
            val bonded = try {
                adapter?.bondedDevices.orEmpty()
            } catch (e: SecurityException) {
                emptySet()
            } catch (e: Exception) {
                emptySet()
            }

            for (dev in bonded) {
                val key = LocatedWith.normaliseKey(runCatching { dev.address }.getOrNull())
                val name = try {
                    dev.name?.trim().orEmpty()
                } catch (e: SecurityException) {
                    ""
                } catch (e: Exception) {
                    ""
                }
                if (key.isNotEmpty() && name.isNotBlank()) {
                    val existing = history[key]
                    if (existing != null) {
                        history[key] = existing.copy(
                            name = existing.name.ifBlank { name },
                            bonded = true
                        )
                    } else {
                        history[key] = KnownBluetoothDevice(
                            key = key,
                            name = name,
                            bonded = true,
                            sightings = 1,
                            lastSeenAt = System.currentTimeMillis()
                        )
                    }
                }
            }
        }
        return history.values.toList()
    }

    @SuppressLint("MissingPermission")
    private fun extractDeviceName(result: ScanResult, hasConnect: Boolean): String {
        val recordName = result.scanRecord?.deviceName?.trim()?.ifEmpty { null }
        if (recordName != null) return recordName
        if (hasConnect) {
            try {
                return result.device?.name?.trim().orEmpty()
            } catch (e: SecurityException) {
            } catch (e: Exception) {
            }
        }
        return ""
    }

    private fun loadHistory(): Map<String, KnownBluetoothDevice> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyMap()
        val map = mutableMapOf<String, KnownBluetoothDevice>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val key = LocatedWith.normaliseKey(obj.optString("key"))
                if (key.isNotEmpty()) {
                    map[key] = KnownBluetoothDevice(
                        key = key,
                        name = obj.optString("name", ""),
                        bonded = obj.optBoolean("bonded", false),
                        sightings = obj.optInt("sightings", 1),
                        lastSeenAt = obj.optLong("lastSeenAt", 0L)
                    )
                }
            }
        } catch (e: Exception) {
            // Unparseable JSON, return empty
        }
        return map
    }

    private fun saveHistory(history: Collection<KnownBluetoothDevice>) {
        val array = JSONArray()
        for (d in history.sortedByDescending { it.lastSeenAt }.take(MAX_HISTORY_ENTRIES)) {
            val obj = JSONObject().apply {
                put("key", d.key)
                put("name", d.name)
                put("bonded", d.bonded)
                put("sightings", d.sightings)
                put("lastSeenAt", d.lastSeenAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun updateHistory(sightings: Collection<BluetoothSighting>) {
        val history = loadHistory().toMutableMap()
        val incrementedKeys = mutableSetOf<String>()
        for (s in sightings) {
            if (s.key.isEmpty()) continue
            val existing = history[s.key]
            if (existing != null) {
                val add = if (incrementedKeys.add(existing.key)) 1 else 0
                history[s.key] = existing.copy(
                    name = s.name.ifBlank { existing.name },
                    bonded = existing.bonded || s.bonded,
                    sightings = existing.sightings + add,
                    lastSeenAt = maxOf(existing.lastSeenAt, s.at)
                )
            } else {
                val matchedByName = if (s.name.isNotBlank()) {
                    history.values.firstOrNull { LocatedWith.sameDeviceName(it.name, s.name) }
                } else null

                if (matchedByName != null) {
                    val add = if (incrementedKeys.add(matchedByName.key)) 1 else 0
                    history[matchedByName.key] = matchedByName.copy(
                        name = matchedByName.name.ifBlank { s.name },
                        bonded = matchedByName.bonded || s.bonded,
                        sightings = matchedByName.sightings + add,
                        lastSeenAt = maxOf(matchedByName.lastSeenAt, s.at)
                    )
                } else {
                    incrementedKeys.add(s.key)
                    history[s.key] = KnownBluetoothDevice(
                        key = s.key,
                        name = s.name,
                        bonded = s.bonded,
                        sightings = 1,
                        lastSeenAt = s.at
                    )
                }
            }
        }
        saveHistory(history.values)
    }

    companion object {
        const val SCAN_DURATION_MS = 4_000L
        private const val PREFS_NAME = "gentle_bluetooth_sightings"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY_ENTRIES = 60
    }
}
