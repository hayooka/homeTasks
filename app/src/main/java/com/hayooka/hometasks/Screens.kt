@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.hayooka.hometasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
    // نحمّل من أول الشهر اللي فات: لترتيب الشهر، لتصفح الأيام السابقة، ولإحصائيات المساعد
    val monthStart = today.withDayOfMonth(1)
    val since = monthStart.minusMonths(1).toString()
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
                    // المتأخرة تنتظر موافقة ولي الأمر (إلا مهام ولي الأمر نفسها)
                    val late = Rules.isLate(t, nowMin, house.grace)
                    var pts = Rules.pointsNow(t, nowMin, house.grace).toDouble()
                    if (t.personal) {
                        // سقف يومي لنقاط المهام الشخصية
                        val ids = tasks.filter { it.personal }.map { it.id }.toSet()
                        val got = done.filter { it.date == date && it.memberId == me.id && it.ok && it.taskId in ids }.sumOf { it.points }
                        pts = minOf(pts, (Personal.MAX_DAY - got).coerceAtLeast(0.0))
                    }
                    // المتأخرة والشخصية اللي لسا ما انوافق على نقاطها تنتظر ولي الأمر
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
    // حساب كل واحد يبدأ من أول مرة يفتح فيها التطبيق (بعد آخر تصفير)
    LaunchedEffect(me.joinDate) {
        if (me.joinDate.isEmpty()) try { repo.markJoined(code, me.id, date, nowMin) } catch (e: Exception) { }
    }
    // نحفظ قائمة مهام اليوم كدليل على اللي كان مطلوب
    val todays = tasks.filter { it.day == Rules.dayIndex(today) }
    LaunchedEffect(date, todays) {
        if (todays.isNotEmpty()) try { repo.snapshotDay(code, date, todays, me.leader) } catch (e: Exception) { }
    }
    LaunchedEffect(house.seedVersion, me.leader) {
        if (me.leader && house.seedVersion < SEED_VERSION) try { repo.migrateSeed(code, house.seedVersion) } catch (e: Exception) { }
    }

    // إذا ولي الأمر حذف البيت، الكل يطلع منه
    LaunchedEffect(code) { repo.houseGoneFlow(code).collect { if (it) onLogout() } }

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
    // تصفح الأيام السابقة: back = كم يوم لورا. الأيام السابقة للعرض بس
    var back by remember { mutableIntStateOf(0) }
    val maxBack = java.time.temporal.ChronoUnit.DAYS.between(today.withDayOfMonth(1).minusMonths(1), today).toInt()
    val viewDay = today.minusDays(back.toLong())
    val past = back > 0
    val mine = tasks.filter { it.memberId == me.id && it.day == Rules.dayIndex(viewDay) }.sortedBy { it.start }
    val doneToday = done.filter { it.date == viewDay.toString() }.associateBy { it.taskId }
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
            if (past) "${DAY_NAMES[Rules.dayIndex(viewDay)]} $viewDay · ${Rules.pts(earned)} من $possible نقطة"
            else if (allDone) "${DAY_NAMES[Rules.dayIndex(today)]} ${today.dayOfMonth} ${MONTH_NAMES[today.monthValue - 1]} · خلصت كل مهامك! ${Rules.pts(earned)} نقطة"
            else "${DAY_NAMES[Rules.dayIndex(today)]} ${today.dayOfMonth} ${MONTH_NAMES[today.monthValue - 1]} · نقاط اليوم ${Rules.pts(earned)} من $possible",
            fontSize = 18.sp, fontWeight = FontWeight.Bold,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = back < maxBack, onClick = { back++ }) { Text("اليوم اللي قبل") }
            Spacer(Modifier.weight(1f))
            if (past) {
                OutlinedButton(onClick = { back-- }) { Text("اليوم اللي بعد") }
                Button(onClick = { back = 0 }) { Text("اليوم") }
            }
        }
        if (past) Text("هذا عرض ليوم سابق، للمتابعة بس.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!past && house.enforce && Penalty.enforcedOnPhone(me)) GuardSetupCard()
        if (!past && st.missed > 0) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("عقوبة: فاتتك ${st.missed} مهام اليوم", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(Penalty.describe(st, me, house), color = MaterialTheme.colorScheme.onErrorContainer)
                    if (me.leader) Text("عندك تنعرض بس، بدون قطع أو قفل.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        val chores = mine.filter { !it.personal }
        val personal = mine.filter { it.personal }
        if (chores.isEmpty()) Text("ما عليك مهام بيت اليوم.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (t in chores) TaskRow(t, doneToday[t.id], nowMin, house.grace, onToggle, null, past)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("مهامي الشخصية", fontWeight = FontWeight.Bold)
                Text("أنت تضيفها وتعدلها. بدون عقوبة، والنقاط بعد موافقة ولي الأمر.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!past) OutlinedButton(onClick = { adding = true }) { Text("إضافة") }
        }
        for (t in personal) TaskRow(t, doneToday[t.id], nowMin, house.grace, onToggle, if (past) null else ({ editing = t }), past)
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
                        // الاقتراحات الجاهزة موافق عليها بنقاطها، وغيرها يقدّرها المساعد وتنتظر ولي الأمر
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
private fun TaskRow(t: Task, d: Done?, nowMin: Int, grace: Int, onToggle: (Task, Done?) -> Unit, onEdit: (() -> Unit)?, past: Boolean = false) {
    val late = d == null && (past || (!t.allDay && Rules.isLate(t, nowMin, grace)))
    val current = !past && !t.allDay && nowMin >= t.start && nowMin <= t.start + t.dur
    Card(
        Modifier.fillMaxWidth().clickable(enabled = !past) { onToggle(t, d) },
        colors = CardDefaults.cardColors(
            containerColor = if (current && d == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = d != null, enabled = !past, onCheckedChange = { onToggle(t, d) })
            Column(Modifier.weight(1f)) {
                Text(t.title, fontWeight = FontWeight.Medium, textDecoration = if (d != null) TextDecoration.LineThrough else null)
                Text(
                    if (t.allDay) "بدون موعد" else "من ${Rules.fmt(t.start)} · آخر موعد ${Rules.fmt(t.start + t.dur)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onEdit != null) TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) { Text("تعديل", fontSize = 12.sp) }
            }
            val label = when {
                d != null && d.excused -> "معذور"
                d != null && !d.ok -> "بانتظار موافقة ولي الأمر"
                d != null -> "تم +${Rules.pts(d.points)}"
                past -> "ما انعملت"
                t.personal && !t.approved -> "مقترح ${t.points} · ينتظر ولي الأمر"
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
    val ctxR = LocalContext.current
    var period by remember { mutableStateOf("week") }
    val rewards by remember(code) { repo.rewardsFlow(code) }.collectAsState(initial = emptyList())
    val redeems by remember(code) { repo.redeemsFlow(code) }.collectAsState(initial = emptyList())
    val weekSums = done.filter { it.ok && it.date >= weekStart }.groupBy { it.memberId }.mapValues { e -> e.value.sumOf { it.points } }
    val penalties = members.map { it to Penalty.forMember(house, it, tasks, done, today, nowMin) }.filter { it.second.missed > 0 }
    val mine = members.firstOrNull { it.id == meId }
    val parent = mine?.leader == true
    val names = members.associate { it.id to it.name }
    fun go(block: suspend () -> Unit) {
        scope.launch { try { block() } catch (x: Exception) { ctxR.toast("ما انحفظ، تأكد من الإنترنت") } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Chips(listOf("week" to "ترتيب الأسبوع", "rewards" to "المكافآت وهدف العائلة"), period) { period = it }

        if (period == "week") {
            val weekEnd = LocalDate.parse(weekStart).plusDays(6)
            Text("من ${LocalDate.parse(weekStart).dayOfMonth} إلى ${weekEnd.dayOfMonth} ${MONTH_NAMES[weekEnd.monthValue - 1]}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            members.sortedByDescending { weekSums[it.id] ?: 0.0 }.forEachIndexed { i, m ->
                val pts = weekSums[m.id] ?: 0.0
                RankRow(i, m, meId, today, pts > 0 && i == 0, Rules.pts(pts), null)
            }
            Text("ترتيب الأسبوع بالنقاط، ويبدأ من جديد كل يوم أحد. المستوى يضل معك.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // ---- هدف العائلة: الكل يجمع له مع بعض
            val familyTotal = members.sumOf { it.total }
            val progress = (familyTotal - house.goalBase).coerceAtLeast(0.0)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("هدف العائلة", fontWeight = FontWeight.Bold, color = GGold)
                    if (house.goalPoints <= 0) {
                        Text(if (parent) "حط هدف مشترك تجمع له العائلة كلها." else "ولي الأمر لسا ما حط هدف.")
                    } else {
                        Text(house.goalTitle, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        XpBar((progress / house.goalPoints).toFloat(), Modifier.fillMaxWidth())
                        Text(
                            if (progress >= house.goalPoints) "وصلتوا الهدف! مبروك للعائلة كلها."
                            else "${Rules.pts(progress)} من ${Rules.pts(house.goalPoints)} نقطة · كل مهمة يخلصها أي واحد تقرّب الكل",
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (parent) {
                        var gTitle by remember { mutableStateOf("") }
                        var gPoints by remember { mutableStateOf("500") }
                        OutlinedTextField(gTitle, { gTitle = it.take(40) }, label = { Text("هدف جديد، مثلاً: يوم نشاط عائلي") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            gPoints, { v -> gPoints = v.filter { it.isDigit() }.take(5) }, label = { Text("كم نقطة") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = gTitle.isNotBlank() && (gPoints.toIntOrNull() ?: 0) > 0,
                            onClick = { val t = gTitle.trim(); val p = gPoints.toDouble(); gTitle = ""; go { repo.setGoal(code, t, p, familyTotal) } },
                        ) { Text("بدء الهدف من الآن") }
                    }
                }
            }

            // ---- المكافآت الفردية
            if (mine != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("المكافآت", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Pill("رصيدي ${Rules.pts(mine.balance)}", GGold)
                }
                Text("تستبدلها بنقاطك. الاستبدال ما ينقّص مستواك.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rewards.isEmpty()) Text(if (parent) "ما في مكافآت لسا. أضف وحدة من تحت." else "ولي الأمر لسا ما أضاف مكافآت.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (r in rewards.sortedBy { it.cost }) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, fontWeight = FontWeight.Medium)
                                Text("${r.cost} نقطة", fontSize = 12.sp, color = GGold)
                            }
                            if (parent) TextButton(onClick = { go { repo.deleteReward(code, r.id) } }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                            Button(enabled = mine.balance >= r.cost, onClick = { go { repo.redeem(code, meId, r, today.toString()); ctxR.toast("انطلبت المكافأة") } }) { Text("استبدال") }
                        }
                    }
                }

                // طلبات الاستبدال: ولي الأمر يشوف الكل، والباقي يشوفون طلباتهم
                val shown = if (parent) redeems else redeems.filter { it.memberId == meId }
                if (shown.isNotEmpty()) Text(if (parent) "طلبات تنتظر التسليم" else "طلباتي", fontWeight = FontWeight.Bold)
                for (q in shown) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${names[q.memberId] ?: ""} · ${q.title}", fontWeight = FontWeight.Medium)
                            Text("${q.cost} نقطة · ${q.date}", fontSize = 12.sp)
                            if (parent) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { go { repo.closeRedeem(code, q, false) } }) { Text("تم التسليم") }
                                    OutlinedButton(onClick = { go { repo.closeRedeem(code, q, true) } }) { Text("رفض وإرجاع النقاط") }
                                }
                            } else Text("بانتظار ولي الأمر", fontSize = 12.sp, color = GGold)
                        }
                    }
                }

                if (parent) {
                    var rTitle by remember { mutableStateOf("") }
                    var rCost by remember { mutableStateOf("100") }
                    HorizontalDivider()
                    Text("مكافأة جديدة", fontWeight = FontWeight.Bold)
                    Chips(Suggest.rewards.map { it.first to it.first }, rTitle) { picked ->
                        rTitle = picked
                        rCost = Suggest.rewards.first { it.first == picked }.second.toString()
                    }
                    OutlinedTextField(rTitle, { rTitle = it.take(40) }, label = { Text("اسم المكافأة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        rCost, { v -> rCost = v.filter { it.isDigit() }.take(5) }, label = { Text("سعرها بالنقاط") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = rTitle.isNotBlank() && (rCost.toIntOrNull() ?: 0) > 0,
                        onClick = { val t = rTitle.trim(); val c = rCost.toInt(); rTitle = ""; go { repo.addReward(code, t, c) } },
                    ) { Text("إضافة المكافأة") }
                }
            }
        }

        HorizontalDivider()
        Text("عواقب اليوم", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (!house.enforce) {
            Text("العقوبات متوقفة حالياً.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (penalties.isEmpty()) {
            Text("ما في عقوبات على أحد اليوم.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for ((m, st) in penalties) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${m.name} · فاتته ${st.missed} مهام", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(Penalty.describe(st, m, house), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        // وقت تنفيذ العقوبات، الخروج وإلغاء حماية الحذف يحتاجون كلمة السر اللي حطها ولي الأمر
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
                        Text("اكتب كلمة السر اللي عند ولي الأمر.")
                        OutlinedTextField(pwd, { pwd = it.take(30) }, label = { Text("كلمة السر") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (house.uninstallHash.isNotEmpty() && secretHash(code, pwd) == house.uninstallHash) {
                            val what = ask
                            ask = ""
                            if (what == "logout") onLogout() else { Guard.removeAdmin(ctx); ctx.toast("انلغت حماية الحذف") }
                        } else ctx.toast(if (house.uninstallHash.isEmpty()) "ولي الأمر لسا ما حط كلمة سر" else "كلمة السر غلط")
                    }) { Text("تأكيد") }
                },
                dismissButton = { TextButton(onClick = { ask = "" }) { Text("إلغاء") } },
            )
        }
    }
}

// ---------------- الإدارة (لولي الأمر) ----------------

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
            AssistantTab(repo, code, house, members, tasks, done, today)
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

                // المهام المتأخرة تنتظر موافقة ولي الأمر قبل ما تنحسب
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
                            if (st.missed > 0) {
                                Text("فاتته ${st.missed} · ${Penalty.describe(st, m, house)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { act { repo.pardon(code, m.id, date, nowMin); ctx.toast("انلغت عواقب اليوم عن ${m.name}") } }) { Text("إلغاء عواقب اليوم") }
                            }
                            if (!m.leader) PermissionStatus(m, Penalty.enforcedOnPhone(m))
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
                                                    // تأشير ولي الأمر عن العضو: تنحسب بوقتها وبنقاطها كاملة
                                                    repo.complete(code, t, date, t.points.toDouble(), minOf(nowMin, t.start + t.dur), true)
                                                    if (Rules.allOnTime(mine, dm.values.toList(), t.id, house.grace)) repo.awardStreak(code, m, today)
                                                }
                                            },
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(t.title)
                                            Text(
                                                "آخر موعد ${Rules.fmt(t.start + t.dur)}" + if (d != null && d.excused) " · معذور" else if (d != null && !d.ok) " · بانتظار الموافقة" else "",
                                                fontSize = 12.sp, color = muted,
                                            )
                                        }
                                        if (t.personal) TextButton(onClick = { personalEdit = m to t }) { Text("تعديل", fontSize = 12.sp) }
                                        if (d == null && !t.personal) TextButton(onClick = { act { repo.excuse(code, t, date) } }) { Text("عذر", fontSize = 12.sp) }
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
                                // إضافة ولي الأمر موافق عليها مباشرة؛ النقاط من الاقتراح الجاهز أو من تقدير المساعد
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
                            Text(m.name + if (m.leader) " (ولي أمر)" else "", fontWeight = FontWeight.Bold)
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
                        Text("تفعيل العواقب", fontWeight = FontWeight.Bold)
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
                    "لمن عمره 10 إلى 17: الفايتة الأولى تنبيه، الثانية عاقبة خفيفة، الثالثة عاقبة أقوى، والرابعة قطع إنترنت ساعة وحدة. " +
                        "تحت 10: تنبيهين، ثم عاقبة خفيفة، ثم أقوى، بدون أي تحكم بالجوال. الكبار بدون عواقب. " +
                        "ما في قفل للجوال ولا منع حذف. تقدر تقبل عذر أي مهمة أو تلغي عواقب اليوم لأي فرد من تبويب اليوم. كل شي يتصفّر آخر اليوم.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                var pen by remember(house.penalties) { mutableStateOf(DEFAULT_PENALTIES + house.penalties) }
                Text("العواقب اللي تحددها أنت", fontWeight = FontWeight.Bold)
                Text("الأفراد يشوفونها مكتوبة قبل، عشان يعرفون القواعد.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((key, label) in listOf(
                    "u16_light" to "10 إلى 17 · خفيفة (مهمتين فايتة)", "u16_heavy" to "10 إلى 17 · أقوى (3 فايتة)",
                    "u10_light" to "تحت 10 · خفيفة (3 فايتة)", "u10_heavy" to "تحت 10 · أقوى (5 فايتة)",
                )) {
                    OutlinedTextField(pen[key] ?: "", { v -> pen = pen + (key to v.take(80)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = { act { repo.saveSettings(code, house.grace, pen); ctx.toast("انحفظت العواقب") } }) { Text("حفظ العواقب") }

                HorizontalDivider()
                // تنزيل السجل كامل: يُجهّز النص ثم يُفتح اختيار مكان الحفظ
                var exportText by remember { mutableStateOf("") }
                fun write(uri: android.net.Uri?) {
                    if (uri == null) return
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray(Charsets.UTF_8)) }
                        ctx.toast("انحفظ الملف")
                    } catch (e: Exception) { ctx.toast("ما قدرنا نحفظ الملف") }
                }
                val saveCsv = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv"),
                ) { uri -> write(uri) }
                val saveJson = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
                ) { uri -> write(uri) }
                Text("تنزيل السجل", fontWeight = FontWeight.Bold)
                Text("سطر لكل مهمة كانت على كل واحد بكل يوم، ونتيجتها: بوقتها، متأخرة، أو ما انعملت.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val c = java.time.LocalTime.now()
                        act { exportText = Export.csv(repo.allDays(code), repo.allDone(code), members, today, c.hour * 60 + c.minute, house.grace); saveCsv.launch("homeTasks-log-$today.csv") }
                    }) { Text("ملف Excel (CSV)") }
                    OutlinedButton(onClick = {
                        val c = java.time.LocalTime.now()
                        act { exportText = Export.json(repo.allDays(code), repo.allDone(code), members, today, c.hour * 60 + c.minute, house.grace); saveJson.launch("homeTasks-log-$today.json") }
                    }) { Text("ملف JSON") }
                }

                HorizontalDivider()
                var secret by remember { mutableStateOf("") }
                Text("كلمة سر تسجيل الخروج", fontWeight = FontWeight.Bold)
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
                var confirmWipe by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) { Text("تصفير النقاط والسجل (بداية جديدة)") }
                if (confirmWipe) {
                    AlertDialog(
                        onDismissRequest = { confirmWipe = false },
                        title = { Text("بداية جديدة؟") },
                        text = { Text("تنمسح كل التأشيرات والسجل، وترجع نقاط ومستويات وسلاسل الكل للصفر. المهام والأعضاء والشخصيات يبقون. وحساب كل واحد يبدأ من أول مرة يفتح فيها التطبيق بعدها. ما في تراجع.") },
                        confirmButton = { TextButton(onClick = { confirmWipe = false; act { repo.resetProgress(code); ctx.toast("تصفّر كل شي") } }) { Text("تصفير", color = MaterialTheme.colorScheme.error) } },
                        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("إلغاء") } },
                    )
                }

                HorizontalDivider()
                var confirmReset by remember { mutableStateOf(false) }
                var confirmDelete by remember { mutableStateOf(false) }
                var typed by remember { mutableStateOf("") }
                OutlinedButton(onClick = { typed = ""; confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("حذف البيت نهائياً", color = MaterialTheme.colorScheme.error)
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("حذف البيت نهائياً؟") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("ينمسح كل شي: الأعضاء، المهام، النقاط، السجل، والأمنيات. كل الجوالات تطلع من البيت، وتنشال حماية الحذف عنها. ما في تراجع. نزّلي السجل قبل إذا تحتاجينه.")
                                Text("للتأكيد اكتبي كود البيت: $code")
                                OutlinedTextField(typed, { typed = it.uppercase().trim() }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                        },
                        confirmButton = {
                            TextButton(enabled = typed == code, onClick = {
                                confirmDelete = false
                                act { repo.deleteHouse(code, house.alias); ctx.toast("انحذف البيت") }
                            }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } },
                    )
                }

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
                if (initial.id.isEmpty()) {
                    val ideas = Suggest.forAge(members.firstOrNull { it.id == t.memberId }?.age ?: "adult")
                    Text("مهام مناسبة لعمره", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Chips(ideas.map { it.first to it.first }, t.title) { picked ->
                        t = t.copy(title = picked)
                        pts = ideas.first { it.first == picked }.second.toString()
                    }
                }
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
    if (vpnOk) return

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("إذن واحد مطلوب", fontWeight = FontWeight.Bold)
            Text("العائلة متفقة: إذا فاتت 4 مهام بيوم واحد، ينقطع الإنترنت عن الجوال ساعة وحدة. المكالمات تبقى شغالة، وولي الأمر يقدر يلغيها.", fontSize = 13.sp)
            if (!vpnOk) Button(onClick = {
                val i = android.net.VpnService.prepare(ctx)
                if (i != null) vpnLauncher.launch(i) else refresh++
            }) { Text("السماح") }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                OutlinedButton(onClick = { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("السماح بإشعارات التذكير") }
            }
        }
    }
}

