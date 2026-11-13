@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.hayooka.hometasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                else repo.complete(code, t, date, Rules.pointsNow(t, nowMin, house.grace), nowMin)
            } catch (e: Exception) { ctx.toast("ما انحفظ، تأكد من الإنترنت") }
        }
    }

    LaunchedEffect(Unit) { Guard.start(ctx) }

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

    val st = Penalty.forMember(house, me, tasks, done, today, nowMin)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // الشخصية هي أول وأكبر شي بالصفحة
        val kind = ((me.character % 6) + 6) % 6
        val level = Rules.level(me.total)
        val left = Rules.LEVEL_SIZE - (me.total % Rules.LEVEL_SIZE)
        val allDone = mine.isNotEmpty() && mine.all { doneToday[it.id] != null }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(230.dp).clip(CircleShape).background(CHARACTER_COLORS[kind].copy(alpha = 0.16f)))
                CharacterView(me.character, level, Modifier.size(200.dp))
            }
            Text("${me.name} · ${CHARACTER_NAMES[kind]}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("المستوى $level · ${me.total} نقطة", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { Rules.levelProgress(me.total) }, modifier = Modifier.width(230.dp))
            Text("باقي $left نقطة للمستوى الجاي", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (allDone) "${DAY_NAMES[Rules.dayIndex(today)]} · خلصت كل مهامك! $earned نقطة"
            else "${DAY_NAMES[Rules.dayIndex(today)]} · نقاط اليوم $earned من $possible",
            fontWeight = FontWeight.Medium,
        )

        if (house.enforce && Penalty.enforcedOnPhone(me)) GuardSetupCard()
        if (st.missed > 0) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("عقوبة: فاتتك ${st.missed} مهام اليوم", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(Penalty.describe(st, me), color = MaterialTheme.colorScheme.onErrorContainer)
                    if (me.leader) Text("عندك تنعرض بس، بدون قطع أو قفل.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
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
                            "من ${Rules.fmt(t.start)} · آخر موعد ${Rules.fmt(t.start + t.dur)}",
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
            Chips(listOf("today" to "اليوم", "tasks" to "المهام", "ai" to "المساعد", "members" to "الأعضاء", "settings" to "الإعدادات"), tab) { tab = it }
        }
        if (tab == "tasks") {
            TimelineEditor(
                members, tasks, today,
                onSave = { t -> act { repo.saveTask(code, t) } },
                onDelete = { id -> act { repo.deleteTask(code, id) } },
            )
        } else if (tab == "ai") {
            AssistantTab(repo, code, house, members)
        } else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        when (tab) {
            "today" -> {
                val date = today.toString()
                val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
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
                            val st = Penalty.forMember(house, m, tasks, done, today, nowMin)
                            if (st.missed > 0) Text("فاتته ${st.missed} · ${Penalty.describe(st, m)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            val stale = System.currentTimeMillis() - m.guardAt > 20 * 60_000
                            if (house.enforce && Penalty.enforcedOnPhone(m) && (stale || !m.guardOk)) {
                                Text("العقوبات مو مفعّلة على جواله حالياً", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("كود البيت", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(code, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("أعطيه لإخوانك عشان يدخلون.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Stepper("مهلة بعد آخر موعد", "$grace د", { grace = (grace - 5).coerceAtLeast(0) }, { grace = (grace + 5).coerceAtMost(180) })
                Text("اللي يخلص قبل آخر موعد (مع المهلة) ياخذ النقاط كاملة، وبعدها تنقص 10% كل ربع ساعة لحد النص.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { act { repo.saveSettings(code, grace, house.penalties); ctx.toast("انحفظ") } }, modifier = Modifier.fillMaxWidth()) { Text("حفظ المهلة") }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("تنفيذ العقوبات على الجوالات", fontWeight = FontWeight.Bold)
                        Text(if (house.enforce) "شغال" else "متوقف", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = house.enforce,
                        onCheckedChange = { on ->
                            val t = java.time.LocalTime.now()
                            act { repo.setEnforce(code, on, today.toString(), t.hour * 60 + t.minute) }
                        },
                    )
                }
                Text(
                    "المهمة الفايتة الأولى = ساعة بدون إنترنت، الثانية = ساعتين، الثالثة = 3 ساعات، والرابعة = قفل الجوال لحد ما تنعمل كل المهام الفايتة. " +
                        "الحساب يبدأ من لحظة التشغيل، وكل شي يتصفّر آخر اليوم. إيقافه من هنا يفك أي قطع أو قفل فوراً. " +
                        "ما ينطبق على جوال الليدر ولا على اللي تحت 10.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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
                // البداية تتحرك وآخر موعد يبقى ثابت، وآخر موعد يتحرك والبداية تبقى ثابتة
                val deadline = t.start + t.dur
                fun moveStart(s: Int) { val ns = s.coerceIn(0, deadline - 5); t = t.copy(start = ns, dur = deadline - ns) }
                fun moveDeadline(d: Int) { t = t.copy(dur = d.coerceIn(t.start + 5, 24 * 60) - t.start) }
                Stepper("تبدأ", Rules.fmt(t.start), { moveStart(t.start - 15) }, { moveStart(t.start + 15) })
                Stepper("آخر موعد", Rules.fmt(deadline), { moveDeadline(deadline - 15) }, { moveDeadline(deadline + 15) })
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

/** يظهر للعضو لحد ما يعطي التطبيق الصلاحيات اللازمة لتنفيذ العقوبات على جواله. */
@Composable
fun GuardSetupCard() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); refresh++ } }
    val vpnLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { refresh++ }
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { refresh++ }
    val vpnOk = remember(refresh) { Guard.vpnReady(ctx) }
    val overlayOk = remember(refresh) { Guard.overlayReady(ctx) }
    if (vpnOk && overlayOk) return

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("فعّل العقوبات على جوالك", fontWeight = FontWeight.Bold)
            Text("البيت متفق إن المهام الفايتة لها عقوبة: قطع الإنترنت أو قفل الجوال. التطبيق يحتاج إذنين عشان ينفذها.", fontSize = 13.sp)
            if (!vpnOk) Button(onClick = {
                val i = android.net.VpnService.prepare(ctx)
                if (i != null) vpnLauncher.launch(i) else refresh++
            }) { Text("1. السماح بقطع الإنترنت") }
            if (!overlayOk) Button(onClick = {
                try {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (e: Exception) { ctx.toast("افتح الإعدادات ← التطبيقات ← مهام البيت ← الظهور فوق التطبيقات") }
            }) { Text("2. السماح بشاشة القفل") }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                TextButton(onClick = { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("السماح بالإشعارات (اختياري)") }
            }
        }
    }
}
