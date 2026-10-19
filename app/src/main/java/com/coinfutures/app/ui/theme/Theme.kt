package com.coinfutures.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coinfutures.app.data.settings.ThemeMode

/** Semantic colors beyond Material roles. State is never conveyed by color alone (FR05). */
@Immutable
data class TradeColors(
    val long: Color,
    val longContainer: Color,
    val short: Color,
    val shortContainer: Color,
    val wait: Color,
    val waitContainer: Color,
    val noTrade: Color,
    val noTradeContainer: Color,
    val unavailable: Color,
    val unavailableContainer: Color,
    val support: Color,
    val resistance: Color,
    val entry: Color,
    val stop: Color,
    val target: Color,
    val candleUp: Color,
    val candleDown: Color,
    val grid: Color,
    val chartText: Color,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF54D1C5),
    onPrimary = Color(0xFF00332F),
    primaryContainer = Color(0xFF0F4A45),
    onPrimaryContainer = Color(0xFFB6F1EA),
    secondary = Color(0xFFF2A65A),
    onSecondary = Color(0xFF3D2200),
    secondaryContainer = Color(0xFF4F3311),
    onSecondaryContainer = Color(0xFFFFDDBA),
    tertiary = Color(0xFFA9C7FF),
    onTertiary = Color(0xFF0A2F5F),
    background = Color(0xFF0A1318),
    onBackground = Color(0xFFE3ECEE),
    surface = Color(0xFF0A1318),
    onSurface = Color(0xFFE3ECEE),
    surfaceVariant = Color(0xFF1B2A31),
    onSurfaceVariant = Color(0xFFB4C4C9),
    surfaceContainerLowest = Color(0xFF060D11),
    surfaceContainerLow = Color(0xFF0F1A20),
    surfaceContainer = Color(0xFF132128),
    surfaceContainerHigh = Color(0xFF192A32),
    surfaceContainerHighest = Color(0xFF20333C),
    outline = Color(0xFF6B7F86),
    outlineVariant = Color(0xFF2C3E46),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0905),
    errorContainer = Color(0xFF5C1712),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF00695F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4EFE7),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF8A5100),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCBC),
    onSecondaryContainer = Color(0xFF2C1600),
    tertiary = Color(0xFF2F5DA8),
    onTertiary = Color.White,
    background = Color(0xFFF5F8F8),
    onBackground = Color(0xFF111A1D),
    surface = Color(0xFFF5F8F8),
    onSurface = Color(0xFF111A1D),
    surfaceVariant = Color(0xFFDCE5E7),
    onSurfaceVariant = Color(0xFF3F4C50),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F4F4),
    surfaceContainer = Color(0xFFEAF0F0),
    surfaceContainerHigh = Color(0xFFE4EBEB),
    surfaceContainerHighest = Color(0xFFDEE6E6),
    outline = Color(0xFF6F7C80),
    outlineVariant = Color(0xFFC3CED1),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkTrade = TradeColors(
    long = Color(0xFF4ADE9B), longContainer = Color(0xFF0E3A27),
    short = Color(0xFFFF8A7A), shortContainer = Color(0xFF4A1B16),
    wait = Color(0xFFF5C451), waitContainer = Color(0xFF43340D),
    noTrade = Color(0xFFB0BEC5), noTradeContainer = Color(0xFF263238),
    unavailable = Color(0xFFC4A7FF), unavailableContainer = Color(0xFF33264F),
    support = Color(0xFF54D1C5), resistance = Color(0xFFF2A65A), entry = Color(0xFFA9C7FF),
    stop = Color(0xFFFF6B6B), target = Color(0xFF4ADE9B),
    candleUp = Color(0xFF4ADE9B), candleDown = Color(0xFFFF7B72),
    grid = Color(0x22FFFFFF), chartText = Color(0xFF9FB2B8),
)

private val LightTrade = TradeColors(
    long = Color(0xFF0B7A47), longContainer = Color(0xFFD3F5E3),
    short = Color(0xFFB3261E), shortContainer = Color(0xFFFDE2DE),
    wait = Color(0xFF8A6100), waitContainer = Color(0xFFFFF0C7),
    noTrade = Color(0xFF455A64), noTradeContainer = Color(0xFFE3E9EC),
    unavailable = Color(0xFF5B3EA6), unavailableContainer = Color(0xFFEDE5FF),
    support = Color(0xFF00897B), resistance = Color(0xFFC46A00), entry = Color(0xFF2F5DA8),
    stop = Color(0xFFC62828), target = Color(0xFF0B7A47),
    candleUp = Color(0xFF0F9D58), candleDown = Color(0xFFD93025),
    grid = Color(0x1A000000), chartText = Color(0xFF55666B),
)

val LocalTradeColors = staticCompositionLocalOf { DarkTrade }

/** Tabular figures keep prices aligned in lists and tables. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

val MonoNumeric = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CoinFuturesTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalTradeColors provides if (dark) DarkTrade else LightTrade) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

object AppTheme {
    val trade: TradeColors @Composable get() = LocalTradeColors.current
}
