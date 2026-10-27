package ir.peygir.app

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat

private val PersianFont = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)
private val BaseTypography = Typography()
private val PeygirTypography = with(BaseTypography) {
    Typography(
        displayLarge = displayLarge.copy(fontFamily = PersianFont, letterSpacing = 0.sp),
        displayMedium = displayMedium.copy(fontFamily = PersianFont, letterSpacing = 0.sp),
        displaySmall = displaySmall.copy(fontFamily = PersianFont, letterSpacing = 0.sp),
        headlineLarge = headlineLarge.copy(fontFamily = PersianFont, lineHeight = 48.sp, letterSpacing = 0.sp),
        headlineMedium = headlineMedium.copy(fontFamily = PersianFont, lineHeight = 44.sp, letterSpacing = 0.sp),
        headlineSmall = headlineSmall.copy(fontFamily = PersianFont, fontSize = 22.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
        titleLarge = titleLarge.copy(fontFamily = PersianFont, fontSize = 20.sp, lineHeight = 30.sp, letterSpacing = 0.sp),
        titleMedium = titleMedium.copy(fontFamily = PersianFont, lineHeight = 28.sp, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontFamily = PersianFont, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontFamily = PersianFont, lineHeight = 30.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontFamily = PersianFont, fontSize = 15.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontFamily = PersianFont, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontFamily = PersianFont, lineHeight = 24.sp, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontFamily = PersianFont, lineHeight = 22.sp, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontFamily = PersianFont, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    )
}

@Composable fun PeygirTheme(mode: String, dynamicColors: Boolean = false, language: String = "fa", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    // Official M3 baseline palettes; optional Material You on Android 12+.
    val colors = when {
        dynamicColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(tertiaryContainer=Color(0xFF254E3B),onTertiaryContainer=Color(0xFFB7EBD3))
        else -> lightColorScheme(tertiaryContainer=Color(0xFFD5F2E3),onTertiaryContainer=Color(0xFF163E2E))
    }
    val activity = LocalActivity.current
    val view = LocalView.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides if (language == "en") LayoutDirection.Ltr else LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colors, typography = PeygirTypography,
            shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp)),
            content = content)
    }
}

