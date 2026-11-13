package com.hayooka.hometasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * المهام الشخصية: كل واحد فوق 10 يضيفها ويعدلها ويحذفها بنفسه. ما عليها عقوبة،
 * ونقاطها ما تنحسب إلا بعد موافقة الليدر (ما عدا الاقتراحات الجاهزة).
 */
object Personal {
    const val MAX_TASK = 15
    const val MAX_DAY = 40.0
    const val ALL_DAY = 24 * 60

    /** start و dur بالدقايق. بدون موعد = من 0 ولمدة اليوم كله. */
    data class Suggestion(val title: String, val points: Int, val start: Int, val dur: Int)

    // أوقات الصلاة تقريبية وتتعدل من نفس المهمة
    val suggestions = listOf(
        Suggestion("الدراسة", 15, 0, ALL_DAY),
        Suggestion("الفرنسي", 15, 0, ALL_DAY),
        Suggestion("حفظ القرآن", 15, 0, ALL_DAY),
        Suggestion("القراءة", 10, 0, ALL_DAY),
        Suggestion("مشروع ممتع", 15, 0, ALL_DAY),
        Suggestion("صلاة الفجر", 10, 4 * 60 + 50, 85),
        Suggestion("صلاة الظهر", 10, 11 * 60 + 35, 175),
        Suggestion("صلاة العصر", 10, 14 * 60 + 35, 135),
        Suggestion("صلاة المغرب", 10, 16 * 60 + 55, 80),
        Suggestion("صلاة العشاء", 10, 18 * 60 + 20, 160),
    )
}

/**
 * existing = null: إضافة مهمة جديدة. غير هيك: تعديل أو حذف مهمة موجودة (التعديل يمشي على كل أيامها).
 * onAdd(العنوان، الأيام، البداية، المدة).
 */
@Composable
fun PersonalDialog(
    existing: Task?, taken: Set<String>,
    onAdd: (String, List<Int>, Int, Int) -> Unit,
    onUpdate: (Task, String, Int, Int) -> Unit,
    onDelete: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasTime = existing != null && !existing.allDay
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var timed by remember { mutableStateOf(hasTime) }
    var start by remember { mutableIntStateOf(if (existing != null && hasTime) existing.start else 17 * 60) }
    var end by remember { mutableIntStateOf(if (existing != null && hasTime) existing.start + existing.dur else 18 * 60) }
    var days by remember { mutableStateOf((0..6).toSet()) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "مهمة شخصية جديدة" else "تعديل المهمة الشخصية") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (existing == null) {
                    val free = Personal.suggestions.filter { it.title !in taken }
                    if (free.isNotEmpty()) {
                        Text("اقتراحات", fontSize = 12.sp, color = muted)
                        Chips(free.map { it.title to it.title }, title) { picked ->
                            val s = free.first { it.title == picked }
                            title = s.title
                            timed = s.dur < Personal.ALL_DAY
                            if (timed) { start = s.start; end = s.start + s.dur }
                        }
                    }
                }
                OutlinedTextField(title, { title = it.take(60) }, label = { Text("اسم المهمة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (existing == null) {
                    Text("الأيام", fontSize = 12.sp, color = muted)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DAY_NAMES.forEachIndexed { i, n ->
                            FilterChip(selected = i in days, onClick = { days = if (i in days) days - i else days + i }, label = { Text(n) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("لها موعد محدد", Modifier.weight(1f))
                    Switch(checked = timed, onCheckedChange = { timed = it })
                }
                if (timed) {
                    Stepper("تبدأ", Rules.fmt(start), { start = (start - 15).coerceAtLeast(0) }, { start = (start + 15).coerceAtMost(end - 5) })
                    Stepper("آخر موعد", Rules.fmt(end), { end = (end - 15).coerceAtLeast(start + 5) }, { end = (end + 15).coerceAtMost(24 * 60) })
                }
                Text(
                    if (existing == null) "المساعد يقترح نقاطها، وتنحسب بعد موافقة الليدر. ما عليها عقوبة إذا فاتت."
                    else "التعديل والحذف يمشون على كل أيام هذي المهمة.",
                    fontSize = 12.sp, color = muted,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (existing != null || days.isNotEmpty()),
                onClick = {
                    val s = if (timed) start else 0
                    val d = if (timed) end - start else Personal.ALL_DAY
                    if (existing == null) onAdd(title.trim(), days.sorted(), s, d) else onUpdate(existing, title.trim(), s, d)
                },
            ) { Text(if (existing == null) "إضافة" else "حفظ") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { onDelete(existing) }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("إلغاء") }
            }
        },
    )
}
