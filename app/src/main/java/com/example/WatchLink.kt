package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the UI shows. */
object WatchLinkState {
    val status = MutableStateFlow("Not connected")
    val connected = MutableStateFlow(false)
    val scanning = MutableStateFlow(false)
    /** name to address */
    val devices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** Android bonded devices; shown as an optional BLE-address fallback, not proof of a BLE endpoint. */
    val pairedDevices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val deviceRssi = MutableStateFlow<Map<String, Int>>(emptyMap())
    val packets = MutableStateFlow<List<String>>(emptyList())
    val buttonEvents = MutableStateFlow<List<String>>(emptyList())
    val buttonEventCount = MutableStateFlow(0)
    /** "PREV", "NEXT" or "TOGGLE" while waiting for the user to press that button on the watch. */
    val teach = MutableStateFlow<String?>(null)
    val savedName = MutableStateFlow("")
}

/**
 * Direct Bluetooth LE link to the CMF Watch Pro 2 using the community protocol
 * (github.com/joshuapassos/CMF-Watch-Pro-2-BLE-Protocol). Written from that description, not tested on a watch.
 */
@SuppressLint("MissingPermission")
object WatchLink {
    private val FFF0 = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val FFF1 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val FFF2 = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    private val DATA_SERVICE = UUID.fromString("02f00000-0000-0000-0000-00000000ffe0")
    private val DATA_W = UUID.fromString("02f00000-0000-0000-0000-00000000ffe1")
    private val DATA_N = UUID.fromString("02f00000-0000-0000-0000-00000000ffe2")
    // The service UUID was corrected in the protocol repository. The characteristic UUIDs
    // remain stable; we still resolve them by UUID because the service UUID may vary by unit.
    private val SH_SERVICE = UUID.fromString("77d4e67c-2fe2-2334-0d35-9ccd078f529c")
    private val SH_W = UUID.fromString("77d4ff01-2fe2-2334-0d35-9ccd078f529c")
    private val SH_N = UUID.fromString("77d4ff02-2fe2-2334-0d35-9ccd078f529c")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val IV = byteArrayOf(
        0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57,
        0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x5A
    )
    // Exact plaintext command set from the reverse-engineered protocol:
    // pairing request/reply and watchface/firmware/AGPS bulk data-chunk writes.
    // AUTH_FAILED is encrypted.
    private val PLAIN = setOf(
        0xFFFF8047.toInt(), // AUTH_PAIR_REQUEST
        0xFFFF0048.toInt(), // AUTH_PAIR_REPLY
        0xFFFF9064.toInt(), // DATA_CHUNK_WRITE_WATCHFACE
        0xFFFF9042.toInt(), // DATA_CHUNK_WRITE_FIRMWARE
        0xFFFF905F.toInt()  // DATA_CHUNK_WRITE_AGPS
    )

