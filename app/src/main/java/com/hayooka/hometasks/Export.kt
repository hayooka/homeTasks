package com.hayooka.hometasks

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** تصدير سجل الإنجاز كامل للقائد: CSV يفتح في Excel، أو JSON. */
object Export {
    private class Row(val date: String, val day: String, val member: String, val title: String, val time: String, val points: String, val state: String, val kind: String)

    private fun rows(done: List<Done>, members: List<Member>, tasks: List<Task>): List<Row> {
        val names = members.associate { it.id to it.name }
        val titles = tasks.associate { it.id to it.title }
        return done.sortedWith(compareBy<Done> { it.date }.thenBy { it.at }).map { d ->
            val bonus = d.taskId == "streak"
            val day = try { DAY_NAMES[Rules.dayIndex(LocalDate.parse(d.date))] } catch (e: Exception) { "" }
            Row(
                d.date, day, names[d.memberId] ?: d.memberId,
                d.title.ifBlank { if (bonus) "مكافأة يوم كامل" else titles[d.taskId] ?: "مهمة محذوفة" },
                if (d.at >= 0) "%02d:%02d".format(d.at / 60, d.at % 60) else "",
                Rules.pts(d.points),
                if (d.ok) "محسوبة" else "تنتظر موافقة القائد",
                if (bonus) "مكافأة" else "مهمة",
            )
        }
    }

    private fun cell(v: String) = "\"" + v.replace("\"", "\"\"") + "\""

    /** يبدأ بعلامة BOM عشان Excel يقرا العربي صح. */
    fun csv(done: List<Done>, members: List<Member>, tasks: List<Task>): String {
        val sb = StringBuilder("﻿")
        sb.append(listOf("التاريخ", "اليوم", "العضو", "المهمة", "وقت التأشير", "النقاط", "الحالة", "النوع").joinToString(",") { cell(it) }).append("\r\n")
        for (r in rows(done, members, tasks)) {
            sb.append(listOf(r.date, r.day, r.member, r.title, r.time, r.points, r.state, r.kind).joinToString(",") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    fun json(done: List<Done>, members: List<Member>, tasks: List<Task>): String {
        val arr = JSONArray()
        for (r in rows(done, members, tasks)) {
            arr.put(
                JSONObject().put("date", r.date).put("day", r.day).put("member", r.member).put("task", r.title)
                    .put("time", r.time).put("points", r.points).put("state", r.state).put("kind", r.kind),
            )
        }
        return arr.toString(2)
    }
}
