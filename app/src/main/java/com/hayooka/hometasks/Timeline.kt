package com.hayooka.hometasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.roundToInt

private const val T_START = 4 * 60
private const val T_END = 24 * 60
private const val DP_PER_MIN = 1.6f
private const val SNAP = 15
private val AXIS_W = 46.dp
private val GRIP_W = 20.dp

val MEMBER_COLORS = listOf(
    Color(0xFF7B4BB8), Color(0xFF2A6ACB), Color(0xFFC93F73),
    Color(0xFF23805F), Color(0xFFB5740F), Color(0xFF0D6B6B),
)

private fun hourLabel(h: Int) = "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "ص" else "م"}"

/** لما تتداخل مهمتين عند نفس الشخص، كل وحدة تاخذ عمود جنب الثانية. يرجّع (العمود، عدد الأعمدة). */
private fun packColumns(list: List<Task>): Map<String, Pair<Int, Int>> {
    val res = HashMap<String, Pair<Int, Int>>()
    var cluster = ArrayList<Pair<Task, Int>>()
    var end = -1
    fun flush() {
        val n = cluster.maxOf { it.second } + 1
        for ((t, c) in cluster) res[t.id] = c to n
        cluster = ArrayList()
    }
    for (t in list.sortedWith(compareBy<Task> { it.start }.thenByDescending { it.dur })) {
        if (cluster.isNotEmpty() && t.start >= end) { flush(); end = -1 }
        val used = cluster.filter { it.first.start + maxOf(it.first.dur, SNAP) > t.start }.map { it.second }
        var c = 0
        while (c in used) c++
        cluster.add(t to c)
        end = maxOf(end, t.start + maxOf(t.dur, SNAP))
    }
    if (cluster.isNotEmpty()) flush()
    return res
}

