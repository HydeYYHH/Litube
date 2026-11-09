package com.hhst.youtubelite.ui.design

import com.hhst.youtubelite.ui.design.appPaneTitle
import androidx.compose.foundation.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R
import kotlinx.coroutines.launch

@Composable
internal fun SearchPanel(
    query: String,
    suggestions: List<String>,
    onQueryChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
    onVoiceSearch: () -> Unit,
    /** Live-mode local history rows (matching entries first); empty in fixture builds. */
    history: List<String> = emptyList(),
    onRemoveHistory: ((String) -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(query, TextRange(query.length)))
    }
    if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val fieldHeight = maxOf(32.dp, with(LocalDensity.current) { 22.sp.toDp() })
    val toolbarHeight = maxOf(48.dp, fieldHeight)
    val motionScale = LocalMotionScale.current
    val reveal = remember { Animatable(if (motionScale <= 0f) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    var closing by remember { mutableStateOf(false) }
    /**
     * Closing only clears focus and hands the event to the host; the IME hides
     * through [onDispose]. An explicit hide here must not be issued while the
     * window may still be animating an earlier IME transition (recreation on
     * older APIs): that synchronous call can wedge the main thread before
     * [onClose] runs, leaving the overlay stuck open.
     */
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            focusManager.clearFocus()
            scope.launch {
                reveal.animateTo(0f, tween(motionDuration(80, motionScale)))
                currentOnClose()
            }
        }
    }
    BackHandler(onBack = close)
    LaunchedEffect(Unit) {
        if (!closing) { focus.requestFocus(); keyboard?.show() }
        reveal.animateTo(1f, tween(motionDuration(100, motionScale), easing = LinearOutSlowInEasing))
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    Box(Modifier.fillMaxSize().padding(top = toolbarHeight).graphicsLayer { alpha = reveal.value }
        .background(DesignTokens.Scrim)
        .clickable(onClick = close).appDescription(R.string.a11y_close_search))
    Column(Modifier.fillMaxWidth().graphicsLayer {
        alpha = reveal.value
        translationY = -4.dp.toPx() * (1f - reveal.value)
    }.background(DesignTokens.Background).testTag("search-overlay").appPaneTitle(R.string.a11y_search_youtube)) {
        Row(Modifier.fillMaxWidth().height(toolbarHeight).background(
            if (DesignTokens.Background == Color.White) Color(0xFFF1F1F1) else DesignTokens.Surface), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcon.Back, stringResource(R.string.a11y_back), onClick = close, modifier = Modifier.testTag("search-panel-back"))
            Row(Modifier.weight(1f).height(fieldHeight).clip(RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp))
                .background(DesignTokens.Ghost).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(field, { field = it; onQueryChanged(it.text) },
                    Modifier.weight(1f).focusRequester(focus).testTag("search-input").appDescription(R.string.a11y_search_youtube),
                    textStyle = DesignTokens.text(15, 22), singleLine = true, cursorBrush = SolidColor(DesignTokens.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit(query) }),
                    decorationBox = { inner -> Box { if (query.isEmpty()) AppText(stringResource(R.string.a11y_search_youtube), size = 15, line = 22, color = DesignTokens.Secondary); inner() } })
                if (query.isNotEmpty()) Box(Modifier.size(50.dp, 16.dp).clickable(role = Role.Button) {
                    onQueryChanged(""); focus.requestFocus(); keyboard?.show()
                }.appDescription(R.string.a11y_clear_search).padding(start = 8.dp, end = 26.dp)) { AppIcon(AppIcon.Close, Modifier.size(16.dp)) }
            }
            Box(Modifier.size(36.dp, fieldHeight).clip(RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)).background(DesignTokens.Ghost)
                .clickable(role = Role.Button) { keyboard?.hide(); onSubmit(query) }
                .appDescription(R.string.a11y_search), contentAlignment = Alignment.CenterStart) { AppIcon(AppIcon.Search) }
            Spacer(Modifier.width(8.dp))
            if (query.isEmpty()) Box(Modifier.padding(start = 4.dp, end = 12.dp).size(32.dp).background(DesignTokens.Ghost, CircleShape), contentAlignment = Alignment.Center) {
                AppIconButton(AppIcon.Voice, stringResource(R.string.a11y_voice_search), modifier = Modifier.wrapContentSize(unbounded = true), diameter = 40.dp) { onVoiceSearch() }
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("search-suggestions")) {
            history.forEachIndexed { index, entry ->
                Row(Modifier.fillMaxWidth().heightIn(min = 37.dp).clickable(role = Role.Button) {
                    keyboard?.hide(); onSubmit(entry)
                }.testTag("history-$index"), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppIcon.Clock, Modifier.padding(start = 8.dp).size(16.dp))
                    BasicText(entry, Modifier.weight(1f).padding(horizontal = 8.dp), style = DesignTokens.text(14, 17), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (onRemoveHistory != null) Box(Modifier.size(37.dp).clickable(role = Role.Button) {
                        onRemoveHistory(entry)
                    }.appDescription(R.string.a11y_remove_from_search_history, entry), contentAlignment = Alignment.Center) {
                        AppIcon(AppIcon.Close, Modifier.size(14.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.Border.copy(alpha = 0.05f)))
            }
            suggestions.forEachIndexed { index, suggestion ->
                Row(Modifier.fillMaxWidth().heightIn(min = 37.dp).clickable(role = Role.Button) {
                    keyboard?.hide(); onSubmit(suggestion)
                }.testTag("suggestion-$index"), verticalAlignment = Alignment.CenterVertically) {
                    val prefixQuery = query.trim()
                    val text = buildAnnotatedString {
                        val prefix = if (suggestion.startsWith(prefixQuery, ignoreCase = true)) prefixQuery.length else suggestion.length
                        append(suggestion.take(prefix))
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(suggestion.drop(prefix)) }
                    }
                    BasicText(text, Modifier.weight(1f).padding(horizontal = 8.dp), style = DesignTokens.text(14, 17), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(37.dp).background(DesignTokens.Border.copy(alpha = 0.05f)).clickable(role = Role.Button) {
                        onQueryChanged(suggestion); focus.requestFocus(); keyboard?.show()
                    }.appDescription(R.string.a11y_use_suggestion, suggestion).padding(end = 4.dp), contentAlignment = Alignment.CenterEnd) {
                        AppIcon(AppIcon.ArrowUpLeft, Modifier.offset(y = 2.dp))
                    }
                }
                if (index < suggestions.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.Border.copy(alpha = 0.05f)))
            }
        }
    }
}
