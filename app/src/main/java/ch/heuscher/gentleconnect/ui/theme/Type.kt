package ch.heuscher.gentleconnect.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

fun createGentleTypography(scaleMultiplier: Float = 1.0f): Typography {
    val scale = scaleMultiplier.coerceIn(0.8f, 2.0f)
    return Typography(
        displayLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = (48.sp.value * scale).sp,
            lineHeight = (56.sp.value * scale).sp
        ),
        displayMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = (36.sp.value * scale).sp,
            lineHeight = (44.sp.value * scale).sp
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = (24.sp.value * scale).sp,
            lineHeight = (30.sp.value * scale).sp
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = (20.sp.value * scale).sp,
            lineHeight = (26.sp.value * scale).sp
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = (18.sp.value * scale).sp,
            lineHeight = (26.sp.value * scale).sp
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = (16.sp.value * scale).sp,
            lineHeight = (24.sp.value * scale).sp
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = (18.sp.value * scale).sp,
            lineHeight = (24.sp.value * scale).sp
        )
    )
}
