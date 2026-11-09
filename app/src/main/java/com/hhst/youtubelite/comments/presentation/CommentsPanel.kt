package com.hhst.youtubelite.comments.presentation

import com.hhst.youtubelite.ui.design.appPaneTitle
import com.hhst.youtubelite.ui.design.labelResource
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.hhst.youtubelite.R
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentAuthor
import com.hhst.youtubelite.comments.domain.CommentMenuAction
import com.hhst.youtubelite.comments.domain.CommentReply
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentThread
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.watch.domain.VideoRating
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsLayout
import com.hhst.youtubelite.comments.domain.CommentsOverlay
import com.hhst.youtubelite.comments.domain.CommentsUiState
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import com.hhst.youtubelite.ui.design.MenuSheetLayout
import com.hhst.youtubelite.ui.design.messageCardShadow
import com.hhst.youtubelite.watch.domain.WatchLayout
import androidx.compose.runtime.snapshotFlow
import com.hhst.youtubelite.ui.design.appDescription
import com.hhst.youtubelite.ui.design.LocalMotionScale
import kotlinx.coroutines.launch

@Composable
fun CommentsPanel(
    state: CommentsUiState,
    commentCount: String,
    onAction: (CommentAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    sheetTopOverride: Float? = null,
) {
    var listIdentity by remember(listState) { mutableStateOf(state.target to state.sort) }
    LaunchedEffect(listState, state.target, state.sort) {
        val next = state.target to state.sort
        if (listIdentity != next) {
            listIdentity = next
            // A new ordering starts at its first comment; reopening keeps its place.
            listState.scrollToItem(0)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value
        val height = maxHeight.value
        if (WatchLayout.sidebar(width, height)) {
            com.hhst.youtubelite.ui.design.PanelTransition(fromBottom = false, onDismiss = onClose,
                backEnabled = state.overlay == CommentsOverlay.None) { panelMotion, close ->
            Row(Modifier.fillMaxSize().testTag("comments-rail")) {
                // Docked rail: the page to its left remains interactive, as sampled.
                Spacer(Modifier.weight(1f).fillMaxHeight())
                CommentsSurface(
                    state = state,
                    commentCount = commentCount,
                    onAction = onAction,
                    onClose = close,
                    panelWidth = WatchLayout.sidebarWidth(width),
                    compact = false,
                    listState = listState,
                    modifier = Modifier.width(WatchLayout.sidebarWidth(width).dp).fillMaxHeight()
                        .padding(top = WatchLayout.TopBarHeight.dp).then(panelMotion),
                )
            }
            }
        } else {
            // Page rounds the fractional 16:9 player; the sheet must start on the same pixel.
            val top = sheetTopOverride ?: CommentsLayout.sheetTop(
                topBar = WatchLayout.TopBarHeight,
                playerHeight = WatchLayout.playerHeight(width, height),
                windowHeight = height,
            ).roundToInt().toFloat()
            val density = LocalDensity.current.density
            val scope = rememberCoroutineScope()
            val close by rememberUpdatedState(onClose)
            val canDrag by rememberUpdatedState(state.overlay == CommentsOverlay.None)
            val motionScale by rememberUpdatedState(LocalMotionScale.current)
            val thresholdHaptic = com.hhst.youtubelite.ui.design.rememberHapticAction(
                com.hhst.youtubelite.core.HapticsController.Event.GESTURE_THRESHOLD)
            val sheetHeight = (height - top).coerceAtLeast(0f)
            var dismissing by rememberSaveable(state.target) { mutableStateOf(false) }
            val motion = remember(state.target, density) {
                CommentSheetMotion(scope, sheetHeight * density, density, { canDrag },
                    { motionScale > 0f }, { close() }, { dismissing = true }, startHidden = true,
                    onThreshold = thresholdHaptic)
            }
            SideEffect { motion.resize(sheetHeight * density) }
            LaunchedEffect(motion) { if (dismissing) close() else motion.enter() }
            DisposableEffect(motion) { onDispose { motion.cancel() } }
            BackHandler(enabled = canDrag || dismissing, onBack = motion::dismiss)
            val headerDrag = Modifier.draggable(
                state = rememberDraggableState { motion.drag(it) },
                orientation = Orientation.Vertical,
                enabled = canDrag,
                onDragStopped = { motion.settle(it) },
            )
            Box(Modifier.fillMaxSize()) {
                // No tinted scrim in the sample; the exposed page area still dismisses.
                Box(
                    Modifier.fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { motion.dismiss() }
                        .appDescription(R.string.a11y_close_comments)
                        .testTag("comments-scrim"),
                )
                CommentsSurface(
                    state = state,
                    commentCount = commentCount,
                    onAction = onAction,
                    onClose = motion::dismiss,
                    panelWidth = width,
                    compact = true,
                    listState = listState,
                    headerModifier = headerDrag,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .height(sheetHeight.dp)
                        .graphicsLayer { translationY = motion.offset }
                        .nestedScroll(motion),
                )
            }
        }
        val composer = state.overlay as? CommentsOverlay.Composer
        if (composer != null) CommentComposerSheet(
            draft = state.draft,
            pending = state.actionPending,
            editing = composer.editing,
            reply = composer.threadId != null,
            onDraft = { onAction(CommentAction.DraftChanged(it)) },
            onCancel = { onAction(CommentAction.DismissComposer) },
            onSubmit = { onAction(CommentAction.SubmitDraft) },
        )
        if (state.overlay is CommentsOverlay.DeleteConfirm) DeleteCommentSheet(
            ownComment = state.overlay.replyId == null,
            pending = state.actionPending,
            onCancel = { onAction(CommentAction.CancelDelete) },
            onDelete = { onAction(CommentAction.ConfirmDelete) },
        )
    }
}

@Composable
private fun CommentsSurface(
    state: CommentsUiState,
    commentCount: String,
    onAction: (CommentAction) -> Unit,
    onClose: () -> Unit,
    panelWidth: Float,
    compact: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { focusManager.clearFocus() } }

    val shape = if (compact) RoundedCornerShape(topStart = CommentsLayout.SheetRadius.dp, topEnd = CommentsLayout.SheetRadius.dp)
    else RectangleShape
    val modal = state.overlay is CommentsOverlay.ThreadMenu || state.overlay is CommentsOverlay.ReplyMenu ||
        state.overlay is CommentsOverlay.Composer || state.overlay is CommentsOverlay.DeleteConfirm
    BoxWithConstraints(modifier) {
        Column(
            Modifier.fillMaxSize().background(DesignTokens.Panel, shape)
                .focusRequester(focus).focusable()
                .appPaneTitle(R.string.comments_title)
                .testTag("comments-panel"),
        ) {
            if (compact) HandleStrip(headerModifier) else Spacer(Modifier.height(CommentsLayout.HeaderTopGap.dp))
            // Modal sheet owns the gesture and the semantics above the panel.
            Box(Modifier.weight(1f).fillMaxWidth().then(if (modal) Modifier.clearAndSetSemantics {} else Modifier)) {
                Column(Modifier.fillMaxSize()) {
                    Box {
                    CommentsHeader(
                        sort = state.sort,
                        count = commentCount,
                        panelWidth = panelWidth,
                        docked = !compact,
                        showSort = state.content !is CommentsContent.Disabled,
                        onSort = {
                            onAction(
                                if (state.overlay == CommentsOverlay.SortMenu) CommentAction.CloseMenu
                                else CommentAction.OpenSortMenu,
                            )
                        },
                        onClose = onClose,
                        modifier = headerModifier,
                    )
                    if (state.refreshing) {
                        androidx.compose.material3.LinearProgressIndicator(Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth().height(2.dp).testTag("comments-refreshing"))
                    }
                    if (state.actionPending && state.overlay !is CommentsOverlay.Composer && state.overlay !is CommentsOverlay.DeleteConfirm &&
                        state.pendingAction !is CommentNavigationRequest.LikeComment && state.pendingAction !is CommentNavigationRequest.DislikeComment) {
                        androidx.compose.material3.LinearProgressIndicator(Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth().height(2.dp).testTag("comment-action-progress"))
                    }
                    }
                    CommentsBody(state = state, listState = listState, onAction = onAction)
                }
            }
        }
        when (state.overlay) {
            CommentsOverlay.SortMenu -> {
                val menuTop = minOf(81.dp, (maxHeight - 112.dp).coerceAtLeast(0.dp))
                Box(
                    Modifier.fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onAction(CommentAction.CloseMenu) }
                        .testTag("comments-sort-dismiss"),
                )
                SortMenu(
                    selected = state.sort,
                    onSelect = { onAction(CommentAction.SelectSort(it)) },
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(top = menuTop, end = 8.dp)
                        .heightIn(max = maxHeight - menuTop),
                )
            }
            is CommentsOverlay.ThreadMenu, is CommentsOverlay.ReplyMenu -> {
                val actions = menuActions(state)
                MenuBottomSheet(
                    items = actions.map { action ->
                        MenuSheetItem(
                            icon = when (action) {
                                CommentMenuAction.Reply -> AppIcon.Comment
                                CommentMenuAction.Report -> AppIcon.Report
                                CommentMenuAction.Edit, CommentMenuAction.Delete -> null
                            },
                            label = stringResource(action.labelResource),
                            tag = "comment-menu-${action.name.lowercase()}",
                        )
                    },
                    onDismiss = { onAction(CommentAction.CloseMenu) },
                    onSelect = { index -> if (index in actions.indices) onAction(CommentAction.MenuAction(actions[index])) },
                    tag = "comments-menu",
                    paneTitle = stringResource(R.string.ui_comment_actions),
                )
            }
            is CommentsOverlay.Composer, is CommentsOverlay.DeleteConfirm -> Unit
            CommentsOverlay.None -> Unit
        }
    }
}

