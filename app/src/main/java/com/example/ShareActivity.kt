package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Receives "Share" from a music app and adds the song to a music group. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)
        MenuStore.init(applicationContext)
        enableEdgeToEdge()
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = LinkInfo.extractUrl(shared).orEmpty()
        // Text before the link is often the song name.
        val hint = shared.replace(url, "").trim().trim('-', '–', '|', ':').trim()

        setContent {
            NothingTheme {
                val groups = MenuStore.groups.value.filter { it.type == GroupType.MUSIC }
                var name by remember { mutableStateOf(hint) }
                var link by remember { mutableStateOf(url) }
                var chosen by remember { mutableStateOf(groups.firstOrNull()?.id ?: "NEW") }
                var loading by remember { mutableStateOf(url.isNotEmpty()) }
                val isList = url.isNotEmpty() && Playlists.isPlaylist(url)
                var playlist by remember { mutableStateOf<Playlists.Result?>(null) }

                LaunchedEffect(Unit) {
                    if (isList) {
                        playlist = Playlists.fetch(url)
                        loading = false
                    } else if (url.isNotEmpty()) {
                        val t = LinkInfo.title(url)
                        if (!t.isNullOrBlank()) name = t
                        loading = false
                    }
                }

                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    NTopBar(title = if (isList) "Add playlist" else "Add song", onBack = { finish() })
                    if (isList) Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        val pl = playlist
                        NCard(Modifier.padding(bottom = 12.dp)) {
                            Text(
                                when {
                                    loading -> "Reading playlist..."
                                    pl?.error != null -> pl.error
                                    else -> "${pl?.name}  (${pl?.items?.size} songs)"
                                },
                                color = NColors.Text, fontFamily = NDot, fontSize = 18.sp
                            )
                        }
                        if (url.contains("music.youtube.com")) {
                            Text(
                                "YouTube Music playlists must be Public, with \"Everyone can vote\" turned on, or the songs may not load.",
                                color = NColors.Text, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                        NButton(
                            text = "Save as a new group",
                            enabled = pl != null && pl.error == null && pl.items.isNotEmpty(),
                            primary = true,
                            onClick = {
                                if (pl != null) {
                                    MenuStore.update(applicationContext) { list ->
                                        list + MenuGroup(type = GroupType.MUSIC, name = pl.name, items = pl.items)
                                    }
                                    Toast.makeText(this@ShareActivity, "Added ${pl.items.size} songs", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }
                        )
                    } else Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                    ) {
                        NField(value = name, onValueChange = { name = it }, label = if (loading) "Name (looking it up...)" else "Name")
                        Spacer(Modifier.height(8.dp))
                        NField(value = link, onValueChange = { link = it }, label = "Link")
                        Spacer(Modifier.height(12.dp))
                        groups.forEach { g ->
                            NCard(Modifier.padding(bottom = 8.dp).clickable { chosen = g.id }) {
                                Text(
                                    g.name,
                                    color = if (chosen == g.id) NColors.Text else NColors.Dim,
                                    fontFamily = NDot, fontSize = 20.sp
                                )
                            }
                        }
                        NCard(Modifier.padding(bottom = 8.dp).clickable { chosen = "NEW" }) {
                            Text(
                                if (groups.isEmpty()) "Music (new group)" else "New group",
                                color = if (chosen == "NEW") NColors.Text else NColors.Dim,
                                fontFamily = NDot, fontSize = 20.sp
                            )
                        }
                        NButton(
                            text = "Save",
                            enabled = link.isNotBlank(),
                            primary = true,
                            onClick = {
                                val item = MenuItem(name = name.ifBlank { "Song" }, value = link.trim())
                                MenuStore.update(applicationContext) { list ->
                                    if (chosen != "NEW" && list.any { it.id == chosen }) {
                                        list.map { if (it.id == chosen) it.copy(items = it.items + item) else it }
                                    } else {
                                        list + MenuGroup(type = GroupType.MUSIC, name = "Music", items = listOf(item))
                                    }
                                }
                                Toast.makeText(this@ShareActivity, "Added: ${item.name}", Toast.LENGTH_SHORT).show()
                                finish()
                            },
                            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                        )
                    }
                }
            }
        }
    }
}
