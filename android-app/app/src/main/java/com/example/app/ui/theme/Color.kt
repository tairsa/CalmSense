package com.example.app.ui.theme

import androidx.compose.ui.graphics.Color

val CalmMint = Color(0xFFE0F2F1)
val DeepTeal = Color(0xFF00695C)
val SoftBlue = Color(0xFFE3F2FD)
val CalmSage = Color(0xFF81C784)
val TextGray = Color(0xFF455A64)

// Secondary text (hints, subtitles, descriptions). Every one of them used to be
// TextGray at 50-70% opacity, which measured 2.0-3.5:1 - under the 4.5:1 body
// text needs. This is 4.8:1 on the mint background and 5.6:1 on white cards,
// and still visibly lighter than body text (6.3:1).
val TextMuted = Color(0xFF566B73)

// Alerts and severity. Dark enough to read as text on their own 15% tint
// (5.1:1 and 4.8:1); the old #EF5350 / #FFA726 were 2.9:1 and 1.8:1.
val AlertRed = Color(0xFFB3261E)
val WarnAmber = Color(0xFFA84300)

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)