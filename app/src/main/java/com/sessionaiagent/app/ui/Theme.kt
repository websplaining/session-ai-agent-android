package com.sessionaiagent.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette matching sessionaiagent.com */
object Saa {
    val Bg = Color(0xFF0A0A0A)
    val Surface = Color(0xFF0F0F0F)
    val Border = Color(0xFF1F3A1F)
    val Text = Color(0xFFC0D0C0)
    val Muted = Color(0xFF5A7A5A)
    val Accent = Color(0xFF00FFA3)
    val Danger = Color(0xFFFF5555)
    val CodeBg = Color(0xFF0D120D)
    val BtnBg = Color(0xFF151F15)
    val BtnHover = Color(0xFF1F2F1F)
}

private val scheme = darkColorScheme(
    primary = Saa.Accent,
    onPrimary = Color(0xFF00210F),
    background = Saa.Bg,
    onBackground = Saa.Text,
    surface = Saa.Surface,
    onSurface = Saa.Text,
    surfaceVariant = Saa.BtnBg,
    onSurfaceVariant = Saa.Text,
    outline = Saa.Border,
    error = Saa.Danger,
    onError = Color(0xFF2A0000),
)

@Composable
fun SaaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
