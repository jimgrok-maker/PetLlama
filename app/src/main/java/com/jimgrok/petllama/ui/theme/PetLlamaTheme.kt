package com.jimgrok.petllama.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Obsidian + Warm Llama Amber palette for PetLlama.
 * High contrast on OLED screens with clear separation between canvas,
 * elevated cards, code blocks, and user/assistant chat bubbles.
 */
object PetLlamaColors {
    val Canvas = Color(0xFF0E1015)
    val Surface = Color(0xFF141821)
    val SurfaceCard = Color(0xFF181D28)
    val SurfaceElevated = Color(0xFF1F2634)
    val CodeBackground = Color(0xFF0A0D13)
    val CodeHeader = Color(0xFF131823)

    val BorderSubtle = Color(0xFF252D3D)
    val BorderStrong = Color(0xFF333F56)

    val Amber = Color(0xFFF59E0B)
    val AmberSoft = Color(0xFF2C2211)
    val AmberOn = Color(0xFF1A1205)

    val UserBubble = Color(0xFF22304A)
    val UserBubbleBorder = Color(0xFF33476B)
    val AssistantBubble = Color(0xFF171C26)
    val AssistantBubbleBorder = Color(0xFF262F40)

    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)

    val Emerald = Color(0xFF34D399)
    val EmeraldSoft = Color(0xFF102822)
    val Sky = Color(0xFF38BDF8)
    val SkySoft = Color(0xFF102433)
    val Coral = Color(0xFFF87171)
    val CoralSoft = Color(0xFF2D161A)
}

private val PetLlamaDarkScheme = darkColorScheme(
    primary = PetLlamaColors.Amber,
    onPrimary = PetLlamaColors.AmberOn,
    primaryContainer = PetLlamaColors.UserBubble,
    onPrimaryContainer = PetLlamaColors.TextPrimary,
    secondary = PetLlamaColors.Sky,
    onSecondary = Color(0xFF061B26),
    secondaryContainer = PetLlamaColors.SurfaceElevated,
    onSecondaryContainer = PetLlamaColors.TextPrimary,
    tertiary = PetLlamaColors.Emerald,
    onTertiary = Color(0xFF042219),
    background = PetLlamaColors.Canvas,
    onBackground = PetLlamaColors.TextPrimary,
    surface = PetLlamaColors.Surface,
    onSurface = PetLlamaColors.TextPrimary,
    surfaceVariant = PetLlamaColors.SurfaceCard,
    onSurfaceVariant = PetLlamaColors.TextSecondary,
    outline = PetLlamaColors.BorderStrong,
    outlineVariant = PetLlamaColors.BorderSubtle,
    error = PetLlamaColors.Coral,
    onError = Color(0xFF2B0A0D),
    errorContainer = PetLlamaColors.CoralSoft,
    onErrorContainer = Color(0xFFFECACA)
)

private val PetLlamaTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

@Composable
fun PetLlamaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PetLlamaDarkScheme,
        typography = PetLlamaTypography,
        content = content
    )
}
