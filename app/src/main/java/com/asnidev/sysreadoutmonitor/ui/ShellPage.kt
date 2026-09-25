package com.asnidev.sysreadoutmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.page.Shell
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.Wrap

/**
 * The shell tab: what ran, and a prompt that takes the next command. The only
 * page with a cursor, because it's the only one waiting for input.
 */
@Composable
fun ShellPage(
    shell: Shell,
    style: TextStyle,
    cols: Int,
    padding: Dp,
    list: LazyListState,
    active: Boolean,
    onTap: (Tap) -> Unit,
) {
    val state by shell.state.collectAsState()
    val intro = remember(state.viaShizuku) { intro(state.viaShizuku) }
    val source = remember(intro, state.lines) { intro + state.lines }
    val rows = remember(source, cols) { wrapRows(source, cols) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val copy = rememberCopier()
    val flash = rememberFlash()

    // Swiping to another page puts the keyboard away.
    LaunchedEffect(active) { if (!active) focusManager.clearFocus() }
    Follow(list, rows.size + 1)

    fun set(text: String?) {
        if (text != null) input = TextFieldValue(text, TextRange(text.length))
    }
    fun submit() {
        if (state.running) return
        shell.submit(input.text)
        input = TextFieldValue("")
    }

    Column(Modifier.fillMaxSize().padding(horizontal = padding)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().clickable(interactionSource = null, indication = null) {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            },
            state = list,
            contentPadding = PaddingValues(vertical = padding),
        ) {
            items(rows.size) { i ->
                val row = rows[i]
                TermLine(row.line, style, onTap, highlighted = flash.source == row.source) {
                    copy(copyText(source[row.source], rows.filter { it.source == row.source }))
                    flash.source = row.source
                }
            }
            // Always there, so the keyboard stays up between commands; dim while a command runs.
            item(key = "input") {
                // A long prompt leaves too little room: then the command goes on the next row.
                val narrow = Wrap.width(state.prompt) > cols - MIN_INPUT
                val field = @Composable { modifier: Modifier ->
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = style,
                        singleLine = true,
                        cursorBrush = SolidColor(Breeze.foreground),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = modifier.focusRequester(focus).onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.Enter, Key.NumPadEnter -> { submit(); true }
                                Key.DirectionUp -> { set(shell.historyBack()); true }
                                Key.DirectionDown -> { set(shell.historyForward()); true }
                                else -> false
                            }
                        },
                    )
                }
                val promptStyle = if (state.running) style.merge(Tone.DIM.spanStyle()) else style
                if (narrow) {
                    Column {
                        plain(state.prompt, cols).forEach { BasicText(it.text, style = promptStyle, softWrap = false, maxLines = 1) }
                        field(Modifier.fillMaxWidth())
                    }
                } else {
                    Row {
                        BasicText(state.prompt, style = promptStyle, softWrap = false, maxLines = 1)
                        field(Modifier.weight(1f))
                    }
                }
            }
        }
        // Keys a phone keyboard doesn't have.
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Key("^C", style) { shell.interrupt() }
            Key("↑", style) { set(shell.historyBack()) }
            Key("↓", style) { set(shell.historyForward()) }
            Key("clear", style) { shell.submit("clear") }
        }
    }
}

@Composable
private fun Key(label: String, style: TextStyle, onClick: () -> Unit) {
    BasicText(
        label,
        style = style.merge(Tone.LINK.spanStyle()),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

private fun intro(viaShizuku: Boolean): List<Line> {
    fun note(vararg spans: Span) = Line(listOf(Span("# ", Tone.DIM)) + spans, indent = 2)
    return listOf(
        note(Span("a shell on this phone, one command at a time. there's no terminal, so vi, less or top without -n 1 won't work. ^C stops a command; cd carries over, variables don't; a running command stops when SR Monitor leaves the screen.", Tone.DIM)),
        if (viaShizuku) {
            note(Span("commands run as shell through shizuku, with the same access as adb: what they change, they change for real.", Tone.DIM))
        } else {
            note(
                Span("commands run as SR Monitor's own user, which sees little of the system. with shizuku they run as the shell user, like adb: ", Tone.DIM),
                Span("set up shizuku", Tone.LINK, Tap.Grant(Access.SHIZUKU)),
            )
        },
    )
}

private const val MIN_INPUT = 12
