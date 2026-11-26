
package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Watch Replies has its own ignore list, separate from Settings > Ignore. */
@Composable
fun WatchRepliesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var ignored by remember { mutableStateOf(RepliesIgnoreConfig.ignored(context)) }

    BackHandler(enabled = editing) { editing = false }

    Column(Modifier.fillMaxSize()) {
        NTopBar(
            title = "Watch Replies",
                onBack = { if (editing) editing = false else onBack() }
        )

        Column(
            Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
        ) {
            if (!editing) {
                SGroup(
                    {
                        SRow(
                            "Apps to ignore",
                             Icons.Filled.Block,
                             onClick = { editing = true }
                        )
                    }
                )
            } else {
                val rows = RepliesIgnoreConfig.groups.map { group ->
                    @Composable {
                        val checked = group.packages.any { it in ignored }

                        SSwitchRow(group.label, checked) { enabled ->
                            ignored = if (enabled) {
                                ignored + group.packages
                            } else {
                                ignored - group.packages
                            }

                            RepliesIgnoreConfig.setIgnored(context, ignored)
                        }
                    }
                }

                SGroup(*rows.toTypedArray())
            }
        }
    }
}
