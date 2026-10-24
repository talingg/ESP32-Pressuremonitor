package com.example.pressuremonitor

/*
  Lists previously logged sessions, with file-explorer-style multi-select
  deletion:
    - Long-press an entry to enter selection mode (selects that entry)
    - Tap other entries to add/remove them from the selection
    - Tap the trash icon to delete the selected entries (with a confirm dialog)
    - Tap the X icon, "Cancel", or the system back gesture to exit
      selection mode without deleting anything
*/

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoggedSessionsScreen(
    onSessionClick: (Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var sessionIds by remember { mutableStateOf(listOf<Long>()) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val isSelectionMode = selectedIds.isNotEmpty()

    fun refreshSessions() {
        sessionIds = SessionStorage.listSessions(context)
    }

    // Re-scan the sessions folder each time this screen is shown.
    LaunchedEffect(Unit) {
        refreshSessions()
    }

    // Pressing system back while selecting exits selection mode instead of
    // leaving the screen - matches common file-explorer behavior.
    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
    }

    val formatter = remember { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Text(text = "${selectedIds.size} selected", fontSize = 20.sp)
                Row {
                    IconButton(onClick = { selectedIds = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                    }
                }
            } else {
                Text(text = "Logged Sessions", fontSize = 20.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (sessionIds.isEmpty()) {
            Text(
                text = "No sessions recorded yet.",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sessionIds, key = { it }) { id ->
                    val isSelected = selectedIds.contains(id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.background
                            )
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedIds = if (isSelected) selectedIds - id else selectedIds + id
                                    } else {
                                        onSessionClick(id)
                                    }
                                },
                                onLongClick = {
                                    selectedIds = selectedIds + id
                                }
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelectionMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    selectedIds = if (isSelected) selectedIds - id else selectedIds + id
                                }
                            )
                        }
                        Text(text = formatter.format(Date(id)))
                    }
                    HorizontalDivider()
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (isSelectionMode) {
                    selectedIds = emptySet()
                } else {
                    onBack()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSelectionMode) "Cancel" else "Back")
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete sessions?") },
            text = {
                Text(
                    "This will permanently delete ${selectedIds.size} recorded session(s). " +
                            "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SessionStorage.deleteSessions(context, selectedIds)
                    selectedIds = emptySet()
                    showDeleteConfirm = false
                    refreshSessions()
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}