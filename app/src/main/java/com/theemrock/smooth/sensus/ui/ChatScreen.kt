package com.theemrock.smooth.sensus.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theemrock.smooth.sensus.conversation.ChatConversation
import com.theemrock.smooth.sensus.conversation.ChatMessage
import com.theemrock.smooth.sensus.conversation.MessageRole
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale

@Composable fun ChatRoute(viewModel: ChatViewModel, onBack: () -> Unit, onOpenLocalModel: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    ChatScreen(state, viewModel::send, viewModel::delete, viewModel::edit, viewModel::editAndSend, viewModel::fork,
        viewModel::startReply, viewModel::cancelReply, viewModel::newConversation,
        viewModel::selectConversation, viewModel::deleteConversation, viewModel::clearError, onBack, onOpenLocalModel,
        onDraftChange = viewModel::updateDraft)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onEditAndSend: (String, String) -> Unit,
    onFork: (String) -> Unit,
    onReply: (String) -> Unit,
    onCancelReply: () -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
    onOpenLocalModel: (() -> Unit)? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    elapsedRealtimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    onDraftChange: (String) -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed); val scope = rememberCoroutineScope()
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val conversationListStates = remember { mutableMapOf<String, ChatListMemory>() }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp)) {
                ChatDrawer(state, onNewConversation = { onNewConversation(); scope.launch { drawerState.close() } },
                    onSelectConversation = { onSelectConversation(it); scope.launch { drawerState.close() } },
                    onDeleteConversation = onDeleteConversation,
                    onOpenLocalModel = onOpenLocalModel?.let { open -> { open(); scope.launch { drawerState.close() }; Unit } })
            }
        }
    ) {
        val editing = editingMessage
        if (editing != null) {
            EditMessageScreen(
                message = editing,
                busy = state.isQueued || state.isGenerating,
                onCancel = { editingMessage = null },
                onSave = { content -> onEdit(editing.id, content); editingMessage = null },
                onEditAndSend = { content -> onEditAndSend(editing.id, content); editingMessage = null }
            )
        } else Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, contentDescription = "Sohbet menüsü") }
                Text(
                    state.conversations.firstOrNull { it.id == state.selectedConversationId }?.title ?: "Chat",
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            HorizontalDivider()
            var sentFromComposer by remember(state.selectedConversationId) { mutableStateOf(false) }
            ChatMessageList(
                state = state,
                modifier = Modifier.weight(1f),
                sentFromComposer = sentFromComposer,
                retainedStates = conversationListStates,
                zoneId = zoneId,
                locale = locale,
                elapsedRealtimeMs = elapsedRealtimeMs,
                onSendScrollHandled = { sentFromComposer = false },
                onDelete = onDelete,
                onEdit = { editingMessage = it },
                onFork = onFork,
                onReply = onReply
            )
            state.errorMessage?.let { error ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)); TextButton(onClick = onClearError) { Text("Kapat") } }
            }
            if (state.isQueued) {
                Text("Sırada", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Composer(
                draft = state.draft,
                reply = state.replyTarget,
                busy = state.isQueued || state.isGenerating,
                onDraftChange = onDraftChange,
                onCancelReply = onCancelReply,
            ) { content ->
                sentFromComposer = true
                onSend(content)
            }
        }
    }
}

