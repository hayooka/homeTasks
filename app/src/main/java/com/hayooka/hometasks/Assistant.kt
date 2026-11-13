package com.hayooka.hometasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class Bubble(val mine: Boolean, val text: String)

/** محادثة مع المساعد لإضافة مهام بالكلام. المهام ما تنضاف إلا بعد ما القائد يضغط "إضافة". */
@Composable
fun AssistantTab(
    repo: Repo, code: String, house: House, members: List<Member>,
    tasks: List<Task>, done: List<Done>, today: java.time.LocalDate,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var setup by remember { mutableStateOf(false) }

    // المفتاح: من بيانات البيت، وإذا فاضي من المفتاح المبني مع التطبيق (local.properties)
    val aiKey = house.aiKey.ifBlank { BuildConfig.GEMINI_KEY }
    if (aiKey.isBlank() || setup) {
        var key by remember { mutableStateOf(house.aiKey) }
        var model by remember { mutableStateOf(house.aiModel.ifBlank { DEFAULT_AI_MODEL }) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("تشغيل المساعد", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("المساعد يشتغل على Gemini بالباقة المجانية، بدون اشتراك وبدون بطاقة. يحتاج مفتاح مرة وحدة:")
            Text("1. افتحي aistudio.google.com وسجلي بحساب Google.\n2. اضغطي Get API key ثم Create API key.\n3. انسخي المفتاح والصقيه هنا.")
            OutlinedTextField(key, { key = it.trim() }, label = { Text("مفتاح Gemini API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it.trim() }, label = { Text("اسم الموديل") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                "الباقة المجانية لها حد يومي للطلبات. إذا خلص، جربي موديل أخف من القائمة في AI Studio.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = key.isNotBlank() && model.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        try { repo.saveAi(code, key, model); setup = false } catch (e: Exception) { ctx.toast("ما انحفظ، تأكدي من الإنترنت") }
                    }
                },
            ) { Text("حفظ") }
            if (aiKey.isNotBlank()) TextButton(onClick = { setup = false }) { Text("رجوع") }
        }
        return
    }

    val bubbles = remember { mutableStateListOf<Bubble>() }
    val turns = remember { mutableStateListOf<AiTurn>() }
    var proposals by remember { mutableStateOf<List<Proposal>>(emptyList()) }
    var edits by remember { mutableStateOf<List<EditOp>>(emptyList()) }
    var deletes by remember { mutableStateOf<List<Task>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val names = members.associate { it.id to it.name }
    LaunchedEffect(bubbles.size, proposals.size, edits.size, deletes.size) { scroll.animateScrollTo(scroll.maxValue) }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        bubbles += Bubble(true, text)
        turns += AiTurn("user", text)
        busy = true
        scope.launch {
            try {
                val clock = java.time.LocalTime.now()
                val r = Ai.ask(
                    aiKey, house.aiModel.ifBlank { DEFAULT_AI_MODEL }, members, tasks, done,
                    today, clock.hour * 60 + clock.minute, house.grace, turns.toList(),
                )
                turns += AiTurn("model", r.raw)
                bubbles += Bubble(false, r.reply)
                proposals = r.adds
                edits = r.edits
                deletes = r.deletes
            } catch (e: AiError) {
                turns.removeAt(turns.lastIndex)
                bubbles += Bubble(
                    false,
                    when (e.http) {
                        429 -> "خلص الحد المجاني حالياً. جربي بعد شوي، أو غيّري الموديل من الإعدادات."
                        400, 401, 403 -> "المفتاح مو مقبول. تأكدي منه من الإعدادات."
                        404 -> "اسم الموديل مو صحيح. غيّريه من الإعدادات."
                        else -> "صار خطأ من الخدمة (${e.http}). ${e.message ?: ""}"
                    },
                )
            } catch (e: Exception) {
                turns.removeAt(turns.lastIndex)
                bubbles += Bubble(false, "ما قدرت أتصل. تأكدي من الإنترنت وجربي مرة ثانية.")
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("أضيف وأعدل وأحذف مهام، وأجاوب عن اللي صار، وأقترح توزيع أعدل.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                TextButton(onClick = { setup = true }) { Text("الإعدادات") }
            }
            if (bubbles.isEmpty()) {
                Text(
                    "أمثلة: ضيفي لحمزة سقي الزرع كل يوم آخر موعد 5 العصر · أخّري عمل العشا نص ساعة · احذفي مهمة الصالون يوم الجمعة · مين أكثر واحد فاتته مهام هالأسبوع؟ · هل التوزيع عادل؟",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (b in bubbles) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (b.mine) Arrangement.Start else Arrangement.End) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (b.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) { Text(b.text, Modifier.padding(10.dp)) }
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (proposals.isNotEmpty() || edits.isNotEmpty() || deletes.isNotEmpty()) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                fun days(l: List<Int>) = if (l.size == 7) "كل يوم" else l.joinToString("، ") { DAY_NAMES[it] }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("التغييرات المقترحة", fontWeight = FontWeight.Bold)
                        if (proposals.isNotEmpty()) Text("إضافة", fontWeight = FontWeight.Bold, color = GNeon, fontSize = 13.sp)
                        for (p in proposals) {
                            Column {
                                Text(p.title, fontWeight = FontWeight.Medium)
                                Text("${names[p.memberId] ?: ""} · ${days(p.days)}", fontSize = 12.sp, color = muted)
                                Text("من ${Rules.fmt(p.start)} · آخر موعد ${Rules.fmt(p.start + p.dur)} · ${p.points} نقطة", fontSize = 12.sp, color = muted)
                            }
                        }
                        if (edits.isNotEmpty()) Text("تعديل", fontWeight = FontWeight.Bold, color = GGold, fontSize = 13.sp)
                        for (e in edits) {
                            val b = e.before
                            val a = e.after
                            val changes = ArrayList<String>()
                            if (a.title != b.title) changes += "الاسم: ${a.title}"
                            if (a.memberId != b.memberId) changes += "من ${names[b.memberId] ?: ""} إلى ${names[a.memberId] ?: ""}"
                            if (a.day != b.day) changes += "اليوم: ${DAY_NAMES[a.day]}"
                            if (a.start != b.start) changes += "تبدأ ${Rules.fmt(a.start)}"
                            if (a.start + a.dur != b.start + b.dur) changes += "آخر موعد ${Rules.fmt(a.start + a.dur)}"
                            if (a.points != b.points) changes += "${a.points} نقطة"
                            Column {
                                Text("${b.title} · ${names[b.memberId] ?: ""} · ${DAY_NAMES[b.day]}", fontWeight = FontWeight.Medium)
                                Text(changes.joinToString(" · "), fontSize = 12.sp, color = muted)
                            }
                        }
                        if (deletes.isNotEmpty()) Text("حذف", fontWeight = FontWeight.Bold, color = GHot, fontSize = 13.sp)
                        for (d in deletes) {
                            Text("${d.title} · ${names[d.memberId] ?: ""} · ${DAY_NAMES[d.day]} ${Rules.fmt(d.start)}", fontSize = 13.sp)
                        }
                        val count = proposals.sumOf { it.days.size } + edits.size + deletes.size
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    val adds = proposals.flatMap { p -> p.days.map { d -> Task("", p.title, p.memberId, d, p.start, p.dur, p.points) } }
                                    val changed = edits.map { it.after }
                                    val removed = deletes.map { it.id }
                                    busy = true
                                    scope.launch {
                                        try {
                                            repo.applyAi(code, adds, changed, removed)
                                            proposals = emptyList(); edits = emptyList(); deletes = emptyList()
                                            turns.clear()
                                            bubbles += Bubble(false, "تم: $count تغيير على الجدول.")
                                        } catch (e: Exception) { ctx.toast("ما انحفظ، تأكدي من الإنترنت") }
                                        busy = false
                                    }
                                },
                            ) { Text("تنفيذ ($count)") }
                            TextButton(onClick = { proposals = emptyList(); edits = emptyList(); deletes = emptyList(); turns.clear() }) { Text("إلغاء") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it.take(400) }, placeholder = { Text("اكتبي هنا…") }, modifier = Modifier.weight(1f), maxLines = 3)
            Button(enabled = !busy && input.isNotBlank(), onClick = { send() }) { Text("إرسال") }
        }
    }
}