/** Default menu keeps the sampled Reply/Report pair; tests/previews supply owned threads for Edit/Delete. */
private fun menuActions(state: CommentsUiState): List<CommentMenuAction> {
    val ready = state.content as? CommentsContent.Ready
    return (when (val overlay = state.overlay) {
        is CommentsOverlay.ThreadMenu -> ready?.threads?.firstOrNull { it.id == overlay.threadId }
            ?.let { CommentMenuAction.forComment(it.ownedByViewer) }
        is CommentsOverlay.ReplyMenu -> ready?.threads?.firstOrNull { it.id == overlay.threadId }
            ?.replies?.firstOrNull { it.id == overlay.replyId }
            ?.let { CommentMenuAction.forComment(it.ownedByViewer) }
        else -> null
    } ?: CommentMenuAction.forComment(ownedByViewer = false)).filter { it in state.supportedMenuActions }
}

@Composable
private fun CommentComposerSheet(
    draft: String,
    pending: Boolean,
    editing: Boolean,
    reply: Boolean,
    onDraft: (String) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val hintText = stringResource(R.string.comments_composer_text_hint)
    val editHintText = stringResource(R.string.comments_composer_text_edit_hint)
    val cancelText = stringResource(R.string.cancel)
    val sendingText = stringResource(R.string.comments_composer_sending)
    val saveText = stringResource(R.string.comments_composer_save)
    val submitText = stringResource(R.string.comments_composer_submit)
    val editorBottom = if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) 8.dp else MenuSheetLayout.BottomInset.dp
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    DisposableEffect(Unit) {
        onDispose {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    Box(
        Modifier.fillMaxSize().background(DesignTokens.MenuScrim).clickable { onCancel() }
            .appDescription(R.string.a11y_close_comment_editor),
    )
    BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = editorBottom)
                .width(MenuSheetLayout.sheetWidth(maxWidth.value, maxHeight.value).dp)
                .heightIn(max = (maxHeight - editorBottom).coerceAtLeast(0.dp))
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(MenuSheetLayout.Corner.dp)).background(DesignTokens.Panel)
                .testTag("comment-composer").appPaneTitle(R.string.ui_comment_editor),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DefaultAvatar(CommentsLayout.AvatarLarge.dp)
                Spacer(Modifier.width(CommentsLayout.AvatarGap.dp))
                BasicTextField(
                    draft, onDraft,
                    Modifier.weight(1f).heightIn(min = CommentsLayout.InputHeight.dp)
                        .clip(RoundedCornerShape(CommentsLayout.InputRadius.dp))
                        .border(1.dp, DesignTokens.Border.copy(alpha = CommentsLayout.DividerAlpha),
                            RoundedCornerShape(CommentsLayout.InputRadius.dp))
                        .padding(horizontal = CommentsLayout.InputTextInset.dp, vertical = 8.dp)
                        .focusRequester(focus)
                        .testTag("comment-composer-input")
                        .semantics { contentDescription = if (editing) editHintText else hintText },
                    maxLines = 6,
                    readOnly = pending,
                    textStyle = DesignTokens.text(14, 20),
                    cursorBrush = SolidColor(DesignTokens.Ink),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) AppText(
                                if (reply) stringResource(R.string.ui_add_a_reply) else stringResource(R.string.ui_add_a_comment),
                                size = 14, line = 20, color = DesignTokens.Secondary,
                            )
                            inner()
                        }
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerAction(cancelText, "comment-composer-cancel", enabled = !pending, onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                ComposerAction(
                    when {
                        pending -> sendingText
                        editing -> saveText
                        else -> submitText
                    },
                    "comment-composer-submit",
                    enabled = draft.isNotBlank() && !pending,
                    pending = pending,
                    onClick = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun DeleteCommentSheet(ownComment: Boolean, pending: Boolean, onCancel: () -> Unit, onDelete: () -> Unit) {
    val cancelText = stringResource(R.string.cancel)
    val deleteText = stringResource(R.string.comments_delete_title)
    Box(
        Modifier.fillMaxSize().background(DesignTokens.MenuScrim).clickable { onCancel() }
            .appDescription(R.string.a11y_close_delete_confirmation),
    )
    BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = MenuSheetLayout.BottomInset.dp)
                .width(MenuSheetLayout.sheetWidth(maxWidth.value, maxHeight.value).dp)
                .heightIn(max = (maxHeight - MenuSheetLayout.BottomInset.dp).coerceAtLeast(0.dp))
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(MenuSheetLayout.Corner.dp)).background(DesignTokens.Panel)
                .testTag("comment-delete-sheet").appPaneTitle(R.string.ui_delete_confirmation),
        ) {
            AppText(
                if (ownComment) stringResource(R.string.ui_delete_comment) else stringResource(R.string.ui_delete_reply),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                size = 18, line = 26, weight = FontWeight.Bold,
            )
            AppText(
                stringResource(R.string.ui_this_action_cannot_be_undone),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                size = 14, line = 20, color = DesignTokens.Secondary,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerAction(cancelText, "comment-delete-cancel", enabled = !pending, onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                ComposerAction(deleteText, "comment-delete-confirm", enabled = !pending, pending = pending, onClick = onDelete, destructive = true)
            }
        }
    }
}

