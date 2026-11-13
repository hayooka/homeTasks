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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    // نحمّل من أول الشهر (لترتيب الشهر) أو من قبل بداية الأسبوع بيوم، الأقدم منهم
    val monthStart = today.withDayOfMonth(1)
    val since = (if (monthStart < weekStart.minusDays(1)) monthStart else weekStart.minusDays(1)).toString()
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
                if (d != null) repo.undo(code, t, date, d)
                else {
                    // المتأخرة تنتظر موافقة القائد (إلا مهام القائد نفسها)
                    val late = Rules.isLate(t, nowMin, house.grace)
                    var pts = Rules.pointsNow(t, nowMin, house.grace).toDouble()
                    if (t.personal) {
                        // سقف يومي لنقاط المهام الشخصية
                        val ids = tasks.filter { it.personal }.map { it.id }.toSet()
                        val got = done.filter { it.date == date && it.memberId == me.id && it.ok && it.taskId in ids }.sumOf { it.points }
                        pts = minOf(pts, (Personal.MAX_DAY - got).coerceAtLeast(0.0))
                    }
                    // المتأخرة والشخصية اللي لسا ما انوافق على نقاطها تنتظر القائد
                    repo.complete(code, t, date, pts, nowMin, (!late || me.leader) && (!t.personal || t.approved))
                    if (!late) {
                        val mine = tasks.filter { it.memberId == me.id && it.day == Rules.dayIndex(today) }
                        if (Rules.allOnTime(mine, done.filter { it.date == date }, t.id, house.grace)) repo.awardStreak(code, me, today)
                    }
                }
            } catch (e: Exception) { ctx.toast("ما انحفظ، تأكد من الإنترنت") }
        }
    }

    // إذن الإشعارات ينطلب من الكل مرة أول ما يفتحون، عشان توصلهم تذكيرات المهام
    val askNotif = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        Guard.start(ctx)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (e: Exception) { }
        }
    }
    LaunchedEffect(house.seedVersion, me.leader) {
        if (me.leader && house.seedVersion < SEED_VERSION) try { repo.migrateSeed(code, house.seedVersion) } catch (e: Exception) { }
    }

    val pages = if (me.leader) listOf("مهامي", "الترتيب", "الإدارة") else listOf("مهامي", "الترتيب")
    val pager = rememberPagerState(pageCount = { pages.size })
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> TodayPage(repo, code, me, house, tasks, done, today, nowMin, toggle)
                1 -> RankPage(repo, code, members, done, weekStart.toString(), monthStart.toString(), meId, house, tasks, today, nowMin, onLogout)
                else -> ManagePage(repo, code, house, members, tasks, done, today)
            }
        }
        GameTabs(pages, pager.currentPage.coerceAtMost(pages.size - 1)) { i -> scope.launch { pager.animateScrollToPage(i) } }
    }
}

// ---------------- مهامي ----------------

