package com.hayooka.hometasks

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.time.LocalDate

/**
 * كل البيانات تحت houses/{code}:
 *   members/{id}  tasks/{id}  done/{date_taskId}
 */
/** مكافأة اللي يخلص كل مهام يومه بوقتها. */
const val DAILY_BONUS = 12.0

/** رقم آخر دفعة مهام في الجدول الأصلي. */
const val SEED_VERSION = 3

class Repo {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun house(code: String) = db.collection("houses").document(code)
    private fun members(code: String) = house(code).collection("members")
    private fun tasks(code: String) = house(code).collection("tasks")
    private fun done(code: String) = house(code).collection("done")

    suspend fun signIn() {
        if (auth.currentUser == null) auth.signInAnonymously().await()
    }

    fun pinHash(code: String, memberId: String, pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$code:$memberId:$pin".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun houseExists(code: String): Boolean = house(code).get().await().exists()

    /** ينشئ البيت ويعبّيه بأعضاء العائلة وجدولها. يرجّع كود البيت. */
    suspend fun createHouse(name: String, leaderPin: String): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val code = (1..6).map { alphabet.random() }.joinToString("")
        val batch = db.batch()
        batch.set(
            house(code),
            mapOf(
                "name" to name, "grace" to 30, "startDate" to LocalDate.now().toString(),
                "penalties" to DEFAULT_PENALTIES, "owner" to (auth.uid ?: ""), "seedVersion" to SEED_VERSION,
            ),
        )
        for (m in Seed.members) {
            batch.set(
                members(code).document(m.id),
                mapOf(
                    "name" to m.name, "age" to m.age, "character" to -1, "leader" to m.leader, "total" to 0L,
                    "pinHash" to if (m.leader) pinHash(code, m.id, leaderPin) else "",
                ),
            )
        }
        for (t in Seed.tasks()) batch.set(tasks(code).document(), taskMap(t))
        batch.commit().await()
        return code
    }

    // ---------- قراءة حيّة ----------

    private fun <T> Query.asFlow(map: (DocumentSnapshot) -> T): Flow<List<T>> = callbackFlow {
        val reg = addSnapshotListener { snap, _ -> if (snap != null) trySend(snap.documents.map(map)) }
        awaitClose { reg.remove() }
    }

    fun houseFlow(code: String): Flow<House?> = callbackFlow {
        val reg = house(code).addSnapshotListener { s, _ ->
            if (s != null && s.exists()) {
                val pen = (s.get("penalties") as? Map<*, *>)?.entries
                    ?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
                trySend(
                    House(
                        s.getString("name") ?: "", (s.getLong("grace") ?: 30L).toInt(), s.getString("startDate") ?: "", pen,
                        s.getString("aiKey") ?: "", s.getString("aiModel") ?: "",
                        s.getBoolean("enforce") ?: false, s.getString("enforceDate") ?: "", (s.getLong("enforceMin") ?: 0L).toInt(),
                        (s.getLong("seedVersion") ?: 1L).toInt(), s.getString("uninstallHash") ?: "",
                    ),
                )
            }
        }
        awaitClose { reg.remove() }
    }

    fun membersFlow(code: String): Flow<List<Member>> = members(code).asFlow {
        Member(
            it.id, it.getString("name") ?: "", it.getString("age") ?: "adult",
            (it.getLong("character") ?: -1L).toInt(), it.getString("pinHash") ?: "",
            it.getBoolean("leader") ?: false, it.getDouble("total") ?: 0.0,
            it.getLong("guardAt") ?: 0L, it.getBoolean("guardOk") ?: false,
            (it.getLong("streak") ?: 0L).toInt(), it.getString("streakDate") ?: "", it.getString("wish") ?: "",
        )
    }

    fun tasksFlow(code: String): Flow<List<Task>> = tasks(code).asFlow {
        Task(
            it.id, it.getString("title") ?: "", it.getString("memberId") ?: "",
            (it.getLong("day") ?: 0L).toInt(), (it.getLong("start") ?: 0L).toInt(),
            (it.getLong("dur") ?: 15L).toInt(), (it.getLong("points") ?: 5L).toInt(),
            it.getBoolean("personal") ?: false, it.getBoolean("approved") ?: true,
        )
    }

    /** كل الإنجازات من تاريخ معيّن (yyyy-MM-dd) وطالع. */
    fun doneFlow(code: String, since: String): Flow<List<Done>> =
        done(code).whereGreaterThanOrEqualTo("date", since).asFlow {
            Done(it.getString("taskId") ?: "", it.getString("memberId") ?: "", it.getString("date") ?: "", it.getDouble("points") ?: 0.0, (it.getLong("at") ?: -1L).toInt(), it.getBoolean("ok") ?: true)
        }

