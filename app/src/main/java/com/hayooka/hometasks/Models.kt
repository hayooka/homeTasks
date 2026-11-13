package com.hayooka.hometasks

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

data class Member(
    val id: String, val name: String, val age: String, val character: Int,
    val pinHash: String, val leader: Boolean, val total: Long,
)

/** day: 0 = الأحد … 6 = السبت. start و dur بالدقايق. */
data class Task(
    val id: String, val title: String, val memberId: String,
    val day: Int, val start: Int, val dur: Int, val points: Int,
)

data class Done(val taskId: String, val memberId: String, val date: String, val points: Int)

data class House(val name: String, val grace: Int, val startDate: String, val penalties: Map<String, String>)

val DAY_NAMES = listOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
val AGE_KEYS = listOf("u10", "u16", "adult")
val AGE_NAMES = mapOf("u10" to "تحت 10", "u16" to "من 10 إلى 15", "adult" to "16 وفوق")

val DEFAULT_PENALTIES = mapOf(
    "u10_light" to "وقت الشاشة اليوم أقل بنص ساعة",
    "u10_heavy" to "ما في ألعاب ولا كرتون اليوم",
    "u16_light" to "وقت الشاشة اليوم ساعة وحدة بس",
    "u16_heavy" to "ما في واي فاي من الساعة 8 المسا",
    "adult_light" to "مهمة إضافية اليوم",
    "adult_heavy" to "مهمة إضافية وما في واي فاي من الساعة 9 المسا",
)

object Rules {
    const val LEVEL_SIZE = 200L

    fun dayIndex(d: LocalDate): Int = d.dayOfWeek.value % 7
    fun weekStart(d: LocalDate): LocalDate = d.minusDays(dayIndex(d).toLong())

    /** النقاط كاملة لحد نهاية المهمة + المهلة، وبعدها تنقص 10% كل ربع ساعة لحد النص. */
    fun pointsNow(t: Task, nowMin: Int, grace: Int): Int {
        val deadline = t.start + t.dur + grace
        if (nowMin <= deadline) return t.points
        val steps = ceil((nowMin - deadline) / 15.0).toInt()
        val factor = maxOf(0.5, 1.0 - 0.1 * steps)
        return (t.points * factor).roundToInt()
    }

    fun isLate(t: Task, nowMin: Int, grace: Int) = nowMin > t.start + t.dur + grace

    fun level(total: Long): Int = (total / LEVEL_SIZE).toInt() + 1
    fun levelProgress(total: Long): Float = (total % LEVEL_SIZE) / LEVEL_SIZE.toFloat()

    /** عقوبة اليوم مبنية على مهام أمس: خفيفة إذا فاته شي، وثقيلة إذا فاته أكثر من النص. */
    fun penalty(house: House, age: String, missed: Int, total: Int): String? {
        if (missed <= 0 || total <= 0) return null
        val key = age + if (missed * 2 > total) "_heavy" else "_light"
        return house.penalties[key]?.takeIf { it.isNotBlank() } ?: DEFAULT_PENALTIES[key]
    }

    fun fmt(min: Int): String {
        val h = (min / 60) % 24
        val m = min % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "ص" else "م"}"
    }
}

/** جدول العائلة الأولي: نفس التوزيع اللي اتفقنا عليه، وكله يتعدل من صفحة الإدارة. */
object Seed {
    val members = listOf(
        Member("F", "فرح", "adult", -1, "", true, 0),
        Member("B", "بدر", "adult", -1, "", false, 0),
        Member("I", "إسراء", "adult", -1, "", false, 0),
        Member("H", "حمزة", "u16", -1, "", false, 0),
        Member("O", "عمرو", "u10", -1, "", false, 0),
    )
    private val names = members.associate { it.id to it.name }

