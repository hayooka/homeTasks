package com.hayooka.hometasks

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * تصدير السجل للقائد: سطر لكل مهمة كانت على كل واحد بكل يوم، ومعها اللي صار فيها.
 * CSV يفتح في Excel، أو JSON.
 */
object Export {
    private val HEADERS = listOf("التاريخ", "اليوم", "العضو", "المهمة", "النوع", "آخر موعد", "النتيجة", "وقت التأشير", "النقاط المحسوبة", "نقاط المهمة")
    private val KEYS = listOf("date", "day", "member", "task", "kind", "deadline", "result", "time", "earned", "points")

    private fun hm(min: Int) = "%02d:%02d".format((min / 60) % 24, min % 60)
    private fun dayName(date: String) = try { DAY_NAMES[Rules.dayIndex(LocalDate.parse(date))] } catch (e: Exception) { "" }

    /**
     * days: مهام كل يوم كما انحفظت بيومها. done: كل التأشيرات.
     * الأيام اللي ما لها قائمة محفوظة (قبل هالتحديث) تنعرض تأشيراتها بس.
     */
    private fun rows(
        days: Map<String, List<Task>>, done: List<Done>, members: List<Member>,
        today: LocalDate, nowMin: Int, grace: Int,
    ): List<List<String>> {
        val names = members.associate { it.id to it.name }
        val byKey = done.associateBy { it.date + "_" + it.taskId }
        val out = ArrayList<List<String>>()
        val dates = (days.keys + done.map { it.date }).distinct().sorted()
        for (date in dates) {
            val planned = days[date]
            val used = HashSet<String>()
            if (planned != null) {
                for (t in planned.sortedWith(compareBy<Task> { names[it.memberId] ?: "" }.thenBy { it.start + it.dur })) {
                    val d = byKey[date + "_" + t.id]
                    if (d != null) used += t.id
                    val limit = t.start + t.dur + grace
                    val result = when {
                        d != null && !d.ok -> "تنتظر موافقة القائد"
                        d != null && !t.allDay && d.at > limit -> "متأخرة"
                        d != null -> "بوقتها"
                        date == today.toString() && (t.allDay || nowMin <= limit) -> "لسا بوقتها"
                        else -> "ما انعملت"
                    }
                    out += listOf(
                        date, dayName(date), names[t.memberId] ?: t.memberId, t.title, if (t.personal) "شخصية" else "بيت",
                        if (t.allDay) "بدون موعد" else hm(t.start + t.dur), result,
                        if (d != null && d.at >= 0) hm(d.at) else "", if (d != null && d.ok) Rules.pts(d.points) else "0", t.points.toString(),
                    )
                }
            }
            // تأشيرات ما لها مهمة بالقائمة المحفوظة: مكافآت اليوم الكامل، أو أيام قبل حفظ القوائم
            for (d in done.filter { it.date == date && it.taskId !in used }.sortedBy { it.at }) {
                val bonus = d.taskId == "streak"
                out += listOf(
                    date, dayName(date), names[d.memberId] ?: d.memberId,
                    d.title.ifBlank { if (bonus) "مكافأة يوم كامل" else "مهمة" }, if (bonus) "مكافأة" else "بيت",
                    "", if (!d.ok) "تنتظر موافقة القائد" else if (bonus) "مكافأة" else "انعملت",
                    if (d.at >= 0) hm(d.at) else "", if (d.ok) Rules.pts(d.points) else "0", "",
                )
            }
        }
        return out
    }

    private fun cell(v: String) = "\"" + v.replace("\"", "\"\"") + "\""

    /** يبدأ بعلامة BOM عشان Excel يقرا العربي صح. */
    fun csv(days: Map<String, List<Task>>, done: List<Done>, members: List<Member>, today: LocalDate, nowMin: Int, grace: Int): String {
        val sb = StringBuilder("﻿")
        sb.append(HEADERS.joinToString(",") { cell(it) }).append("\r\n")
        for (r in rows(days, done, members, today, nowMin, grace)) sb.append(r.joinToString(",") { cell(it) }).append("\r\n")
        return sb.toString()
    }

    fun json(days: Map<String, List<Task>>, done: List<Done>, members: List<Member>, today: LocalDate, nowMin: Int, grace: Int): String {
        val arr = JSONArray()
        for (r in rows(days, done, members, today, nowMin, grace)) {
            val o = JSONObject()
            KEYS.forEachIndexed { i, k -> o.put(k, r[i]) }
            arr.put(o)
        }
        return arr.toString(2)
    }
}