    // ---------- كتابة ----------

    /** ok = false للمهمة المتأخرة: تنسجل بس نقاطها ما تنضاف لحد ما القائد يوافق. */
    suspend fun complete(code: String, t: Task, date: String, points: Double, at: Int, ok: Boolean) {
        val b = db.batch()
        b.set(
            done(code).document("${date}_${t.id}"),
            mapOf("taskId" to t.id, "memberId" to t.memberId, "date" to date, "points" to points, "at" to at, "ok" to ok),
        )
        if (ok) b.update(members(code).document(t.memberId), "total", FieldValue.increment(points))
        b.commit().await()
    }

    suspend fun undo(code: String, t: Task, date: String, d: Done) {
        val b = db.batch()
        b.delete(done(code).document("${date}_${t.id}"))
        if (d.ok) b.update(members(code).document(t.memberId), "total", FieldValue.increment(-d.points))
        b.commit().await()
    }

    /** موافقة اللقائد على مهمة متأخرة: تنحسب نقاطها. */
    suspend fun approve(code: String, d: Done) {
        val b = db.batch()
        b.update(done(code).document("${d.date}_${d.taskId}"), "ok", true)
        b.update(members(code).document(d.memberId), "total", FieldValue.increment(d.points))
        b.commit().await()
    }

    /** رفض اللقائد: المهمة ترجع غير معمولة. */
    suspend fun reject(code: String, d: Done) {
        done(code).document("${d.date}_${d.taskId}").delete().await()
    }

    /** يوم كامل بوقته: مكافأة ثابتة، ومعها نص نقطة عن كل يوم بالسلسلة. مرة وحدة باليوم. */
    suspend fun awardStreak(code: String, m: Member, today: java.time.LocalDate) {
        val date = today.toString()
        if (m.streakDate == date) return
        val streak = if (m.streakDate == today.minusDays(1).toString()) m.streak + 1 else 1
        val bonus = DAILY_BONUS + 0.5 * streak
        val b = db.batch()
        b.update(members(code).document(m.id), mapOf("streak" to streak, "streakDate" to date, "total" to FieldValue.increment(bonus)))
        b.set(
            done(code).document("${date}_streak_${m.id}"),
            mapOf("taskId" to "streak", "memberId" to m.id, "date" to date, "points" to bonus, "at" to -1, "ok" to true),
        )
        b.commit().await()
    }

    /** تغيير اسم مهمة يغيّر كل المهام اللي لها نفس الاسم، بكل الأيام ولكل الأشخاص. */
    suspend fun renameTasks(code: String, old: String, new: String) {
        val docs = tasks(code).whereEqualTo("title", old).get().await().documents
        if (docs.isEmpty()) return
        val b = db.batch()
        for (d in docs) b.update(d.reference, "title", new)
        b.commit().await()
    }

    /**
     * يضيف لبيت موجود المهام اللي انضافت للجدول الأصلي بعد إنشائه، مرة وحدة لكل دفعة وبدون تكرار.
     * كل دفعة تنضاف بس إذا البيت ما وصلها، عشان اللي انحذف أو تعدّل من دفعة قديمة ما يرجع.
     */
    suspend fun migrateSeed(code: String, from: Int) {
        val existing = tasks(code).get().await().documents
            .map { Triple(it.getString("title") ?: "", it.getString("memberId") ?: "", (it.getLong("day") ?: 0L).toInt()) }.toSet()
        val hasOmar = members(code).document("O").get().await().exists()
        val fresh = (if (from < 2) Seed.extras() else emptyList()) + (if (from < 3) Seed.extrasV3() else emptyList())
        val b = db.batch()
        if (hasOmar) for (t in fresh) if (Triple(t.title, t.memberId, t.day) !in existing) b.set(tasks(code).document(), taskMap(t))
        b.update(house(code), "seedVersion", SEED_VERSION)
        b.commit().await()
    }

    suspend fun setPin(code: String, memberId: String, hash: String) {
        members(code).document(memberId).update("pinHash", hash).await()
    }

    suspend fun setCharacter(code: String, memberId: String, kind: Int) {
        members(code).document(memberId).update("character", kind).await()
    }

    private fun taskMap(t: Task) = mapOf(
        "title" to t.title, "memberId" to t.memberId, "day" to t.day, "start" to t.start, "dur" to t.dur, "points" to t.points,
        "personal" to t.personal, "approved" to t.approved,
    )

