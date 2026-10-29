package de.greenganja.growguardian.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Farben aus dem App-Design: dunkles Waldgrün, leuchtendes Grün als Akzent, Gold für Premium. */
object GrowPalette {
    val Background = Color(0xFF07130D)
    val Surface = Color(0xFF0E1E16)
    val SurfaceHigh = Color(0xFF14281D)
    val Outline = Color(0xFF223A2C)
    val Green = Color(0xFF4ADE80)
    val GreenDeep = Color(0xFF0B3B21)
    val GreenSoft = Color(0xFF8EF0B4)
    val OnGreen = Color(0xFF04210F)
    val Gold = Color(0xFFF5C33B)
    val OnGold = Color(0xFF2B1D00)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFF87171)
    val Text = Color(0xFFE8F3EC)
    val TextMuted = Color(0xFF94AE9F)
}

private val DarkGreenScheme = darkColorScheme(
    primary = GrowPalette.Green,
    onPrimary = GrowPalette.OnGreen,
    primaryContainer = GrowPalette.GreenDeep,
    onPrimaryContainer = GrowPalette.GreenSoft,
    secondary = GrowPalette.Gold,
    onSecondary = GrowPalette.OnGold,
    background = GrowPalette.Background,
    onBackground = GrowPalette.Text,
    surface = GrowPalette.Background,
    onSurface = GrowPalette.Text,
    surfaceVariant = GrowPalette.SurfaceHigh,
    onSurfaceVariant = GrowPalette.TextMuted,
    surfaceContainerLowest = GrowPalette.Background,
    surfaceContainerLow = GrowPalette.Surface,
    surfaceContainer = GrowPalette.Surface,
    surfaceContainerHigh = GrowPalette.SurfaceHigh,
    surfaceContainerHighest = GrowPalette.SurfaceHigh,
    outline = GrowPalette.Outline,
    outlineVariant = GrowPalette.Outline,
    error = GrowPalette.Error,
    onError = Color(0xFF2A0606),
)

private val GrowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * App-Theme im Grow-Guardian-Design. Immer dunkel, wie im Entwurf.
 * Einmal um den Inhalt der Activity legen, dann übernehmen auch die Club-Bildschirme den Stil:
 *
 * ```
 * setContent { GrowGuardianTheme { AppNavigation() } }
 * ```
 */
@Composable
fun GrowGuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkGreenScheme, shapes = GrowShapes, content = content)
}

/** Farben des Mitgliederbereichs im Club: dunkles Aubergine, Rot-Lila als Akzent. */
object ClubMemberPalette {
    val Background = Color(0xFF14071A)
    val Surface = Color(0xFF210C27)
    val SurfaceHigh = Color(0xFF2E1235)
    val Outline = Color(0xFF4D2457)
    val Accent = Color(0xFFD9467F)
    val AccentDeep = Color(0xFF4A1238)
    val AccentSoft = Color(0xFFF5A3C7)
    val OnAccent = Color(0xFF2B0618)
    val Violet = Color(0xFFB57BEE)
    val OnViolet = Color(0xFF1F0A33)
    val Text = Color(0xFFF7E9F3)
    val TextMuted = Color(0xFFBBA0B8)
    val Error = Color(0xFFFF8A8A)
}

private val ClubMemberScheme = darkColorScheme(
    primary = ClubMemberPalette.Accent,
    onPrimary = ClubMemberPalette.OnAccent,
    primaryContainer = ClubMemberPalette.AccentDeep,
    onPrimaryContainer = ClubMemberPalette.AccentSoft,
    secondary = ClubMemberPalette.Violet,
    onSecondary = ClubMemberPalette.OnViolet,
    background = ClubMemberPalette.Background,
    onBackground = ClubMemberPalette.Text,
    surface = ClubMemberPalette.Background,
    onSurface = ClubMemberPalette.Text,
    surfaceVariant = ClubMemberPalette.SurfaceHigh,
    onSurfaceVariant = ClubMemberPalette.TextMuted,
    surfaceContainerLowest = ClubMemberPalette.Background,
    surfaceContainerLow = ClubMemberPalette.Surface,
    surfaceContainer = ClubMemberPalette.Surface,
    surfaceContainerHigh = ClubMemberPalette.SurfaceHigh,
    surfaceContainerHighest = ClubMemberPalette.SurfaceHigh,
    outline = ClubMemberPalette.Outline,
    outlineVariant = ClubMemberPalette.Outline,
    error = ClubMemberPalette.Error,
    onError = Color(0xFF2A0606),
)

/** Rot-Lila für den Mitgliederbereich ([member] = true), sonst unverändert das App-Grün. */
@Composable
fun ClubAreaTheme(member: Boolean, content: @Composable () -> Unit) {
    if (member) MaterialTheme(colorScheme = ClubMemberScheme, shapes = GrowShapes, content = content) else content()
}
