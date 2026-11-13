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
                "penalties" to DEFAULT_PENALTIES, "owner" to (auth.uid ?: ""),
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
                trySend(House(s.getString("name") ?: "", (s.getLong("grace") ?: 30L).toInt(), s.getString("startDate") ?: "", pen))
            }
        }
        awaitClose { reg.remove() }
    }

    fun membersFlow(code: String): Flow<List<Member>> = members(code).asFlow {
        Member(
            it.id, it.getString("name") ?: "", it.getString("age") ?: "adult",
            (it.getLong("character") ?: -1L).toInt(), it.getString("pinHash") ?: "",
            it.getBoolean("leader") ?: false, it.getLong("total") ?: 0L,
        )
    }

    fun tasksFlow(code: String): Flow<List<Task>> = tasks(code).asFlow {
        Task(
            it.id, it.getString("title") ?: "", it.getString("memberId") ?: "",
            (it.getLong("day") ?: 0L).toInt(), (it.getLong("start") ?: 0L).toInt(),
            (it.getLong("dur") ?: 15L).toInt(), (it.getLong("points") ?: 5L).toInt(),
        )
    }

    /** كل الإنجازات من تاريخ معيّن (yyyy-MM-dd) وطالع. */
    fun doneFlow(code: String, since: String): Flow<List<Done>> =
        done(code).whereGreaterThanOrEqualTo("date", since).asFlow {
            Done(it.getString("taskId") ?: "", it.getString("memberId") ?: "", it.getString("date") ?: "", (it.getLong("points") ?: 0L).toInt())
        }

    // ---------- كتابة ----------

    suspend fun complete(code: String, t: Task, date: String, points: Int) {
        val b = db.batch()
        b.set(done(code).document("${date}_${t.id}"), mapOf("taskId" to t.id, "memberId" to t.memberId, "date" to date, "points" to points))
        b.update(members(code).document(t.memberId), "total", FieldValue.increment(points.toLong()))
        b.commit().await()
    }

    suspend fun undo(code: String, t: Task, date: String, points: Int) {
        val b = db.batch()
        b.delete(done(code).document("${date}_${t.id}"))
        b.update(members(code).document(t.memberId), "total", FieldValue.increment(-points.toLong()))
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
    )

    suspend fun saveTask(code: String, t: Task) {
        val ref = if (t.id.isEmpty()) tasks(code).document() else tasks(code).document(t.id)
        ref.set(taskMap(t)).await()
    }

    suspend fun deleteTask(code: String, id: String) {
        tasks(code).document(id).delete().await()
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
