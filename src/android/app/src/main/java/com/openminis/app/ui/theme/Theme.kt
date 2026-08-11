package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Reference UI palette: cool neutral canvas, white cards and a restrained
// violet accent. Keeping the palette semantic lets every existing Material
// component adopt the new visual language without page-specific hard-coding.
private val TealPrimary = Color(0xFF252733)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFE8E9ED)
private val TealOnPrimaryContainer = Color(0xFF252733)
private val TealSecondary = Color(0xFF626570)
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealSecondaryContainer = Color(0xFFECEDEF)
private val TealOnSecondaryContainer = Color(0xFF292B33)
private val TealTertiary = Color(0xFF5271C4)
private val TealOnTertiary = Color(0xFFFFFFFF)
private val TealTertiaryContainer = Color(0xFFE3EAFF)
private val TealOnTertiaryContainer = Color(0xFF172A61)
private val TealOnBackground = Color(0xFF252733)
private val TealOnSurface = Color(0xFF252733)
private val TealOnSurfaceVariant = Color(0xFF6F7280)

private val TealDarkPrimary = Color(0xFFE1E1E6)
private val TealDarkOnPrimary = Color(0xFF24242B)
private val TealDarkPrimaryContainer = Color(0xFF3A3A44)
private val TealDarkOnPrimaryContainer = Color(0xFFF0F0F3)
private val TealDarkSecondary = Color(0xFFC7C7CF)
private val TealDarkOnSecondary = Color(0xFF303038)
private val TealDarkSecondaryContainer = Color(0xFF45454E)
private val TealDarkOnSecondaryContainer = Color(0xFFE7E7EC)
private val TealDarkOnBackground = Color(0xFFE8E8ED)
private val TealDarkOnSurface = Color(0xFFE8E8ED)
private val TealDarkOnSurfaceVariant = Color(0xFFB9B8C2)

// Neutral grouped-card surfaces (iOS-style system-grouped background).
// Override Material3's tonal `surfaceContainer*` so cards don't pick up the
// teal primary tint.
// Light: page = #F2F2F7 gray, card = white
// Dark:  page = #000, card = #1C1C1E
private val NeutralGroupedBg = Color(0xFFF5F6F8)
private val NeutralGroupedCard = Color(0xFFFFFFFF)
private val NeutralGroupedCardElevated = Color(0xFFF0F1F5)
private val NeutralOutline = Color(0xFFE2E3E9)

private val NeutralDarkGroupedBg = Color(0xFF121217)
private val NeutralDarkGroupedCard = Color(0xFF1D1D24)
private val NeutralDarkGroupedCardElevated = Color(0xFF292932)
private val NeutralDarkOutline = Color(0xFF3B3B46)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,
    background = NeutralGroupedBg,
    onBackground = TealOnBackground,
    surface = NeutralGroupedCard,
    onSurface = TealOnSurface,
    surfaceVariant = NeutralGroupedCardElevated,
    onSurfaceVariant = TealOnSurfaceVariant,
    surfaceContainerLowest = NeutralGroupedBg,
    surfaceContainerLow = NeutralGroupedCard,
    surfaceContainer = NeutralGroupedCard,
    surfaceContainerHigh = NeutralGroupedCardElevated,
    surfaceContainerHighest = NeutralGroupedCardElevated,
    outline = NeutralOutline,
    outlineVariant = NeutralOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealDarkPrimary,
    onPrimary = TealDarkOnPrimary,
    primaryContainer = TealDarkPrimaryContainer,
    onPrimaryContainer = TealDarkOnPrimaryContainer,
    secondary = TealDarkSecondary,
    onSecondary = TealDarkOnSecondary,
    secondaryContainer = TealDarkSecondaryContainer,
    onSecondaryContainer = TealDarkOnSecondaryContainer,
    background = NeutralDarkGroupedBg,
    onBackground = TealDarkOnBackground,
    surface = NeutralDarkGroupedCard,
    onSurface = TealDarkOnSurface,
    surfaceVariant = NeutralDarkGroupedCardElevated,
    onSurfaceVariant = TealDarkOnSurfaceVariant,
    surfaceContainerLowest = NeutralDarkGroupedBg,
    surfaceContainerLow = NeutralDarkGroupedCard,
    surfaceContainer = NeutralDarkGroupedCard,
    surfaceContainerHigh = NeutralDarkGroupedCardElevated,
    surfaceContainerHighest = NeutralDarkGroupedCardElevated,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutline,
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val typography = scaledTypography(fontScale)
    val chatPalette = if (darkTheme) DarkChatPalette else LightChatPalette

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(LocalChatPalette provides chatPalette, content = content)
    }
}

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

private fun scaledTypography(factor: Float): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}
