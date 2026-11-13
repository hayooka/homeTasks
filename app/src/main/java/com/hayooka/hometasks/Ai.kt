package com.hayooka.hometasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val DEFAULT_AI_MODEL = "gemini-3.5-flash"

/** مهمة يقترحها المساعد، قبل ما الليدر توافق عليها. */
data class Proposal(val title: String, val memberId: String, val days: List<Int>, val start: Int, val dur: Int, val points: Int)

/** role: "user" أو "model". text هو النص كما انرسل أو رجع. */
data class AiTurn(val role: String, val text: String)

class AiReply(val reply: String, val tasks: List<Proposal>, val raw: String)

class AiError(val http: Int, message: String) : Exception(message)

/** اتصال مباشر بـ Gemini API بمفتاح مجاني من Google AI Studio. المفتاح محفوظ في بيانات البيت. */
object Ai {
    private fun system(members: List<Member>): String {
        val people = members.joinToString("، ") { "${it.id} = ${it.name}" }
        return """
            أنت مساعد داخل تطبيق اسمه "مهام البيت". شغلتك الوحيدة: تحويل كلام الليدر إلى مهام بيت تنضاف للجدول.
            الأعضاء (المعرّف = الاسم): $people
            الأيام بالأرقام: 0 الأحد، 1 الاثنين، 2 الثلاثاء، 3 الأربعاء، 4 الخميس، 5 الجمعة، 6 السبت.
            أرجع JSON فقط بهذا الشكل:
            {"reply": "جملة قصيرة بالعربي", "tasks": [{"title": "اسم المهمة", "member": "معرّف العضو", "days": [0, 1], "start": "17:00", "deadline": "17:30", "points": 10}]}
            القواعد:
            - start متى تبدأ المهمة و deadline آخر موعد لازم تخلص قبله، الاثنين بصيغة 24 ساعة HH:MM.
            - إذا الليدر ذكر وقت واحد بس اعتبره آخر موعد، وخلي start قبله بنص ساعة.
            - "كل يوم" يعني الأيام السبعة كلها. "أيام المدرسة" يعني من 0 إلى 4.
            - إذا المهمة على أكثر من شخص، كرّر العنصر لكل شخص.
            - النقاط حسب التعب: خفيفة 5، متوسطة 10 إلى 15، ثقيلة 20 إلى 30، إلا إذا حددها الليدر.
            - إذا ناقص شي ضروري (مين، أي يوم، أي ساعة) اسأل عنه في reply وخلي tasks فاضية.
            - في كل رد أرجع القائمة الكاملة للمهام المقترحة حالياً بعد تعديلات الليدر، مو بس الجديد.
            - لا تضيف مهام ما انطلبت، ولا تجاوب على شي خارج مهام البيت.
        """.trimIndent()
    }

    private fun parseTime(s: String): Int? {
        val p = s.trim().split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    private fun parse(raw: String, members: List<Member>): AiReply {
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return AiReply(raw.trim(), emptyList(), raw)
        val obj = try { JSONObject(raw.substring(a, b + 1)) } catch (e: Exception) { return AiReply(raw.trim(), emptyList(), raw) }
        val out = ArrayList<Proposal>()
        val arr = obj.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim().take(60)
            val who = o.optString("member").trim()
            val member = members.firstOrNull { it.id == who } ?: members.firstOrNull { it.name == who }
            val d = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until d.length()).map { d.optInt(it, -1) }.filter { it in 0..6 }.distinct().sorted()
            val start = parseTime(o.optString("start"))
            if (title.isEmpty() || member == null || days.isEmpty() || start == null) continue
            val deadline = parseTime(o.optString("deadline"))
            val dur = if (deadline != null && deadline > start) deadline - start else 30
            out += Proposal(title, member.id, days, start, dur, o.optInt("points", 5).coerceIn(1, 100))
        }
        return AiReply(obj.optString("reply").ifBlank { if (out.isEmpty()) "ما فهمت الطلب، ممكن توضحين؟" else "جهزت المهام." }, out, raw)
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

    suspend fun ask(key: String, model: String, members: List<Member>, turns: List<AiTurn>): AiReply = withContext(Dispatchers.IO) {
        fun textPart(t: String) = JSONArray().put(JSONObject().put("text", t))
        val contents = JSONArray()
        for (t in turns) contents.put(JSONObject().put("role", t.role).put("parts", textPart(t.text)))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", textPart(system(members))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
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
            parse(raw, members)
        } finally {
            conn.disconnect()
        }
    }
}
