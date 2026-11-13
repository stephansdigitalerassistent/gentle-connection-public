package ch.heuscher.gentleconnect.ui.theme

import androidx.compose.ui.graphics.Color

// Calm, warm, high-contrast palette tailored for senior readability
val GentleCream = Color(0xFFFAF7F2)
val GentleDarkBg = Color(0xFF121411)
val GentleSurfaceLight = Color(0xFFFFFFFF)
val GentleSurfaceDark = Color(0xFF1E221D)

val WarmForestGreen = Color(0xFF2D5A27)
val SoftSage = Color(0xFF81C784)
val WarmAmber = Color(0xFFD97706)
val DeepNavy = Color(0xFF1E293B)

// Lighter counterparts, for the dark scheme. The role a colour plays has to survive the switch:
// with WarmAmber left in place on a near-black background it loses its warmth, and SoftSage is
// already light enough to serve as the green.
val WarmAmberOnDark = Color(0xFFF0A83C)
val PaleSage = Color(0xFFA5D6A7)

// Container roles.
//
// Material fills these in from its own baseline palette when they are left unset, and that is how
// a cream-and-forest-green app came to have lavender contact faces, a lavender pill under the
// selected navigation icon and a pink card announcing a message. None of those colours appear
// anywhere in the palette above; they were Material's, showing through the gaps. Every container
// below is derived from the three colours this app actually uses, so a face, a nudge and a
// selected tab now belong to the same picture.
val GreenContainerLight = Color(0xFFD7E8D1)
val OnGreenContainerLight = Color(0xFF12300D)
val SageContainerLight = Color(0xFFE1EFDC)
val OnSageContainerLight = Color(0xFF1B3317)
val AmberContainerLight = Color(0xFFFBE7C6)
val OnAmberContainerLight = Color(0xFF452900)

val GreenContainerDark = Color(0xFF2C4A27)
val OnGreenContainerDark = Color(0xFFCDE8C6)
val SageContainerDark = Color(0xFF33452F)
val OnSageContainerDark = Color(0xFFD8EBD3)
val AmberContainerDark = Color(0xFF4C3714)
val OnAmberContainerDark = Color(0xFFFBE3BB)

// Warning colours, in the palette's own register. Material's default error red is a fire alarm;
// nothing this app has to say to her warrants one.
val TerracottaLight = Color(0xFF9A3412)
val TerracottaContainerLight = Color(0xFFF7DED4)
val OnTerracottaContainerLight = Color(0xFF4A1505)
val TerracottaDark = Color(0xFFE79B80)
val TerracottaContainerDark = Color(0xFF5C2415)
val OnTerracottaContainerDark = Color(0xFFF9DDD4)

val GentleTextPrimaryLight = Color(0xFF0F172A)
val GentleTextPrimaryDark = Color(0xFFF8FAFC)
val GentleTextSecondaryLight = Color(0xFF334155)
val GentleTextSecondaryDark = Color(0xFFCBD5E1)

// Distinct, vibrant reaction colors
val HeartRed = Color(0xFFE53935)
val ThumbsBlue = Color(0xFF1E88E5)
val SmileYellow = Color(0xFFF57C00)
val PrayPurple = Color(0xFF8E24AA)
val LaughOrange = Color(0xFFD81B60)

// Support button blue (calm medium blue, high contrast)
val SupportBlue = Color(0xFF1F5FA8)
val SupportBlueOnDark = Color(0xFF8AB4F8)