@Composable
fun TodayPage(
    repo: Repo, code: String, me: Member, house: House, tasks: List<Task>, done: List<Done>,
    today: LocalDate, nowMin: Int, onToggle: (Task, Done?) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    val date = today.toString()
    val mine = tasks.filter { it.memberId == me.id && it.day == Rules.dayIndex(today) }.sortedBy { it.start }
    val doneToday = done.filter { it.date == date }.associateBy { it.taskId }
    val earned = mine.sumOf { t -> doneToday[t.id]?.takeIf { it.ok }?.points ?: 0.0 }
    val streak = Rules.streakNow(me, today)
    val possible = mine.sumOf { it.points }

    val st = Penalty.forMember(house, me, tasks, done, today, nowMin)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // الشخصية هي أول وأكبر شي بالصفحة
        val kind = ((me.character % 6) + 6) % 6
        val level = Rules.level(me.total)
        val left = Rules.LEVEL_SIZE - (me.total % Rules.LEVEL_SIZE)
        val allDone = mine.isNotEmpty() && mine.all { doneToday[it.id]?.ok == true }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(contentAlignment = Alignment.Center) {
                // هالة مضيئة بلون الشخصية
                Box(
                    Modifier.size(260.dp).background(
                        Brush.radialGradient(listOf(CHARACTER_COLORS[kind].copy(alpha = 0.55f), CHARACTER_COLORS[kind].copy(alpha = 0.10f), Color.Transparent)),
                        CircleShape,
                    ),
                )
                CharacterView(me.character, level, Modifier.size(215.dp))
            }
            Text(me.name, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("المستوى $level", GNeon)
                Pill("${Rules.pts(me.total)} نقطة", GGold)
                if (streak > 0) Pill("سلسلة $streak", GHot)
            }
            XpBar(Rules.levelProgress(me.total), Modifier.fillMaxWidth(0.82f))
            Text("باقي ${Rules.pts(left)} نقطة للمستوى الجاي", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (streak > 0) "مكافأة اليوم الكامل الجاي +${Rules.pts(DAILY_BONUS + 0.5 * (streak + if (me.streakDate == date) 0 else 1))}"
                else "خلّص كل مهامك بوقتها: +12 نقطة عن كل يوم كامل، ومعها سلسلة تزيد نص نقطة كل يوم",
                fontSize = 12.sp, color = GNeon,
            )
        }
        Text(
            if (allDone) "${DAY_NAMES[Rules.dayIndex(today)]} · خلصت كل مهامك! ${Rules.pts(earned)} نقطة"
            else "${DAY_NAMES[Rules.dayIndex(today)]} · نقاط اليوم ${Rules.pts(earned)} من $possible",
            fontSize = 18.sp, fontWeight = FontWeight.Bold,
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

        val chores = mine.filter { !it.personal }
        val personal = mine.filter { it.personal }
        if (chores.isEmpty()) Text("ما عليك مهام بيت اليوم.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (t in chores) TaskRow(t, doneToday[t.id], nowMin, house.grace, onToggle, null)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("مهامي الشخصية", fontWeight = FontWeight.Bold)
                Text("أنت تضيفها وتعدلها. بدون عقوبة، والنقاط بعد موافقة القائد.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { adding = true }) { Text("إضافة") }
        }
        for (t in personal) TaskRow(t, doneToday[t.id], nowMin, house.grace, onToggle) { editing = t }
    }

    val e = editing
    if (adding || e != null) {
        PersonalDialog(
            existing = e,
            taken = tasks.filter { it.memberId == me.id && it.personal }.map { it.title }.toSet(),
            onAdd = { title, days, start, dur ->
                adding = false
                scope.launch {
                    try {
                        // الاقتراحات الجاهزة موافق عليها بنقاطها، وغيرها يقدّرها المساعد وتنتظر القائد
                        val sug = Personal.suggestions.firstOrNull { it.title == title }
                        val key = house.aiKey.ifBlank { BuildConfig.GEMINI_KEY }
                        val points = sug?.points ?: if (key.isBlank()) 5 else try {
                            Ai.estimate(key, house.aiModel.ifBlank { DEFAULT_AI_MODEL }, title, Personal.MAX_TASK)
                        } catch (x: Exception) { 5 }
                        repo.addTasks(code, days.map { d -> Task("", title, me.id, d, start, dur, points, personal = true, approved = sug != null) })
                    } catch (x: Exception) { ctx.toast("ما انحفظ، تأكد من الإنترنت") }
                }
            },
            onUpdate = { old, title, start, dur ->
                editing = null
                scope.launch {
                    try { repo.updatePersonal(code, me.id, old.title, title, start, dur) } catch (x: Exception) { ctx.toast("ما انحفظ، تأكد من الإنترنت") }
                }
            },
            onDelete = { old ->
                editing = null
                scope.launch {
                    try { repo.deletePersonal(code, me.id, old.title) } catch (x: Exception) { ctx.toast("ما انحذف، تأكد من الإنترنت") }
                }
            },
            onDismiss = { adding = false; editing = null },
        )
    }
}

