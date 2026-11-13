@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.hayooka.hometasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun Home(repo: Repo, code: String, meId: String, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val houseState by remember(code) { repo.houseFlow(code) }.collectAsState(initial = null)
    val membersState by remember(code) { repo.membersFlow(code) }.collectAsState(initial = null)
    val tasks by remember(code) { repo.tasksFlow(code) }.collectAsState(initial = emptyList())
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }

    val today = now.toLocalDate()
    val weekStart = Rules.weekStart(today)
    val since = weekStart.minusDays(1).toString() // يوم زيادة عشان نحسب عقوبة مهام أمس
    val done by remember(code, since) { repo.doneFlow(code, since) }.collectAsState(initial = emptyList())

    val house = houseState
    val members = membersState
    if (house == null || members == null) { Centered { CircularProgressIndicator() }; return }
    val me = members.firstOrNull { it.id == meId }
    if (me == null) { LaunchedEffect(Unit) { onLogout() }; return }
    if (me.character < 0) {
        ChooseCharacter { k -> scope.launch { try { repo.setCharacter(code, meId, k) } catch (e: Exception) { ctx.toast("صار خطأ، حاول مرة ثانية") } } }
        return
    }

    val date = today.toString()
    val nowMin = now.hour * 60 + now.minute
    val toggle: (Task, Done?) -> Unit = { t, d ->
        scope.launch {
            try {
                if (d != null) repo.undo(code, t, date, d.points)
                else repo.complete(code, t, date, Rules.pointsNow(t, nowMin, house.grace))
            } catch (e: Exception) { ctx.toast("ما انحفظ، تأكد من الإنترنت") }
        }
    }

    val pages = if (me.leader) listOf("مهامي", "الترتيب", "الإدارة") else listOf("مهامي", "الترتيب")
    val pager = rememberPagerState(pageCount = { pages.size })
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pager.currentPage.coerceAtMost(pages.size - 1)) {
            pages.forEachIndexed { i, title ->
                Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(title) })
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> TodayPage(me, house, tasks, done, today, nowMin, toggle)
                1 -> RankPage(members, done, weekStart.toString(), meId, onLogout)
                else -> ManagePage(repo, code, house, members, tasks, done, today)
            }
        }
    }
}

// ---------------- مهامي ----------------

