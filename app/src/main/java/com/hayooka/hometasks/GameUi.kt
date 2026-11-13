package com.hayooka.hometasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ألوان الواجهة: ليل بنفسجي غامق مع نيون
val GNeon = Color(0xFF5EEAD4)
val GViolet = Color(0xFF8B7BFF)
val GGold = Color(0xFFFFC94A)
val GHot = Color(0xFFFF6B8B)
val GNight = Color(0xFF0B1026)
private val GCard = Color(0xFF171D3A)
private val GLine = Color(0xFF2E3A6E)
private val GText = Color(0xFFF2F4FF)
private val GMuted = Color(0xFFA9B2D6)

val GameColors = darkColorScheme(
    primary = GNeon, onPrimary = Color(0xFF06201D),
    primaryContainer = Color(0xFF1D3560), onPrimaryContainer = GText,
    secondary = GViolet, onSecondary = Color.White,
    secondaryContainer = Color(0xFF2B2A6B), onSecondaryContainer = GText,
    background = GNight, onBackground = GText,
    surface = GCard, onSurface = GText,
    surfaceVariant = Color(0xFF232B52), onSurfaceVariant = GMuted,
    surfaceContainerLowest = Color(0xFF10152E), surfaceContainerLow = Color(0xFF141A36),
    surfaceContainer = GCard, surfaceContainerHigh = Color(0xFF1C2346), surfaceContainerHighest = Color(0xFF1F274D),
    outline = Color(0xFF4A578F), outlineVariant = GLine,
    error = GHot, onError = Color(0xFF3A0714),
    errorContainer = Color(0xFF4A1830), onErrorContainer = Color(0xFFFFC9D6),
)

private val GameShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(30.dp),
)

/** ثيم التطبيق كله: خلفية متدرجة وألوان ثابتة غامقة بأي وضع للجوال. */
@Composable
fun GameTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GameColors, shapes = GameShapes) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1026), Color(0xFF1B1247), Color(0xFF0B1026))))) {
            Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = GText, content = content)
        }
    }
}

/** شريط التنقل تحت: حبّة مضيئة على الصفحة الحالية. */
@Composable
fun GameTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp)).background(GCard).border(1.dp, GLine, RoundedCornerShape(28.dp)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        titles.forEachIndexed { i, title ->
            val on = i == selected
            val fill: Brush = if (on) Brush.horizontalGradient(listOf(GNeon, GViolet)) else SolidColor(Color.Transparent)
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(23.dp)).background(fill).clickable { onSelect(i) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(title, fontWeight = if (on) FontWeight.Black else FontWeight.Medium, color = if (on) GNight else GMuted)
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
    Box(modifier.height(16.dp).clip(RoundedCornerShape(50)).background(Color(0xFF232B52)).border(1.dp, GLine, RoundedCornerShape(50))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.03f, 1f)).clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(GNeon, GViolet))),
        )
    }
}