@Composable private fun ChatMessageList(
    state: ChatUiState, modifier: Modifier, sentFromComposer: Boolean,
    retainedStates: MutableMap<String, ChatListMemory>,
    zoneId: ZoneId,
    locale: Locale,
    elapsedRealtimeMs: () -> Long,
    onSendScrollHandled: () -> Unit, onDelete: (String) -> Unit,
    onEdit: (ChatMessage) -> Unit, onFork: (String) -> Unit, onReply: (String) -> Unit
) {
    val conversationKey = state.selectedConversationId ?: "__unselected__"
    val memory = remember(conversationKey) {
        retainedStates.getOrPut(conversationKey) { ChatListMemory(LazyListState()) }
    }
    val listState = memory.listState
    val timeline = remember(state.messages, zoneId, locale) { buildChatTimeline(state.messages, zoneId, locale) }
    val messagesById = remember(state.messages) { state.messages.associateBy(ChatMessage::id) }
    val generatingHere = state.isGenerating && state.generatingConversationId == state.selectedConversationId
    val tip = state.messages.lastOrNull()?.id
    // Track viewport movement, not message-count changes. Appending a response must
    // use the reader's position before the append, rather than its new distance away.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.index to it.offset } }
            .drop(1).collect {
                val layout = listState.layoutInfo
                memory.followBottom = (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 2
            }
    }
    LaunchedEffect(tip, generatingHere) {
        val newTip = tip != null && tip != memory.previousTip
        if (tip != null && (!memory.positioned || (newTip && (memory.followBottom || sentFromComposer)) || (generatingHere && memory.followBottom))) {
            val lastIndex = timeline.lastIndex + if (generatingHere) 1 else 0
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
            memory.positioned = true
            memory.followBottom = true
            if (newTip && sentFromComposer) onSendScrollHandled()
        }
        memory.previousTip = tip
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val currentImeVisible by rememberUpdatedState(imeVisible)
    val dismissImeOnFling = remember(keyboard, focus) {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (currentImeVisible && available.y < -1_200f) {
                    focus.clearFocus(force = true)
                    keyboard?.hide()
                }
                return Velocity.Zero
            }
        }
    }
    LazyColumn(
        modifier.fillMaxWidth().nestedScroll(dismissImeOnFling).padding(horizontal = 12.dp)
            .alpha(if (state.messages.isEmpty() || memory.positioned) 1f else 0f),
        state = listState,
        contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = timeline,
            key = {
                when (it) {
                    is ChatTimelineItem.DateSeparator -> "date-${it.epochDay}"
                    is ChatTimelineItem.Message -> "message-${it.value.id}"
                }
            }
        ) { item ->
            when (item) {
                is ChatTimelineItem.DateSeparator -> DateSeparator(item.label)
                is ChatTimelineItem.Message -> {
                    val message = item.value
                    MessageBubble(
                        message = message,
                        replyTarget = message.replyToMessageId?.let(messagesById::get),
                        responseDurationMs = state.responseDurationsMs[message.id],
                        zoneId = zoneId,
                        locale = locale,
                        onDelete = onDelete,
                        onEdit = { onEdit(message) },
                        onFork = onFork,
                        onReply = onReply
                    )
                }
            }
        }
        if (generatingHere) item(key = "generating-$conversationKey") {
            state.generationStartedAtElapsedMs?.let { startedAt ->
                LiveResponseTimer(startedAt, locale, elapsedRealtimeMs)
            }
        }
    }
}

private class ChatListMemory(val listState: LazyListState) {
    var positioned by mutableStateOf(false)
    var followBottom by mutableStateOf(true)
    var previousTip by mutableStateOf<String?>(null)
}

@Composable private fun DateSeparator(label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable private fun LiveResponseTimer(startedAt: Long, locale: Locale, elapsedRealtimeMs: () -> Long) {
    var elapsedMs by remember(startedAt) { mutableLongStateOf((elapsedRealtimeMs() - startedAt).coerceAtLeast(0)) }
    LaunchedEffect(startedAt, elapsedRealtimeMs) {
        while (true) {
            elapsedMs = (elapsedRealtimeMs() - startedAt).coerceAtLeast(0)
            delay(100)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                formatResponseDuration(elapsedMs, locale),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable private fun ChatDrawer(
    state: ChatUiState, onNewConversation: () -> Unit, onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit, onOpenLocalModel: (() -> Unit)?
) {
    var chats by remember { mutableStateOf(true) }
    var projects by remember { mutableStateOf(false) }
    var instructions by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { DrawerSectionHeader("SOHBETLER", chats) { chats = !chats } }
        if (chats) {
            item { TextButton(onClick = onNewConversation, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Yeni sohbet")
            } }
            items(state.conversations, key = ChatConversation::id) { conversation ->
                ConversationRow(conversation, conversation.id == state.selectedConversationId, conversation.id in state.unreadConversationIds,
                    onClick = { onSelectConversation(conversation.id) }, onDelete = { onDeleteConversation(conversation.id) })
            }
            item { ComingSoonRow("Gizli Sohbetler") }
        }
        item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item { DrawerSectionHeader("PROJELER", projects) { projects = !projects } }
        if (projects) item { ComingSoonRow("Yeni proje", add = true) }
        item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item { DrawerSectionHeader("TALİMATLAR", instructions) { instructions = !instructions } }
        if (instructions) {
            item { ComingSoonRow("Genel") }
            item { ComingSoonRow("Proje talimatları") }
        }
        item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item { DrawerSectionHeader("ARAÇLAR", tools) { tools = !tools } }
        if (tools && onOpenLocalModel != null) item {
            TextButton(onClick = onOpenLocalModel, modifier = Modifier.fillMaxWidth()) { Text("Yerel Model") }
        }
    }
}

@Composable private fun DrawerSectionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        .semantics { stateDescription = if (expanded) "Açık" else "Kapalı" }) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
    }
}

