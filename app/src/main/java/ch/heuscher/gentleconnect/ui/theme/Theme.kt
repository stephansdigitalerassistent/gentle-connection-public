package ch.heuscher.gentleconnect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// Roles, so that a colour means the same thing on both schemes:
//   primary   — green  — her own actions: calling, the selected tab, the button she should press
//   secondary — sage   — her post
//   tertiary  — amber  — other people: their faces, and the card saying one of them wrote
// The dark scheme used to swap secondary and tertiary, which turned the mail icon amber and the
// partner faces green the moment the phone dimmed for the evening.
private val LightColorScheme = lightColorScheme(
    primary = WarmForestGreen,
    onPrimary = Color.White,
    primaryContainer = GreenContainerLight,
    onPrimaryContainer = OnGreenContainerLight,
    secondary = SoftSage,
    onSecondary = GentleTextPrimaryLight,
    secondaryContainer = SageContainerLight,
    onSecondaryContainer = OnSageContainerLight,
    tertiary = WarmAmber,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = OnAmberContainerLight,
    error = TerracottaLight,
    onError = Color.White,
    errorContainer = TerracottaContainerLight,
    onErrorContainer = OnTerracottaContainerLight,
    background = GentleCream,
    onBackground = GentleTextPrimaryLight,
    surface = GentleSurfaceLight,
    onSurface = GentleTextPrimaryLight,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = GentleTextSecondaryLight,
    outline = Color(0xFFCBD5E1)
)

private val DarkColorScheme = darkColorScheme(
    primary = SoftSage,
    onPrimary = GentleDarkBg,
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = OnGreenContainerDark,
    secondary = PaleSage,
    onSecondary = GentleDarkBg,
    secondaryContainer = SageContainerDark,
    onSecondaryContainer = OnSageContainerDark,
    tertiary = WarmAmberOnDark,
    onTertiary = GentleDarkBg,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = OnAmberContainerDark,
    error = TerracottaDark,
    onError = GentleDarkBg,
    errorContainer = TerracottaContainerDark,
    onErrorContainer = OnTerracottaContainerDark,
    background = GentleDarkBg,
    onBackground = GentleTextPrimaryDark,
    surface = GentleSurfaceDark,
    onSurface = GentleTextPrimaryDark,
    surfaceVariant = Color(0xFF2C332B),
    onSurfaceVariant = GentleTextSecondaryDark,
    outline = Color(0xFF475569)
)

@Composable
fun GentleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    textSizeMultiplier: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val scales = currentWindowScales()
    val typography = createGentleTypography(textSizeMultiplier * scales.textScale)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography
    ) {
        CompositionLocalProvider(LocalGentleFaceScale provides scales.faceScale) {
            content()
        }
    }
}