@Composable
private fun ComposerAction(
    label: String,
    tag: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    pending: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, enabled = enabled) { if (enabled) onClick() }
            .testTag(tag).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (pending) com.hhst.youtubelite.ui.design.ActionProgress()
        else AppText(
            label,
            size = 14, line = 20, weight = FontWeight.Medium,
            color = when {
                !enabled -> DesignTokens.Secondary.copy(alpha = 0.5f)
                destructive -> DesignTokens.Red
                else -> DesignTokens.Ink
            },
        )
    }
}

@Composable
private fun HandleStrip(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(CommentsLayout.HandleStripHeight.dp).testTag("comments-handle"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier.width(CommentsLayout.HandleWidth.dp).height(CommentsLayout.HandleHeight.dp)
                .background(DesignTokens.Ink.copy(alpha = .15f), RoundedCornerShape(2.dp))
                .testTag("comments-handle-bar"),
        )
    }
}

@Composable
private fun CommentsHeader(
    sort: CommentSort, count: String, panelWidth: Float, docked: Boolean, showSort: Boolean,
    onSort: () -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val chipTopText = stringResource(R.string.comments_sort_chip_top)
        val chipNewestText = stringResource(R.string.comments_sort_chip_newest)
        val titleWidth = measurer.measure(count, DesignTokens.text(
            CommentsLayout.titleSize(panelWidth, docked).toInt(), CommentsLayout.titleLine(panelWidth, docked).toInt(), FontWeight.Bold,
        )).size.width
        val label = if (sort == CommentSort.Top) chipTopText else chipNewestText
        val sortWidth = measurer.measure(label, DesignTokens.text(14, 20, FontWeight.Medium)).size.width
        val sortHeight = with(density) { maxOf(40.dp, 20.sp.toDp() + 8.dp) }
        val headerHeight = with(density) { maxOf(CommentsLayout.HeaderHeight.dp, 20.sp.toDp() + 16.dp) }
        val stacked = showSort && titleWidth + sortWidth + 2 + with(density) { 100.dp.roundToPx() } > constraints.maxWidth
        Box(Modifier.fillMaxWidth().height(headerHeight + if (stacked) maxOf(48.dp, sortHeight + 8.dp) else 0.dp).testTag("comments-header")) {
            Row(
                Modifier.fillMaxWidth().height(headerHeight)
                    .padding(start = CommentsLayout.HeaderPaddingStart.dp, end = CommentsLayout.CloseEndInset.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(count, Modifier.weight(1f).testTag("comments-count"), size = 14, line = 20, color = DesignTokens.Secondary)
                if (showSort && !stacked) {
                    SortControl(label, onSort)
                    Spacer(Modifier.width(8.dp))
                }
                AppIconButton(AppIcon.Close, stringResource(R.string.a11y_close_comments), diameter = CommentsLayout.CloseTarget.dp,
                    modifier = Modifier.testTag("comments-close"), onClick = onClose)
            }
            if (stacked) Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp)) {
                SortControl(label, onSort)
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp)
                .background(DesignTokens.Border.copy(alpha = CommentsLayout.DividerAlpha)))
        }
    }
}