/** جدول زمني لليوم: عمود لكل شخص، والمهام تنسحب لتغيير وقتها ومدتها. */
@Composable
fun TimelineEditor(
    members: List<Member>, tasks: List<Task>, today: LocalDate,
    onSave: (Task) -> Unit, onDelete: (String) -> Unit, onRename: (String, String) -> Unit,
) {
    var day by remember { mutableIntStateOf(Rules.dayIndex(today)) }
    var who by remember { mutableStateOf("all") }
    var editing by remember { mutableStateOf<Task?>(null) }
    val hs = rememberScrollState()
    val vs = rememberScrollState()
    val pxPerMin = with(LocalDensity.current) { DP_PER_MIN.dp.toPx() }
    val total = ((T_END - T_START) * DP_PER_MIN).dp
    LaunchedEffect(Unit) { vs.scrollTo(((8.5f * 60 - T_START) * pxPerMin).toInt()) }

    val colors = members.mapIndexed { i, m -> m.id to MEMBER_COLORS[i % MEMBER_COLORS.size] }.toMap()
    val lanes = if (who == "all") members else members.filter { it.id == who }
    val dayTasks = tasks.filter { it.day == day && !it.allDay }
    val line = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Chips(DAY_NAMES.mapIndexed { i, n -> i.toString() to n }, day.toString()) { day = it.toInt() }
            Chips(listOf("all" to "الكل") + members.map { it.id to it.name }, who) { who = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = Task("", "", (lanes.firstOrNull() ?: members.first()).id, day, 16 * 60, 30, 5) }) { Text("مهمة جديدة") }
                Text("اسحبي الشريط الملوّن لتحريك المهمة، والحافة السفلية لتغيير آخر موعد. اضغطي المهمة للتعديل.", fontSize = 11.sp, lineHeight = 14.sp, color = muted)
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val laneW = maxOf(132.dp, (maxWidth - AXIS_W) / lanes.size.coerceAtLeast(1))
            Column(Modifier.fillMaxSize()) {
                // أسماء الأشخاص: ثابتة فوق وتتحرك بالعرض مع الجدول
                Row {
                    Spacer(Modifier.width(AXIS_W))
                    Row(Modifier.weight(1f).horizontalScroll(hs)) {
                        for (m in lanes) {
                            val count = dayTasks.count { it.memberId == m.id }
                            Column(Modifier.width(laneW).padding(horizontal = 6.dp, vertical = 4.dp)) {
                                Text(m.name, fontWeight = FontWeight.Bold, color = colors[m.id] ?: MaterialTheme.colorScheme.primary)
                                Text("$count مهام", fontSize = 11.sp, color = muted)
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.weight(1f)) {
                    // عمود الساعات: ثابت على الطرف ويتحرك بالطول مع الجدول
                    Box(Modifier.width(AXIS_W).fillMaxHeight().verticalScroll(vs)) {
                        Box(Modifier.height(total).fillMaxWidth()) {
                            for (h in T_START / 60..23) {
                                Text(
                                    hourLabel(h), fontSize = 11.sp, color = muted, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().offset(y = ((h * 60 - T_START) * DP_PER_MIN).dp),
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(vs).horizontalScroll(hs)) {
                        Row(Modifier.height(total)) {
                            for (m in lanes) {
                                val mine = dayTasks.filter { it.memberId == m.id }
                                val cols = packColumns(mine)
                                val color = colors[m.id] ?: MaterialTheme.colorScheme.primary
                                Box(
                                    Modifier.width(laneW).fillMaxHeight().drawBehind {
                                        for (h in 0..(T_END - T_START) / 60) {
                                            val y = h * 60 * pxPerMin
                                            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                                        }
                                        drawLine(line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
                                    },
                                ) {
                                    for (t in mine) {
                                        key(t.id) {
                                            val (c, n) = cols[t.id] ?: (0 to 1)
                                            TaskBlock(t, color, c, n, laneW, pxPerMin, onTap = { editing = t }, onChange = onSave)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val e = editing
    if (e != null) {
        TaskDialog(
            e, members,
            onSave = { t ->
                editing = null
                // الاسم الجديد يمشي على كل المهام اللي لها نفس الاسم القديم
                if (e.id.isNotEmpty() && t.title != e.title) onRename(e.title, t.title)
                onSave(t)
            },
            onDelete = if (e.id.isEmpty()) null else ({ editing = null; onDelete(e.id) }),
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun TaskBlock(t: Task, color: Color, col: Int, cols: Int, laneW: Dp, pxPerMin: Float, onTap: () -> Unit, onChange: (Task) -> Unit) {
    // القيم الحيّة أثناء السحب؛ ترجع تتطابق مع المحفوظ أول ما يوصل التحديث
    var start by remember(t.id, t.start) { mutableIntStateOf(t.start) }
    var dur by remember(t.id, t.dur) { mutableIntStateOf(t.dur) }
    val w = laneW / cols

    Box(
        Modifier
            .offset(x = w * col, y = ((start.coerceAtLeast(T_START) - T_START) * DP_PER_MIN).dp)
            .size(width = w - 2.dp, height = (maxOf(dur, SNAP) * DP_PER_MIN).dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .clickable(onClick = onTap),
    ) {
        Row(Modifier.fillMaxSize()) {
            // مقبض التحريك
            Box(
                Modifier.width(GRIP_W).fillMaxHeight().background(color).pointerInput(t.id, t.start, t.dur) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                        onDragEnd = { if (start != t.start) onChange(t.copy(start = start)) },
                        onDragCancel = { start = t.start },
                    ) { change, dy ->
                        change.consume()
                        acc += dy
                        start = (t.start + ((acc / pxPerMin) / SNAP).roundToInt() * SNAP).coerceIn(T_START, T_END - t.dur)
                    }
                },
            )
            Column(Modifier.weight(1f).padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text(
                    t.title, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = if (dur >= 30) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
                if (dur >= 30) {
                    Text("لحد ${Rules.fmt(start + dur)}", fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // مقبض تغيير المدة
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(10.dp).padding(start = GRIP_W).pointerInput(t.id, t.start, t.dur) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = { if (dur != t.dur) onChange(t.copy(dur = dur)) },
                    onDragCancel = { dur = t.dur },
                ) { change, dy ->
                    change.consume()
                    acc += dy
                    dur = (t.dur + ((acc / pxPerMin) / SNAP).roundToInt() * SNAP).coerceIn(SNAP, maxOf(SNAP, T_END - t.start))
                }
            },
        )
    }
}
