package org.zzssg.llmchatapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.zzssg.llmchatapp.data.ChatSummary
import org.zzssg.llmchatapp.ui.theme.Spacing
import java.util.Calendar
import java.util.Locale

/**
 * Conversation history and the primary destinations.
 *
 * A drawer rather than a menu: history is a list that grows without bound, and a
 * dropdown cannot carry a scrollable list, per-row delete, and date grouping. The
 * top-left control that used to be a bare "new chat" button now opens this, so a
 * single tap reaches both past conversations and a fresh one.
 */
@Composable
fun ChatDrawer(
    chats: List<ChatSummary>,
    activeChatId: String?,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pendingDeletion by remember { mutableStateOf<ChatSummary?>(null) }

    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
    ) {
        Column(Modifier.statusBarsPadding()) {
            Text(
                text = "Chats",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = Spacing.xl, top = Spacing.lg, bottom = Spacing.md),
            )

            NavigationDrawerItem(
                label = { Text("New chat") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                selected = activeChatId == null,
                onClick = onNewChat,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )

            if (chats.isEmpty()) {
                EmptyHistory()
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    // Grouping by recency turns a flat list into something
                    // scannable once a few days of history accumulate.
                    val grouped = chats.groupBy { it.updatedAt.recencyBucket() }
                    RecencyBucket.entries.forEach { bucket ->
                        val bucketChats = grouped[bucket] ?: return@forEach

                        item(key = "header-${bucket.name}") {
                            Text(
                                text = bucket.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = Spacing.xl,
                                    top = Spacing.lg,
                                    bottom = Spacing.xs,
                                ),
                            )
                        }

                        items(bucketChats, key = { it.id }) { chat ->
                            ChatRow(
                                chat = chat,
                                selected = chat.id == activeChatId,
                                onOpen = { onOpenChat(chat.id) },
                                onDelete = { pendingDeletion = chat },
                            )
                        }
                    }
                }
            }

            HorizontalRule()

            NavigationDrawerItem(
                label = { Text("Models") },
                icon = { Icon(Icons.Outlined.Layers, contentDescription = null) },
                selected = false,
                onClick = onOpenModels,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
            NavigationDrawerItem(
                label = { Text("Settings") },
                icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                selected = false,
                onClick = onOpenSettings,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
            Spacer(Modifier.height(Spacing.md))
        }
    }

    pendingDeletion?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${chat.title}\" will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteChat(chat.id)
                    pendingDeletion = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ChatRow(
    chat: ChatSummary,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Text(
                text = chat.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        icon = { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null) },
        badge = {
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete ${chat.title}",
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        selected = selected,
        onClick = onOpen,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Your conversations will appear here",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HorizontalRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

private enum class RecencyBucket(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("Earlier this week"),
    OLDER("Older"),
}

private fun Long.recencyBucket(): RecencyBucket {
    if (this <= 0L) return RecencyBucket.OLDER

    val startOfToday = Calendar.getInstance(Locale.getDefault()).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val day = 24L * 60 * 60 * 1000
    return when {
        this >= startOfToday -> RecencyBucket.TODAY
        this >= startOfToday - day -> RecencyBucket.YESTERDAY
        this >= startOfToday - 7 * day -> RecencyBucket.THIS_WEEK
        else -> RecencyBucket.OLDER
    }
}