    suspend fun saveTask(code: String, t: Task) {
        val ref = if (t.id.isEmpty()) tasks(code).document() else tasks(code).document(t.id)
        ref.set(taskMap(t)).await()
    }

    suspend fun addTasks(code: String, list: List<Task>) {
        val b = db.batch()
        for (t in list) b.set(tasks(code).document(), taskMap(t))
        b.commit().await()
    }

    suspend fun setStartDate(code: String, date: String) {
        house(code).update("startDate", date).await()
    }

    /** تشغيل أو إيقاف تنفيذ العقوبات. وقت التشغيل ينحفظ عشان ما نحاسب على اللي قبله. */
    suspend fun setEnforce(code: String, on: Boolean, date: String, nowMin: Int) {
        house(code).update(mapOf("enforce" to on, "enforceDate" to date, "enforceMin" to nowMin)).await()
    }

    /** نبضة من خدمة الحراسة على جوال العضو: آخر وقت كانت شغالة وهل صلاحياتها كاملة. */
    suspend fun beat(code: String, memberId: String, ok: Boolean) {
        members(code).document(memberId).update(mapOf("guardAt" to System.currentTimeMillis(), "guardOk" to ok)).await()
    }

    suspend fun setWish(code: String, memberId: String, wish: String) {
        members(code).document(memberId).update("wish", wish).await()
    }

    suspend fun setUninstallHash(code: String, hash: String) {
        house(code).update("uninstallHash", hash).await()
    }

    suspend fun saveAi(code: String, key: String, model: String) {
        house(code).update(mapOf("aiKey" to key, "aiModel" to model)).await()
    }

    // ---------- المهام الشخصية ----------

    private suspend fun personalDocs(code: String, memberId: String, title: String) =
        tasks(code).whereEqualTo("memberId", memberId).get().await().documents
            .filter { it.getString("title") == title && it.getBoolean("personal") == true }

    /** تعديل صاحب المهمة لاسمها أو موعدها، على كل أيامها. */
    suspend fun updatePersonal(code: String, memberId: String, oldTitle: String, newTitle: String, start: Int, dur: Int) {
        val docs = personalDocs(code, memberId, oldTitle)
        if (docs.isEmpty()) return
        val b = db.batch()
        for (d in docs) b.update(d.reference, mapOf("title" to newTitle, "start" to start, "dur" to dur))
        b.commit().await()
    }

    suspend fun deletePersonal(code: String, memberId: String, title: String) {
        val docs = personalDocs(code, memberId, title)
        if (docs.isEmpty()) return
        val b = db.batch()
        for (d in docs) b.delete(d.reference)
        b.commit().await()
    }

    /** موافقة اللقائد على نقاط مهمة شخصية. إذا صاحبها أشّر عليها اليوم وهي تنتظر، تنحسب له بالنقاط الموافق عليها. */
    suspend fun approvePersonal(code: String, memberId: String, title: String, points: Int, pending: Done?) {
        val docs = personalDocs(code, memberId, title)
        val b = db.batch()
        for (d in docs) b.update(d.reference, mapOf("approved" to true, "points" to points))
        if (pending != null) {
            b.update(done(code).document("${pending.date}_${pending.taskId}"), mapOf("ok" to true, "points" to points.toDouble()))
            b.update(members(code).document(memberId), "total", FieldValue.increment(points.toDouble()))
        }
        b.commit().await()
    }

    suspend fun deleteTask(code: String, id: String) {
        tasks(code).document(id).delete().await()
    }

    /** يمسح كل المهام ويرجّع الجدول الأصلي من Seed. */
    suspend fun resetTasks(code: String) {
        val old = tasks(code).get().await().documents
        val b = db.batch()
        for (d in old) b.delete(d.reference)
        for (t in Seed.tasks()) b.set(tasks(code).document(), taskMap(t))
        b.commit().await()
    }

    suspend fun addMember(code: String, name: String, age: String) {
        members(code).document().set(
            mapOf("name" to name, "age" to age, "character" to -1, "leader" to false, "total" to 0L, "pinHash" to ""),
        ).await()
    }

    suspend fun setAge(code: String, memberId: String, age: String) {
        members(code).document(memberId).update("age", age).await()
    }

    suspend fun deleteMember(code: String, memberId: String) {
        members(code).document(memberId).delete().await()
    }

    suspend fun saveSettings(code: String, grace: Int, penalties: Map<String, String>) {
        house(code).update(mapOf("grace" to grace, "penalties" to penalties)).await()
    }
}