@Composable
private fun SortControl(label: String, onClick: () -> Unit) {
    val height = with(LocalDensity.current) { maxOf(40.dp, 20.sp.toDp() + 8.dp) }
    Box(
        Modifier.heightIn(min = height).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp).testTag("comments-sort").appDescription(R.string.a11y_sort_comments),
        contentAlignment = Alignment.Center,
    ) { AppText(label, size = 14, line = 20, weight = FontWeight.Medium) }
}

@Composable
private fun SortMenu(selected: CommentSort, onSelect: (CommentSort) -> Unit, modifier: Modifier = Modifier) {
    val topCommentsText = stringResource(R.string.comments_sort_top)
    val newestFirstText = stringResource(R.string.comments_sort_newest)
    Column(
        modifier.width(200.dp).messageCardShadow().background(DesignTokens.Panel, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp).testTag("comments-sort-menu")
            .appPaneTitle(R.string.a11y_sort_comments),
    ) {
        SortMenuRow(topCommentsText, selected == CommentSort.Top, "comments-sort-top") { onSelect(CommentSort.Top) }
        SortMenuRow(newestFirstText, selected == CommentSort.Newest, "comments-sort-newest") { onSelect(CommentSort.Newest) }
    }
}

@Composable
private fun SortMenuRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            label, size = 14, line = 20,
            weight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun ColumnScope.CommentsBody(state: CommentsUiState, listState: LazyListState, onAction: (CommentAction) -> Unit) {
    val retryText = stringResource(R.string.retry)
    val loadMoreFailedText = stringResource(R.string.comments_load_more_failed)
    val content = state.content
    if (content is CommentsContent.Ready) {
        ThreadList(content, state.expanded, listState, (state.target as? com.hhst.youtubelite.comments.domain.CommentTarget.Video)?.id,
            state.pendingAction, state.actionPending, onAction)
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        val bodyHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
            if (content != CommentsContent.Disabled) Simplebox(onAction)
            val messageMinHeight = (bodyHeight - if (content == CommentsContent.Disabled) 0.dp else 89.dp).coerceAtLeast(48.dp)
            when (content) {
                CommentsContent.Loading -> Box(
                    Modifier.fillMaxWidth().heightIn(min = messageMinHeight), contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp).testTag("comments-loading"),
                        color = DesignTokens.Secondary, strokeWidth = 2.dp)
                }
                CommentsContent.Failed -> MessageBlock(stringResource(R.string.ui_something_went_wrong_while_loading_comments), messageMinHeight, onRetry = { onAction(CommentAction.Retry) })
                CommentsContent.Empty -> MessageBlock(stringResource(R.string.ui_no_comments_yet), messageMinHeight)
                CommentsContent.Disabled -> MessageBlock(stringResource(R.string.ui_comments_are_turned_off), messageMinHeight)
                is CommentsContent.Ready -> Unit
            }
        }
    }
}

