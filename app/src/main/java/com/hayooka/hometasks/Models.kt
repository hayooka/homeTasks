package com.hayooka.hometasks

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

data class Member(
    val id: String, val name: String, val age: String, val character: Int,
    val pinHash: String, val leader: Boolean, val total: Double,
    val guardAt: Long = 0, val guardOk: Boolean = false,
    val streak: Int = 0, val streakDate: String = "",
    /** اللي يتمناه إذا طلع الأول بالشهر. */
    val wish: String = "",
)

/** day: 0 = الأحد … 6 = السبت. start بالدقايق من أول اليوم، وآخر موعد للمهمة = start + dur. */
data class Task(
    val id: String, val title: String, val memberId: String,
    val day: Int, val start: Int, val dur: Int, val points: Int,
    /** personal: مهمة شخصية أضافها صاحبها. approved: القائد وافق على نقاطها. */
    val personal: Boolean = false, val approved: Boolean = true,
) {
    /** بدون موعد: متاحة طول اليوم وما تتأخر. */
    val allDay get() = start == 0 && dur >= 24 * 60
}

/** at: الدقيقة من اليوم اللي تأشّرت فيها المهمة، و-1 إذا مو معروفة. */
/** ok: محسوبة. المهمة المتأخرة تبقى ok = false لحد ما القائد يوافق عليها. taskId = "streak" لمكافأة السلسلة. */
data class Done(val taskId: String, val memberId: String, val date: String, val points: Double, val at: Int, val ok: Boolean)

data class House(
    val name: String, val grace: Int, val startDate: String, val penalties: Map<String, String>,
    val aiKey: String, val aiModel: String,
    val enforce: Boolean, val enforceDate: String, val enforceMin: Int,
    val seedVersion: Int,
    /** بصمة كلمة السر اللي يحتاجها الأعضاء للخروج أو لإلغاء حماية الحذف. */
    val uninstallHash: String,
)

