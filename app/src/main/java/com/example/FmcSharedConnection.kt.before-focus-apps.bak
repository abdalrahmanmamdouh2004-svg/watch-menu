package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Optional FMC-style K session on a second Android GATT client. This is experimental on physical Android watches. */
object FmcSharedConnection {
    private const val PREFS = "watch_link"
    private const val KEY_SHARED = "fmc_shared_connection"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var session: FmcKSession? = null
    @Volatile private var transport: FmcAndroidGattLink? = null
    @Volatile private var connecting = false
    @Volatile private var connectionGeneration = 0L
    private var eventsJob: Job? = null
    private var connectJob: Job? = null
    private val pingQueued = AtomicBoolean(false)

    fun isSelected(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHARED, false)

    fun setSelected(context: Context, selected: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHARED, selected).apply()
    }

    fun isConnected(): Boolean = session != null && WatchLinkState.connected.value
    fun isConnecting(): Boolean = connecting

    fun connectSaved(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("ble_address_verified", false)) {
            WatchLinkState.status.value = "Find the watch first, then connect using the shared mode."
            return
        }
        val address = prefs.getString("addr", "").orEmpty()
        if (address.isBlank()) return
        connect(context, address, prefs.getString("name", "CMF Watch Pro 2").orEmpty())
    }

    fun connect(context: Context, address: String, name: String) {
        if (connecting || isConnected()) return
        val attempt = ++connectionGeneration
        connecting = true
        WatchLinkState.connected.value = false
        WatchLinkState.savedName.value = name
        WatchLinkState.status.value = "Joining the Nothing X Bluetooth link…"
        AppLogger.log("FMCShared", "Opening second GATT client for $name")

        connectJob = scope.launch {
            var newTransport: FmcAndroidGattLink? = null
            try {
                newTransport = FmcAndroidGattLink(context.applicationContext, address)
                transport = newTransport
                newTransport.onDropped {
                    if (transport === newTransport) {
                        session = null
                        transport = null
                        eventsJob?.cancel()
                        WatchLinkState.connected.value = false
                        WatchLinkState.status.value = "Not connected" // WATCHMENU_FMC_DROP_STATUS_V1
                        AppLogger.log("FMCShared", "Shared GATT link dropped")
                    }
                }
                newTransport.connect()
                val newSession = FmcKSession.open(newTransport)
                transport = newTransport
                session = newSession
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("addr", address)
                    .putString("name", name)
                    .putBoolean("ble_address_verified", true)
                    .apply()
                // One PING registers this K host. WatchApp's existing PING loop remains available
                // for older loaders; K-capable loaders can also push EVENTs without polling.
                val state = newSession.ping()
                WatchLinkState.connected.value = true
                WatchLinkState.status.value = "CMF Watch Pro 2 is connected" // WATCHMENU_FMC_CONNECTED_STATUS_V1
                AppLogger.log("FMCShared", "K session registered; running slot=${state.first}, flags=${state.second}")
                WatchApp.onPing(byteArrayOf(0, state.first.toByte(), state.second.toByte()))

                eventsJob?.cancel()
                eventsJob = scope.launch {
                    while (session === newSession) {
                        try {
                            val (sequence, slot, data) = newSession.receive()
                            val event = byteArrayOf(sequence.toByte(), slot.toByte()) + data
                            WatchApp.onEvent(event)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (session === newSession) {
                                AppLogger.log("FMCShared", "App event receive: ${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                newTransport?.disconnect()
                if (transport === newTransport) transport = null
                if (session == null) WatchLinkState.connected.value = false
                throw e
            } catch (e: Exception) {
                newTransport?.disconnect()
                session = null
                transport = null
                WatchLinkState.connected.value = false
                WatchLinkState.status.value = "Connection failed" // WATCHMENU_FMC_FAILED_STATUS_V1
                AppLogger.logError("FMCShared", "connect", e)
            } finally {
                if (connectionGeneration == attempt) {
                    connecting = false
                    connectJob = null
                }
            }
        }
    }

    fun disconnect() {
        connectionGeneration++
        connectJob?.cancel()
        connectJob = null
        eventsJob?.cancel()
        eventsJob = null
        session = null
        transport?.disconnect()
        transport = null
        connecting = false
        WatchLinkState.connected.value = false
        WatchLinkState.status.value = "Not connected"
    }

    @SuppressLint("MissingPermission")
    fun connectedNothingXWatches(context: Context): List<Pair<String, String>> {
        val manager = context.applicationContext.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        return try {
            manager.getConnectedDevices(BluetoothProfile.GATT).mapNotNull { device ->
                val name = runCatching { device.name }.getOrNull() ?: return@mapNotNull null
                if (!name.startsWith("CMF Watch", ignoreCase = true)) return@mapNotNull null
                name to device.address
            }.distinctBy { it.second }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** Existing watch apps keep their original 0x90A5/0x90A7 protocol; only the host transport changes. */
    fun sendAppFrame(opcode: Int, payload: ByteArray): Boolean {
        val active = session ?: return false
        return when (opcode) {
            0x90A5 -> {
                val data = if (payload.firstOrNull() == 0xFF.toByte()) payload.copyOfRange(1, payload.size) else payload.copyOf()
                scope.launch {
                    try {
                        active.send(data)
                        // Reuse the existing phone-side ACK routing: it already distinguishes Music from Replies.
                        WatchApp.onAck(byteArrayOf(0))
                    } catch (e: Exception) {
                        AppLogger.log("FMCShared", "App message failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                true
            }
            0x90A7 -> {
                // WatchApp polls every 250 ms while an app is open. Coalesce those ticks so a slow
                // GATT response cannot create an unbounded queue of redundant PING requests.
                if (!pingQueued.compareAndSet(false, true)) return true
                scope.launch {
                    try {
                        val (slot, flags) = active.ping()
                        WatchApp.onPing(byteArrayOf(0, slot.toByte(), flags.toByte()))
                    } catch (e: Exception) {
                        AppLogger.log("FMCShared", "PING failed: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        pingQueued.set(false)
                    }
                }
                true
            }
            else -> false
        }
    }
}

@SuppressLint("MissingPermission")
private class FmcAndroidGattLink(
    context: Context,
    private val address: String
) : FmcGattLink {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val operationLock = Mutex()
    private val handlers = CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
    private val dropHandlers = CopyOnWriteArrayList<() -> Unit>()
    private val chars = mutableMapOf<String, BluetoothGattCharacteristic>()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var mtu = 23
    @Volatile private var dropped = false
    private data class Pending(val kind: String, val result: CompletableDeferred<Int>)
    @Volatile private var pending: Pending? = null

    suspend fun connect() {
        val adapter = manager?.adapter ?: throw IllegalStateException("Bluetooth adapter is unavailable")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is turned off")
        val device = adapter.getRemoteDevice(address)
        await("connect", 20_000L) {
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
        val g = gatt ?: throw IllegalStateException("Android did not create a GATT client")
        runCatching { await("mtu", 4_000L) { g.requestMtu(247) } }
        await("services", 15_000L) { g.discoverServices() }

        val all = g.services.flatMap { it.characteristics }
        all.forEach { chars[it.uuid.toString().uppercase()] = it }
        val required = listOf(FMC_CMD_WRITE, FMC_CMD_NOTIFY, FMC_SHELL_WRITE, FMC_SHELL_NOTIFY)
        val missing = required.filterNot { chars.containsKey(it) }
        if (missing.isNotEmpty()) throw IllegalStateException("The connected watch is missing FMC K-channel characteristics: ${missing.joinToString()}")

        listOf(FMC_CMD_NOTIFY, FMC_SHELL_NOTIFY).forEach { uuid ->
            val c = chars[uuid] ?: throw IllegalStateException("Missing notify characteristic $uuid")
            if (!g.setCharacteristicNotification(c, true)) throw IllegalStateException("Could not enable notification $uuid")
            val descriptor = c.getDescriptor(CCCD) ?: throw IllegalStateException("Missing CCCD on $uuid")
            await("descriptor", 12_000L) { writeDescriptor(g, descriptor) }
        }
    }

    override fun onNotify(handler: (String, ByteArray) -> Unit) { handlers += handler }
    override fun onDropped(handler: () -> Unit) { dropHandlers += handler }

    override suspend fun write(uuid: String, bytes: ByteArray) {
        operationLock.withLock {
            val g = gatt?.takeUnless { dropped } ?: throw IllegalStateException("Bluetooth link was dropped")
            val c = chars[uuid.uppercase()] ?: throw IllegalStateException("Characteristic $uuid is unavailable")
            val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val type = if (bytes.size > mtu - 3 || !noResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            awaitUnlocked("write", 15_000L) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = type
                    @Suppress("DEPRECATION")
                    c.value = bytes
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            }
        }
    }

    private suspend fun await(kind: String, timeoutMs: Long, start: () -> Boolean) =
        operationLock.withLock { awaitUnlocked(kind, timeoutMs, start) }

    private suspend fun awaitUnlocked(kind: String, timeoutMs: Long, start: () -> Boolean) {
        val p = Pending(kind, CompletableDeferred())
        pending = p
        if (!start()) {
            if (pending === p) pending = null
            throw IllegalStateException("Android did not start GATT operation: $kind")
        }
        try {
            val result = withTimeout(timeoutMs) { p.result.await() }
            if (result != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("GATT $kind failed with status $result")
        } finally {
            if (pending === p) pending = null
        }
    }

    private fun finish(kind: String, status: Int) {
        val p = pending ?: return
        if (p.kind == kind) p.result.complete(status)
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    fun disconnect() {
        dropped = true
        pending?.result?.completeExceptionally(IllegalStateException("Bluetooth disconnected"))
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                finish("connect", BluetoothGatt.GATT_SUCCESS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val unexpected = !dropped
                dropped = true
                pending?.result?.completeExceptionally(IllegalStateException("Watch disconnected (GATT status $status)"))
                if (unexpected) dropHandlers.forEach { handler -> runCatching { handler() } }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            finish("mtu", BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish("services", status)
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = finish("descriptor", status)
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = finish("write", status)

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            dispatch(characteristic.uuid, value.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            dispatch(characteristic.uuid, value.copyOf())
        }
    }

    private fun dispatch(uuid: UUID, bytes: ByteArray) {
        val name = uuid.toString().uppercase()
        handlers.forEach { handler -> runCatching { handler(name, bytes) } }
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