    // حرف لكل يوم، الأحد أول
    private val rota = mapOf(
        "cook" to "BFHFFBH", "adk" to "IIIIIFF", "alk" to "FFFFFII", "ls" to "BOBOBOB", "lc" to "OHOHOHH",
        "ds" to "OHOHBOB", "dc" to "HOBBHHO", "gr" to "FIFIIIF", "br" to "HOBHOBH", "te" to "BIIBBHH",
        "sa" to "IBHIHIF", "wa" to "HIHIHFI", "fo" to "HBIBHIB", "bi" to "OBHOBHO", "tr" to "BHBHHBB",
    )

    fun pointsFor(t: String): Int = when {
        t.startsWith("عمل الغدا") -> 30
        t.startsWith("عمل العشا") -> 20
        t.contains("جزء") || t.contains("تكملة") -> 10
        t.startsWith("المطبخ بعد الغدا") -> 20
        t.startsWith("المطبخ بعد العشا") -> 15
        t.startsWith("المطبخ") -> 10
        t.startsWith("الحمام") -> 15
        t.startsWith("تدريس") -> 20
        t.startsWith("طوي الغسيل مع") -> 6
        t.startsWith("طوي") -> 10
        t.startsWith("الصالون") -> 10
        t.startsWith("غرفة") || t.startsWith("الغسالة") -> 8
        else -> 5
    }

    fun tasks(): List<Task> {
        val all = mutableListOf<Task>()
        for (d in 0..6) {
            val out = mutableListOf<Task>()
            fun add(t: String, p: String, h: Int, m: Int, dur: Int) {
                out += Task("", t, p, d, h * 60 + m, dur, pointsFor(t))
            }
            fun r(k: String): String = rota.getValue(k)[d].toString()
            val part = d == 1 || d == 3 || d == 4

            add("المطبخ الصبح", "F", 9, 0, 30)
            add("الحمام الأول", "F", 9, 30, 20)
            add("عمل الغدا", "F", 12, 0, 90)
            add("فرش سفرة الغدا", r("ls"), 14, 15, 15)
            add("نقل سفرة الغدا", r("lc"), 15, 0, 15)
            add(if (part) "المطبخ بعد الغدا (جزء)" else "المطبخ بعد الغدا", r("alk"), 15, 15, if (part) 20 else 45)
            if (d == 0 || d == 2) add("تدريس عمرو", "F", 16, 15, 90)
            if (part) {
                add("تدريس عمرو (جزء)", "F", 15, 45, 45)
                add("تدريس عمرو (تكملة)", "B", 19, 0, 45)
                add("تكملة المطبخ", "F", 19, 0, 30)
            }
            add("غرفة البنات", r("gr"), 17, 0, 15)
            add("غرفة الأولاد", r("br"), 17, 0, 15)
            add("غرفة تيتا", r("te"), 17, 15, 15)
            add("الصالون", r("sa"), 17, 30, 20)
            add("الحمام الثاني", "I", 17, 50, 20)
            add("الغسالة والنشافة", r("wa"), 18, 10, 15)
            add("طوي الغسيل", r("fo"), 18, 30, 20)
            add("طوي الغسيل مع " + names.getValue(r("fo")), "O", 18, 30, 20)
            add("عمل العشا", r("cook"), 19, 30, 45)
            add("تفضاية الزبايل", r("bi"), 20, 0, 15)
            add("فرش سفرة العشا", r("ds"), 20, 15, 15)
            add("نقل سفرة العشا", r("dc"), 21, 0, 15)
            add("المطبخ بعد العشا", r("adk"), 21, 15, 30)
            add("تنزيل الزبالة", r("tr"), 21, 30, 15)

            // ما حد عليه مهمتين بنفس الوقت: المتداخلة تتأخر لبعد اللي قبلها
            for (p in names.keys) {
                var end = 0
                val mine = out.withIndex().filter { it.value.memberId == p }.sortedBy { it.value.start }
                for ((i, t) in mine) {
                    var s = t.start
                    if (s < end) s = ceil(end / 5.0).toInt() * 5
                    out[i] = t.copy(start = s)
                    end = s + t.dur
                }
            }
            all += out
        }
        return all
    }
}
