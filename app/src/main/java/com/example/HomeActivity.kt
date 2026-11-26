package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ChatBubbleOutline // WATCHMENU_REPLY_TILE_ICON_IMPORT_V1
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : ComponentActivity() {
    private val dictationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) PhoneDictationService.ensureReady(this)
        else AppLogger.log("VoiceDictation", "Microphone permission not granted; watch dictation is unavailable")
    }

    /** The only prompt at the first start: Bluetooth (what the watch link needs) and, on Android 13+, the one running notice. */
    private val startupPermissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)
        WatchLink.initialize(applicationContext)
        FmcSharedConnection.setSelected(applicationContext, true)
        MenuStore.init(applicationContext)
        enableEdgeToEdge()
        // The companion bridge is now the permanent core of the app; there is no Watch Menu on/off switch.
        MediaMenuConfig.setAutoStart(this, true)
        if (!MediaMenuState.running.value) {
            try {
                MediaMenuService.start(this)
            } catch (e: Exception) {
                AppLogger.logError("Home", "start companion service", e)
            }
        }
        setContent {
            NothingTheme { AppRoot() }
        }
        val want = ArrayList<String>(WatchLink.neededPermissions().toList())
        if (android.os.Build.VERSION.SDK_INT >= 33) want.add(android.Manifest.permission.POST_NOTIFICATIONS)
        val missing = want.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) startupPermissions.launch(missing.toTypedArray())
        // The microphone is NOT asked for here. It is asked for the first time the watch's microphone is used
        // (see PhoneDictationService.toggle). If it is already allowed, the voice service is made ready as before.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            PhoneDictationService.ensureReady(this)
        } else if (intent?.getBooleanExtra(EXTRA_ASK_MIC, false) == true) {
            dictationPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        const val EXTRA_ASK_MIC = "ask_mic"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ASK_MIC, false)) dictationPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Setup : Screen
    data object Onboarding : Screen
    data object Watch : Screen
    data object WatchMusic : Screen // WATCHMENU_SCREEN_ROUTES_V1
    data object WatchReplies : Screen
    data object WatchRemote : Screen
    data class Add(val groupId: String?) : Screen
    data class GroupDetail(val id: String) : Screen
}