@Composable
private fun MessageBlock(message: String, minHeight: Dp, onRetry: (() -> Unit)? = null) {
    val retryText = stringResource(R.string.retry)
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(
                message,
                Modifier.testTag(if (onRetry != null) "comments-error" else "comments-message"),
                size = 14, line = 20, color = DesignTokens.Secondary,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onRetry)
                        .padding(horizontal = 16.dp).testTag("comments-retry"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(retryText, size = 14, line = 20, weight = FontWeight.Medium, color = Color(0xFF065FD4))
                }
            }
        }
    }
}

@Composable
private fun Simplebox(onAction: (CommentAction) -> Unit) {
    val retryText = stringResource(R.string.retry)
    Column(Modifier.fillMaxWidth().testTag("comments-simplebox")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = CommentsLayout.SimpleboxHeight.dp)
                .padding(start = CommentsLayout.CommentStartInset.dp, end = CommentsLayout.InputEndInset.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DefaultAvatar(CommentsLayout.AvatarLarge.dp)
            Spacer(Modifier.width(CommentsLayout.AvatarGap.dp))
            Box(
                Modifier.weight(1f).heightIn(min = CommentsLayout.InputHeight.dp)
                    .clip(RoundedCornerShape(CommentsLayout.InputRadius.dp))
                    .border(1.dp, DesignTokens.Border.copy(alpha = CommentsLayout.DividerAlpha), RoundedCornerShape(CommentsLayout.InputRadius.dp))
                    .clickable(role = Role.Button) { onAction(CommentAction.Compose) }
                    .testTag("comments-input")
                    .appDescription(R.string.a11y_add_comment)
                    .padding(start = CommentsLayout.InputTextInset.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                AppText(stringResource(R.string.ui_add_a_comment), size = 14, line = 20, color = DesignTokens.Secondary)
            }
        }
        Divider()
    }
}