/** صف مهمة في صفحة "مهامي". onEdit موجود بس للمهام الشخصية. */
@Composable
private fun TaskRow(t: Task, d: Done?, nowMin: Int, grace: Int, onToggle: (Task, Done?) -> Unit, onEdit: (() -> Unit)?) {
    val late = d == null && !t.allDay && Rules.isLate(t, nowMin, grace)
    val current = !t.allDay && nowMin >= t.start && nowMin <= t.start + t.dur
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
                    if (t.allDay) "بدون موعد" else "من ${Rules.fmt(t.start)} · آخر موعد ${Rules.fmt(t.start + t.dur)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onEdit != null) TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) { Text("تعديل", fontSize = 12.sp) }
            }
            val label = when {
                d != null && !d.ok -> "بانتظار موافقة القائد"
                d != null -> "تم +${Rules.pts(d.points)}"
                t.personal && !t.approved -> "مقترح ${t.points} · ينتظر القائد"
                late -> "متأخر · ${Rules.pointsNow(t, nowMin, grace)} من ${t.points}"
                else -> "${t.points} نقطة"
            }
            Pill(label, if (late) GHot else if (d != null && d.ok) GNeon else GGold, Modifier.padding(horizontal = 6.dp))
        }
    }
}

// ---------------- الترتيب ----------------