@Composable
fun AppRoot() {
    val appContext = LocalContext.current
    val stack = remember { mutableStateListOf<Screen>(Screen.Home).apply { if (!Onboarding.isDone(appContext)) add(Screen.Onboarding) } }
    fun pop() {
        if (stack.last() == Screen.Onboarding) Onboarding.setDone(appContext)
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    Box(
        Modifier
            .fillMaxSize()
            .background(NColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        when (val s = stack.last()) {
            Screen.Home -> HomeScreen(
                onWatchMusic = { stack.add(Screen.WatchMusic) },
                onWatchReplies = { stack.add(Screen.WatchReplies) },
                onWatchRemote = { stack.add(Screen.WatchRemote) },
                onSetup = { stack.add(Screen.Setup) },
                onWatch = { stack.add(Screen.Watch) }
            )
            Screen.WatchMusic -> WatchMusicScreen(
                onOpenGroup = { stack.add(Screen.GroupDetail(it)) },
                onAdd = { stack.add(Screen.Add(null)) },
                onBack = { pop() }
            )
            Screen.WatchReplies -> WatchRepliesScreen(onBack = { pop() })
            Screen.WatchRemote -> WatchRemoteScreen(onBack = { pop() })
            Screen.Watch -> WatchScreen(onBack = { pop() })
            // WATCHMENU_ROOT_APP_ROUTES_V1
            Screen.Setup -> SetupScreen(onBack = { pop() })
            Screen.Onboarding -> OnboardingScreen(onDone = { pop() })
            is Screen.Add -> AddFlowScreen(
                addToGroupId = s.groupId,
                onBack = { pop() },
                onDone = { id ->
                    pop()
                    if (s.groupId == null) stack.add(Screen.GroupDetail(id))
                }
            )
            is Screen.GroupDetail -> GroupScreen(
                groupId = s.id,
                onBack = { pop() },
                onAdd = { stack.add(Screen.Add(s.id)) }
            )
        }
    }
}

private fun purposeLabel(t: GroupType) = t.label

// =================================================================================================
// Home
// =================================================================================================

@Composable
fun WatchMusicScreen(onOpenGroup: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) { // WATCHMENU_MUSIC_PAGE_V1
    val context = LocalContext.current
    val groups by MenuStore.groups.collectAsState()
    val running by MediaMenuState.running.collectAsState()
    val menuMode by MediaMenuState.menuMode.collectAsState()
    val preview = remember { mutableStateMapOf<String, Int>() }

    Column(Modifier.fillMaxSize()) {
        NTopBar(
            title = "Watch Music",
            onBack = onBack,
            trailing = {
                IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add group", tint = NColors.Text) }
            }
        )
        // WATCHMENU_MUSIC_PAGE_NO_CONNECTION_PILL_V1

        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No group", color = NColors.Dim, fontFamily = NType)
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                ReorderableList(
                    items = groups,
                    idOf = { it.id },
                    rowHeight = 96.dp,
                    onMove = { from, to ->
                        MenuStore.update(context) { list ->
                            list.toMutableList().also { m -> m.add(to, m.removeAt(from)) }
                        }
                    }
                ) { group, handle ->
                    val idx = (preview[group.id] ?: 0).coerceIn(0, maxOf(0, group.items.size - 1))
                    val shown = group.items.getOrNull(idx)?.name ?: "(empty)"
                    Row(
                        Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxSize()
                            .background(NColors.Card, RoundedCornerShape(18.dp))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.DragHandle, "Move",
                            tint = NColors.Dim,
                            modifier = handle.size(40.dp).padding(8.dp)
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onOpenGroup(group.id) },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(group.name, style = smallLabel())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "<", color = NColors.Dim, fontFamily = NDot, fontSize = 22.sp,
                                    modifier = Modifier
                                        .clickable {
                                            val n = group.items.size
                                            if (n > 0) preview[group.id] = (idx - 1 + n) % n
                                        }
                                        .padding(horizontal = 14.dp, vertical = 4.dp)
                                )
                                Text(
                                    shown, color = NColors.Text, fontFamily = NDot, fontSize = 20.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Text(
                                    ">", color = NColors.Dim, fontFamily = NDot, fontSize = 22.sp,
                                    modifier = Modifier
                                        .clickable {
                                            val n = group.items.size
                                            if (n > 0) preview[group.id] = (idx + 1) % n
                                        }
                                        .padding(horizontal = 14.dp, vertical = 4.dp)
                                )
                            }
                        }
                        IconButton(onClick = {
                            MenuStore.update(context) { list -> list.filterNot { it.id == group.id } }
                        }) { Icon(Icons.Filled.Delete, "Delete group", tint = NColors.Dim) }
                    }
                }
            }
        }
    }
}