@Composable
private fun ThreadList(content: CommentsContent.Ready, expanded: Set<String>, listState: LazyListState, videoId: String?,
    pending: CommentNavigationRequest?, busy: Boolean, onAction: (CommentAction) -> Unit) {
    val currentAction by rememberUpdatedState(onAction)
    val scrollScope = rememberCoroutineScope()
    LaunchedEffect(listState, content.nextCursor, content.loadingMore, content.loadMoreFailed) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount > 1 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 2
        }.collect { nearEnd ->
            if (content.nextCursor == null || content.loadingMore || content.loadMoreFailed) return@collect
            if (nearEnd) currentAction(CommentAction.LoadMore)
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("comments-list"), state = listState) {
        item(key = "simplebox", contentType = "composer") { Simplebox(onAction) }
        var nextItemIndex = 1
        content.threads.forEach { thread ->
            val threadIndex = nextItemIndex++
            val isExpanded = thread.id in expanded && (thread.replies.isNotEmpty() || thread.replyKey != null)
            item(key = "thread:${thread.id}", contentType = "thread") {
                ThreadItem(thread, isExpanded, videoId, pending, busy, onAction)
            }
            if (isExpanded) {
                if (thread.repliesLoading && thread.replies.isEmpty() || thread.repliesFailed) {
                    nextItemIndex++
                    item(key = "reply-status:${thread.id}", contentType = "reply-status") {
                        Column(Modifier.fillMaxWidth().padding(start = CommentsLayout.ReplyToggleStart.dp, end = 20.dp)
                            .then(if (thread.replies.isEmpty()) Modifier.testTag("comment-${thread.id}-replies") else Modifier)) {
                            if (thread.repliesLoading && thread.replies.isEmpty()) AppText(stringResource(R.string.ui_loading_replies), size = 12, line = 18)
                            if (thread.repliesFailed) AppText(stringResource(R.string.ui_couldn_t_load_replies_tap_below_to_retry), size = 12, line = 18)
                        }
                    }
                }
                // Replies are individual lazy items so a long thread only measures visible reply rows.
                items(thread.replies.size, key = { "reply:${thread.id}:${thread.replies[it].id}" }, contentType = { "reply" }) { index ->
                    Column(Modifier.fillMaxWidth().padding(start = CommentsLayout.ReplyToggleStart.dp, end = 20.dp)
                        .then(if (index == 0) Modifier.testTag("comment-${thread.id}-replies") else Modifier)) {
                        ReplyContent(thread.id, thread.replies[index], videoId, pending, busy, onAction)
                        Spacer(Modifier.height(CommentsLayout.ReplyRowGap.dp))
                    }
                }
                nextItemIndex += thread.replies.size + 1
                item(key = "reply-footer:${thread.id}", contentType = "reply-footer") {
                    ExpandedRepliesFooter(thread) { action ->
                        if (action is CommentAction.ToggleReplies && !thread.repliesFailed) {
                            // Footer disappears on collapse; anchor the surviving thread first
                            // so the reader keeps context instead of jumping to the next comment.
                            scrollScope.launch {
                                listState.scrollToItem(threadIndex)
                                currentAction(action)
                            }
                        } else currentAction(action)
                    }
                }
            }
        }
        item(key = "footer", contentType = "pagination") { ListFooter(content, onAction) }
    }
}

@Composable
private fun ListFooter(content: CommentsContent.Ready, onAction: (CommentAction) -> Unit) {
    val retryText = stringResource(R.string.retry)
    val loadMoreFailedText = stringResource(R.string.comments_load_more_failed)
    when {
        content.loadingMore || content.threads.isEmpty() && content.nextCursor != null && !content.loadMoreFailed -> Box(
            Modifier.fillMaxWidth().height(56.dp).testTag("comments-footer-loading"),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                Modifier.size(20.dp), color = DesignTokens.Secondary, strokeWidth = 2.dp,
            )
        }
        content.loadMoreFailed -> Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp).testTag("comments-footer-error"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(loadMoreFailedText, Modifier.weight(1f), size = 14, line = 20, color = DesignTokens.Secondary)
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button) { onAction(CommentAction.LoadMore) }
                    .padding(horizontal = 16.dp).testTag("comments-footer-retry"),
                contentAlignment = Alignment.Center,
            ) { AppText(retryText, size = 14, line = 20, weight = FontWeight.Medium) }
        }
        else -> Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ThreadItem(thread: CommentThread, expanded: Boolean, videoId: String?, pending: CommentNavigationRequest?, busy: Boolean, onAction: (CommentAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("comment-${thread.id}")) {
        Spacer(Modifier.height(CommentsLayout.ThreadTopPad.dp))
        ThreadContent(thread, videoId, pending, busy, onAction)
        if (thread.replies.isNotEmpty() || thread.replyKey != null) {
            Spacer(Modifier.height(CommentsLayout.ReplyToggleTopGap.dp))
            if (!expanded) RepliesToggle(
                label = if (thread.repliesFailed) stringResource(R.string.retry) else stringResource(R.string.comments_show_more_replies),
                tag = "comment-${thread.id}-reply-toggle",
                onClick = { onAction(CommentAction.ToggleReplies(thread.id)) },
            )
        }
        if (!expanded) Spacer(Modifier.height(CommentsLayout.ThreadBottomGap.dp))
    }
}

