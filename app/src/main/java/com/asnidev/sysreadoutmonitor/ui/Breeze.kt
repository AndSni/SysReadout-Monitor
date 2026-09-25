package com.asnidev.sysreadoutmonitor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.asnidev.sysreadoutmonitor.R
import com.asnidev.sysreadoutmonitor.term.Tone

/** Konsole's "Breeze" colour scheme (konsole/data/color-schemes/Breeze.colorscheme). */
object Breeze {
    val background = Color(0xFF232627)
    val backgroundFaint = Color(0xFF31363B)
    val foreground = Color(0xFFFCFCFC)
    val foregroundIntense = Color(0xFF3DAEE9)
    val red = Color(0xFFED1515)
    val green = Color(0xFF11D116)
    val greenIntense = Color(0xFF1CDC9A)
    val yellow = Color(0xFFF67400)
    val yellowIntense = Color(0xFFFDBC4B)
    val blue = Color(0xFF1D99F3)
    val magenta = Color(0xFF9B59B6)
    val cyan = Color(0xFF1ABC9C)
    val blackIntense = Color(0xFF7F8C8D)
}

/** Hack, Plasma's default fixed-width font, as Konsole shows it. */
val Hack = FontFamily(
    Font(R.font.hack_regular, FontWeight.Normal),
    Font(R.font.hack_bold, FontWeight.Bold),
)

private val bold = FontWeight.Bold

fun Tone.spanStyle(): SpanStyle = when (this) {
    Tone.FG -> SpanStyle(color = Breeze.foreground)
    Tone.KEY -> SpanStyle(color = Breeze.foregroundIntense, fontWeight = bold)
    Tone.GOOD -> SpanStyle(color = Breeze.green)
    Tone.WARN -> SpanStyle(color = Breeze.yellow)
    Tone.CRIT -> SpanStyle(color = Breeze.red)
    Tone.DIM -> SpanStyle(color = Breeze.blackIntense)
    Tone.LINK -> SpanStyle(color = Breeze.cyan, textDecoration = TextDecoration.Underline)
    Tone.SYSTEM -> SpanStyle(color = Breeze.foregroundIntense, fontWeight = bold)
    Tone.USAGE -> SpanStyle(color = Breeze.magenta)
    Tone.SHELL -> SpanStyle(color = Breeze.blue)
    Tone.DNS -> SpanStyle(color = Breeze.greenIntense)
    Tone.NOTIF -> SpanStyle(color = Breeze.yellowIntense)
}