@Composable
fun TodayPage(me: Member, house: House, tasks: List<Task>, done: List<Done>, today: LocalDate, nowMin: Int, onToggle: (Task, Done?) -> Unit) {
    val date = today.toString()
    val mine = tasks.filter { it.memberId == me.id && it.day == Rules.dayIndex(today) }.sortedBy { it.start }
    val doneToday = done.filter { it.date == date }.associateBy { it.taskId }
    val earned = mine.sumOf { doneToday[it.id]?.points ?: 0 }
    val possible = mine.sumOf { it.points }

    val yesterday = today.minusDays(1)
    val yTasks = tasks.filter { it.memberId == me.id && it.day == Rules.dayIndex(yesterday) }
    val yDone = done.filter { it.date == yesterday.toString() && it.memberId == me.id }.map { it.taskId }.toSet()
    val counted = house.startDate.isNotEmpty() && yesterday.toString() >= house.startDate
    val penalty = if (counted) Rules.penalty(house, me.age, yTasks.count { it.id !in yDone }, yTasks.size) else null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CharacterView(me.character, Rules.level(me.total), Modifier.size(72.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("أهلاً ${me.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("المستوى ${Rules.level(me.total)} · ${me.total} نقطة", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { Rules.levelProgress(me.total) }, modifier = Modifier.fillMaxWidth())
            }
        }
        Text("${DAY_NAMES[Rules.dayIndex(today)]} · نقاط اليوم $earned من $possible", fontWeight = FontWeight.Medium)

        if (penalty != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("عقوبة اليوم", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(penalty, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("بسبب مهام أمس اللي ما انعملت. تتصفّر بكرة.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        if (mine.isEmpty()) Text("ما عليك مهام اليوم.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (t in mine) {
            val d = doneToday[t.id]
            val late = d == null && Rules.isLate(t, nowMin, house.grace)
            val current = nowMin >= t.start && nowMin <= t.start + t.dur
            Card(
                Modifier.fillMaxWidth().clickable { onToggle(t, d) },
                colors = CardDefaults.cardColors(
                    containerColor = if (current && d == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = d != null, onCheckedChange = { onToggle(t, d) })
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontWeight = FontWeight.Medium, textDecoration = if (d != null) TextDecoration.LineThrough else null)
                        Text(
                            "${Rules.fmt(t.start)} إلى ${Rules.fmt(t.start + t.dur)}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val label = when {
                        d != null -> "تم +${d.points}"
                        late -> "متأخر · ${Rules.pointsNow(t, nowMin, house.grace)} من ${t.points}"
                        else -> "${t.points} نقطة"
                    }
                    Text(
                        label, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp),
                        color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

// ---------------- الترتيب ----------------

@Composable
fun RankPage(members: List<Member>, done: List<Done>, weekStart: String, meId: String, onLogout: () -> Unit) {
    val week = done.filter { it.date >= weekStart }.groupBy { it.memberId }.mapValues { e -> e.value.sumOf { it.points } }
    val ranked = members.sortedByDescending { week[it.id] ?: 0 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ترتيب هذا الأسبوع", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        ranked.forEachIndexed { i, m ->
            val pts = week[m.id] ?: 0
            val first = i == 0 && pts > 0
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (first) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${i + 1}", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
                    if (m.character >= 0) CharacterView(m.character, Rules.level(m.total), Modifier.size(52.dp)) else Spacer(Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name + if (m.id == meId) " (أنت)" else "", fontWeight = FontWeight.Medium)
                        Text("المستوى ${Rules.level(m.total)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("$pts", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("الترتيب يبدأ من جديد كل يوم أحد. المستوى يضل معك.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onLogout) { Text("تسجيل خروج") }
    }
}

// ---------------- الإدارة (لليدر) ----------------

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(value, Modifier.widthIn(min = 84.dp).padding(horizontal = 8.dp), textAlign = TextAlign.Center)
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
fun ManagePage(repo: Repo, code: String, house: House, members: List<Member>, tasks: List<Task>, done: List<Done>, today: LocalDate) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("today") }
    fun act(block: suspend () -> Unit) {
        scope.launch { try { block() } catch (e: Exception) { ctx.toast("ما انحفظ، تأكدي من الإنترنت") } }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Chips(listOf("today" to "اليوم", "tasks" to "المهام", "members" to "الأعضاء", "settings" to "الإعدادات"), tab) { tab = it }
        }
        if (tab == "tasks") {
            TimelineEditor(
                members, tasks, today,
                onSave = { t -> act { repo.saveTask(code, t) } },
                onDelete = { id -> act { repo.deleteTask(code, id) } },
            )
        } else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        when (tab) {
            "today" -> {
                val date = today.toString()
                for (m in members) {
                    val mine = tasks.filter { it.memberId == m.id && it.day == Rules.dayIndex(today) }
                    val d = done.filter { it.date == date && it.memberId == m.id }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Text(m.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                Text("${d.size} من ${mine.size} · ${d.sumOf { it.points }} نقطة")
                            }
                            LinearProgressIndicator(progress = { if (mine.isEmpty()) 0f else d.size / mine.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            "members" -> {
                var newName by remember { mutableStateOf("") }
                var newAge by remember { mutableStateOf("adult") }
                var removing by remember { mutableStateOf<Member?>(null) }
                val ages = AGE_KEYS.map { it to (AGE_NAMES[it] ?: it) }
                for (m in members) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(m.name + if (m.leader) " (ليدر)" else "", fontWeight = FontWeight.Bold)
                            Chips(ages, m.age) { a -> act { repo.setAge(code, m.id, a) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!m.leader) {
                                    TextButton(onClick = { act { repo.setPin(code, m.id, ""); ctx.toast("يختار رقم جديد أول ما يدخل") } }) { Text("تصفير الرقم السري") }
                                    TextButton(onClick = { removing = m }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("عضو جديد", fontWeight = FontWeight.Bold)
                OutlinedTextField(newName, { newName = it.take(20) }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Chips(ages, newAge) { newAge = it }
                Button(enabled = newName.isNotBlank(), onClick = { val n = newName.trim(); newName = ""; act { repo.addMember(code, n, newAge) } }) { Text("إضافة") }

                val r = removing
                if (r != null) {
                    AlertDialog(
                        onDismissRequest = { removing = null },
                        title = { Text("حذف ${r.name}؟") },
                        text = { Text("مهامه تبقى بالجدول بدون شخص، وتقدرين تنقلينها لغيره.") },
                        confirmButton = { TextButton(onClick = { removing = null; act { repo.deleteMember(code, r.id) } }) { Text("حذف") } },
                        dismissButton = { TextButton(onClick = { removing = null }) { Text("إلغاء") } },
                    )
                }
            }

            else -> {
                var grace by remember(house.grace) { mutableIntStateOf(house.grace) }
                var pen by remember(house.penalties) { mutableStateOf(DEFAULT_PENALTIES + house.penalties) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("كود البيت", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(code, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("أعطيه لإخوانك عشان يدخلون.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Stepper("المهلة بعد وقت المهمة", "$grace د", { grace = (grace - 5).coerceAtLeast(0) }, { grace = (grace + 5).coerceAtMost(180) })
                Text("بعد المهلة تنقص النقاط 10% كل ربع ساعة لحد النص.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text("العقوبات", fontWeight = FontWeight.Bold)
                Text("خفيفة إذا فاتته مهمة أو أكثر، وثقيلة إذا فاته أكثر من نص مهام اليوم.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (age in AGE_KEYS) {
                    Text(AGE_NAMES[age] ?: age, fontWeight = FontWeight.Medium)
                    for ((suffix, label) in listOf("_light" to "خفيفة", "_heavy" to "ثقيلة")) {
                        val key = age + suffix
                        OutlinedTextField(pen[key] ?: "", { v -> pen = pen + (key to v.take(80)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Button(onClick = { act { repo.saveSettings(code, grace, pen); ctx.toast("انحفظ") } }, modifier = Modifier.fillMaxWidth()) { Text("حفظ الإعدادات") }
                HorizontalDivider()
                var confirmReset by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) { Text("إرجاع الجدول الأصلي") }
                if (confirmReset) {
                    AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("إرجاع الجدول الأصلي؟") },
                        text = { Text("تنمسح كل المهام الحالية وتعديلاتك عليها، ويرجع جدول الأسبوع الأساسي. النقاط المجموعة تبقى، بس اللي تأشّر اليوم يرجع غير مؤشّر.") },
                        confirmButton = { TextButton(onClick = { confirmReset = false; act { repo.resetTasks(code); ctx.toast("رجع الجدول الأصلي") } }) { Text("إرجاع") } },
                        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("إلغاء") } },
                    )
                }
            }
        }
        }
    }
}

@Composable
fun TaskDialog(initial: Task, members: List<Member>, onSave: (Task) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    var pts by remember { mutableStateOf(initial.points.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "مهمة جديدة" else "تعديل المهمة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(t.title, { t = t.copy(title = it.take(60)) }, label = { Text("اسم المهمة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Chips(members.map { it.id to it.name }, t.memberId) { t = t.copy(memberId = it) }
                Chips(DAY_NAMES.mapIndexed { i, n -> i.toString() to n }, t.day.toString()) { t = t.copy(day = it.toInt()) }
                Stepper("تبدأ", Rules.fmt(t.start), { t = t.copy(start = (t.start - 15).coerceAtLeast(0)) }, { t = t.copy(start = (t.start + 15).coerceAtMost(23 * 60 + 45)) })
                Stepper("المدة", "${t.dur} د", { t = t.copy(dur = (t.dur - 5).coerceAtLeast(5)) }, { t = t.copy(dur = (t.dur + 5).coerceAtMost(240)) })
                OutlinedTextField(
                    pts, { v -> pts = v.filter { it.isDigit() }.take(3) }, label = { Text("النقاط") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = t.title.isNotBlank() && (pts.toIntOrNull() ?: 0) > 0,
                onClick = { onSave(t.copy(title = t.title.trim(), points = pts.toInt())) },
            ) { Text("حفظ") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("إلغاء") }
            }
        },
    )
}
