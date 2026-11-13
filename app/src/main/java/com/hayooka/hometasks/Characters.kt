package com.hayooka.hometasks

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** ست شخصيات أصلية مرسومة بالكود. تتطور مع المستوى: خدود من 2، تاج من 3، لمعة من 5. */
val CHARACTER_NAMES = listOf("نجمة", "غيمة", "برق", "ورقة", "موجة", "جمرة")
val CHARACTER_COLORS = listOf(
    Color(0xFFF2B33D), Color(0xFF7FB7E6), Color(0xFF8E6FD8),
    Color(0xFF5DBB7A), Color(0xFF3FA7A3), Color(0xFFE8744F),
)
private val Ink = Color(0xFF1B2430)
private val Gold = Color(0xFFF5C542)

private fun DrawScope.tri(color: Color, a: Offset, b: Offset, c: Offset) {
    val p = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }
    drawPath(p, color)
}

@Composable
fun CharacterView(kind: Int, level: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        val k = ((kind % 6) + 6) % 6
        val c = CHARACTER_COLORS[k]
        fun o(x: Float, y: Float) = Offset(w * x, w * y)

        // الميزة اللي تميّز كل شخصية، ورا الجسم
        when (k) {
            0 -> { tri(c, o(.22f, .40f), o(.26f, .14f), o(.44f, .32f)); tri(c, o(.78f, .40f), o(.74f, .14f), o(.56f, .32f)) }
            1 -> { drawCircle(c, w * .15f, o(.34f, .32f)); drawCircle(c, w * .18f, o(.52f, .26f)); drawCircle(c, w * .13f, o(.68f, .33f)) }
            2 -> {
                drawLine(c, o(.50f, .30f), o(.44f, .18f), strokeWidth = w * .04f, cap = StrokeCap.Round)
                drawLine(c, o(.44f, .18f), o(.54f, .12f), strokeWidth = w * .04f, cap = StrokeCap.Round)
                drawCircle(Gold, w * .05f, o(.54f, .10f))
            }
            3 -> {
                drawLine(c, o(.50f, .30f), o(.50f, .14f), strokeWidth = w * .035f, cap = StrokeCap.Round)
                drawOval(c, topLeft = o(.50f, .08f), size = Size(w * .20f, w * .11f))
                drawOval(c, topLeft = o(.30f, .12f), size = Size(w * .20f, w * .11f))
            }
            4 -> { tri(c, o(.40f, .32f), o(.52f, .10f), o(.62f, .32f)); tri(c, o(.20f, .62f), o(.06f, .54f), o(.20f, .76f)); tri(c, o(.80f, .62f), o(.94f, .54f), o(.80f, .76f)) }
            else -> { tri(c, o(.30f, .36f), o(.34f, .16f), o(.46f, .32f)); tri(Gold, o(.42f, .32f), o(.50f, .08f), o(.58f, .32f)); tri(c, o(.54f, .32f), o(.66f, .16f), o(.70f, .36f)) }
        }

        // الرجلين والجسم
        drawCircle(c, w * .08f, o(.38f, .90f))
        drawCircle(c, w * .08f, o(.62f, .90f))
        drawRoundRect(c, topLeft = o(.18f, .28f), size = Size(w * .64f, w * .60f), cornerRadius = CornerRadius(w * .28f, w * .28f))

        // الوجه
        if (level >= 2) {
            drawCircle(Color(0x55E0566F), w * .055f, o(.29f, .64f))
            drawCircle(Color(0x55E0566F), w * .055f, o(.71f, .64f))
        }
        for (x in listOf(.38f, .62f)) {
            drawCircle(Color.White, w * .09f, o(x, .52f))
            drawCircle(Ink, w * .045f, o(x + .01f, .53f))
        }
        drawArc(
            Ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = o(.42f, .60f), size = Size(w * .16f, w * .10f),
            style = Stroke(width = w * .03f, cap = StrokeCap.Round),
        )

        if (level >= 3) {
            val crown = Path().apply {
                moveTo(w * .36f, w * .30f); lineTo(w * .34f, w * .16f); lineTo(w * .43f, w * .23f)
                lineTo(w * .50f, w * .12f); lineTo(w * .57f, w * .23f); lineTo(w * .66f, w * .16f)
                lineTo(w * .64f, w * .30f); close()
            }
            drawPath(crown, Gold)
        }
        if (level >= 5) {
            drawCircle(Gold, w * .025f, o(.10f, .26f))
            drawCircle(Gold, w * .035f, o(.90f, .20f))
            drawCircle(Gold, w * .02f, o(.92f, .44f))
        }
    }
}