@Composable
private fun ExpandedRepliesFooter(thread: CommentThread, onAction: (CommentAction) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (thread.repliesNextCursor != null && !thread.repliesFailed) {
            Box(Modifier.fillMaxWidth().padding(start = CommentsLayout.ReplyToggleStart.dp, end = 20.dp)
                .heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !thread.repliesLoading, role = Role.Button) { onAction(CommentAction.LoadMoreReplies(thread.id)) }
                .testTag("comment-${thread.id}-replies-more"), contentAlignment = Alignment.CenterStart) {
                if (thread.repliesLoading) CircularProgressIndicator(Modifier.size(16.dp), color = DesignTokens.Secondary, strokeWidth = 2.dp)
                else AppText(stringResource(R.string.show_more_replies), size = 13, line = 18,
                    weight = FontWeight.Medium, color = DesignTokens.Secondary)
            }
        }
        Spacer(Modifier.height(4.dp))
        RepliesToggle(
            label = stringResource(if (thread.repliesFailed) R.string.retry else R.string.comments_hide_replies),
            tag = "comment-${thread.id}-reply-toggle",
            onClick = { onAction(CommentAction.ToggleReplies(thread.id)) },
        )
        Spacer(Modifier.height(CommentsLayout.ThreadBottomGap.dp))
    }
}

@Composable
private fun ThreadContent(thread: CommentThread, videoId: String?, pending: CommentNavigationRequest?, busy: Boolean, onAction: (CommentAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = CommentsLayout.CommentStartInset.dp, end = 20.dp),
    ) {
        CommentAvatar(thread.author, CommentsLayout.AvatarLarge.dp, thread.authorChannelId, onAction)
        Spacer(Modifier.width(CommentsLayout.AvatarGap.dp))
        Column(Modifier.weight(1f).padding(top = CommentsLayout.AuthorTopPad.dp)) {
            CommentAuthorRow(thread.author, thread.age, thread.id, thread.authorChannelId, onAction)
            Spacer(Modifier.height(CommentsLayout.AuthorBodyGap.dp))
            CommentRichBody(thread.body, thread.runs, videoId, Modifier.testTag("comment-${thread.id}-body")) {
                onAction(CommentAction.OpenLink(it))
            }
            Spacer(Modifier.height(3.dp))
            CommentActions(
                likes = thread.likes,
                replyCount = thread.replyCount,
                description = stringResource(R.string.ui_comment_by_1_s , thread.author.handle),
                tagPrefix = "comment-${thread.id}",
                commentId = thread.id, rating = thread.rating, pending = pending, enabled = !busy,
                onLike = { onAction(CommentAction.LikeThread(thread.id)) },
                onDislike = { onAction(CommentAction.DislikeThread(thread.id)) },
                onReply = { onAction(CommentAction.Reply(thread.id)) },
                onMore = { onAction(CommentAction.OpenThreadMenu(thread.id)) },
            )
            Spacer(Modifier.height(5.dp))
        }
    }
}

@Composable
private fun ReplyContent(threadId: String, reply: CommentReply, videoId: String?, pending: CommentNavigationRequest?, busy: Boolean, onAction: (CommentAction) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("reply-${reply.id}")) {
        CommentAvatar(reply.author, CommentsLayout.ReplyAvatar.dp, reply.authorChannelId, onAction)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            CommentAuthorRow(reply.author, reply.age, reply.id, reply.authorChannelId, onAction)
            Spacer(Modifier.height(CommentsLayout.AuthorBodyGap.dp))
            CommentRichBody(reply.body, reply.runs, videoId, Modifier.testTag("reply-${reply.id}-body")) {
                onAction(CommentAction.OpenLink(it))
            }
            Spacer(Modifier.height(3.dp))
            CommentActions(
                likes = reply.likes,
                replyCount = null,
                description = stringResource(R.string.ui_reply_by_1_s , reply.author.handle),
                tagPrefix = "reply-${reply.id}",
                commentId = reply.id, rating = reply.rating, pending = pending, enabled = !busy,
                onLike = { onAction(CommentAction.LikeReply(threadId, reply.id)) },
                onDislike = { onAction(CommentAction.DislikeReply(threadId, reply.id)) },
                onReply = { onAction(CommentAction.Reply(threadId, reply.id)) },
                onMore = { onAction(CommentAction.OpenReplyMenu(threadId, reply.id)) },
            )
            Spacer(Modifier.height(1.dp))
        }
    }
}

