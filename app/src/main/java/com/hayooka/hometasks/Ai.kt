package com.hayooka.hometasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val DEFAULT_AI_MODEL = "gemini-3.5-flash"

/** مهمة جديدة يقترحها المساعد، قبل ما ولي الأمر يوافق عليها. */
data class Proposal(val title: String, val memberId: String, val days: List<Int>, val start: Int, val dur: Int, val points: Int)

/** تعديل مقترح على مهمة موجودة: القديمة والجديدة. */
data class EditOp(val before: Task, val after: Task)

/** role: "user" أو "model". text هو النص كما انرسل أو رجع. */
data class AiTurn(val role: String, val text: String)

class AiReply(val reply: String, val adds: List<Proposal>, val edits: List<EditOp>, val deletes: List<Task>, val raw: String)

class AiError(val http: Int, message: String) : Exception(message)

/** اتصال مباشر بـ Gemini API بمفتاح مجاني من Google AI Studio. */
object Ai {
    private fun hm(min: Int) = "%02d:%02d".format((min / 60) % 24, min % 60)

    /** ملخص اللي صار هذا الأسبوع وحمل كل واحد، عشان المساعد يجاوب ويقترح توزيع أعدل. */
    private fun stats(members: List<Member>, tasks: List<Task>, done: List<Done>, today: java.time.LocalDate, nowMin: Int, grace: Int): String {
        val weekStart = Rules.weekStart(today)
        val byKey = done.associateBy { it.date + "_" + it.taskId }
        val sb = StringBuilder()
        sb.append("الآن: ${DAY_NAMES[Rules.dayIndex(today)]} $today الساعة ${hm(nowMin)}.\n")
        sb.append("إحصائيات هذا الأسبوع من الأحد لحد الآن (مهام البيت بس):\n")
        for (m in members) {
            var onTime = 0
            var late = 0
            var missed = 0
            val missedTitles = LinkedHashMap<String, Int>()
            var d = weekStart
            while (!d.isAfter(today)) {
                for (t in tasks) {
                    if (t.memberId != m.id || t.personal || t.day != Rules.dayIndex(d)) continue
                    val limit = t.start + t.dur + grace
                    val dn = byKey[d.toString() + "_" + t.id]
                    if (dn != null && dn.ok && (dn.at < 0 || dn.at <= limit)) onTime++
                    else if (dn != null) late++
                    else if (m.accountable(d.toString(), limit) && (d.isBefore(today) || nowMin > limit)) { missed++; missedTitles[t.title] = (missedTitles[t.title] ?: 0) + 1 }
                }
                d = d.plusDays(1)
            }
            val pts = done.filter { it.ok && it.memberId == m.id && it.date >= weekStart.toString() }.sumOf { it.points }
            val load = tasks.filter { it.memberId == m.id && !it.personal }
            sb.append("- ${m.name} (${m.id}): بوقتها $onTime، متأخرة $late، فايتة $missed، نقاط الأسبوع ${Rules.pts(pts)}، السلسلة ${Rules.streakNow(m, today)} يوم. ")
            sb.append("حمله بالأسبوع: ${load.size} مهمة بيت بمجموع ${load.sumOf { it.points }} نقطة.")
            if (missedTitles.isNotEmpty()) {
                sb.append(" أكثر اللي فاته: " + missedTitles.entries.sortedByDescending { it.value }.take(4).joinToString("، ") { "${it.key} (${it.value})" } + ".")
            }
            sb.append("\n")
        }
        // ملخص شهري: هذا الشهر واللي قبله. محسوب من سجل الإنجاز الفعلي
        val thisMonth = today.withDayOfMonth(1)
        val lastMonth = thisMonth.minusMonths(1)
        for ((label, from, to) in listOf(Triple("هذا الشهر", thisMonth, today.plusDays(1)), Triple("الشهر اللي فات", lastMonth, thisMonth))) {
            sb.append("ملخص $label (${from} إلى ${to.minusDays(1)}):\n")
            for (m in members) {
                val mine = done.filter { it.ok && it.memberId == m.id && it.date >= from.toString() && it.date < to.toString() }
                val finished = mine.count { it.taskId != "streak" }
                val fullDays = mine.count { it.taskId == "streak" }
                val activeDays = mine.filter { it.taskId != "streak" }.map { it.date }.distinct().size
                sb.append("- ${m.name}: ${Rules.pts(mine.sumOf { it.points })} نقطة، $finished مهمة منجزة على $activeDays يوم، وأيام كاملة بوقتها: $fullDays.\n")
            }
        }
        sb.append("ملاحظة: الملخص الشهري فيه المنجز والنقاط بس. عدد المهام الفايتة متوفر لهذا الأسبوع فقط.\n")
        return sb.toString()
    }

