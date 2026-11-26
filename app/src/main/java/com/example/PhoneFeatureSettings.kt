package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ContactsFeaturePage() {
    val context = LocalContext.current
    var permission by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_CONTACTS)) }
    var showPicker by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var phoneContacts by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }
    val selected by ContactsManager.selectedContacts.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = hasPermission(context, Manifest.permission.READ_CONTACTS)
    }

    LaunchedEffect(Unit) {
        ContactsManager.init(context)
    }
    LaunchedEffect(permission, showPicker) {
        if (permission && showPicker) {
            phoneContacts = withContext(Dispatchers.IO) { ContactsManager.loadPhoneContacts(context) }
        }
    }

    SGroup(
        {
            SRow("Contacts permission", value = if (permission) "On" else "Off", chevron = false, onClick = {
                if (!permission) permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            })
        },
        { SRow("Add contacts", Icons.Filled.Add, onClick = {
            if (permission) showPicker = true else permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }) },
        {
            SRow("Sync contacts", Icons.Filled.Sync, chevron = false, onClick = {
                if (WatchFeatureSwitches.isEnabled(context, WatchFeature.CONTACTS)) {
                    ContactsManager.syncToWatch(context)
                }
            })
        }
    )
    if (selected.isNotEmpty()) {
        SSection("Selected contacts")
        SGroup(*(selected.map { contact ->
            @Composable {
                SRow(contact.name, chevron = false, onClick = {
                    ContactsManager.removeContact(context, contact.id, contact.phone)
                }) { Icon(Icons.Filled.Close, null, tint = NColors.Dim) }
            }
        }).toTypedArray())
    }

    var nameDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf("") }
    var nameNumber by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var replacements by remember { mutableStateOf(NumberNames.all(context)) }
    if (nameDialog) {
        AlertDialog(
            onDismissRequest = { nameDialog = false; editing = false },
            containerColor = NColors.Card,
            confirmButton = {
                if (editing) {
                    TextButton(onClick = {
                        if (editKey.isNotBlank() && editKey != nameNumber.trim()) NumberNames.set(context, editKey, "")
                        NumberNames.set(context, nameNumber, nameText)
                        replacements = NumberNames.all(context)
                        editing = false
                    }) { Text("Save", color = NColors.Text, fontFamily = NType) }
                } else {
                    TextButton(onClick = { editKey = ""; nameNumber = ""; nameText = ""; editing = true }) {
                        Text("Add", color = NColors.Text, fontFamily = NType)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (editing) editing = false else nameDialog = false }) {
                    Text(if (editing) "Cancel" else "Close", color = NColors.Dim, fontFamily = NType)
                }
            },
            text = {
                if (editing) {
                    Column {
                        NField(value = nameNumber, onValueChange = { nameNumber = it }, label = "Name or number")
                        Spacer(Modifier.height(8.dp))
                        NField(value = nameText, onValueChange = { nameText = it }, label = "Replace with")
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(replacements.entries.toList(), key = { it.key }) { entry ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.value, color = NColors.Text, fontFamily = NDot, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    editKey = entry.key; nameNumber = entry.key; nameText = entry.value; editing = true
                                }) { Icon(Icons.Filled.Edit, null, tint = NColors.Dim, modifier = Modifier.size(20.dp)) }
                                Text(
                                    "x", color = NColors.Dim, fontFamily = NDot, fontSize = 22.sp,
                                    modifier = Modifier.clickable {
                                        NumberNames.set(context, entry.key, "")
                                        replacements = NumberNames.all(context)
                                    }.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Done", color = NColors.Text, fontFamily = NType) } },
            containerColor = NColors.Card,
            text = {
                Column {
                    NField(value = search, onValueChange = { search = it }, label = "Search contacts")
                    LazyColumn(Modifier.height(380.dp)) {
                        items(phoneContacts.filter { it.name.contains(search, true) || it.phone.contains(search, true) }, key = { it.id + it.phone }) { contact ->
                            val checked = selected.any { it.phone == contact.phone }
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (checked) ContactsManager.removeContact(context, selected.first { it.phone == contact.phone }.id, contact.phone)
                                    else ContactsManager.addContact(context, contact)
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(contact.name, color = NColors.Text, fontFamily = NType, fontSize = 14.sp)
                                    Text(contact.phone, color = NColors.Dim, fontFamily = NType, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        )
    }
    TextButton(
        onClick = { replacements = NumberNames.all(context); editing = false; nameDialog = true },
        modifier = Modifier.padding(top = 20.dp, bottom = 24.dp)
    ) { Text("Replace", color = NColors.Dim, fontFamily = NType) }
}

@Composable
fun CallsFeaturePage() {
    val context = LocalContext.current
    var permission by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_PHONE_STATE)) }
    val state by CallsManager.callState.collectAsState()
    val caller by CallsManager.lastCallerName.collectAsState()
    val hfp by CallsManager.bluetoothCallProfileConnected.collectAsState()
    val hfpStatus by CallsManager.bluetoothCallProfileStatus.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = hasPermission(context, Manifest.permission.READ_PHONE_STATE)
        if (permission && WatchFeatureSwitches.isEnabled(context, WatchFeature.CALLS)) CallsManager.startListening(context)
    }

    LaunchedEffect(Unit) {
        if (permission && WatchFeatureSwitches.isEnabled(context, WatchFeature.CALLS)) CallsManager.startListening(context)
        CallsManager.checkBluetoothCallProfile(context)
    }

    SGroup(
        { SRow("Phone state permission", value = if (permission) "On" else "Off", chevron = false, onClick = {
            if (!permission) permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }) },
        { SRow("Call listener", value = if (state == "Idle") "Ready" else state, chevron = false) },
        { SRow("Caller", value = caller.ifBlank { "None" }, chevron = false) },
        { SRow("Bluetooth call audio (HFP)", value = if (hfp) "Connected" else "Not connected", chevron = false, onClick = { CallsManager.checkBluetoothCallProfile(context) }) },
        { SRow("Bluetooth settings", Icons.Filled.Call, onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, chevron = true) }
    )
}

private fun hasPermission(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