@Composable
private fun CommentAuthorRow(
    author: CommentAuthor,
    age: String,
    testTagSuffix: String,
    channelId: String,
    onAction: (CommentAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 4.dp).appDescription(R.string.a11y_open_channel, author.handle).clickable(role = Role.Button) {
            if (channelId.isNotEmpty()) onAction(CommentAction.OpenAuthor(channelId))
        }.testTag("comment-$testTagSuffix-open-author"),
    ) {
        AppText(
            author.handle.ifBlank { author.displayName },
            Modifier.weight(1f).testTag("comment-$testTagSuffix-author"),
            size = CommentsLayout.AuthorSize.toInt(),
            line = CommentsLayout.AuthorLine.toInt(),
            maxLines = 1,
        )
        AppText(
            age,
            size = CommentsLayout.AgeSize.toInt(),
            line = CommentsLayout.AgeLine.toInt(),
            maxLines = 1,
        )
    }
}

@Composable
private fun CommentActions(
    likes: String,
    replyCount: Int?,
    description: String,
    tagPrefix: String,
    commentId: String,
    rating: VideoRating,
    pending: CommentNavigationRequest?,
    enabled: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onReply: () -> Unit,
    onMore: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(CommentsLayout.ActionRowHeight.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).offset(x = (-6).dp).horizontalScroll(rememberScrollState())) {
            CountedAction(AppIcon.Like, likes, stringResource(R.string.ui_like_1_s , description), "$tagPrefix-like", onLike,
                enabled, rating == VideoRating.Like, (pending as? CommentNavigationRequest.LikeComment)?.commentId == commentId)
            Spacer(Modifier.width(7.dp))
            PlainAction(AppIcon.Dislike, stringResource(R.string.ui_dislike_1_s , description), "$tagPrefix-dislike", onDislike,
                enabled, rating == VideoRating.Dislike, (pending as? CommentNavigationRequest.DislikeComment)?.commentId == commentId)
            Spacer(Modifier.width(7.dp))
            if (replyCount != null) {
                CountedAction(AppIcon.Comment, replyCount.toString(), stringResource(R.string.ui_reply_to_1_s , description), "$tagPrefix-reply", onReply, enabled)
            } else {
                PlainAction(AppIcon.Comment, stringResource(R.string.ui_reply_to_1_s , description), "$tagPrefix-reply", onReply, enabled)
            }
        }
        Box(
            Modifier.size(CommentsLayout.KebabTarget.dp).clip(CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onMore)
                .testTag("$tagPrefix-more")
                .appDescription(R.string.a11y_more_actions_for, description),
            contentAlignment = Alignment.Center,
        ) { AppIcon(AppIcon.More, Modifier.size(CommentsLayout.KebabIcon.dp)) }
    }
}

@Composable
private fun CountedAction(icon: AppIcon, count: String, description: String, tag: String, onClick: () -> Unit,
    enabled: Boolean = true, selected: Boolean = false, pending: Boolean = false) {
    Row(
        Modifier.height(CommentsLayout.ActionRowHeight.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag).semantics { contentDescription = description; this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (pending) com.hhst.youtubelite.ui.design.ActionProgress()
            else AppIcon(icon, Modifier.size(CommentsLayout.ActionIcon.dp), color = if (selected) DesignTokens.Red else DesignTokens.Ink)
        }
        Spacer(Modifier.width(1.dp))
        AppText(count, size = CommentsLayout.ActionCountSize.toInt(), line = 13, color = DesignTokens.Secondary)
    }
}

@Composable
private fun PlainAction(icon: AppIcon, description: String, tag: String, onClick: () -> Unit,
    enabled: Boolean = true, selected: Boolean = false, pending: Boolean = false) {
    Box(
        Modifier.height(CommentsLayout.ActionRowHeight.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag).semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (pending) com.hhst.youtubelite.ui.design.ActionProgress()
            else AppIcon(icon, Modifier.size(CommentsLayout.ActionIcon.dp), color = if (selected) DesignTokens.Red else DesignTokens.Ink)
        }
    }
}

@Composable
private fun RepliesToggle(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(start = CommentsLayout.ReplyToggleStart.dp, end = 12.dp)
            .heightIn(min = CommentsLayout.ReplyToggleHeight.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        AppText(label, size = 14, line = 20, weight = FontWeight.Medium)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.Border.copy(alpha = CommentsLayout.DividerAlpha)))
}

@Composable
private fun DefaultAvatar(size: Dp) {
    Image(painterResource(R.drawable.comment_avatar), contentDescription = null,
        modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
}

@Composable
private fun CommentAvatar(author: CommentAuthor, size: Dp, channelId: String, onAction: (CommentAction) -> Unit) {
    com.hhst.youtubelite.ui.design.Artwork(author.artwork,
        modifier = Modifier.size(size).clip(CircleShape).then(if (channelId.isBlank()) Modifier else
            Modifier.clickable(role = Role.Button) { onAction(CommentAction.OpenAuthor(channelId)) }
                .appDescription(R.string.a11y_open_channel, author.handle)),
    )
}
