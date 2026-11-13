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

/** محادثة مع المساعد لإضافة مهام بالكلام. المهام ما تنضاف إلا بعد ما الليدر تضغط "إضافة". */
@Composable
fun AssistantTab(repo: Repo, code: String, house: House, members: List<Member>) {
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
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val names = members.associate { it.id to it.name }
    LaunchedEffect(bubbles.size, proposals.size) { scroll.animateScrollTo(scroll.maxValue) }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        bubbles += Bubble(true, text)
        turns += AiTurn("user", text)
        busy = true
        scope.launch {
            try {
                val r = Ai.ask(aiKey, house.aiModel.ifBlank { DEFAULT_AI_MODEL }, members, turns.toList())
                turns += AiTurn("model", r.raw)
                bubbles += Bubble(false, r.reply)
                proposals = r.tasks
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
                Text("اكتبي المهمة بكلامك وأنا أجهزها.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                TextButton(onClick = { setup = true }) { Text("الإعدادات") }
            }
            if (bubbles.isEmpty()) {
                Text(
                    "مثال: ضيفي لحمزة سقي الزرع كل يوم، آخر موعد 5 العصر، 10 نقاط.",
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

            if (proposals.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("المهام المقترحة", fontWeight = FontWeight.Bold)
                        for (p in proposals) {
                            Column {
                                Text(p.title, fontWeight = FontWeight.Medium)
                                Text(
                                    "${names[p.memberId] ?: ""} · ${if (p.days.size == 7) "كل يوم" else p.days.joinToString("، ") { DAY_NAMES[it] }}",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "من ${Rules.fmt(p.start)} · آخر موعد ${Rules.fmt(p.start + p.dur)} · ${p.points} نقطة",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val count = proposals.sumOf { it.days.size }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    val list = proposals.flatMap { p -> p.days.map { d -> Task("", p.title, p.memberId, d, p.start, p.dur, p.points) } }
                                    busy = true
                                    scope.launch {
                                        try {
                                            repo.addTasks(code, list)
                                            proposals = emptyList()
                                            turns.clear()
                                            bubbles += Bubble(false, "انضافت $count مهمة للجدول.")
                                        } catch (e: Exception) { ctx.toast("ما انحفظ، تأكدي من الإنترنت") }
                                        busy = false
                                    }
                                },
                            ) { Text("إضافة للجدول ($count)") }
                            TextButton(onClick = { proposals = emptyList(); turns.clear() }) { Text("إلغاء") }
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