// WATCHMENU_APP_HOME_V1
@Composable
fun HomeScreen(
    onWatchMusic: () -> Unit,
    onWatchReplies: () -> Unit,
    onWatchRemote: () -> Unit,
    onSetup: () -> Unit,
    onWatch: () -> Unit
) {
    val linked by WatchLinkState.connected.collectAsState()
    val installedWatchAppIds by WatchInstalledApps.installedIds.collectAsState()
    LaunchedEffect(linked) {
        if (linked) {
            // INFO is read-only. Recheck while Home is visible so newly installed apps appear automatically.
            while (WatchLinkState.connected.value) {
                FmcSharedConnection.refreshInstalledApps()
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }
    val appTiles = buildList {
        if (WatchInstalledApps.WATCH_MUSIC in installedWatchAppIds) {
            add(Triple("Watch Music", Icons.Filled.MusicNote, onWatchMusic))
        }
        if (WatchInstalledApps.WATCH_REPLIES in installedWatchAppIds) {
            add(Triple("Watch Replies", Icons.Filled.ChatBubbleOutline, onWatchReplies))
        }
        if (WatchInstalledApps.WATCH_REMOTE in installedWatchAppIds) {
            add(Triple("Watch Remote", Icons.Filled.SettingsRemote, onWatchRemote))
        }
        // Future owned apps get one exact package ID above and one matching Home action here.
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NTopBar(
            title = "Home",
            leading = {
                IconButton(onClick = onSetup) { Icon(Icons.Filled.Settings, "Settings", tint = NColors.Text) }
            }
        )
        NButton(
            text = if (linked) "Watch connected" else "Connect watch",
            onClick = onWatch,
            primary = !linked,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            appTiles.chunked(2).forEach { rowTiles ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTiles.forEach { (title, icon, action) ->
                        AppLauncherTile(title, icon, action, Modifier.weight(1f))
                    }
                    if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        DottedDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp))
    }
}

@Composable
private fun AppLauncherTile(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.aspectRatio(1.25f) // 5:4: a little shorter than the old square tiles
            .background(NColors.CardHi, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = NColors.Text, fontFamily = NType, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(icon, contentDescription = title, tint = NColors.Text, modifier = Modifier.size(24.dp))
    }
}

// =================================================================================================
// Group detail
// =================================================================================================

@Composable
fun GroupScreen(groupId: String, onBack: () -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by MenuStore.groups.collectAsState()
    val group = groups.firstOrNull { it.id == groupId }
    if (group == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var result by remember { mutableStateOf("") }
    var fixItem by remember { mutableStateOf<MenuItem?>(null) }
    var fixSearch by remember { mutableStateOf("") }
    var groupName by remember(groupId) { mutableStateOf(group.name) }

    fixItem?.let { fi ->
        val live = group.items.firstOrNull { it.id == fi.id } ?: fi
        val eps = PodcastFeed.decodeEpisodes(live.podcastEpisodes)
        AlertDialog(
            onDismissRequest = { fixItem = null },
            containerColor = NColors.Card,
            confirmButton = { TextButton(onClick = { fixItem = null }) { Text("Done", color = NColors.Text, fontFamily = NType) } },
            title = { Text(live.episode.ifBlank { live.name }, color = NColors.Text, fontFamily = NType, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    NField(value = fixSearch, onValueChange = { fixSearch = it }, label = "Search")
                    val shown = eps.indices.filter { fixSearch.isBlank() || eps[it].title.contains(fixSearch.trim(), true) }.take(60)
                    LazyColumn(Modifier.height(340.dp)) {
                        items(shown.size) { n ->
                            val idx = shown[n]
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val e = eps[idx]
                                    MenuStore.update(context) { list ->
                                        list.map { gr -> if (gr.id != groupId) gr else gr.copy(items = gr.items.map {
                                            if (it.id == live.id) it.copy(episode = e.title, podcastIndex = idx, podcastCurrentUrl = e.link.ifBlank { e.audioUrl }, podcastPositionMs = 0L) else it
                                        }) }
                                    }
                                }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(26.dp)) { if (idx == live.podcastIndex) Icon(Icons.Filled.Check, null, tint = NColors.Text, modifier = Modifier.size(18.dp)) }
                                Text(eps[idx].title, color = NColors.Text, fontFamily = NType, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        NTopBar(
            title = group.type.label,
            onBack = onBack,
            trailing = {
                IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add item", tint = NColors.Text) }
            }
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            NField(
                value = groupName,
                onValueChange = { v ->
                    groupName = v
                    MenuStore.update(context) { list ->
                        list.map { if (it.id == groupId) it.copy(name = v) else it }
                    }
                },
                label = "Group name (shown on the watch)"
            )
            if (group.type == GroupType.MUSIC) {
                Spacer(Modifier.height(12.dp))
                SSection("Playback")
                SGroup(
                    { SSwitchRow("Stay in this group", group.stayInGroup) { enabled ->
                        MenuStore.update(context) { list -> list.map { if (it.id == groupId) it.copy(stayInGroup = enabled, repeatGroup = if (enabled) it.repeatGroup else false) else it } }
                    } },
                    { SSwitchRow("Repeat group", group.repeatGroup) { enabled ->
                        MenuStore.update(context) { list -> list.map { if (it.id == groupId) it.copy(stayInGroup = true, repeatGroup = enabled) else it } }
                    } }
                )
            }
            Spacer(Modifier.height(12.dp))
            if (group.items.isEmpty()) {
            }
            ReorderableList(
                items = group.items,
                idOf = { it.id },
                rowHeight = 118.dp,
                onMove = { from, to ->
                    MenuStore.update(context) { list ->
                        list.map { g ->
                            if (g.id == groupId) g.copy(items = g.items.toMutableList().also { m -> m.add(to, m.removeAt(from)) })
                            else g
                        }
                    }
                }
            ) { item, handle ->
                var name by remember(item.id) { mutableStateOf(item.name) }
                Row(
                    Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxSize()
                        .background(NColors.Card, RoundedCornerShape(18.dp))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.DragHandle, "Move",
                        tint = NColors.Dim,
                        modifier = handle.size(40.dp).padding(8.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        NField(
                            value = name,
                            onValueChange = { v ->
                                name = v
                                MenuStore.update(context) { list ->
                                    list.map { g ->
                                        if (g.id == groupId) g.copy(items = g.items.map { if (it.id == item.id) it.copy(name = v) else it })
                                        else g
                                    }
                                }
                            },
                            label = "Name"
                        )
                        Text(
                            displayValue(group.type, item),
                            style = smallLabel(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val r = ActionRunner.run(context.applicationContext, group, item)
                            result = "${item.name}: $r".take(300)
                        }
                    }) { Icon(Icons.Filled.PlayArrow, "Test", tint = NColors.Green) }
                    if (group.type == GroupType.PODCAST) {
                        IconButton(onClick = { fixSearch = ""; fixItem = item }) { Icon(Icons.Filled.Edit, "Current episode", tint = NColors.Text) }
                        IconButton(onClick = {
                            scope.launch {
                                val before = PodcastFeed.decodeEpisodes(item.podcastEpisodes).size
                                val updated = PodcastPlayback.refresh(context.applicationContext, item.id)
                                val after = PodcastFeed.decodeEpisodes(updated?.podcastEpisodes.orEmpty()).size
                                result = "${item.name}: " + if (after > before) "+${after - before} new" else "up to date"
                            }
                        }) { Icon(Icons.Filled.Refresh, "Reload", tint = NColors.Text) }
                    }
                    IconButton(onClick = {
                        MenuStore.update(context) { list ->
                            list.map { g ->
                                if (g.id == groupId) g.copy(items = g.items.filterNot { it.id == item.id }) else g
                            }
                        }
                    }) { Icon(Icons.Filled.Delete, "Delete item", tint = NColors.Dim) }
                }
            }
            if (result.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(result, color = NColors.Dim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun displayValue(type: GroupType, item: MenuItem): String = when (type) {
    GroupType.TOGGLES -> ToggleKind.values().firstOrNull { it.name == item.value }?.label ?: item.value
    GroupType.CUSTOM -> (if (item.silent) "silent GET  " else "open link  ") + item.value
    GroupType.PODCAST -> if (item.episode.isBlank()) item.value else "last: " + item.episode
    else -> item.value
}

// =================================================================================================
// Add flow: purpose -> pick -> names
// =================================================================================================

@Composable
fun AddFlowScreen(addToGroupId: String?, onBack: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val existing = remember(addToGroupId) { addToGroupId?.let { MenuStore.group(it) } }
    val firstStep = if (existing != null) 1 else 0

    var step by remember { mutableIntStateOf(firstStep) }
    var type by remember { mutableStateOf(existing?.type ?: GroupType.MUSIC) }
    var typeMenu by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateListOf<MenuItem>() }
    var urlInput by remember { mutableStateOf("") }
    val isUrlType = type == GroupType.MUSIC || type == GroupType.PODCAST || type == GroupType.CUSTOM

    fun back() {
        if (step > firstStep) step-- else onBack()
    }
    BackHandler { back() }

    val scope = rememberCoroutineScope()
    var importMsg by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf<String?>(null) }

    // Podcast setup is deliberately a short, separate two-link flow: show first, current episode second.
    var podcastInput by remember { mutableStateOf("") }
    var podcastEpisodeInput by remember { mutableStateOf("") }
    var podcastFeed by remember { mutableStateOf<PodcastFeed.Feed?>(null) }
    var podcastBusy by remember { mutableStateOf(false) }
    var podcastError by remember { mutableStateOf("") }
    var podcastMatchChecked by remember { mutableStateOf(false) }
    var podcastSelectedIndex by remember { mutableIntStateOf(-1) }
    var podcastShown by remember { mutableIntStateOf(15) }
    var podcastSearch by remember { mutableStateOf("") }
    var askTrim by remember { mutableStateOf(false) }
    var podcastTrim by remember { mutableStateOf(true) }

    fun addUrl() {
        val u = urlInput.trim()
        if (u.isNotEmpty() && type == GroupType.MUSIC && Playlists.isPlaylist(u)) {
            urlInput = ""
            importMsg = "Reading playlist..."
            scope.launch {
                val r = Playlists.fetch(u)
                if (r.error != null) importMsg = r.error
                else {
                    drafts.addAll(r.items)
                    if (existing == null && playlistName == null) playlistName = r.name
                    importMsg = "Added ${r.items.size} songs from ${r.name}"
                }
            }
        } else if (u.isNotEmpty()) {
            val placeholder = "${when (type) { GroupType.MUSIC -> "Song"; GroupType.PODCAST -> "Podcast"; else -> "Link" }} ${drafts.size + 1}"
            val item = MenuItem(name = placeholder, value = u)
            drafts.add(item)
            urlInput = ""
            scope.launch {
                val t = LinkInfo.title(u)
                val i = drafts.indexOfFirst { it.id == item.id }
                // Only fill the name if the user has not changed it meanwhile.
                if (!t.isNullOrBlank() && i >= 0 && drafts[i].name == placeholder) drafts[i] = drafts[i].copy(name = t)
            }
        }
    }

    fun finish() {
        val allEpisodes = podcastFeed?.episodes.orEmpty()
        val podcastCurrent = allEpisodes.getOrNull(podcastSelectedIndex)
        val keepFrom = if (podcastTrim) podcastSelectedIndex.coerceAtLeast(0) else 0
        val keepTo = minOf(allEpisodes.size, podcastSelectedIndex.coerceAtLeast(0) + MediaMenuConfig.podcastLoadCount(context))
        val podcastEpisodeList = if (podcastCurrent != null) allEpisodes.subList(keepFrom, keepTo) else allEpisodes
        val items = if (type == GroupType.PODCAST && podcastFeed != null && podcastCurrent != null) {
            listOf(
                MenuItem(
                    name = podcastFeed!!.title.ifBlank { "Podcast" },
                    value = podcastInput.trim(),
                    episode = podcastCurrent.title,
                    podcastFeedUrl = podcastFeed!!.feedUrl,
                    podcastEpisodes = PodcastFeed.encodeEpisodes(podcastEpisodeList),
                    podcastIndex = podcastSelectedIndex - keepFrom,
                    podcastCurrentUrl = podcastCurrent.link.ifBlank { podcastCurrent.audioUrl },
                    podcastPlayerHint = ""
                )
            )
        } else drafts.map { it.copy(name = it.name.ifBlank { "Item" }) }

        if (existing != null) {
            MenuStore.update(context) { list ->
                list.map { if (it.id == existing.id) it.copy(items = it.items + items) else it }
            }
            onDone(existing.id)
        } else {
            val groupName = if (type == GroupType.PODCAST) "Podcast" else playlistName ?: purposeLabel(type)
            val group = MenuGroup(type = type, name = groupName, items = items)
            MenuStore.update(context) { it + group }
            onDone(group.id)
        }
    }

    val canNext = when {
        step == 0 -> true
        type == GroupType.PODCAST && step == 1 -> podcastInput.isNotBlank() && !podcastBusy
        type == GroupType.PODCAST && step == 2 -> !podcastBusy && (podcastSelectedIndex >= 0 || podcastEpisodeInput.isNotBlank())
        step == 1 -> when (type) {
            GroupType.MUSIC, GroupType.CUSTOM -> drafts.isNotEmpty() || urlInput.isNotBlank()
            GroupType.PODCAST, GroupType.APPS, GroupType.TOGGLES -> false
        }
        else -> drafts.isNotEmpty()
    }

    fun next() {
        if (type == GroupType.PODCAST) {
            when (step) {
                0 -> step = 1
                1 -> {
                    if (podcastBusy) return
                    podcastBusy = true
                    podcastError = ""
                    scope.launch {
                        val result = PodcastFeed.load(podcastInput)
                        podcastBusy = false
                        if (result.feed == null) {
                            podcastError = result.error ?: "Couldn't read that podcast."
                        } else {
                            podcastFeed = result.feed
                            podcastMatchChecked = false
                            podcastSelectedIndex = -1
                            podcastShown = 15
                            step = 2
                        }
                    }
                }
                2 -> if (podcastSelectedIndex >= 0) askTrim = true
                else -> finish()
            }
            return
        }

        when (step) {
            0 -> step = 1
            1 -> {
                when (type) {
                    GroupType.MUSIC, GroupType.CUSTOM -> addUrl()
                    GroupType.PODCAST, GroupType.APPS, GroupType.TOGGLES -> Unit
                }
                step = 2
            }
            else -> finish()
        }
    }

    if (askTrim) {
        AlertDialog(
            onDismissRequest = { askTrim = false },
            containerColor = NColors.Card,
            title = { Text("Delete everything before this episode?", color = NColors.Text, fontFamily = NType) },
            confirmButton = {
                TextButton(onClick = { podcastTrim = true; askTrim = false; finish() }) { Text("Delete", color = NColors.Text, fontFamily = NType) }
            },
            dismissButton = {
                TextButton(onClick = { podcastTrim = false; askTrim = false; finish() }) { Text("Keep", color = NColors.Dim, fontFamily = NType) }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        NTopBar(
            title = "",
            onBack = { back() },
            trailing = {
                NButton(
                    text = if ((type == GroupType.PODCAST && step == 2 && podcastSelectedIndex >= 0) || (type != GroupType.PODCAST && step == 2)) "Save" else "Next",
                    onClick = { next() },
                    enabled = canNext,
                    primary = true
                )
            }
        )

        when {
            type == GroupType.PODCAST && step == 1 -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            ) {
                Text("Podcast URL", color = NColors.Text, fontFamily = NType, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                NField(value = podcastInput, onValueChange = { podcastInput = it }, label = "URL")
                if (podcastError.isNotBlank()) {
                    Text(podcastError, color = NColors.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            type == GroupType.PODCAST && step == 2 -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                val feed = podcastFeed
                Text("Choose episode", color = NColors.Text, fontFamily = NType, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                NField(value = podcastSearch, onValueChange = { podcastSearch = it }, label = "Search")
                if (feed != null) {
                    Spacer(Modifier.height(10.dp))
                    val newestFirst = feed.episodes.indices.reversed()
                    val shownIdx = if (podcastSearch.isBlank()) newestFirst.take(podcastShown)
                    else newestFirst.filter { feed.episodes[it].title.contains(podcastSearch.trim(), ignoreCase = true) }.take(60)
                    LazyColumn(Modifier.weight(1f)) {
                        items(shownIdx.size) { n ->
                            val index = shownIdx[n]
                            val episode = feed.episodes[index]
                            NCard(Modifier.padding(bottom = 6.dp).clickable { podcastSelectedIndex = index }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(26.dp)) {
                                        if (podcastSelectedIndex == index) Icon(Icons.Filled.Check, null, tint = NColors.Text, modifier = Modifier.size(18.dp))
                                    }
                                    Text(episode.title, color = NColors.Text, fontFamily = NType, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (podcastSearch.isBlank() && podcastShown < feed.episodes.size) {
                            item {
                                NButton("More", onClick = { podcastShown += 15 })
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }

            else -> when (step) {
            0 -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Text("Type", color = NColors.Text, fontFamily = NType, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                listOf(GroupType.MUSIC, GroupType.PODCAST, GroupType.CUSTOM).forEach { t ->
                    val chosen = t == type
                    NCard(
                        Modifier.padding(bottom = 8.dp).clickable {
                            if (t != type) drafts.clear()
                            type = t
                        }
                    ) {
                        Text(
                            purposeLabel(t),
                            color = if (chosen) NColors.Text else NColors.Dim,
                            fontFamily = NDot, fontSize = 20.sp
                        )
                    }
                }
            }

            1 -> when (type) {
                GroupType.MUSIC, GroupType.PODCAST, GroupType.CUSTOM -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                ) {
                    Text(
                        when (type) { GroupType.MUSIC -> "Music URL"; GroupType.PODCAST -> "Podcast URL"; else -> "Custom URL" },
                        color = NColors.Text, fontFamily = NType, fontSize = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    NField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = "URL",
                        trailing = {
                            IconButton(onClick = { addUrl() }) { Icon(Icons.Filled.Check, "Add", tint = NColors.Green) }
                        }
                    )
                    if (type == GroupType.MUSIC && urlInput.contains("music.youtube.com") && urlInput.contains("list=")) {
                        Text(
                            "YouTube Music playlists must be Public, with \"Everyone can vote\" turned on, or the songs may not load.",
                            color = NColors.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (importMsg.isNotEmpty()) Text(importMsg, color = NColors.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(12.dp))
                    drafts.forEachIndexed { idx, d ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .background(NColors.Card, RoundedCornerShape(12.dp))
                                .padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                d.value, color = NColors.Text, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { drafts.removeAt(idx) }) {
                                Icon(Icons.Filled.Delete, "Remove", tint = NColors.Dim)
                            }
                        }
                    }
                }

                GroupType.APPS, GroupType.TOGGLES -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Text("This item type is no longer supported.", color = NColors.Dim, fontFamily = NType, fontSize = 13.sp)
                }

            }

            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            ) {
                Text("Names (shown on the watch)", color = NColors.Text, fontFamily = NType, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                drafts.forEachIndexed { idx, d ->
                    NCard(Modifier.padding(bottom = 8.dp)) {
                        NField(
                            value = d.name,
                            onValueChange = { drafts[idx] = d.copy(name = it) },
                            label = "Name"
                        )
                        if (isUrlType) {
                            Spacer(Modifier.height(6.dp))
                            NField(
                                value = d.value,
                                onValueChange = { drafts[idx] = d.copy(value = it) },
                                label = "URL"
                            )
                        }
                        if (type == GroupType.CUSTOM) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = d.silent,
                                    onCheckedChange = { drafts[idx] = d.copy(silent = it) },
                                    colors = nSwitchColors()
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Send silently (GET request) instead of opening", color = NColors.Dim, fontSize = 12.sp)
                            }
                        }
                        if (!isUrlType) {
                            Text(displayValue(type, d), style = smallLabel(), modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    Box(
        Modifier.size(18.dp).background(
            if (selected) NColors.Green else NColors.CardHi,
            RoundedCornerShape(50)
        ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Box(Modifier.size(6.dp).background(NColors.Bg, RoundedCornerShape(50)))
    }
}

// =================================================================================================
// Setup
// =================================================================================================

private fun tokenIcon(token: String): ImageVector = when (token) {
    "PAUSE" -> Icons.Filled.Pause
    "PLAY" -> Icons.Filled.PlayArrow
    "NEXT" -> Icons.Filled.SkipNext
    else -> Icons.Filled.SkipPrevious
}

private fun nextToggleToken(seq: List<String>): String =
    if (seq.lastOrNull { it == "PLAY" || it == "PAUSE" } == "PAUSE") "PLAY" else "PAUSE"

@Composable
fun SeqEditor(title: String, hint: String, text: String, firstMustBeSkip: Boolean, onChange: (String) -> Unit) {
    val seq = text.split(",").map { it.trim().uppercase() }.filter { it in setOf("PLAY", "PAUSE", "NEXT", "PREV") }
    fun set(list: List<String>) = onChange(list.joinToString(","))
    val toggleToken = nextToggleToken(seq)

    NCard(Modifier.padding(bottom = 8.dp)) {
        Text(title, color = NColors.Text, fontFamily = NType, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (seq.isEmpty()) Text("(empty)", color = NColors.Dim, fontSize = 12.sp)
            seq.forEach { t ->
                Box(
                    Modifier.size(44.dp).background(NColors.CardHi, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(tokenIcon(t), t, tint = NColors.Text) }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val full = seq.size >= 8
            IconButton(onClick = { set(seq + "PREV") }, enabled = !full) {
                Icon(Icons.Filled.SkipPrevious, "Previous", tint = NColors.Text)
            }
            // One button: it adds pause or play, whichever is the valid next press.
            IconButton(onClick = { set(seq + toggleToken) }, enabled = !full && !(firstMustBeSkip && seq.isEmpty())) {
                Icon(tokenIcon(toggleToken), "Play or pause", tint = if (firstMustBeSkip && seq.isEmpty()) NColors.Dim else NColors.Text)
            }
            IconButton(onClick = { set(seq + "NEXT") }, enabled = !full) {
                Icon(Icons.Filled.SkipNext, "Next", tint = NColors.Text)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (seq.isNotEmpty()) set(seq.dropLast(1)) }) {
                Icon(Icons.Filled.Close, "Remove last", tint = NColors.Dim)
            }
            IconButton(onClick = { set(emptyList()) }) {
                Icon(Icons.Filled.Delete, "Clear", tint = NColors.Dim)
            }
        }
        if (seq.size < 3) {
            Text("At least 3", color = NColors.Red, fontSize = 12.sp)
        } else if (firstMustBeSkip && (seq.first() == "PLAY" || seq.first() == "PAUSE")) {
            Text("Start with previous or next", color = NColors.Red, fontSize = 12.sp)
        }
    }
}

@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ignored by remember { mutableStateOf(MediaMenuConfig.ignoredApps(context)) }
    var showPicker by remember { mutableStateOf(false) }
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = page != "") { page = "" }
    val launchable by produceState<List<LaunchableApp>>(emptyList()) {
        value = withContext(Dispatchers.IO) { AppLauncherHelper.getLaunchableApps(context) }
    }

    androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = NType)) {
        Column(Modifier.fillMaxSize()) {
            NPageHeader(
                title = when (page) {
                    "ignore" -> "Ignore"
// WATCHMENU_REMOVE_NOTIFICATIONS_SETTING_TITLE
// WATCHMENU_REMOVE_WEATHER_SETTING_TITLE
                    "alarms" -> "Alarms"
                    "perm" -> "Permissions"
                    else -> "Settings"
                },
                onBack = { if (page == "") onBack() else page = "" }
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                when (page) {
                    "" -> {
                        SSection("Phone")
                        SGroup(
                            { SRow("Ignore", Icons.Filled.Block, onClick = { page = "ignore" }) },
// WATCHMENU_REMOVE_NOTIFICATIONS_SETTING_ROW
// WATCHMENU_REMOVE_WEATHER_SETTING_ROW
                            { SRow("Alarms", Icons.Filled.Alarm, onClick = { page = "alarms" }) },
                            { SRow("Permissions", Icons.Filled.Security, onClick = { page = "perm" }) }
                        )
                    }
                    "ignore" -> {
                        // Apps that have a media session right now: one tap puts them on the ignore list.
                        val seen = MediaMenuState.seenPlayers.collectAsState().value.filter { it !in ignored }
// WATCHMENU_REMOVE_IGNORE_EXPLANATION
                        if (seen.isNotEmpty()) {
                            SSection("Playing now")
                            SGroup(*seen.map { pkg ->
                                @Composable {
                                    SRow("Ignore " + (launchable.firstOrNull { it.packageName == pkg }?.label ?: pkg), Icons.Filled.Block, onClick = {
                                        ignored = ignored + pkg
                                        MediaMenuConfig.setIgnoredApps(context, ignored)
                                        MediaMenuService.instance?.reattachObservers()
                                    }, chevron = false)
                                }
                            }.toTypedArray())
                            SSection("Ignored")
                        }
                        val rows = ignored.map { pkg ->
                            @Composable {
                                SRow(launchable.firstOrNull { it.packageName == pkg }?.label ?: pkg, onClick = {
                                    ignored = ignored - pkg
                                    MediaMenuConfig.setIgnoredApps(context, ignored)
                                    MediaMenuService.instance?.reattachObservers()
                                }, chevron = false) { Icon(Icons.Filled.Close, null, tint = NColors.Dim) }
                            }
                        }
                        SGroup(*(rows + @Composable {
                            SRow("Add", Icons.Filled.Add, onClick = { showPicker = true }, chevron = false)
                        }).toTypedArray())
                    }
// WATCHMENU_REMOVE_NOTIFICATIONS_SETTING_PAGE
// WATCHMENU_REMOVE_WEATHER_SETTING_PAGE
                    "alarms" -> AlarmsPage()
                    else -> PermissionRows()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showPicker) {
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Done", color = NColors.Text, fontFamily = NType) } },
            containerColor = NColors.Card,
            text = {
                Column {
                    NField(value = q, onValueChange = { q = it }, label = "Search or type a package name")
                    val typed = q.trim()
                    if (typed.contains('.') && !typed.contains(' ') && launchable.none { it.packageName == typed } && typed !in ignored) {
                        TextButton(onClick = {
                            ignored = ignored + typed
                            MediaMenuConfig.setIgnoredApps(context, ignored)
                            MediaMenuService.instance?.reattachObservers()
                            q = ""
                        }) { Text("Add $typed", color = NColors.Text, fontFamily = NDot) }
                    }
                    LazyColumn(Modifier.height(360.dp)) {
                        items(launchable.filter { it.label.contains(q, ignoreCase = true) }, key = { it.packageName }) { app ->
                            val on = app.packageName in ignored
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    ignored = if (on) ignored - app.packageName else ignored + app.packageName
                                    MediaMenuConfig.setIgnoredApps(context, ignored)
                                    MediaMenuService.instance?.reattachObservers()
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = on, onCheckedChange = null, colors = nCheckboxColors())
                                Spacer(Modifier.width(8.dp))
                                Text(app.label, color = NColors.Text, fontFamily = NType, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, colors = nSwitchColors())
        Spacer(Modifier.width(8.dp))
        Text(label, color = NColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))


/** Shows the episode the app thinks you are on, and lets you pick the right one. */
@Composable
fun EpisodeFixDialog(item: MenuItem, groupId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val episodes = remember(item.podcastEpisodes) { PodcastFeed.decodeEpisodes(item.podcastEpisodes) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = NColors.Card,
        title = { Text(item.episode.ifBlank { item.name }, color = NColors.Text, fontFamily = NType, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(episodes.size) { n ->
                    val index = episodes.size - 1 - n
                    val e = episodes[index]
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            MenuStore.update(context) { list ->
                                list.map { g ->
                                    if (g.id == groupId) g.copy(items = g.items.map {
                                        if (it.id == item.id) it.copy(
                                            episode = e.title, podcastIndex = index,
                                            podcastCurrentUrl = e.link.ifBlank { e.audioUrl },
                                            podcastPositionMs = 0L, podcastDurationMs = 0L
                                        ) else it
                                    }) else g
                                }
                            }
                            onClose()
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(26.dp)) {
                            if (index == item.podcastIndex) Icon(Icons.Filled.Check, null, tint = NColors.Text, modifier = Modifier.size(18.dp))
                        }
                        Text(e.title, color = NColors.Text, fontFamily = NType, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = NColors.Dim, fontFamily = NType) } }
    )
}
