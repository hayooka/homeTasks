package com.hayooka.hometasks

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * ست شخصيات أصلية مرسومة بالكود، بنفس أسلوب أيقونة التطبيق.
 * تتطور مع المستوى: تكبر شوي من 1 إلى 4، وشاح من 2، تاج من 3، لمعات من 5، أجنحة من 7، وهالة ذهبية من 10.
 */
val CHARACTER_NAMES = listOf("نجمة", "غيمة", "برق", "ورقة", "موجة", "جمرة")

private class Palette(val base: Color, val shade: Color, val light: Color)

private val PALETTES = listOf(
    Palette(Color(0xFFF8BD42), Color(0xFFF4AE2E), Color(0xFFFFD98A)),
    Palette(Color(0xFF8CC3EE), Color(0xFF6FAEE2), Color(0xFFC7E4F9)),
    Palette(Color(0xFFA78BE6), Color(0xFF8E6FD8), Color(0xFFD4C6F6)),
    Palette(Color(0xFF6CC98A), Color(0xFF52B574), Color(0xFFB9E8C7)),
    Palette(Color(0xFF4FBFBA), Color(0xFF39A7A2), Color(0xFFABE5E1)),
    Palette(Color(0xFFF0845F), Color(0xFFE56B45), Color(0xFFF9C2AC)),
)
val CHARACTER_COLORS: List<Color> = PALETTES.map { it.base }

private val White = Color.White
private val Ink = Color(0xFF1B2430)
private val Pink = Color(0xFFF08A7C)
private val Gold = Color(0xFFFFC94A)
private val Red = Color(0xFFE5484D)
private val Wing = Color(0xFFCFDDF0)

/** يرسم مسار بإحداثيات لوحة التصميم (108 وحدة) ويحوّلها لإحداثيات الشاشة. */
private class Pen(val path: Path, val fx: (Float) -> Float, val fy: (Float) -> Float) {
    private var px = 0f
    private var py = 0f
    fun m(x: Float, y: Float) { path.moveTo(fx(x), fy(y)); px = x; py = y }
    fun l(x: Float, y: Float) { path.lineTo(fx(x), fy(y)); px = x; py = y }
    fun c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
        path.cubicTo(fx(x1), fy(y1), fx(x2), fy(y2), fx(x), fy(y)); px = x; py = y
    }
    /** منحنى تربيعي، محوّل لمكعّب. */
    fun q(qx: Float, qy: Float, x: Float, y: Float) {
        c(px + 2f / 3f * (qx - px), py + 2f / 3f * (qy - py), x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y), x, y)
    }
    fun z() { path.close() }
}