    private fun system(members: List<Member>, index: List<Task>, statsText: String): String {
        val people = members.joinToString("، ") { "${it.id} = ${it.name} (${AGE_NAMES[it.age] ?: ""}${if (it.leader) "، ولي الأمر" else ""})" }
        val names = members.associate { it.id to it.name }
        val list = index.mapIndexed { i, t ->
            "$i|${t.title}|${names[t.memberId] ?: t.memberId}|${t.day}|${hm(t.start)}|${hm(t.start + t.dur)}|${t.points}|${if (t.personal) "شخصية" else "بيت"}"
        }.joinToString("\n")
        return """
أنت مساعد ولي الأمر داخل تطبيق اسمه "مهام البيت". تقدر: تضيف مهام، تعدل أو تحذف مهام موجودة، تجاوب عن اللي صار، وتقترح توزيع أعدل.
الأعضاء (المعرّف = الاسم): $people
الأيام بالأرقام: 0 الأحد، 1 الاثنين، 2 الثلاثاء، 3 الأربعاء، 4 الخميس، 5 الجمعة، 6 السبت.

$statsText
المهام الحالية، كل سطر: الرقم|الاسم|العضو|اليوم|البداية|آخر موعد|النقاط|النوع
$list

أرجع JSON فقط بهذا الشكل (اترك أي قائمة فاضية إذا ما تحتاجها):
{"reply": "ردك بالعربي", "add": [{"title": "اسم", "member": "معرّف", "days": [0, 1], "start": "17:00", "deadline": "17:30", "points": 10}], "edit": [{"n": 12, "member": "معرّف", "title": "اسم", "start": "17:00", "deadline": "17:30", "points": 10, "day": 3}], "delete": [5, 6]}
القواعد:
- الأوقات بصيغة 24 ساعة HH:MM. start متى تبدأ و deadline آخر موعد. إذا ذُكر وقت واحد اعتبره آخر موعد وخلي start قبله بنص ساعة.
- "كل يوم" يعني الأيام السبعة. "أيام المدرسة" يعني من 0 إلى 4.
- في edit: n هو رقم المهمة من القائمة، واكتب بس الحقول اللي تتغير. إذا التعديل يخص مهمة متكررة بعدة أيام، اكتب عنصر لكل سطر منها.
- في delete: أرقام المهام من القائمة.
- نقاط المهام الجديدة حسب التعب: خفيفة 5، متوسطة 10 إلى 15، ثقيلة 20 إلى 30، إلا إذا حددها ولي الأمر.
- للأسئلة عن اللي صار: جاوب من الإحصائيات بالأرقام، وخلي القوائم فاضية. إذا السؤال عن فترة أو تفصيل ما عندك أرقامه، قول إنه مو متوفر ولا تخمّن.
- لطلب توزيع أعدل: قارن حمل كل واحد (عدد المهام ومجموع النقاط) مع مراعاة عمر كل واحد وقدرته (الصغار مهام خفيفة وقليلة)، واقترح نقل مهام محددة عن طريق edit بتغيير member، واشرح السبب باختصار في reply.
- لا تغيّر شي ما انطلب، وما في شي يتنفذ إلا بعد موافقة ولي الأمر على اقتراحك.
- إذا ناقص شي ضروري اسأل عنه في reply وخلي القوائم فاضية.
- في كل رد أرجع القوائم الكاملة للتغييرات المقترحة حالياً بعد تعديلات ولي الأمر.
        """.trim()
    }