    private fun op(c1: Int, c2: Int) = (c1 shl 16) or c2

    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private var onToken: ((String) -> Unit)? = null
    private var onConnectionChanged: ((Boolean) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var cmdW: BluetoothGattCharacteristic? = null
    private var dataW: BluetoothGattCharacteristic? = null
    private var dataN: BluetoothGattCharacteristic? = null
    private var shellW: BluetoothGattCharacteristic? = null
    private var shellN: BluetoothGattCharacteristic? = null
    // Android's default ATT MTU is 23. Do not send encrypted CMF frames until a larger MTU
    // has actually been negotiated and reported by onMtuChanged().
    private var mtu = 23
    private var scanCb: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var connectionTimeout: Runnable? = null
    private var discoveryTimeout: Runnable? = null
    private var discoveryStarted = false
    private var pendingAddress = ""
    private var pendingName = ""

    // crypto state
    private var key: ByteArray? = null
    private var secret: ByteArray? = null
    private var rnd1: ByteArray = ByteArray(16)
    private var authKey: ByteArray? = null
    private var usedStoredKey = false
    private var ready = false
    private var shellText = StringBuilder()
    private var triedCrlf = false

    private val rx = ArrayList<Byte>()
    private val parts = HashMap<Int, ArrayList<ByteArray>>()

    // write queue
    private val queue = ArrayDeque<() -> Unit>()
    private var busy = false
    private var contactsAckTimeout: Runnable? = null
    private var contactsAckCallback: ((Boolean, String) -> Unit)? = null
    private var alarmsGetTimeout: Runnable? = null
    private var alarmsGetCallback: ((List<WatchAlarm>?, String) -> Unit)? = null
    private var alarmsSetTimeout: Runnable? = null
    private var alarmsSetCallback: ((Boolean, String) -> Unit)? = null

    private var musicState = 1
    private var musicTitle = ""
    private var musicArtist = ""
    private var musicRunnable: Runnable? = null
    private var stepTimeout: Runnable? = null


    // ---- keeping the link alive: reconnect by itself after a drop, and notice a link that looks connected but is dead ----
    private var wantLink = false
    private var retryDelay = 2000L
    private var retryTask: Runnable? = null
    private var probeTask: Runnable? = null
    private var probeCheck: Runnable? = null
    private var probeMissed = 0

    private fun scheduleReconnect() {
        if (!wantLink || !::app.isInitialized || !prefs().getBoolean("ble_address_verified", false)) return
        retryTask?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            retryTask = null
            if (!wantLink || gatt != null || WatchLinkState.connected.value) return@Runnable
            val addr = prefs().getString("addr", "") ?: ""
            if (addr.isBlank()) return@Runnable
            log("AUTO_RECONNECT address=$addr")
            connectInternal(addr, prefs().getString("name", "") ?: "CMF Watch Pro 2", true)
            if (gatt == null) scheduleReconnect()
        }
        retryTask = r
        log("AUTO_RECONNECT in ${retryDelay}ms")
        handler.postDelayed(r, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(20000L)
    }

    private fun stopKeepAlive() {
        retryTask?.let { handler.removeCallbacks(it) }; retryTask = null
        probeTask?.let { handler.removeCallbacks(it) }; probeTask = null
        probeCheck?.let { handler.removeCallbacks(it) }; probeCheck = null
        probeMissed = 0
    }

    /** Every 12 s ask the radio for the signal strength; a dead link never answers, and then the link is rebuilt. */
    private fun startProbe() {
        probeTask?.let { handler.removeCallbacks(it) }
        probeMissed = 0
        val t = object : Runnable {
            override fun run() {
                val g = gatt
                if (g == null || !WatchLinkState.connected.value) { probeTask = null; return }
                val check = Runnable {
                    probeMissed++
                    log("LINK_PROBE no answer ($probeMissed)")
                    if (probeMissed >= 2 && gatt === g) {
                        log("LINK_DEAD: rebuilding the link")
                        WatchLinkState.connected.value = false
                        onConnectionChanged?.invoke(false)
                        epoch++
                        closeGatt()
                        retryDelay = 1000L
                        scheduleReconnect()
                    }
                }
                probeCheck = check
                handler.postDelayed(check, 6000)
                val ok = try { g.readRemoteRssi() } catch (_: Exception) { false }
                if (!ok) { /* no probe started: the check above counts it as a miss */ }
                handler.postDelayed(this, 12000)
            }
        }
        probeTask = t
        handler.postDelayed(t, 12000)
    }

    private fun prefs() = app.getSharedPreferences("watch_link", Context.MODE_PRIVATE)

    /** Safe to call from the Activity before the foreground service has started. */
    fun initialize(ctx: Context) {
        app = ctx.applicationContext
        WatchLinkState.savedName.value = prefs().getString("name", "") ?: ""
    }

    fun attach(
        ctx: Context,
        tokenSink: (String) -> Unit,
        connectionSink: (Boolean) -> Unit = {}
    ) {
        initialize(ctx)
        onToken = tokenSink
        onConnectionChanged = connectionSink
    }

    fun connectedNow() = WatchLinkState.connected.value

    /** Counts every new authenticated link (and every loss of one), so the Watch Music bridge notices a reconnect between two polls. */
    @Volatile var epoch = 0
        private set

    fun hasPermission(): Boolean {
        val need = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return need.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun adapter(): BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun log(s: String) {
        AppLogger.log("WatchLink", s)
        WatchLinkState.packets.value = (listOf(s) + WatchLinkState.packets.value).take(14)
    }

    private fun status(s: String) {
        WatchLinkState.status.value = s
        AppLogger.log("WatchLink", "STATUS: $s")
    }

    // ------------------------------------------------------------------------------------------
    // Scan
    // ------------------------------------------------------------------------------------------

    fun startScan() {
        // FMC_SHARED_SCAN_GUARD
        if (FmcSharedConnection.isConnected() || FmcSharedConnection.isConnecting()) { status("The shared Nothing X link is already connected or connecting."); return }
        if (!::app.isInitialized) { status("Bluetooth is not initialized. Reopen the Watch screen and retry."); return }
        if (!hasPermission()) { status("Bluetooth permission missing"); return }
        val ad = adapter() ?: run { status("Bluetooth adapter is unavailable"); return }
        if (!ad.isEnabled) { status("Bluetooth is off. Turn it on, then scan again."); return }
        val scanner = ad.bluetoothLeScanner ?: run {
            status("Android did not provide a BLE scanner. Toggle Bluetooth off and on, then retry.")
            return
        }
        if (gatt != null && WatchLinkState.connected.value) {
            status("Already connected to ${WatchLinkState.savedName.value}. Disconnect before scanning again.")
            return
        }

        // Bonded devices are shown separately for diagnosis only. Android's "Connected for calls"
        // entry is normally a Classic connection and its address is not a safe GATT target.
        closeGatt()
        WatchLinkState.connected.value = false
        stopScan()
        WatchLinkState.devices.value = emptyList()
        WatchLinkState.deviceRssi.value = emptyMap()
        WatchLinkState.pairedDevices.value = emptyList()
        WatchLinkState.packets.value = emptyList()
        try {
            WatchLinkState.pairedDevices.value = ad.bondedDevices.orEmpty().mapNotNull { device ->
                val name = try { device.name } catch (_: SecurityException) { null }
                (name?.takeIf { it.isNotBlank() } ?: "Paired Bluetooth device") to device.address
            }.sortedBy { if (it.first.contains("CMF Watch Pro 2", true)) 0 else 1 }
            log("Android bonded list: ${WatchLinkState.pairedDevices.value.size} device(s); these are not BLE scan results")
        } catch (e: SecurityException) {
            log("Cannot read bonded devices: ${e.message}")
        }

        status("Scanning for nearby BLE advertisements… Keep the watch awake and open its Music remote.")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = addScanResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::addScanResult)
            }
            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    WatchLinkState.scanning.value = false
                    val detail = when (errorCode) {
                        SCAN_FAILED_ALREADY_STARTED -> "Android says a scan is already running"
                        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android could not register this app for BLE scanning"
                        SCAN_FAILED_INTERNAL_ERROR -> "Android Bluetooth stack internal error"
                        SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning is unsupported"
                        SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "BLE scan resources are exhausted"
                        else -> "Unknown Android BLE scanner error"
                    }
                    status("BLE scan failed ($errorCode): $detail")
                    log("SCAN_FAILED=$errorCode ($detail)")
                }
            }
        }
        scanCb = callback
        try {
            // No name-only filter: the Pro 2 may omit its local name in some advertising packets.
            // We collect every advertisement and rank CMF / FFF0 candidates at the top.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build()
            scanner.startScan(null, settings, callback)
            WatchLinkState.scanning.value = true
            log("Unfiltered BLE scan started")
            val timeout = Runnable {
                if (scanCb === callback) {
                    stopScan()
                    val candidates = WatchLinkState.devices.value
                    val cmf = candidates.firstOrNull { it.first.contains("CMF Watch Pro 2", true) }
                    val paired = WatchLinkState.pairedDevices.value.firstOrNull { it.first.contains("CMF Watch Pro 2", true) }
                    status(when {
                        cmf != null -> "Found ${cmf.first}. Select this BLE result and connect."
                        candidates.isNotEmpty() -> "Found ${candidates.size} BLE device(s), but no named CMF Watch Pro 2. Keep the watch awake and retry."
                        paired != null -> "Android sees ${paired.first} as bonded, but it did not advertise over BLE during this scan. The calls pairing is not the BLE endpoint."
                        else -> "No BLE advertisements received. Keep the watch awake, open the Music remote, bring it close, and scan again."
                    })
                    log("Scan complete: ${candidates.size} BLE device(s)")
                }
            }
            scanTimeout = timeout
            handler.postDelayed(timeout, 30000)
        } catch (e: Exception) {
            WatchLinkState.scanning.value = false
            status("BLE scan could not start: ${e.message ?: e.javaClass.simpleName}")
            log("SCAN_START_EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun addScanResult(result: ScanResult) {
        try {
            val record = result.scanRecord
            val deviceName = try { result.device.name } catch (_: SecurityException) { null }
            val advertisedName = record?.deviceName
            val address = result.device.address
            val bondedAlias = WatchLinkState.pairedDevices.value
                .firstOrNull { it.second.equals(address, ignoreCase = true) }?.first
            val name = deviceName?.takeIf { it.isNotBlank() }
                ?: advertisedName?.takeIf { it.isNotBlank() }
                ?: bondedAlias?.takeIf { it.isNotBlank() }
                ?: "(unnamed BLE device)"
            val services = record?.serviceUuids?.joinToString { it.uuid.toString() }.orEmpty()
            handler.post {
                val current = WatchLinkState.devices.value
                val old = current.firstOrNull { it.second == address }
                val betterName = if (name == "(unnamed BLE device)") old?.first ?: name else name
                val next = if (old == null) current + (betterName to address)
                    else current.map { if (it.second == address) betterName to address else it }
                WatchLinkState.devices.value = next.sortedWith(
                    compareBy<Pair<String, String>> {
                        when {
                            it.first.contains("CMF Watch Pro 2", true) -> 0
                            it.first.contains("CMF", true) || it.first.contains("Watch", true) -> 1
                            else -> 2
                        }
                    }.thenBy { it.first }
                )
                WatchLinkState.deviceRssi.value = WatchLinkState.deviceRssi.value + (address to result.rssi)
                if (name.contains("CMF", true) || services.contains("fff0", true)) {
                    log("CMF BLE candidate: name=$name address=$address rssi=${result.rssi} services=${services.ifBlank { "not advertised" }}")
                }
            }
        } catch (e: SecurityException) {
            handler.post { status("Bluetooth permission was revoked during scanning") }
        }
    }

    fun stopScan() {
        scanTimeout?.let { handler.removeCallbacks(it) }
        scanTimeout = null
        try { scanCb?.let { adapter()?.bluetoothLeScanner?.stopScan(it) } }
        catch (e: Exception) { log("stopScan: ${e.message ?: e.javaClass.simpleName}") }
        scanCb = null
        WatchLinkState.scanning.value = false
    }

    // ------------------------------------------------------------------------------------------
    // Connect
    // ------------------------------------------------------------------------------------------

    fun connectSaved() {
        // FMC_SHARED_CONNECT_SAVED
        if (!::app.isInitialized || !hasPermission() || WatchLinkState.scanning.value) return
        if (FmcSharedConnection.isSelected(app)) { FmcSharedConnection.connectSaved(app); return }
        if (gatt != null) return
        // Do not retry addresses saved by earlier builds unless their live GATT services were
        // positively validated. This prevents a Classic calls-pairing MAC being used as BLE.
        if (!prefs().getBoolean("ble_address_verified", false)) return
        val address = prefs().getString("addr", "") ?: return
        if (address.isNotBlank()) connect(address, prefs().getString("name", "") ?: "CMF Watch Pro 2", autoReconnect = true)
    }

    fun connect(addr: String, name: String) = connectInternal(addr, name, false)

    private fun connect(addr: String, name: String, autoReconnect: Boolean) = connectInternal(addr, name, autoReconnect)

    private fun connectInternal(addr: String, name: String, autoReconnect: Boolean) {
        if (!::app.isInitialized) { status("Bluetooth is not initialized. Reopen the Watch screen."); return }
        if (!hasPermission()) { status("Bluetooth permission missing"); return }
        // FMC_SHARED_CONNECT_ROUTE
        if (FmcSharedConnection.isSelected(app)) { stopScan(); FmcSharedConnection.connect(app, addr, name); return }
        stopScan()
        wantLink = true
        val ad = adapter() ?: run { status("Bluetooth adapter is unavailable"); return }
        if (!ad.isEnabled) { status("Bluetooth is off"); return }
        if (gatt != null) closeGatt()
        if (WatchLinkState.connected.value) onConnectionChanged?.invoke(false)
        WatchLinkState.connected.value = false

        pendingAddress = addr
        pendingName = name.ifBlank { addr }
        WatchLinkState.savedName.value = pendingName
        WatchLinkState.connected.value = false
        WatchLinkState.buttonEvents.value = emptyList()
        WatchLinkState.buttonEventCount.value = 0
        WatchLinkState.packets.value = emptyList()
        if (!autoReconnect) prefs().edit().putBoolean("ble_address_verified", false).apply()
        resetSession()
        discoveryStarted = false
        status("Connecting to ${pendingName} over Bluetooth LE…")
        log("CONNECT attempt address=$addr name=$pendingName")
        try {
            val device = ad.getRemoteDevice(addr)
            val opened = if (Build.VERSION.SDK_INT >= 23)
                device.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(app, false, gattCb)
            if (opened == null) {
                status("Android did not create a GATT connection. Rescan and select the live BLE result.")
                return
            }
            gatt = opened
            val expected = opened
            val timeout = Runnable {
                if (gatt === expected && !WatchLinkState.connected.value) {
                    status("BLE connection/service discovery timed out. Check the watch is awake and not busy with another BLE app, then retry.")
                    log("GATT_CONNECT_TIMEOUT address=$addr")
                    closeGatt()
                    scheduleReconnect()
                }
            }
            connectionTimeout = timeout
            handler.postDelayed(timeout, 30000)
        } catch (e: Exception) {
            status("BLE connection failed: ${e.message ?: e.javaClass.simpleName}")
            log("GATT_CONNECT_EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            closeGatt()
        }
    }

    fun disconnect(forget: Boolean) {
        // FMC_SHARED_DISCONNECT_ROUTE
        if (::app.isInitialized && FmcSharedConnection.isSelected(app)) {
            wantLink = false
            FmcSharedConnection.disconnect()
            if (forget) {
                prefs().edit().remove("addr").remove("name").remove("authkey").remove("ble_address_verified").apply()
                WatchLinkState.savedName.value = ""
            }
            return
        }
        wantLink = false
        stopKeepAlive()
        closeGatt()
        WatchLinkState.connected.value = false
        onConnectionChanged?.invoke(false)
        if (forget) {
            prefs().edit().remove("addr").remove("name").remove("authkey").remove("ble_address_verified").apply()
            WatchLinkState.savedName.value = ""
        }
        status("Not connected")
    }

    fun shutdown() {
        // FMC_SHARED_SHUTDOWN_ROUTE
        if (::app.isInitialized && FmcSharedConnection.isSelected(app)) { FmcSharedConnection.disconnect(); return }
        wantLink = false
        stopKeepAlive()
        stopScan()
        closeGatt()
        WatchLinkState.connected.value = false
        onConnectionChanged?.invoke(false)
    }

    private fun closeGatt() {
        stepTimeout?.let { handler.removeCallbacks(it) }
        stepTimeout = null
        connectionTimeout?.let { handler.removeCallbacks(it) }
        connectionTimeout = null
        discoveryTimeout?.let { handler.removeCallbacks(it) }
        discoveryTimeout = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        cmdW = null
        dataW = null
        dataN = null
        shellW = null
        shellN = null
        ready = false
        discoveryStarted = false
        queue.clear()
        busy = false
    }

    private fun resetSession() {
        key = null; secret = null; authKey = null; ready = false; usedStoredKey = false
        rx.clear(); parts.clear(); shellText = StringBuilder(); triedCrlf = false; mtu = 23
    }

    private fun beginServiceDiscovery(g: BluetoothGatt) {
        if (g !== gatt || discoveryStarted) return
        discoveryStarted = true
        discoveryTimeout?.let { handler.removeCallbacks(it) }
        val ok = try { g.discoverServices() } catch (_: Exception) { false }
        log("Service discovery started=$ok")
        if (!ok) {
            discoveryStarted = false
            status("Could not start GATT service discovery")
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            handler.post {
                if (g !== gatt) { try { g.close() } catch (_: Exception) {}; return@post }
                if (st != BluetoothGatt.GATT_SUCCESS) log("GATT connection status=$st state=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                    status("BLE link established. Negotiating MTU and discovering CMF services…")
                    val accepted = try { g.requestMtu(247) } catch (_: Exception) { false }
                    log("MTU request 247 accepted=$accepted")
                    if (!accepted) {
                        mtu = 23
                        beginServiceDiscovery(g)
                    } else {
                        // Android should call onMtuChanged. We still let discovery complete if
                        // it does not, but authentication will refuse the unnegotiated MTU 23.
                        val timeout = Runnable { beginServiceDiscovery(g) }
                        discoveryTimeout = timeout
                        handler.postDelayed(timeout, 5000)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    WatchLinkState.connected.value = false
                    onConnectionChanged?.invoke(false)
                    epoch++
                    ready = false
                    val detail = when (st) {
                        0 -> "peer disconnected"
                        8 -> "connection timeout"
                        19 -> "peer terminated connection"
                        22 -> "local host terminated connection"
                        133 -> "Android GATT error 133 (often a stale/busy BLE link)"
                        else -> "GATT status $st"
                    }
                    status("BLE disconnected: $detail. Reconnecting…")
                    log("GATT_DISCONNECTED=$st ($detail)")
                    probeTask?.let { handler.removeCallbacks(it) }; probeTask = null
                    probeCheck?.let { handler.removeCallbacks(it) }; probeCheck = null
                    closeGatt()
                    scheduleReconnect()
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, st: Int) {
            handler.post {
                if (g !== gatt) return@post
                probeMissed = 0
                probeCheck?.let { handler.removeCallbacks(it) }; probeCheck = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) {
            handler.post {
                if (g !== gatt) return@post
                mtu = if (st == BluetoothGatt.GATT_SUCCESS) m else 23
                log("MTU negotiated=$mtu status=$st")
                beginServiceDiscovery(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            handler.post {
                if (g !== gatt) return@post
                connectionTimeout?.let { handler.removeCallbacks(it) }
                connectionTimeout = null
                discoveryTimeout?.let { handler.removeCallbacks(it) }
                discoveryTimeout = null
                if (st != BluetoothGatt.GATT_SUCCESS) {
                    status("GATT service discovery failed (status $st)")
                    log("SERVICE_DISCOVERY_FAILED=$st")
                    closeGatt()
                    return@post
                }

                val all = g.services.flatMap { service -> service.characteristics.map { service to it } }
                log("GATT services: " + g.services.joinToString { it.uuid.toString() })
                val commandService = g.getService(FFF0)
                val dataService = g.getService(DATA_SERVICE)
                val shellService = g.getService(SH_SERVICE)

                cmdW = commandService?.getCharacteristic(FFF2) ?: all.firstOrNull { it.second.uuid == FFF2 }?.second
                val cmdNotify = commandService?.getCharacteristic(FFF1) ?: all.firstOrNull { it.second.uuid == FFF1 }?.second
                dataW = dataService?.getCharacteristic(DATA_W) ?: all.firstOrNull { it.second.uuid == DATA_W }?.second
                dataN = dataService?.getCharacteristic(DATA_N) ?: all.firstOrNull { it.second.uuid == DATA_N }?.second
                shellW = shellService?.getCharacteristic(SH_W) ?: all.firstOrNull { it.second.uuid == SH_W }?.second
                shellN = shellService?.getCharacteristic(SH_N) ?: all.firstOrNull { it.second.uuid == SH_N }?.second

                log("CMF GATT: cmdWrite=${cmdW != null}, cmdNotify=${cmdNotify != null}, dataWrite=${dataW != null}, dataNotify=${dataN != null}, shellWrite=${shellW != null}, shellNotify=${shellN != null}")
                val missing = buildList {
                    if (cmdW == null) add("FFF2 command write")
                    if (cmdNotify == null) add("FFF1 command notify")
                    if (dataW == null) add("02F0…FFE1 data write")
                    if (dataN == null) add("02F0…FFE2 data notify")
                }
                if (missing.isNotEmpty()) {
                    status("Connected to BLE device, but required CMF channels are missing: ${missing.joinToString()}. This is not the Pro 2 GATT endpoint.")
                    log("REQUIRED_GATT_CHANNELS_MISSING=${missing.joinToString()}")
                    closeGatt()
                    return@post
                }
                if ((prefs().getString("authkey", "") ?: "").length != 32 && (shellW == null || shellN == null)) {
                    status("CMF command/data channels found, but first pairing needs the shell write/notify channel.")
                    log("FIRST_PAIR_SHELL_CHANNEL_MISSING")
                    closeGatt()
                    return@post
                }

                // Save only after the live BLE endpoint has the full CMF command + data layout.
                prefs().edit()
                    .putString("addr", pendingAddress)
                    .putString("name", pendingName)
                    .putBoolean("ble_address_verified", true)
                    .apply()
                WatchLinkState.savedName.value = pendingName

                val notifyChars = listOfNotNull(cmdNotify, dataN, shellN)
                queue.clear()
                notifyChars.forEach { characteristic ->
                    queue.addLast {
                        val localEnabled = try { g.setCharacteristicNotification(characteristic, true) }
                        catch (_: Exception) { false }
                        if (!localEnabled) {
                            log("setCharacteristicNotification failed for ${characteristic.uuid}")
                            if (characteristic.uuid == FFF1 || characteristic.uuid == DATA_N) {
                                status("Could not enable required notifications on ${characteristic.uuid}")
                                closeGatt()
                            } else next()
                            return@addLast
                        }
                        val descriptor = characteristic.getDescriptor(CCCD)
                        if (descriptor == null) {
                            log("CCCD descriptor missing on ${characteristic.uuid}")
                            if (characteristic.uuid == FFF1 || characteristic.uuid == DATA_N) {
                                status("CMF notification descriptor missing on ${characteristic.uuid}")
                                closeGatt()
                            } else next()
                            return@addLast
                        }
                        val accepted = try {
                            if (Build.VERSION.SDK_INT >= 33) {
                                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                g.writeDescriptor(descriptor)
                            }
                        } catch (e: Exception) {
                            log("CCCD write exception ${characteristic.uuid}: ${e.message}")
                            false
                        }
                        if (!accepted) {
                            log("CCCD write not accepted for ${characteristic.uuid}")
                            if (characteristic.uuid == FFF1 || characteristic.uuid == DATA_N) {
                                status("Android refused required notifications on ${characteristic.uuid}")
                                closeGatt()
                            } else next()
                        }
                    }
                }
                queue.addLast {
                    val negotiated = mtu
                    if (negotiated < 30) {
                        status("The watch did not negotiate a usable BLE MTU (MTU=$negotiated). Authentication cannot be sent safely.")
                        closeGatt()
                    } else {
                        status("CMF GATT channels ready. Starting authentication…")
                        startAuth()
                        next()
                    }
                }
                if (!busy) next()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            handler.post {
                if (g !== gatt) return@post
                log("Notification descriptor ${d.characteristic.uuid} status=$st")
                if (st != BluetoothGatt.GATT_SUCCESS &&
                    (d.characteristic.uuid == FFF1 || d.characteristic.uuid == DATA_N)) {
                    status("Android could not subscribe to required CMF notifications (${d.characteristic.uuid}, status $st)")
                    closeGatt()
                    return@post
                }
                next()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (st != BluetoothGatt.GATT_SUCCESS) log("Write failed ${c.uuid} status=$st")
                next()
            }
        }

        @Deprecated("old signature")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            val u = c.uuid
            handler.post { if (g === gatt) onNotify(u, v.copyOf()) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val u = c.uuid
            handler.post { if (g === gatt) onNotify(u, value.copyOf()) }
        }
    }

    /**
     * Every queued job ends by calling next() (usually from a GATT callback). When Android never delivers that callback
     * the queue used to stay busy for good and nothing more reached the watch until a reconnect. The watchdog frees it.
     */
    private var jobSerial = 0
    private val jobWatchdog = object : Runnable {
        var serial = 0
        override fun run() {
            if (busy && serial == jobSerial) {
                log("BLE queue: no callback for 2.5 s, moving on")
                next()
            }
        }
    }

    private fun next() {
        busy = false
        handler.removeCallbacks(jobWatchdog)
        val job = queue.removeFirstOrNull() ?: return
        busy = true
        jobSerial++
        jobWatchdog.serial = jobSerial
        handler.postDelayed(jobWatchdog, 2500)
        job()
    }

    private fun enqueue(job: () -> Unit) {
        queue.addLast(job)
        if (!busy) next()
    }

    private fun writeRaw(ch: BluetoothGattCharacteristic?, data: ByteArray) {
        val characteristic = ch ?: run { log("Write skipped: characteristic not available"); return }
        enqueue {
            val currentGatt = gatt
            if (currentGatt == null) { log("Write skipped: GATT is closed"); next(); return@enqueue }
            val withResponse = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val withoutResponse = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            if (!withResponse && !withoutResponse) {
                log("Characteristic ${characteristic.uuid} is not writable")
                next()
                return@enqueue
            }
            val writeType = if (!withResponse && withoutResponse)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.writeType = writeType
            val accepted = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    currentGatt.writeCharacteristic(characteristic, data, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = data
                    @Suppress("DEPRECATION")
                    currentGatt.writeCharacteristic(characteristic)
                }
            } catch (e: Exception) {
                log("Write exception ${characteristic.uuid}: ${e.message}")
                false
            }
            if (!accepted) {
                log("Write not accepted ${characteristic.uuid} (${data.size} bytes)")
                next()
            } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                // Android does not guarantee onCharacteristicWrite for WRITE_TYPE_NO_RESPONSE.
                next()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Framing + crypto
    // ------------------------------------------------------------------------------------------

    private fun sha(vararg p: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        p.forEach { md.update(it) }
        return md.digest()
    }

    private fun aes(enc: Boolean, k: ByteArray, d: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(if (enc) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(IV))
        return c.doFinal(d)
    }

    private fun crcLe(d: ByteArray): ByteArray {
        val c = CRC32().apply { update(d) }.value
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c.toInt()).array()
    }

    /** One frame for the Watch Music app (opcodes 0x90A5 message, 0x90A7 ping). False while not connected. */
    /** Short BLE connection interval while a latency-sensitive watch app (Watch Remote) is open. */
    @android.annotation.SuppressLint("MissingPermission")
    fun setFastLink(fast: Boolean) {
        if (::app.isInitialized && FmcSharedConnection.isSelected(app)) { FmcSharedConnection.setFastLink(fast); return }
        try {
            gatt?.requestConnectionPriority(
                if (fast) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            )
        } catch (_: Exception) {}
    }

    fun appFrame(c2: Int, payload: ByteArray): Boolean {
        // FMC_SHARED_APP_CHANNEL_ROUTE
        if (::app.isInitialized && FmcSharedConnection.isSelected(app)) return FmcSharedConnection.sendAppFrame(c2, payload)
        if (!ready) return false
        if (c2 == 0x90A7 && queue.size > 4) return true // a ping behind a backlog only adds to it
        handler.post { sendCmd(0xFFFF, c2, payload) }
        return true
    }

    /** Phone notification (0065/0001). Returns false if disabled or disconnected. */
    fun sendNotification(payload: ByteArray): Boolean {
        if (!ready || !WatchFeatureSwitches.isEnabled(app, WatchFeature.NOTIFICATIONS)) return false
        handler.post { sendCmd(0x0065, 0x0001, payload) }
        return true
    }

    fun sendTempUnit(value: Int): Boolean {
        if (!ready || !WatchFeatureSwitches.isEnabled(app, WatchFeature.WEATHER)) return false
        handler.post { sendCmd(0xFFFF, 0x9068, byteArrayOf(value.toByte())) }
        return true
    }

    /** Weather push (FFFF 906B). Returns false if the watch is not connected. */
    fun sendWeather(payload: ByteArray): Boolean {
        if (!ready || !WatchFeatureSwitches.isEnabled(app, WatchFeature.WEATHER)) return false
        handler.post { sendCmd(0xFFFF, 0x906B, payload) }
        return true
    }

    data class WatchContact(val name: String, val phone: String)

    /** Contact records are encoded as 32 UTF-8 name bytes plus 25 UTF-8 phone bytes. */
    fun buildContactsPayload(contacts: List<WatchContact>): ByteArray {
        val list = contacts.take(20)
        val buf = ByteBuffer.allocate(list.size * 57)
        for (contact in list) {
            val nameBytes = NotificationForwarder.truncate(contact.name, 31)
            val phoneBytes = NotificationForwarder.truncate(contact.phone, 24)
            buf.put(ByteArray(32).also { System.arraycopy(nameBytes, 0, it, 0, nameBytes.size) })
            buf.put(ByteArray(25).also { System.arraycopy(phoneBytes, 0, it, 0, phoneBytes.size) })
        }
        return buf.array()
    }

    /** CMF phonebook SET. The watch protocol defines 00D5/0003 as the SET acknowledgement. */
    fun sendContacts(contacts: List<WatchContact>, onResult: (Boolean, String) -> Unit): Boolean {
        if (!ready || !WatchFeatureSwitches.isEnabled(app, WatchFeature.CONTACTS) || contacts.isEmpty()) return false
        val safeContacts = contacts.take(20)
        val payload = buildContactsPayload(safeContacts)
        handler.post {
            contactsAckTimeout?.let { handler.removeCallbacks(it) }
            contactsAckCallback?.invoke(false, "A newer contacts sync replaced this request")
            contactsAckCallback = onResult
            val timeout = Runnable {
                contactsAckCallback?.also { callback ->
                    contactsAckCallback = null
                    contactsAckTimeout = null
                    callback(false, "Contacts were sent, but the watch did not acknowledge the sync. Check the watch before retrying.")
                }
            }
            contactsAckTimeout = timeout
            handler.postDelayed(timeout, 8000)
            log("Contacts SET queued: ${safeContacts.size} contact(s), ${payload.size} bytes")
            sendCmd(0x00D5, 0x0001, payload)
        }
        return true
    }

    /** Empty CONTACTS_SET removes the app-synced phonebook from the watch when possible. */
    fun clearContactsFromWatch(): Boolean {
        if (!ready) return false
        handler.post {
            contactsAckTimeout?.let { handler.removeCallbacks(it) }
            contactsAckTimeout = null
            contactsAckCallback?.invoke(false, "Contacts were cleared while a sync was pending")
            contactsAckCallback = null
            log("Clearing watch contacts list")
            sendCmd(0x00D5, 0x0001, byteArrayOf())
        }
        return true
    }

    /** Read the watch's five native alarm slots (GET 0063/0002, reply 0063/0001). */
    fun getAlarms(onResult: (List<WatchAlarm>?, String) -> Unit): Boolean {
        if (!ready) {
            onResult(null, "Watch is not connected")
            return false
        }
        handler.post {
            alarmsGetTimeout?.let { handler.removeCallbacks(it) }
            alarmsGetCallback?.invoke(null, "A newer alarm read replaced this request")
            alarmsGetCallback = onResult
            WatchAlarmState.loading.value = true
            WatchAlarmState.status.value = "Reading"
            val timeout = Runnable {
                alarmsGetCallback?.also { callback ->
                    alarmsGetCallback = null
                    alarmsGetTimeout = null
                    WatchAlarmState.loading.value = false
                    WatchAlarmState.status.value = "No reply from watch"
                    callback(null, "No reply from watch")
                }
            }
            alarmsGetTimeout = timeout
            handler.postDelayed(timeout, 8000)
            sendCmd(0x0063, 0x0002, byteArrayOf(0xA5.toByte()))
        }
        return true
    }

    /** Write all five native alarm slots (SET 0063/0001). */
    fun setAlarms(alarms: List<WatchAlarm>, onResult: (Boolean, String) -> Unit): Boolean {
        if (!ready) {
            onResult(false, "Watch is not connected")
            return false
        }
        val normalized = WatchAlarmCodec.emptySlots().map { empty ->
            alarms.firstOrNull { it.index == empty.index } ?: empty
        }
        val payload = WatchAlarmCodec.encode(normalized)
        handler.post {
            alarmsSetTimeout?.let { handler.removeCallbacks(it) }
            alarmsSetCallback?.invoke(false, "A newer alarm save replaced this request")
            alarmsSetCallback = onResult
            WatchAlarmState.saving.value = true
            WatchAlarmState.status.value = "Saving"
            val timeout = Runnable {
                alarmsSetCallback?.also { callback ->
                    alarmsSetCallback = null
                    alarmsSetTimeout = null
                    WatchAlarmState.saving.value = false
                    WatchAlarmState.status.value = "No save acknowledgement"
                    callback(false, "The watch did not acknowledge the alarm update")
                }
            }
            alarmsSetTimeout = timeout
            handler.postDelayed(timeout, 8000)
            log("TX alarms 0063 0001 ${payload.hx()}")
            sendCmd(0x0063, 0x0001, payload)
        }
        return true
    }

    /** Experimental incoming-caller alert command; this does not answer or route call audio. */
    fun sendIncomingCall(callerName: String, callerNumber: String): Boolean {
        if (!ready || !WatchFeatureSwitches.isEnabled(app, WatchFeature.CALLS)) return false
        val nameBytes = NotificationForwarder.truncate(callerName, 31)
        val numBytes = NotificationForwarder.truncate(callerNumber, 24)
        val payload = ByteBuffer.allocate(57)
            .put(ByteArray(32).also { System.arraycopy(nameBytes, 0, it, 0, nameBytes.size) })
            .put(ByteArray(25).also { System.arraycopy(numBytes, 0, it, 0, numBytes.size) })
            .array()
        handler.post { sendCmd(0x0064, 0x0001, payload) }
        // The call frame is unconfirmed, so also show the caller as a normal notification. That path is known to work.
        val shown = callerName.ifBlank { callerNumber }.ifBlank { "Incoming call" }
        val body = if (callerNumber.isNotBlank() && callerNumber != shown) "Incoming call  $callerNumber" else "Incoming call"
        sendNotification(NotificationForwarder.payload(0xFF, System.currentTimeMillis() / 1000, shown, body))
        log("call alert sent for '${shown.take(20)}'")
        return true
    }

    private fun sendCmd(c1: Int, c2: Int, payload: ByteArray) {
        val plain = op(c1, c2) in PLAIN
        val maxWrite = mtu - 3
        val piece = if (plain) maxOf(1, maxWrite - 11 - 4 - 2)
        else maxOf(1, (maxWrite - 11) / 16 * 16 - 4 - 1)
        val count = maxOf(1, (payload.size + piece - 1) / piece)
        for (idx in 1..count) {
            val from = (idx - 1) * piece
            val p = payload.copyOfRange(from, minOf(payload.size, from + piece))
            val body: ByteArray
            val chunkLen: Int
            if (plain) {
                // Outgoing plaintext frames carry payload + CRC32-LE on the wire.
                body = p + crcLe(p)
                chunkLen = body.size
            } else {
                val k = key ?: run { log("no key for ${op(c1, c2).toString(16)}"); return }
                body = aes(true, k, p + crcLe(p))
                chunkLen = body.size
            }
            val h = ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN)
                .put(0xF5.toByte()).putShort(chunkLen.toShort()).putShort(c1.toShort())
                .putShort(count.toShort()).putShort(idx.toShort()).putShort(c2.toShort()).array()
            writeRaw(cmdW, h + body)
        }
        if (c2 != 0x90A7) log("TX ${hex(c1, 4)} ${hex(c2, 4)} len=${payload.size}")
    }

    private fun hex(v: Int, w: Int) = (v and 0xFFFF).toString(16).padStart(w, '0')
    private fun ByteArray.hx() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun onNotify(u: UUID, v: ByteArray) {
        if (u == DATA_N) {
            log("DATA notify ${v.size} bytes (no transfer active)")
            return
        }
        if (u == SH_N) {
            shellText.append(String(v, Charsets.UTF_8))
            val t = shellText.toString()
            val m = Regex("GETSECRET:([0-9A-Fa-f]{32}),OK").find(t)
            if (m != null && secret == null) {
                secret = m.groupValues[1].unhex()
                shellText = StringBuilder()
                log("Watch accepted GETSECRET request; beginning authenticated pairing")
                beginPair()
                return
            }
            if (t.contains("GETSECRET:") && (t.contains(",ERR") || t.contains(",FAIL") || t.contains(",NO"))) {
                status("The watch refused GETSECRET. Close its other companion connection, keep the watch awake, and retry.")
                log("GETSECRET rejected: ${t.trim().take(100)}")
                shellText = StringBuilder()
                return
            }
            if (shellText.length > 1000) shellText = StringBuilder(shellText.takeLast(500))
            return
        }
        for (b in v) rx.add(b)
        while (true) {
            if (rx.isEmpty()) return
            if (rx[0] != 0xF5.toByte()) { rx.removeAt(0); continue }
            if (rx.size < 11) return
            val bb = ByteBuffer.wrap(rx.take(11).toByteArray()).order(ByteOrder.BIG_ENDIAN)
            bb.get()
            val chunkLen = bb.short.toInt() and 0xFFFF
            val c1 = bb.short.toInt() and 0xFFFF
            val count = bb.short.toInt() and 0xFFFF
            val idx = bb.short.toInt() and 0xFFFF
            val c2 = bb.short.toInt() and 0xFFFF
            val o = op(c1, c2)
            val plain = o in PLAIN
            val bodyLen = if (plain) maxOf(0, chunkLen - 4) else chunkLen
            if (rx.size < 11 + bodyLen) return
            val body = rx.subList(11, 11 + bodyLen).toByteArray()
            repeat(11 + bodyLen) { rx.removeAt(0) }
            var payload = body
            if (!plain) {
                val k = key
                if (k == null) { log("RX ${hex(c1, 4)} ${hex(c2, 4)} (no key)"); continue }
                payload = try {
                    val d = aes(false, k, body)
                    if (d.size < 4) throw IllegalArgumentException("decrypted frame shorter than CRC")
                    val data = d.copyOf(d.size - 4)
                    val crc = d.copyOfRange(d.size - 4, d.size)
                    if (!crc.contentEquals(crcLe(data))) throw IllegalArgumentException("CRC mismatch")
                    data
                } catch (e: Exception) {
                    log("RX ${hex(c1, 4)} ${hex(c2, 4)} decrypt failed")
                    // AUTH_FAILED is encrypted in the CMF protocol. A rejected saved key
                    // therefore commonly surfaces here as a decrypt/CRC failure rather than
                    // as a decodable AUTH_FAILED frame.
                    if (o == op(0xFFFF, 0xA061)) handleAuthFailed()
                    // The watch's alarm-save acknowledgement is not a valid encrypted payload.
                    if (o == op(0x0063, 0x0003)) handler.post { onAlarmAck() }
                    continue
                }
            }
            val list = parts.getOrPut(o) { ArrayList() }
            if (idx <= 1) list.clear()
            list.add(payload)
            if (idx >= count) {
                val full = list.fold(ByteArray(0)) { a, b -> a + b }
                parts.remove(o)
                onFrame(c1, c2, full)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pairing / session
    // ------------------------------------------------------------------------------------------

    private fun armTimeout(ms: Long = 15000) {
        stepTimeout?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (ready || gatt == null) return@Runnable
            if (usedStoredKey) {
                log("Saved key timed out during reconnect; trying a clean first-time pairing")
                prefs().edit().remove("authkey").apply()
                resetSessionKeepLink()
                startAuth()
            } else {
                val phase = when {
                    secret == null -> "the watch's GETSECRET response"
                    else -> "the watch's pairing approval/key response"
                }
                status("Timed out waiting for $phase. Keep the watch awake, open its Music remote, accept any pairing prompt, then retry.")
                log("AUTH_TIMEOUT waiting=$phase")
            }
        }
        stepTimeout = r
        handler.postDelayed(r, ms)
    }

    private fun resetSessionKeepLink() {
        key = null; secret = null; authKey = null; ready = false; usedStoredKey = false
        rx.clear(); parts.clear(); shellText = StringBuilder(); triedCrlf = false
    }

    private fun startAuth() {
        val stored = prefs().getString("authkey", "") ?: ""
        if (stored.length == 32) {
            authKey = stored.unhex()
            key = authKey
            usedStoredKey = true
            status("Authenticating with saved watch key…")
            sendCmd(0xFFFF, 0x8049, byteArrayOf(0xA5.toByte()) + Build.MODEL.toByteArray())
        } else {
            status("Pairing: asking the watch for its secret...")
            if (shellW == null || shellN == null) {
                status("The watch's pairing shell channel is missing; cannot start a first-time pair")
                return
            }
            triedCrlf = false
            writeRaw(shellW, "AT GETSECRET".toByteArray())
        }
        armTimeout(20000)
    }

    private fun beginPair() {
        val s = secret ?: return
        rnd1 = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sendCmd(0xFFFF, 0x8047, rnd1 + sha(rnd1, s))
        status("Pairing request sent. Check the watch and approve pairing if it asks; this step can take up to a minute.")
        armTimeout(60000)
    }

    private fun handleAuthFailed() {
        if (usedStoredKey && shellW != null && shellN != null) {
            log("Saved authentication key rejected; removing it and trying fresh pairing")
            prefs().edit().remove("authkey").apply()
            resetSessionKeepLink()
            startAuth()
        } else {
            status("The watch rejected authentication. Forget this watch and pair it again.")
        }
    }

    private fun onAlarmAck() {
        alarmsSetTimeout?.let { handler.removeCallbacks(it) }
        alarmsSetTimeout = null
        alarmsSetCallback?.also { callback ->
            alarmsSetCallback = null
            WatchAlarmState.saving.value = false
            WatchAlarmState.status.value = "Saved"
            callback(true, "Saved")
        }
        log("Watch acknowledged alarm update")
    }

    private fun onFrame(c1: Int, c2: Int, p: ByteArray) {
        val o = op(c1, c2)
        if (c2 != 0xB0A7) log("RX ${hex(c1, 4)} ${hex(c2, 4)} ${if (c1 == 0x0063) p.hx() else p.hx().take(80)}")
        if (c1 == 0x9073 || c2 == 0x9073) {
            if (::app.isInitialized) QuickReply.onWatchReply(app, p)
            return
        }
        when (o) {
            op(0xFFFF, 0x0048) -> {
                val s = secret ?: return
                if (p.size < 48) { status("Bad pair reply"); return }
                val rnd2 = p.copyOfRange(0, 16)
                val sig2 = p.copyOfRange(16, 48)
                if (!sig2.contentEquals(sha(rnd2, s))) { status("Pairing signature mismatch"); return }
                val ak = sha(rnd1, rnd2, s).copyOf(16)
                authKey = ak
                key = ak
                prefs().edit().putString("authkey", ak.hx()).apply()
                sendCmd(0xFFFF, 0x8049, byteArrayOf(0xA5.toByte()) + Build.MODEL.toByteArray())
                status("Pairing secret received. Waiting for the watch's pairing response…")
                armTimeout(60000)
            }
            op(0xFFFF, 0x0049) -> {
                sendCmd(0xFFFF, 0x804B, byteArrayOf(0xA5.toByte()))
                status("Watch key accepted. Completing authentication…")
                armTimeout(15000)
            }
            op(0xFFFF, 0x004C) -> {
                val ak = authKey ?: return
                key = sha(p, ak).copyOf(16)
                sendCmd(0xFFFF, 0x804D, byteArrayOf(0xA5.toByte()))
                status("Confirming authenticated session…")
                armTimeout(15000)
            }
            op(0xFFFF, 0x0004) -> {
                epoch++
                ready = true
                stepTimeout?.let { handler.removeCallbacks(it) }
                stepTimeout = null
                prefs().edit().putBoolean("ble_address_verified", true).apply()
                WatchLinkState.connected.value = true
                retryDelay = 2000L
                startProbe()
                onConnectionChanged?.invoke(true)
                status("CMF Watch Pro 2 is connected") // WATCHMENU_CONNECTED_STATUS_V1
                val now = (System.currentTimeMillis() / 1000).toInt()
                val off = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
                sendCmd(0xFFFF, 0x8004, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(now).putInt(off).array())
                log("Authenticated. Music-button events are now listened for on the watch's Music screen.")
                sendMusicNow()
// WATCHMENU_WEATHER_LEFT_TO_NOTHING_X
                // If Contacts was disabled while disconnected, remove the previously synced
                // phonebook as soon as the authenticated BLE link becomes ready.
                if (!WatchFeatureSwitches.isEnabled(app, WatchFeature.CONTACTS)) {
                    clearContactsFromWatch()
                }
                NotificationForwarder.flush()
                VoiceAlarmRelay.flushVoice(app)
            }
            op(0x0065, 0x0003) -> NotificationForwarder.onAck()
            op(0x00D5, 0x0003) -> {
                contactsAckTimeout?.let { handler.removeCallbacks(it) }
                contactsAckTimeout = null
                contactsAckCallback?.also { callback ->
                    contactsAckCallback = null
                    callback(true, "The watch acknowledged the contacts sync")
                }
                log("Contacts SET acknowledged by watch")
            }
            op(0x0063, 0x0001) -> {
                try {
                    val decoded = WatchAlarmCodec.decode(p)
                    WatchAlarmState.alarms.value = decoded
                    WatchAlarmState.status.value = "Loaded"
                    val callback = alarmsGetCallback
                    if (callback != null) {
                        alarmsGetTimeout?.let { handler.removeCallbacks(it) }
                        alarmsGetTimeout = null
                        alarmsGetCallback = null
                        WatchAlarmState.loading.value = false
                        callback(decoded, "Loaded")
                    }
                } catch (e: Exception) {
                    log("Alarm payload decode failed: ${e.message}")
                    alarmsGetTimeout?.let { handler.removeCallbacks(it) }
                    alarmsGetTimeout = null
                    alarmsGetCallback?.also { callback ->
                        alarmsGetCallback = null
                        WatchAlarmState.loading.value = false
                        WatchAlarmState.status.value = "Invalid alarm data"
                        callback(null, "Invalid alarm data")
                    }
                }
            }
            op(0x0063, 0x0003) -> onAlarmAck()
            op(0xFFFF, 0xA061) -> handleAuthFailed()
            op(0xFFFF, 0xB0A5) -> WatchApp.onAck(p)
            op(0xFFFF, 0xB0A6) -> WatchApp.onEvent(p)
            op(0xFFFF, 0xB0A7) -> WatchApp.onPing(p)
            op(0xFFFF, 0xA05D) -> onButton(p)
            // Watch "Find my phone" (listed as FIND_PHONE 005B/0001 in the protocol notes; direction not confirmed).
            op(0x005B, 0x0001) -> FindPhone.toggle(app)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------------------------------

    private fun mapOf(label: String): Set<String> =
        (prefs().getString("btn_$label", "") ?: "").split(";").filter { it.isNotBlank() }.toSet()

    fun mappedSummary(): String =
        listOf("PREV", "NEXT", "TOGGLE").joinToString("   ") { "$it=${mapOf(it).size}" }

    fun clearMapping() {
        prefs().edit().remove("btn_PREV").remove("btn_NEXT").remove("btn_TOGGLE").apply()
    }

    private fun onButton(p: ByteArray) {
        if (p.size < 2) { log("MUSIC_BUTTON event shorter than uint16: ${p.hx()}"); return }
        val raw = ByteBuffer.wrap(p, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val h = p.hx()
        val protocolToken = when (raw) {
            0x0001 -> "PAUSE"
            0x0101 -> "PLAY"
            0x0102 -> "NEXT"
            0x0002 -> "PREV"
            0x0003 -> "VOLUME_DOWN"
            0x0103 -> "VOLUME_UP"
            else -> null
        }
        WatchLinkState.buttonEvents.value =
            (listOf("${h.uppercase()}  →  ${protocolToken ?: "unknown 0x${raw.toString(16).padStart(4, '0')}"}") +
                WatchLinkState.buttonEvents.value).take(12)
        WatchLinkState.buttonEventCount.value = WatchLinkState.buttonEventCount.value + 1

        val teaching = WatchLinkState.teach.value
        if (teaching != null) {
            val set = mapOf(teaching) + h
            prefs().edit().putString("btn_$teaching", set.joinToString(";")).apply()
            WatchLinkState.teach.value = null
            log("TAUGHT custom $teaching = $h")
            return
        }

        // Prefer the documented Pro 2 event ids. User-taught codes remain a fallback for
        // firmware variants or additional controls not yet described by the protocol.
        val token = protocolToken ?: listOf("PREV", "NEXT", "TOGGLE").firstOrNull { h in mapOf(it) }
        log("BUTTON raw=0x${raw.toString(16).padStart(4, '0')} -> ${token ?: "unknown (teach it)"}")
        if (token != null) {
            val sink = onToken
            if (sink == null) log("BUTTON ROUTE NOT READY: MediaMenuService is not attached")
            else sink.invoke(token)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Music text
    // ------------------------------------------------------------------------------------------

    /** state: 1 = paused, 2 = playing */
    /** Menu crown mode: the volume bar on the watch shows the group position (1.0 = top). null = real volume. */
    @Volatile var barFraction: Float? = null

    fun pushMusic(state: Int, title: String, artist: String) {
        if (!ready || !WatchFeatureSwitches.isEnabled(app, WatchFeature.MUSIC)) return
        musicState = state; musicTitle = title; musicArtist = artist
        WatchApp.onMusic(state, title, artist)
        musicRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { sendMusicNow() }
        musicRunnable = r
        handler.postDelayed(r, 30)
    }

    private fun fixed(s: String, n: Int): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        return ByteArray(n).also { System.arraycopy(raw, 0, it, 0, minOf(raw.size, n - 1)) }
    }

    fun onMusicFeatureDisabled() {
        musicRunnable?.let { handler.removeCallbacks(it) }
        musicRunnable = null
        musicTitle = ""
        musicArtist = ""
        musicState = 1
        // One final blank metadata frame clears the last Watch Menu song label on the watch.
        // This is intentionally a one-shot clear; normal music frames remain feature-gated.
        if (ready) {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val head = byteArrayOf(1, 0, max.toByte())
            handler.post { sendCmd(0xFFFF, 0x905C, head + fixed("", 64) + fixed("", 64)) }
        }
    }

    private fun sendMusicNow() {
        if (!ready || !WatchFeatureSwitches.isEnabled(app, WatchFeature.MUSIC)) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val bar = barFraction
        val shownVol = if (bar != null) Math.round(max * bar) else vol
        val head = byteArrayOf(musicState.toByte(), shownVol.toByte(), max.toByte())
        sendCmd(0xFFFF, 0x905C, head + fixed(musicTitle, 64) + fixed(musicArtist, 64))
    }
}