/** صف بالترتيب: الرقم، الشخصية، الاسم، القيمة، وسطر إضافي اختياري. */
@Composable
private fun RankRow(i: Int, m: Member, meId: String, today: LocalDate, first: Boolean, value: String, extra: String?) {
    val streak = Rules.streakNow(m, today)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (first) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${i + 1}", fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(26.dp), textAlign = TextAlign.Center, color = if (first) GGold else MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.character >= 0) CharacterView(m.character, Rules.level(m.total), Modifier.size(56.dp)) else Spacer(Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name + (if (m.id == meId) " (أنت)" else "") + (if (m.leader) " · ولي الأمر" else ""), fontWeight = FontWeight.Bold)
                Text("المستوى ${Rules.level(m.total)}" + if (streak > 0) " · سلسلة $streak أيام" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (extra != null) Text(extra, fontSize = 12.sp, color = GGold)
            }
            Pill(value, if (first) GGold else GNeon)
        }
    }
}

/** حالة أذونات جوال العضو، يشوفها ولي الأمر في الإدارة ← اليوم. */
@Composable
private fun PermissionStatus(m: Member, full: Boolean) {
    if (m.guardAt == 0L) {
        Text("جواله لسا ما اتصل بالتطبيق", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        return
    }
    val items = buildList {
        if (full) {
            add("الإنترنت" to m.vpnOk)
        }
        add("الإشعارات" to m.notifOk)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        for ((name, ok) in items) Pill((if (ok) "✓ " else "✗ ") + name, if (ok) GNeon else GHot)
    }
    val mins = ((System.currentTimeMillis() - m.guardAt) / 60_000).toInt()
    Text(
        when {
            mins < 1 -> "آخر اتصال: الآن"
            mins < 60 -> "آخر اتصال قبل $mins دقيقة"
            else -> "آخر اتصال قبل ${mins / 60} ساعة · ممكن الجوال مطفي أو التطبيق موقوف"
        },
        fontSize = 11.sp, color = if (mins >= 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
