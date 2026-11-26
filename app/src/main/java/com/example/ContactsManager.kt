package com.example

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneContact(
    val id: String,
    val name: String,
    val phone: String
)

object ContactsManager {
    private const val PREFS = "watch_contacts"
    private const val KEY_SELECTED = "selected_contacts_json"
    private const val KEY_LAST_SYNC = "last_sync_time"

    val selectedContacts = MutableStateFlow<List<PhoneContact>>(emptyList())
    val syncStatus = MutableStateFlow("")
    val lastSyncTime = MutableStateFlow("")

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_SELECTED, "[]") ?: "[]"
        selectedContacts.value = parse(raw)
        lastSyncTime.value = p.getString(KEY_LAST_SYNC, "") ?: ""
    }

    suspend fun loadPhoneContacts(context: Context, query: String = ""): List<PhoneContact> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PhoneContact>()
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return@withContext emptyList()
        }
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = if (query.isNotBlank()) "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (query.isNotBlank()) arrayOf("%$query%") else null
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

        try {
            val cursor: Cursor? = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seenNumbers = mutableSetOf<String>()
                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getString(idIdx) else ""
                    val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty().trim() else ""
                    val rawNum = if (numIdx >= 0) c.getString(numIdx).orEmpty().trim() else ""
                    val cleanNum = rawNum.replace(" ", "").replace("-", "")
                    if (name.isNotBlank() && cleanNum.isNotBlank() && seenNumbers.add(cleanNum)) {
                        list.add(PhoneContact(id, name, rawNum))
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.logError("Contacts", "loadPhoneContacts", e)
        }
        list
    }

    fun addContact(context: Context, contact: PhoneContact): Boolean {
        init(context)
        val current = selectedContacts.value.toMutableList()
        if (current.size >= 20) {
            syncStatus.value = "Watch limit reached (maximum 20 contacts)"
            return false
        }
        if (current.any { it.phone == contact.phone }) return false
        current.add(contact)
        save(context, current)
        return true
    }

    fun removeContact(context: Context, contactId: String, phone: String? = null) {
        init(context)
        val current = selectedContacts.value.filterNot {
            it.id == contactId && (phone == null || it.phone == phone)
        }
        save(context, current)
    }

    fun clearContacts(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, list: List<PhoneContact>) {
        val trimmed = list.take(20)
        selectedContacts.value = trimmed
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED, arr.toString())
            .apply()
    }

    private fun parse(raw: String): List<PhoneContact> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                PhoneContact(o.optString("id"), o.optString("name"), o.optString("phone"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun syncToWatch(context: Context): Boolean {
        init(context)
        if (!WatchFeatureSwitches.isEnabled(context, WatchFeature.CONTACTS)) {
            syncStatus.value = "Contacts are off"
            return false
        }
        val list = selectedContacts.value.take(20)
        if (list.isEmpty()) {
            syncStatus.value = "No contacts selected to sync"
            return false
        }
        if (!WatchLinkState.connected.value) {
            syncStatus.value = "Watch not connected"
            return false
        }
        val watchContacts = list.map { WatchLink.WatchContact(it.name, it.phone) }
        syncStatus.value = "Sending ${list.size} contacts…"
        val queued = WatchLink.sendContacts(watchContacts) { acknowledged, message ->
            if (acknowledged) {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                lastSyncTime.value = time
                syncStatus.value = "${message}: ${list.size} contacts at $time"
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_SYNC, time).apply()
            } else {
                syncStatus.value = message
                AppLogger.log("Contacts", message)
            }
        }
        if (!queued) syncStatus.value = "Could not queue contacts. Check the watch connection."
        return queued
    }
}