@Composable private fun ComingSoonRow(title: String, add: Boolean = false) {
    TextButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        if (add) { Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp)) }
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("Yakında", style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ConversationRow(conversation: ChatConversation, selected: Boolean, unread: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(conversation.title, modifier = Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (unread) Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("Sohbeti sil") }, onClick = { menu = false; onDelete() }) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun MessageBubble(
    message: ChatMessage,
    replyTarget: ChatMessage?,
    responseDurationMs: Long?,
    zoneId: ZoneId,
    locale: Locale,
    onDelete: (String) -> Unit,
    onEdit: () -> Unit,
    onFork: (String) -> Unit,
    onReply: (String) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current; val user = message.role == MessageRole.USER
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * 0.88f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            Box(Modifier.widthIn(max = bubbleMaxWidth).combinedClickable(onClick = {}, onLongClick = { menu = true })) {
                Surface(
                    color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        replyTarget?.let { ReplyQuote(it) }
                        if (user) Text(message.content) else AssistantMarkdown(message.content)
                        val time = formatMessageTime(message.timestamp, zoneId)
                        val metadata = if (!user && responseDurationMs != null) {
                            "$time · ${formatResponseDuration(responseDurationMs, locale)}"
                        } else time
                        Text(
                            metadata,
                            modifier = Modifier.align(if (user) Alignment.End else Alignment.Start),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Yanıtla") }, onClick = { menu = false; onReply(message.id) })
                    DropdownMenuItem({ Text("Kopyala") }, onClick = { menu = false; clipboard.setText(AnnotatedString(message.content)) })
                    DropdownMenuItem({ Text("Düzenle") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem({ Text("Fork → Yeni sohbet") }, onClick = { menu = false; onFork(message.id) })
                    DropdownMenuItem({ Text("Sil") }, onClick = { menu = false; onDelete(message.id) })
                }
            }
        }
    }
}

@Composable private fun ReplyQuote(target: ChatMessage) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                "Yanıtlanan: ${messageAuthor(target)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                target.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable private fun AssistantMarkdown(source: String) {
    val blocks = remember(source) { parseAssistantMarkdown(source) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(markdownText(block.content))
                is MarkdownBlock.Heading -> Text(
                    markdownText(block.content),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold
                )
                is MarkdownBlock.Bullet -> Text(markdownText(block.content, "• "))
                is MarkdownBlock.Numbered -> Text(markdownText(block.content, "${block.number}. "))
                is MarkdownBlock.Code -> Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        block.code,
                        modifier = Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun markdownText(content: MarkdownInline, prefix: String = ""): AnnotatedString = buildAnnotatedString {
    append(prefix)
    append(content.text)
    content.spans.forEach { span ->
        val style = when (span.style) {
            MarkdownInlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            MarkdownInlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            MarkdownInlineStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
        }
        addStyle(style, prefix.length + span.start, prefix.length + span.end)
    }
}

private fun messageAuthor(message: ChatMessage): String =
    if (message.role == MessageRole.USER) "Sen" else "Asistan"

@Composable private fun EditMessageScreen(
    message: ChatMessage,
    busy: Boolean,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
    onEditAndSend: (String) -> Unit
) {
    var text by rememberSaveable(message.id) { mutableStateOf(message.content) }
    Column(Modifier.fillMaxSize().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("İptal") }
            Text("Mesajı düzenle", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            minLines = 5,
            maxLines = Int.MAX_VALUE
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank() && !busy) { Text("Kaydet") }
            if (message.role == MessageRole.USER) {
                Button(onClick = { onEditAndSend(text) }, enabled = text.isNotBlank() && !busy) { Text("Düzenle ve Gönder") }
            }
        }
    }
}

@Composable private fun Composer(
    draft: String,
    reply: ChatMessage?,
    busy: Boolean,
    onDraftChange: (String) -> Unit,
    onCancelReply: () -> Unit,
    onSend: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        reply?.let { target ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Yanıt: ${messageAuthor(target)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            target.content,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = onCancelReply) {
                        Icon(Icons.Default.Close, contentDescription = "Yanıtı iptal et")
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Mesaj") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            minLines = 1,
            maxLines = 5,
            trailingIcon = {
                IconButton(
                    onClick = {
                        onSend(draft)
                        focus.clearFocus(force = true)
                        keyboard?.hide()
                    },
                    enabled = draft.isNotBlank() && !busy
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder") }
            }
        )
    }
}