@Composable
fun CharacterView(kind: Int, level: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        val k = ((kind % 6) + 6) % 6
        val p = PALETTES[k]
        // الشخصية تكبر شوي مع أول المستويات. النافذة المرئية من 20 إلى 88 على لوحة 108
        val grow = (0.84f + 0.055f * (level - 1)).coerceAtMost(1f)
        var g = grow
        val fx: (Float) -> Float = { v -> (54f + (v - 54f) * g - 20f) / 68f * w }
        val fy: (Float) -> Float = { v -> (81f + (v - 81f) * g - 20f) / 68f * w }
        fun len(v: Float) = v * g / 68f * w

        fun fill(color: Color, build: Pen.() -> Unit) {
            val path = Path()
            Pen(path, fx, fy).build()
            drawPath(path, color)
        }
        fun line(color: Color, width: Float, build: Pen.() -> Unit) {
            val path = Path()
            Pen(path, fx, fy).build()
            drawPath(path, color, style = Stroke(width = len(width), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun dot(color: Color, cx: Float, cy: Float, r: Float) {
            drawCircle(color, len(r), Offset(fx(cx), fy(cy)))
        }
        fun oval(color: Color, cx: Float, cy: Float, rx: Float, ry: Float, rot: Float) {
            rotate(rot, Offset(fx(cx), fy(cy))) {
                drawOval(color, topLeft = Offset(fx(cx) - len(rx), fy(cy) - len(ry)), size = Size(len(rx) * 2, len(ry) * 2))
            }
        }

        // الهالة والظل بالحجم الكامل
        g = 1f
        if (level >= 10) dot(Gold.copy(alpha = 0.28f), 54f, 56f, 33f)
        oval(Color.Black.copy(alpha = 0.14f), 54f, 81.5f, 19f, 3.4f, 0f)
        g = grow

        if (level >= 7) {
            fill(Wing) { m(31f, 52f); q(20f, 42f, 21f, 56f); q(17f, 60f, 23f, 64f); q(21f, 71f, 32f, 68f); z() }
            fill(Wing) { m(77f, 52f); q(88f, 42f, 87f, 56f); q(91f, 60f, 85f, 64f); q(87f, 71f, 76f, 68f); z() }
        }

        // الميزة اللي تميّز كل شخصية، ورا الجسم
        when (k) {
            0 -> {
                fill(p.shade) { m(34f, 46f); q(31f, 27f, 36f, 26.5f); q(40f, 26.5f, 51f, 38f); z() }
                fill(p.shade) { m(74f, 46f); q(77f, 27f, 72f, 26.5f); q(68f, 26.5f, 57f, 38f); z() }
                fill(p.light) { m(36.5f, 40f); q(35.5f, 31.5f, 37.5f, 31.5f); q(39.5f, 32f, 45f, 38f); z() }
                fill(p.light) { m(71.5f, 40f); q(72.5f, 31.5f, 70.5f, 31.5f); q(68.5f, 32f, 63f, 38f); z() }
            }
            1 -> {
                dot(p.shade, 41f, 38f, 8.5f)
                dot(p.shade, 67f, 38f, 8.5f)
                dot(p.base, 54f, 33f, 10.5f)
                dot(p.light, 51f, 29.5f, 3.2f)
            }
            2 -> {
                dot(p.shade, 35f, 43f, 6.5f)
                dot(p.shade, 73f, 43f, 6.5f)
                dot(p.light, 35f, 43f, 3f)
                dot(p.light, 73f, 43f, 3f)
                line(p.shade, 2.6f) { m(54f, 36f); l(54f, 29f) }
                fill(Gold) { m(57f, 13f); l(48.5f, 24.5f); l(53.5f, 24.5f); l(51f, 32f); l(60f, 20.5f); l(55f, 20.5f); z() }
            }
            3 -> {
                line(p.shade, 2.6f) { m(54f, 36f); l(54f, 26f) }
                fill(p.shade) { m(54f, 27f); q(43f, 15f, 37f, 24f); q(43f, 34f, 54f, 27f); z() }
                fill(p.base) { m(54f, 27f); q(65f, 15f, 71f, 24f); q(65f, 34f, 54f, 27f); z() }
                line(p.light, 1.2f) { m(41f, 24.5f); l(50f, 26.5f) }
                line(p.light, 1.2f) { m(67f, 24.5f); l(58f, 26.5f) }
            }
            4 -> {
                fill(p.shade) { m(45f, 38f); q(50f, 19f, 63f, 23f); q(58f, 30f, 63f, 38f); z() }
                fill(p.shade) { m(30f, 57f); q(20f, 52f, 21.5f, 66f); q(26f, 66.5f, 31f, 64f); z() }
                fill(p.shade) { m(78f, 57f); q(88f, 52f, 86.5f, 66f); q(82f, 66.5f, 77f, 64f); z() }
            }
            else -> {
                fill(p.shade) { m(43f, 39f); q(35f, 31f, 42f, 22f); q(50f, 31f, 43f, 39f); z() }
                fill(p.shade) { m(65f, 39f); q(73f, 31f, 66f, 22f); q(58f, 31f, 65f, 39f); z() }
                fill(Gold) { m(54f, 37f); q(45f, 28f, 54f, 15f); q(63f, 28f, 54f, 37f); z() }
                fill(p.light) { m(54f, 35f); q(50f, 29f, 54f, 23f); q(58f, 29f, 54f, 35f); z() }
            }
        }

        // الجسم
        fill(p.base) { m(54f, 34f); c(70f, 34f, 80f, 44f, 80f, 58.5f); c(80f, 72f, 70f, 80.5f, 54f, 80.5f); c(38f, 80.5f, 28f, 72f, 28f, 58.5f); c(28f, 44f, 38f, 34f, 54f, 34f); z() }
        fill(p.shade) { m(28.6f, 63f); c(31f, 74f, 41f, 80.5f, 54f, 80.5f); c(67f, 80.5f, 77f, 74f, 79.4f, 63f); c(74f, 71f, 65f, 75f, 54f, 75f); c(43f, 75f, 34f, 71f, 28.6f, 63f); z() }
        oval(p.light, 45f, 41.5f, 9f, 3.2f, -18f)

        // الوجه
        oval(White, 44f, 56f, 5.6f, 6.6f, 0f)
        oval(White, 64f, 56f, 5.6f, 6.6f, 0f)
        dot(Ink, 45f, 57f, 3.6f)
        dot(Ink, 65f, 57f, 3.6f)
        dot(White, 46.4f, 55.4f, 1.3f)
        dot(White, 66.4f, 55.4f, 1.3f)
        oval(Pink, 36.2f, 64.5f, 3.6f, 2.2f, 0f)
        oval(Pink, 71.8f, 64.5f, 3.6f, 2.2f, 0f)
        line(Ink, 2.1f) { m(49.5f, 65.5f); q(54f, 70.5f, 58.5f, 65.5f) }

        if (level >= 2) {
            fill(Red) { m(30.5f, 68.5f); q(54f, 78.5f, 77.5f, 68.5f); l(78.4f, 72.6f); q(54f, 83f, 29.6f, 72.6f); z() }
            fill(Red) { m(62f, 76f); l(69f, 86f); l(61.5f, 83.5f); z() }
        }
        if (level >= 3) {
            fill(Gold) { m(58f, 38f); l(57f, 27.5f); l(62f, 32.5f); l(66f, 25.5f); l(70f, 32.5f); l(75f, 27.5f); l(74f, 38f); z() }
            dot(Red, 66f, 34.5f, 1.5f)
        }
        if (level >= 5) {
            g = 1f
            fill(Gold) { m(82f, 21.5f); q(82.99f, 26.01f, 87.5f, 27f); q(82.99f, 27.99f, 82f, 32.5f); q(81.01f, 27.99f, 76.5f, 27f); q(81.01f, 26.01f, 82f, 21.5f); z() }
            fill(Gold) { m(25f, 27.4f); q(25.648f, 30.352f, 28.6f, 31f); q(25.648f, 31.648f, 25f, 34.6f); q(24.352f, 31.648f, 21.4f, 31f); q(24.352f, 30.352f, 25f, 27.4f); z() }
            fill(Gold) { m(85f, 44f); q(85.54f, 46.46f, 88f, 47f); q(85.54f, 47.54f, 85f, 50f); q(84.46f, 47.54f, 82f, 47f); q(84.46f, 46.46f, 85f, 44f); z() }
        }
    }
}
