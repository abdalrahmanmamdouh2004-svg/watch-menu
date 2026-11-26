package com.example

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow

/** Incoming-call state bridge. Caller alerts are experimental; Android call audio remains on HFP. */
object CallsManager {
    val callState = MutableStateFlow("Idle")
    val bluetoothCallProfileConnected = MutableStateFlow(false)
    val bluetoothCallProfileStatus = MutableStateFlow("Not checked")
    val lastCallerName = MutableStateFlow("")
    val lastCallerNumber = MutableStateFlow("")

    private var headsetProfile: BluetoothProfile? = null
    private var telephonyManager: TelephonyManager? = null
    private var modernCallback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    @Volatile private var isListening = false

    fun checkBluetoothCallProfile(context: Context) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) {
            bluetoothCallProfileConnected.value = false
            bluetoothCallProfileStatus.value = "Bluetooth is off"
            return
        }
        try {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = proxy
                        val connectedDevices = proxy?.connectedDevices.orEmpty()
                        val watchName = WatchLinkState.savedName.value
                        val watchHfp = connectedDevices.firstOrNull { device ->
                            val name = try { device.name.orEmpty() } catch (_: SecurityException) { "" }
                            name.contains("CMF Watch Pro 2", ignoreCase = true) ||
                                (watchName.isNotBlank() && name.equals(watchName, ignoreCase = true))
                        }
                        bluetoothCallProfileConnected.value = watchHfp != null
                        val status = when {
                            watchHfp != null -> "CMF watch call audio connected"
                            connectedDevices.isNotEmpty() -> "Another Bluetooth headset is connected; CMF watch call audio is not connected"
                            else -> "Not connected — enable Phone calls for the CMF watch in Bluetooth settings"
                        }
                        bluetoothCallProfileStatus.value = status
                        AppLogger.log("Calls", status)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = null
                        bluetoothCallProfileConnected.value = false
                        bluetoothCallProfileStatus.value = "Bluetooth call profile disconnected"
                    }
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            bluetoothCallProfileConnected.value = false
            bluetoothCallProfileStatus.value = "Could not check Bluetooth call profile: ${e.message ?: e.javaClass.simpleName}"
            AppLogger.logError("Calls", "checkBluetoothCallProfile", e)
        }
    }

    @Suppress("DEPRECATION")
    fun startListening(context: Context) {
        if (!WatchFeatureSwitches.isEnabled(context, WatchFeature.CALLS)) return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (isListening && telephonyManager === tm) return
        stopListening()
        telephonyManager = tm
        checkBluetoothCallProfile(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        // Android 12+ does not expose the caller number through this callback.
                        handleCallState(context.applicationContext, state, null)
                    }
                }
                modernCallback = callback
                tm.registerTelephonyCallback(context.mainExecutor, callback)
            } else {
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(context.applicationContext, state, phoneNumber)
                    }
                }
                legacyListener = listener
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            isListening = true
            AppLogger.log("Calls", "Call-state listener registered")
        } catch (e: Exception) {
            AppLogger.logError("Calls", "startListening", e)
            isListening = false
        }
    }

    @Suppress("DEPRECATION")
    fun stopListening() {
        val tm = telephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (modernCallback as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                legacyListener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (e: Exception) {
            AppLogger.logError("Calls", "stopListening", e)
        }
        modernCallback = null
        legacyListener = null
        telephonyManager = null
        isListening = false
    }

    @Volatile private var ringTitle: String? = null
    private val ringHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Android 12+ hides the caller number, but the dialer's own ringing notification names the caller. */
    fun noteCallNotification(title: String) {
        if (callState.value == "Ringing" && title.isNotBlank()) ringTitle = title
    }

    fun handleCallState(context: Context, state: Int, incomingNumber: String?) {
        if (!WatchFeatureSwitches.isEnabled(context, WatchFeature.CALLS)) return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                callState.value = "Ringing"
                val number = incomingNumber?.takeIf { it.isNotBlank() } ?: "Unknown"
                val name = resolveContactName(context, number)
                lastCallerName.value = name
                lastCallerNumber.value = number
                AppLogger.log("Calls", "Incoming call state received")
                ringTitle = null
                if (number != "Unknown") {
                    WatchLink.sendIncomingCall(name, number)
                } else {
                    // Give the dialer's notification a moment to arrive, then send its caller name.
                    ringHandler.postDelayed({
                        if (callState.value == "Ringing") {
                            val shown = ringTitle ?: "Incoming call"
                            lastCallerName.value = shown
                            WatchLink.sendIncomingCall(shown, "")
                        }
                    }, 900L)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                callState.value = "Active call"
                AppLogger.log("Calls", "Call active / offhook")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                callState.value = "Idle"
                AppLogger.log("Calls", "Call ended / idle")
            }
        }
    }

    private fun resolveContactName(context: Context, number: String): String {
        if (number == "Unknown" || number.isBlank()) return "Incoming call"
        NumberNames.lookup(context, number)?.let { return it }
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return number
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cursor: Cursor? = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) else number
                } else number
            } ?: number
        } catch (_: Exception) { number }
    }

    fun sendTestIncomingCall(name: String = "Test Caller", number: String = "") : Boolean {
        lastCallerName.value = name
        lastCallerNumber.value = number
        callState.value = "Test alert"
        return WatchLink.sendIncomingCall(name, number)
    }
}
