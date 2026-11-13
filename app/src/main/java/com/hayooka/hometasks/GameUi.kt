package com.hayooka.hometasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** الوضع الفاتح، يختاره كل واحد على جواله من صفحة الترتيب. */
val LightMode = mutableStateOf(false)

// ألوان الواجهة: ليل بنفسجي مع نيون، أو نسخة فاتحة بنفس الألوان بدرجات أغمق عشان تنقرأ
val GNeon: Color get() = if (LightMode.value) Color(0xFF0B8F83) else Color(0xFF5EEAD4)
val GViolet: Color get() = if (LightMode.value) Color(0xFF6A58E8) else Color(0xFF8B7BFF)
val GGold: Color get() = if (LightMode.value) Color(0xFFA87400) else Color(0xFFFFC94A)
val GHot: Color get() = if (LightMode.value) Color(0xFFD23358) else Color(0xFFFF6B8B)
val GNight = Color(0xFF0B1026)

private val NightColors = darkColorScheme(
    primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF06201D),
    primaryContainer = Color(0xFF1D3560), onPrimaryContainer = Color(0xFFF2F4FF),
    secondary = Color(0xFF8B7BFF), onSecondary = Color.White,
    secondaryContainer = Color(0xFF2B2A6B), onSecondaryContainer = Color(0xFFF2F4FF),
    background = GNight, onBackground = Color(0xFFF2F4FF),
    surface = Color(0xFF171D3A), onSurface = Color(0xFFF2F4FF),
    surfaceVariant = Color(0xFF232B52), onSurfaceVariant = Color(0xFFA9B2D6),
    surfaceContainerLowest = Color(0xFF10152E), surfaceContainerLow = Color(0xFF141A36),
    surfaceContainer = Color(0xFF171D3A), surfaceContainerHigh = Color(0xFF1C2346), surfaceContainerHighest = Color(0xFF1F274D),
    outline = Color(0xFF4A578F), outlineVariant = Color(0xFF2E3A6E),
    error = Color(0xFFFF6B8B), onError = Color(0xFF3A0714),
    errorContainer = Color(0xFF4A1830), onErrorContainer = Color(0xFFFFC9D6),
)

private val DayColors = lightColorScheme(
    primary = Color(0xFF0B8F83), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F3EE), onPrimaryContainer = Color(0xFF05302C),
    secondary = Color(0xFF6A58E8), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E0FF), onSecondaryContainer = Color(0xFF1B1452),
    background = Color(0xFFF3F5FC), onBackground = Color(0xFF151A33),
    surface = Color.White, onSurface = Color(0xFF151A33),
    surfaceVariant = Color(0xFFE3E7F5), onSurfaceVariant = Color(0xFF59638A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F9FE),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFFBFBFF), surfaceContainerHighest = Color.White,
    outline = Color(0xFF8E97BC), outlineVariant = Color(0xFFD3D9EE),
    error = Color(0xFFD23358), onError = Color.White,
    errorContainer = Color(0xFFFFE0E7), onErrorContainer = Color(0xFF5C0F22),
)

private val GameShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(30.dp),
)

/** ثيم التطبيق كله: خلفية متدرجة، غامقة أو فاتحة حسب اختيار صاحب الجوال. */
@Composable
fun GameTheme(content: @Composable () -> Unit) {
    val light = LightMode.value
    val scheme = if (light) DayColors else NightColors
    val sky = if (light) listOf(Color(0xFFF3F5FC), Color(0xFFE6E4FB), Color(0xFFF3F5FC)) else listOf(Color(0xFF0B1026), Color(0xFF1B1247), Color(0xFF0B1026))
    MaterialTheme(colorScheme = scheme, shapes = GameShapes) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(sky))) {
            Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = scheme.onBackground, content = content)
        }
    }
}

/** شريط التنقل تحت: حبّة مضيئة على الصفحة الحالية. */
@Composable
fun GameTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        titles.forEachIndexed { i, title ->
            val on = i == selected
            val fill: Brush = if (on) Brush.horizontalGradient(listOf(GNeon, GViolet)) else SolidColor(Color.Transparent)
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(23.dp)).background(fill).clickable { onSelect(i) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(title, fontWeight = if (on) FontWeight.Black else FontWeight.Medium, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** شارة صغيرة ملونة: مستوى، نقاط، سلسلة. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** شريط التقدم للمستوى الجاي. */
@Composable
fun XpBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(16.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.03f, 1f)).clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(GNeon, GViolet))),
        )
    }
}
