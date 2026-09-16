package eu.ottop.yamlauncher.compose

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import eu.ottop.yamlauncher.R

// ---- size tables (ported from UIUtils) ----

private val CLOCK_SIZES = floatArrayOf(48f, 58f, 70f, 78f, 82f, 84f)
private val DATE_SIZES = floatArrayOf(14f, 17f, 20f, 23f, 26f, 29f)
private val APP_SIZES = floatArrayOf(21f, 24f, 27f, 30f, 33f, 36f)
private val REGION_SIZES = floatArrayOf(11f, 14f, 17f, 20f, 23f, 26f)
private val SEARCH_SIZES = floatArrayOf(18f, 21f, 25f, 27f, 30f, 33f)
private val MENU_TITLE_SIZES = floatArrayOf(27f, 30f, 33f, 36f, 39f, 42f)
private val SHORTCUT_SIZES = floatArrayOf(20f, 24f, 28f, 32f, 36f, 40f)

private fun sizeIndex(size: String?): Int = when (size) {
    "tiny" -> 0
    "small" -> 1
    "medium" -> 2
    "large" -> 3
    "extra" -> 4
    "huge" -> 5
    else -> 2
}

fun clockSize(preset: String?): Float = CLOCK_SIZES[sizeIndex(preset)]
fun dateSize(preset: String?): Float = DATE_SIZES[sizeIndex(preset)]
fun appSize(preset: String?): Float = APP_SIZES[sizeIndex(preset)]
fun regionSize(preset: String?): Float = REGION_SIZES[sizeIndex(preset)]
fun searchSize(preset: String?): Float = SEARCH_SIZES[sizeIndex(preset)]
fun menuTitleSize(preset: String?): Float = MENU_TITLE_SIZES[sizeIndex(preset)]
fun shortcutSize(preset: String?): Float = SHORTCUT_SIZES[sizeIndex(preset)]

// ---- fonts ----

// FontFamily instances are cached: building one from a bundled font resource
// parses font data, so recreating it on every launcherTextStyle() call (once
// per alphabet letter, search field, row, ...) stalls composition and scroll.
private val fontFamilyCache = java.util.concurrent.ConcurrentHashMap<String, FontFamily>()

fun fontFamilyFor(fontKey: String?): FontFamily {
    val key = fontKey ?: "system"
    return fontFamilyCache.getOrPut(key) { uncachedFontFamilyFor(key) }
}

private fun uncachedFontFamilyFor(fontKey: String): FontFamily = when (fontKey) {
    "monospace" -> FontFamily.Monospace
    "serif" -> FontFamily.Serif
    "cursive" -> FontFamily.Cursive
    "casual" -> FontFamily.SansSerif
    "sans-serif", "sans-serif-light", "sans-serif-thin", "sans-serif-condensed",
    "sans-serif-condensed-light", "sans-serif-smallcaps" -> FontFamily.SansSerif
    "arbutus" -> FontFamily(Font(R.font.arbutus))
    "ubuntu" -> FontFamily(Font(R.font.ubuntu))
    "ubuntu_light" -> FontFamily(Font(R.font.ubuntu_light, FontWeight.Light))
    "ubuntu_condensed_regular" -> FontFamily(Font(R.font.ubuntu_condensed_regular))
    "workbench" -> FontFamily(Font(R.font.workbench))
    else -> FontFamily.Default
}

fun fontWeightFor(style: String?): FontWeight = when (style) {
    "bold", "bold-italic" -> FontWeight.Bold
    else -> FontWeight.Normal
}

fun fontStyleFor(style: String?): FontStyle = when (style) {
    "italic", "bold-italic" -> FontStyle.Italic
    else -> FontStyle.Normal
}

// ---- text styles ----

fun launcherTextStyle(
    p: UiPrefs,
    fontSize: TextUnit,
    align: TextAlign = TextAlign.Start,
): TextStyle = TextStyle(
    color = Color(p.textColor),
    fontSize = fontSize,
    fontFamily = fontFamilyFor(p.font),
    fontWeight = fontWeightFor(p.textStyle),
    fontStyle = fontStyleFor(p.textStyle),
    textAlign = align,
    shadow = if (p.shadow) Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 4f) else null,
)

fun alignmentFor(key: String?): TextAlign = when (key) {
    "center" -> TextAlign.Center
    "right" -> TextAlign.End
    else -> TextAlign.Start
}

// ---- theme ----

@Composable
fun YamTheme(p: UiPrefs, content: @Composable () -> Unit) {
    val textColor = Color(p.textColor)
    val scheme = darkColorScheme(
        primary = textColor,
        onPrimary = textColor,
        surface = Color(p.bgColor),
        onSurface = textColor,
        background = Color(p.bgColor),
        onBackground = textColor,
        surfaceVariant = Color(p.bgColor),
        onSurfaceVariant = textColor.copy(alpha = 0.7f),
    )
    MaterialTheme(colorScheme = scheme) {
        // Text-only launcher: taps launch directly with no ripple/box highlight.
        CompositionLocalProvider(LocalIndication provides null) {
            content()
        }
    }
}