fun secretHash(code: String, secret: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest("$code:secret:$secret".toByteArray()).joinToString("") { "%02x".format(it) }

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
    const val LEVEL_SIZE = 200.0

    fun dayIndex(d: LocalDate): Int = d.dayOfWeek.value % 7
    fun weekStart(d: LocalDate): LocalDate = d.minusDays(dayIndex(d).toLong())

    /** النقاط كاملة لحد آخر موعد + المهلة، وبعدها تنقص 10% كل ربع ساعة لحد النص. */
    fun pointsNow(t: Task, nowMin: Int, grace: Int): Int {
        val deadline = t.start + t.dur + grace
        if (nowMin <= deadline) return t.points
        val steps = ceil((nowMin - deadline) / 15.0).toInt()
        val factor = maxOf(0.5, 1.0 - 0.1 * steps)
        return (t.points * factor).roundToInt()
    }

    fun isLate(t: Task, nowMin: Int, grace: Int) = nowMin > t.start + t.dur + grace

    fun level(total: Double): Int = (total / LEVEL_SIZE).toInt() + 1
    fun levelProgress(total: Double): Float = ((total % LEVEL_SIZE) / LEVEL_SIZE).toFloat()

    /** عرض النقاط: بدون كسور إلا إذا فيها نص نقطة. */
    fun pts(x: Double): String = if (x % 1.0 == 0.0) x.toLong().toString() else String.format(java.util.Locale.US, "%.1f", x)

    /** السلسلة الحالية: تنحسب بس إذا آخر يوم كامل كان اليوم أو أمس، وغير هيك ترجع صفر. */
    fun streakNow(m: Member, today: LocalDate): Int =
        if (m.streakDate == today.toString() || m.streakDate == today.minusDays(1).toString()) m.streak else 0

    /** هل كل مهام اليوم انعملت بوقتها؟ justDone هي المهمة اللي تأشّرت للتو. */
    fun allOnTime(mine: List<Task>, doneToday: List<Done>, justDone: String, grace: Int): Boolean =
        mine.isNotEmpty() && mine.filter { !it.personal || it.approved }.all { t ->
            t.id == justDone || doneToday.any { d -> d.taskId == t.id && d.ok && (d.at < 0 || d.at <= t.start + t.dur + grace) }
        }

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
        Member("F", "فرح", "adult", -1, "", true, 0.0),
        Member("B", "بدر", "adult", -1, "", false, 0.0),
        Member("I", "إسراء", "adult", -1, "", false, 0.0),
        Member("H", "حمزة", "u16", -1, "", false, 0.0),
        Member("O", "عمرو", "u10", -1, "", false, 0.0),
    )
    private val names = members.associate { it.id to it.name }

    // حرف لكل يوم، الأحد أول
    private val rota = mapOf(
        "cook" to "BFHFFBH", "adk" to "IIIIIFF", "alk" to "FFFFFII", "ls" to "BOBOBOB", "lc" to "OHOHOHH",
        "ds" to "OHOHBOB", "dc" to "HOBBHHO", "gr" to "FIFIIIF", "br" to "HOBHOBH", "te" to "BIIBBHH",
        "sa" to "IBHIHIF", "wa" to "HIHIHFI", "fo" to "HBIBHIB", "bi" to "OBHOBHO", "tr" to "BHBHHBB",
    )

    /** الأيام اللي لها جدول خاص: 4 = الخميس، 5 = الجمعة. */
    val SPECIAL_DAYS = setOf(4, 5)

    fun pointsFor(t: String): Int = when {
        t.startsWith("صلاة") || t.startsWith("الاستحمام") -> 10
        t.startsWith("الدراسة") || t.startsWith("حفظ القرآن") -> 20
        t.startsWith("تنظيف البيت") -> 40
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
            val part = d == 1 || d == 3
            val special = d in SPECIAL_DAYS

            if (special) {
                // يوم خاص: فرح من 10 إلى 3 (طبخ ومطبخ وحمامات)، إسراء تنظيف البيت كامل 12 إلى 2،
                // والأولاد الصبح زبايل وغرفتهم، ومع السفرة قبل الأكل وبعده
                add("المطبخ الصبح", "F", 10, 0, 30)
                add("الحمام الأول", "F", 10, 30, 20)
                add("الحمام الثاني", "F", 10, 50, 20)
                add("عمل الغدا", "F", 11, 15, 105)
                add("فرش سفرة الغدا", r("ls"), 13, 0, 15)
                add("نقل سفرة الغدا", r("lc"), 14, 0, 15)
                add("المطبخ بعد الغدا", "F", 14, 15, 45)
                add("تنظيف البيت كامل", "I", 12, 0, 120)
                add("تفضاية الزبايل", r("bi"), 10, 0, 15)
                for (boy in listOf("B", "H", "O")) add("غرفة الأولاد", boy, 10, 15, 15)
                if (d == 4) {
                    add("تدريس عمرو (جزء)", "F", 15, 45, 45)
                    add("تدريس عمرو (تكملة)", "B", 19, 0, 45)
                }
            } else {
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
                add("تفضاية الزبايل", r("bi"), 20, 0, 15)
            }

            add("الغسالة والنشافة", r("wa"), 18, 10, 15)
            add("طوي الغسيل", r("fo"), 18, 30, 20)
            add("طوي الغسيل مع " + names.getValue(r("fo")), "O", 18, 30, 20)
            add("عمل العشا", r("cook"), 19, 30, 45)
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
        return all + extras() + extrasV3()
    }

    /**
     * مهام عمرو اليومية: الصلوات والدراسة وحفظ القرآن. أوقات الصلاة تقريبية وتتغير خلال السنة،
     * فعدّليها من الجدول الزمني. كل مهمة: (الاسم، البداية، آخر موعد).
     */
    fun extras(): List<Task> {
        val out = mutableListOf<Task>()
        fun add(d: Int, t: String, h1: Int, m1: Int, h2: Int, m2: Int) {
            val s = h1 * 60 + m1
            out += Task("", t, "O", d, s, h2 * 60 + m2 - s, pointsFor(t))
        }
        for (d in 0..6) {
            add(d, "صلاة الفجر", 4, 50, 6, 15)
            add(d, "صلاة الظهر", 11, 35, 14, 30)
            add(d, "صلاة العصر", 14, 35, 16, 50)
            add(d, "صلاة المغرب", 16, 55, 18, 15)
            add(d, "صلاة العشاء", 18, 20, 21, 0)
            if (d <= 4) add(d, "الدراسة", 16, 0, 18, 0)
            add(d, "حفظ القرآن", 19, 0, 20, 0)
        }
        return out
    }

    /** إضافة ثانية لعمرو: تفريش الأسنان الصبح وبالليل كل يوم، والاستحمام الثلاثاء والخميس والسبت بالليل. */
    fun extrasV3(): List<Task> {
        val out = mutableListOf<Task>()
        fun add(d: Int, t: String, h1: Int, m1: Int, h2: Int, m2: Int) {
            val s = h1 * 60 + m1
            out += Task("", t, "O", d, s, h2 * 60 + m2 - s, pointsFor(t))
        }
        for (d in 0..6) {
            // الجمعة والسبت بدون مدرسة، فموعد الصبح أوسع
            if (d >= 5) add(d, "تفريش الأسنان الصبح", 8, 0, 10, 0) else add(d, "تفريش الأسنان الصبح", 6, 15, 7, 0)
            add(d, "تفريش الأسنان بالليل", 20, 0, 21, 0)
            if (d == 2 || d == 4 || d == 6) add(d, "الاستحمام", 19, 30, 21, 0)
        }
        return out
    }
}