    private fun parseTime(s: String): Int? {
        val p = s.trim().split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    private fun parse(raw: String, members: List<Member>, index: List<Task>): AiReply {
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        val plain = AiReply(raw.trim(), emptyList(), emptyList(), emptyList(), raw)
        if (a < 0 || b <= a) return plain
        val obj = try { JSONObject(raw.substring(a, b + 1)) } catch (e: Exception) { return plain }
        fun member(who: String) = members.firstOrNull { it.id == who } ?: members.firstOrNull { it.name == who }

        val adds = ArrayList<Proposal>()
        val addArr = obj.optJSONArray("add") ?: obj.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until addArr.length()) {
            val o = addArr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim().take(60)
            val m = member(o.optString("member").trim())
            val d = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until d.length()).map { d.optInt(it, -1) }.filter { it in 0..6 }.distinct().sorted()
            val start = parseTime(o.optString("start"))
            if (title.isEmpty() || m == null || days.isEmpty() || start == null) continue
            val deadline = parseTime(o.optString("deadline"))
            val dur = if (deadline != null && deadline > start) deadline - start else 30
            adds += Proposal(title, m.id, days, start, dur, o.optInt("points", 5).coerceIn(1, 100))
        }

        val edits = ArrayList<EditOp>()
        val editArr = obj.optJSONArray("edit") ?: JSONArray()
        for (i in 0 until editArr.length()) {
            val o = editArr.optJSONObject(i) ?: continue
            val t = index.getOrNull(o.optInt("n", -1)) ?: continue
            val start = parseTime(o.optString("start")) ?: t.start
            var deadline = parseTime(o.optString("deadline")) ?: (t.start + t.dur)
            if (deadline <= start) deadline = start + t.dur
            val after = t.copy(
                title = o.optString("title").trim().take(60).ifEmpty { t.title },
                memberId = member(o.optString("member").trim())?.id ?: t.memberId,
                day = o.optInt("day", t.day).let { if (it in 0..6) it else t.day },
                start = start, dur = (deadline - start).coerceIn(5, 24 * 60),
                points = o.optInt("points", t.points).coerceIn(1, 100),
            )
            if (after != t) edits += EditOp(t, after)
        }

        val deletes = ArrayList<Task>()
        val delArr = obj.optJSONArray("delete") ?: JSONArray()
        for (i in 0 until delArr.length()) index.getOrNull(delArr.optInt(i, -1))?.let { if (it !in deletes) deletes += it }

        val empty = adds.isEmpty() && edits.isEmpty() && deletes.isEmpty()
        return AiReply(obj.optString("reply").ifBlank { if (empty) "ما فهمت الطلب، ممكن توضحين؟" else "جهزت التغييرات." }, adds, edits, deletes, raw)
    }

    /** تقدير نقاط مهمة شخصية من اسمها، من 1 إلى max. */
    suspend fun estimate(key: String, model: String, title: String, max: Int): Int = withContext(Dispatchers.IO) {
        val prompt = "مهمة شخصية يومية لفرد من العائلة اسمها: \"$title\". قدّر نقاطها من 1 إلى $max حسب الجهد والوقت اللي تاخذه: " +
            "شي بسيط دقايق = 3 إلى 5، نص ساعة تقريباً = 8 إلى 10، ساعة أو أكثر بتركيز = 12 إلى $max. " +
            "لا تتأثر بالمبالغة في صياغة الاسم. أرجع JSON فقط: {\"points\": رقم}"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.0))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key.trim())
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val http = conn.responseCode
            val text = (if (http in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (http !in 200..299) throw AiError(http, "")
            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val raw = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).optInt("points", 5).coerceIn(1, max)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun ask(
        key: String, model: String, members: List<Member>, tasks: List<Task>, done: List<Done>,
        today: java.time.LocalDate, nowMin: Int, grace: Int, turns: List<AiTurn>,
    ): AiReply = withContext(Dispatchers.IO) {
        // ترتيب ثابت للمهام عشان المساعد يشير لها بأرقام قصيرة
        val index = tasks.sortedWith(compareBy<Task> { it.day }.thenBy { it.start }.thenBy { it.id })
        fun textPart(t: String) = JSONArray().put(JSONObject().put("text", t))
        val contents = JSONArray()
        for (t in turns) contents.put(JSONObject().put("role", t.role).put("parts", textPart(t.text)))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", textPart(system(members, index, stats(members, tasks, done, today, nowMin, grace)))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key.trim())
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val http = conn.responseCode
            val stream = if (http in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (http !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").optString("message") } catch (e: Exception) { "" }
                throw AiError(http, msg)
            }
            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val raw = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            parse(raw, members, index)
        } finally {
            conn.disconnect()
        }
    }
}