@Composable
fun RankPage(
    repo: Repo, code: String, members: List<Member>, done: List<Done>, weekStart: String, monthStart: String, meId: String,
    house: House, tasks: List<Task>, today: LocalDate, nowMin: Int, onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf("week") }
    val from = if (period == "week") weekStart else monthStart
    val sums = done.filter { it.ok && it.date >= from }.groupBy { it.memberId }.mapValues { e -> e.value.sumOf { it.points } }
    val ranked = members.sortedByDescending { sums[it.id] ?: 0.0 }
    val penalties = members.map { it to Penalty.forMember(house, it, tasks, done, today, nowMin) }.filter { it.second.missed > 0 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("الترتيب", fontSize = 22.sp, fontWeight = FontWeight.Black)
        Chips(listOf("week" to "هذا الأسبوع", "month" to "هذا الشهر"), period) { period = it }
        ranked.forEachIndexed { i, m ->
            val pts = sums[m.id] ?: 0.0
            val streak = Rules.streakNow(m, today)
            val first = i == 0 && pts > 0
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (first) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${i + 1}", fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(26.dp), textAlign = TextAlign.Center, color = if (first) GGold else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (m.character >= 0) CharacterView(m.character, Rules.level(m.total), Modifier.size(56.dp)) else Spacer(Modifier.size(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name + if (m.id == meId) " (أنت)" else "", fontWeight = FontWeight.Bold)
                        Text("المستوى ${Rules.level(m.total)}" + if (streak > 0) " · سلسلة $streak أيام" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // أمنية كل واحد تبان في ترتيب الشهر
                        if (period == "month" && m.wish.isNotBlank()) Text("أمنيته: ${m.wish}", fontSize = 12.sp, color = GGold)
                    }
                    Pill(Rules.pts(pts), if (first) GGold else GNeon)
                }
            }
        }
        Text(
            if (period == "week") "ترتيب الأسبوع يبدأ من جديد كل يوم أحد. المستوى يضل معك."
            else "الأول بنهاية الشهر تتحقق أمنيته. الترتيب يبدأ من جديد أول كل شهر.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // صندوق الأمنية
        val mine = members.firstOrNull { it.id == meId }
        if (mine != null) {
            var wish by remember(mine.wish) { mutableStateOf(mine.wish) }
            val ctxWish = LocalContext.current
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أمنيتي إذا طلعت الأول هذا الشهر", fontWeight = FontWeight.Bold, color = GGold)
                    OutlinedTextField(wish, { wish = it.take(80) }, placeholder = { Text("اكتب اللي تبغاه…") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                    Button(
                        enabled = wish.trim() != mine.wish,
                        onClick = {
                            val v = wish.trim()
                            scope.launch { try { repo.setWish(code, meId, v); ctxWish.toast("انحفظت أمنيتك") } catch (x: Exception) { ctxWish.toast("ما انحفظت، تأكد من الإنترنت") } }
                        },
                    ) { Text("حفظ الأمنية") }
                }
            }
        }

        HorizontalDivider()
        Text("عقوبات اليوم", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (!house.enforce) {
            Text("العقوبات متوقفة حالياً.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (penalties.isEmpty()) {
            Text("ما في عقوبات على أحد اليوم.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for ((m, st) in penalties) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${m.name} · فاتته ${st.missed} مهام", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(Penalty.describe(st, m), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        // وقت تنفيذ العقوبات، الخروج وإلغاء حماية الحذف يحتاجون كلمة السر اللي حطها القائد
        val ctx = LocalContext.current
        val me = members.firstOrNull { it.id == meId }
        val guarded = house.enforce && me != null && Penalty.enforcedOnPhone(me)
        var ask by remember { mutableStateOf("") }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الوضع الفاتح", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(
                checked = LightMode.value,
                onCheckedChange = { on ->
                    LightMode.value = on
                    ctx.getSharedPreferences("session", android.content.Context.MODE_PRIVATE).edit().putBoolean("light", on).apply()
                },
            )
        }
        TextButton(onClick = { if (guarded) ask = "logout" else onLogout() }) { Text("تسجيل خروج") }
        if (guarded && Guard.adminReady(ctx)) TextButton(onClick = { ask = "admin" }) { Text("إلغاء حماية الحذف") }
        if (ask.isNotEmpty()) {
            var pwd by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { ask = "" },
                title = { Text(if (ask == "logout") "تسجيل الخروج" else "إلغاء حماية الحذف") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("اكتب كلمة السر اللي عند القائد.")
                        OutlinedTextField(pwd, { pwd = it.take(30) }, label = { Text("كلمة السر") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (house.uninstallHash.isNotEmpty() && secretHash(code, pwd) == house.uninstallHash) {
                            val what = ask
                            ask = ""
                            if (what == "logout") onLogout() else { Guard.removeAdmin(ctx); ctx.toast("انلغت حماية الحذف") }
                        } else ctx.toast(if (house.uninstallHash.isEmpty()) "القائد لسا ما حط كلمة سر" else "كلمة السر غلط")
                    }) { Text("تأكيد") }
                },
                dismissButton = { TextButton(onClick = { ask = "" }) { Text("إلغاء") } },
            )
        }
    }
}

// ---------------- الإدارة (للقائد) ----------------

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
                onRename = { old, new -> act { repo.renameTasks(code, old, new) } },
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
                var open by remember { mutableStateOf("") }
                // إضافة أو تعديل مهمة شخصية عن عضو (مثل عمرو اللي ما معه جوال)
                var personalFor by remember { mutableStateOf<Member?>(null) }
                var personalEdit by remember { mutableStateOf<Pair<Member, Task>?>(null) }
                val names = members.associate { it.id to it.name }
                val titles = tasks.associate { it.id to it.title }
                val muted = MaterialTheme.colorScheme.onSurfaceVariant

                // المهام المتأخرة تنتظر موافقة القائد قبل ما تنحسب
                val fresh = tasks.filter { it.personal && !it.approved }
                val freshIds = fresh.map { it.id }.toSet()
                val pending = done.filter { it.date == date && !it.ok && it.taskId !in freshIds }
                if (pending.isNotEmpty() || fresh.isNotEmpty()) {
                    Text("بانتظار موافقتك", fontWeight = FontWeight.Bold)
                    // مهام شخصية جديدة: توافقين على نقاطها مرة وحدة
                    for ((who, list) in fresh.groupBy { it.memberId to it.title }) {
                        key(who.first, who.second) {
                            var pts by remember { mutableIntStateOf(list.first().points.coerceIn(1, Personal.MAX_TASK)) }
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${names[who.first] ?: ""} أضاف مهمة شخصية: ${who.second}", fontWeight = FontWeight.Medium)
                                    Text("${list.size} أيام بالأسبوع · اقتراح المساعد ${list.first().points} نقطة", fontSize = 12.sp)
                                    Stepper("النقاط", "$pts", { pts = (pts - 1).coerceAtLeast(1) }, { pts = (pts + 1).coerceAtMost(Personal.MAX_TASK) })
                                    Button(onClick = {
                                        val ids = list.map { it.id }.toSet()
                                        val waiting = done.firstOrNull { it.date == date && !it.ok && it.taskId in ids }
                                        act { repo.approvePersonal(code, who.first, who.second, pts, waiting) }
                                    }) { Text("موافقة على النقاط") }
                                }
                            }
                        }
                    }
                    for (p in pending) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${names[p.memberId] ?: ""} · ${titles[p.taskId] ?: "مهمة محذوفة"}", fontWeight = FontWeight.Medium)
                                Text("أشّر عليها ${Rules.fmt(p.at.coerceAtLeast(0))} متأخرة · ${Rules.pts(p.points)} نقطة", fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { act { repo.approve(code, p) } }) { Text("موافقة") }
                                    OutlinedButton(onClick = { act { repo.reject(code, p) } }) { Text("رفض") }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }

                for (m in members) {
                    val mine = tasks.filter { it.memberId == m.id && it.day == Rules.dayIndex(today) }.sortedBy { it.start + it.dur }
                    val dm = done.filter { it.date == date && it.memberId == m.id && it.taskId != "streak" }.associateBy { it.taskId }
                    val okCount = mine.count { dm[it.id]?.ok == true }
                    val earned = mine.sumOf { t -> dm[t.id]?.takeIf { it.ok }?.points ?: 0.0 }
                    Card(Modifier.fillMaxWidth().clickable { open = if (open == m.id) "" else m.id }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Text(m.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                Text("$okCount من ${mine.size} · ${Rules.pts(earned)} نقطة")
                            }
                            LinearProgressIndicator(progress = { if (mine.isEmpty()) 0f else okCount / mine.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                            val st = Penalty.forMember(house, m, tasks, done, today, nowMin)
                            if (st.missed > 0) Text("فاتته ${st.missed} · ${Penalty.describe(st, m)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            val stale = System.currentTimeMillis() - m.guardAt > 20 * 60_000
                            if (house.enforce && Penalty.enforcedOnPhone(m) && (stale || !m.guardOk)) {
                                Text("الحماية مو كاملة على جواله: إذن ناقص أو حماية الحذف متوقفة", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                            val streak = Rules.streakNow(m, today)
                            if (streak > 0) Text("سلسلة $streak أيام", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)

                            if (open != m.id) {
                                Text("اضغطي لعرض مهامه والتأشير عنه", fontSize = 11.sp, color = muted)
                            } else {
                                for (t in mine) {
                                    val d = dm[t.id]
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = d != null,
                                            onCheckedChange = {
                                                if (d != null) act { repo.undo(code, t, date, d) }
                                                else act {
                                                    // تأشير القائد عن العضو: تنحسب بوقتها وبنقاطها كاملة
                                                    repo.complete(code, t, date, t.points.toDouble(), minOf(nowMin, t.start + t.dur), true)
                                                    if (Rules.allOnTime(mine, dm.values.toList(), t.id, house.grace)) repo.awardStreak(code, m, today)
                                                }
                                            },
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(t.title)
                                            Text(
                                                "آخر موعد ${Rules.fmt(t.start + t.dur)}" + if (d != null && !d.ok) " · بانتظار الموافقة" else "",
                                                fontSize = 12.sp, color = muted,
                                            )
                                        }
                                        if (t.personal) TextButton(onClick = { personalEdit = m to t }) { Text("تعديل", fontSize = 12.sp) }
                                        Text("${t.points}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                TextButton(onClick = { personalFor = m }) { Text("إضافة مهمة شخصية لـ ${m.name}") }
                            }
                        }
                    }
                }

                val target = personalFor ?: personalEdit?.first
                if (target != null) {
                    PersonalDialog(
                        existing = personalEdit?.second,
                        taken = tasks.filter { it.memberId == target.id && it.personal }.map { it.title }.toSet(),
                        onAdd = { title, days, start, dur ->
                            personalFor = null
                            act {
                                // إضافة القائد موافق عليها مباشرة؛ النقاط من الاقتراح الجاهز أو من تقدير المساعد
                                val sug = Personal.suggestions.firstOrNull { it.title == title }
                                val key = house.aiKey.ifBlank { BuildConfig.GEMINI_KEY }
                                val points = sug?.points ?: if (key.isBlank()) 5 else try {
                                    Ai.estimate(key, house.aiModel.ifBlank { DEFAULT_AI_MODEL }, title, Personal.MAX_TASK)
                                } catch (x: Exception) { 5 }
                                repo.addTasks(code, days.map { d -> Task("", title, target.id, d, start, dur, points, personal = true, approved = true) })
                            }
                        },
                        onUpdate = { old, title, start, dur -> personalEdit = null; act { repo.updatePersonal(code, target.id, old.title, title, start, dur) } },
                        onDelete = { old -> personalEdit = null; act { repo.deletePersonal(code, target.id, old.title) } },
                        onDismiss = { personalFor = null; personalEdit = null },
                    )
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
                            Text(m.name + if (m.leader) " (قائد)" else "", fontWeight = FontWeight.Bold)
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
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val shown = house.alias.ifBlank { code }
                var newCode by remember { mutableStateOf("") }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("كود البيت", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(shown, Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Black)
                            Button(onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(shown))
                                ctx.toast("انسخ الكود")
                            }) { Text("نسخ") }
                        }
                        Text("أعطيه لإخوانك عشان يدخلون.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("تغيير الكود", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            newCode, { v -> newCode = v.uppercase().filter { it.isLetterOrDigit() }.take(12) },
                            label = { Text("كود جديد (4 إلى 12 حرف أو رقم)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = newCode.length >= 4 && newCode != shown,
                            onClick = {
                                val v = newCode
                                act {
                                    if (repo.setAlias(code, house.alias, v)) { newCode = ""; ctx.toast("تغيّر الكود") } else ctx.toast("هذا الكود مأخوذ، جربي غيره")
                                }
                            },
                        ) { Text("حفظ الكود الجديد") }
                        Text(
                            "اللي دخلوا قبل ما يتأثرون. الكود القديم اللي اخترتيه يتعطل، بس الكود الأصلي ($code) يبقى شغال دايماً.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        "ما ينطبق على جوال القائد ولا على اللي تحت 10.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                var secret by remember { mutableStateOf("") }
                Text("كلمة سر الحذف والخروج", fontWeight = FontWeight.Bold)
                Text(
                    if (house.uninstallHash.isEmpty()) "ما في كلمة سر حالياً." else "في كلمة سر محفوظة. اكتبي وحدة جديدة لتغييرها.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(secret, { secret = it.take(30) }, label = { Text("كلمة السر") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    enabled = secret.length >= 4,
                    onClick = { val v = secret; secret = ""; act { repo.setUninstallHash(code, secretHash(code, v)); ctx.toast("انحفظت كلمة السر") } },
                ) { Text("حفظ كلمة السر") }
                Text(
                    "إخوانك يحتاجونها عشان يسجلون خروج أو يلغون حماية الحذف من داخل التطبيق. لا تعطينها لهم إلا لما تبغين.",
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
    val adminOk = remember(refresh) { Guard.adminReady(ctx) }
    if (vpnOk && overlayOk && adminOk) return

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("فعّل العقوبات على جوالك", fontWeight = FontWeight.Bold)
            Text("البيت متفق إن المهام الفايتة لها عقوبة: قطع الإنترنت أو قفل الجوال. التطبيق يحتاج ثلاث أذونات عشان ينفذها.", fontSize = 13.sp)
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
            if (!adminOk) Button(onClick = {
                try {
                    ctx.startActivity(
                        android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, android.content.ComponentName(ctx, AdminReceiver::class.java))
                            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "يمنع حذف التطبيق بدون كلمة السر اللي عند القائد."),
                    )
                } catch (e: Exception) { ctx.toast("ما قدرنا نفتح صفحة الإذن") }
            }) { Text("3. حماية التطبيق من الحذف") }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                OutlinedButton(onClick = { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("السماح بإشعارات التذكير") }
            }
        }
    }
}
