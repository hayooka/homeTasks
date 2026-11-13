package com.hayooka.hometasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** حالة عقوبة شخص الآن. blockUntil بالدقايق من أول اليوم، و0 يعني ما في قطع. */
data class PenaltyState(val missed: Int, val overdue: List<Task>, val blockUntil: Int, val lock: Boolean) {
    val block get() = blockUntil > 0
    companion object { val NONE = PenaltyState(0, emptyList(), 0, false) }
}

object Penalty {
    /**
     * المهمة تعتبر فايتة إذا عدّى آخر موعد + المهلة وما انعملت قبله.
     * الفايتة الأولى = ساعة بدون إنترنت، الثانية = ساعتين، الثالثة = 3 ساعات،
     * والرابعة = قفل الجوال لحد ما تنعمل كل المهام الفايتة. كل شي يتصفّر آخر اليوم.
     */
    fun forMember(house: House, m: Member, tasks: List<Task>, done: List<Done>, date: LocalDate, nowMin: Int): PenaltyState {
        if (!house.enforce) return PenaltyState.NONE
        val d = date.toString()
        if (house.enforceDate.isEmpty() || d < house.enforceDate) return PenaltyState.NONE
        // يوم التفعيل: ما نحاسب على مواعيد عدّت قبل لحظة التفعيل
        val floor = if (d == house.enforceDate) house.enforceMin else -1
        val mineDone = done.filter { it.date == d && it.memberId == m.id }.associateBy { it.taskId }
        val missed = tasks.filter { it.memberId == m.id && it.day == Rules.dayIndex(date) && !it.personal }
            .map { it to (it.start + it.dur + house.grace) }
            .filter { (t, deadline) ->
                val dn = mineDone[t.id]
                deadline > floor && nowMin > deadline && (dn == null || dn.at > deadline)
            }
            .sortedBy { it.second }
        // تبقى "فايتة" لحد ما تنعمل والقائد يوافق عليها
        val overdue = missed.filter { mineDone[it.first.id]?.ok != true }.map { it.first }
        var until = 0
        missed.take(3).forEachIndexed { i, (_, deadline) ->
            val end = minOf(deadline + (i + 1) * 60, 24 * 60)
            if (nowMin < end) until = maxOf(until, end)
        }
        return PenaltyState(missed.size, overdue, until, missed.size >= 4 && overdue.isNotEmpty())
    }

    /** هل العقوبة تتنفذ فعلياً على جوال هذا الشخص؟ القائد والصغار (عقوبتهم التلفزيون) لا. */
    fun enforcedOnPhone(m: Member) = !m.leader && m.age != "u10"

    fun describe(st: PenaltyState, m: Member): String = when {
        // سلّم أخف للصغار لأن مهامهم اليومية كثيرة: أول مهمتين تنبيه، وبعدها ساعة عن كل مهمتين
        m.age == "u10" -> when {
            st.missed >= 9 -> "ما في يوتيوب اليوم."
            st.missed >= 7 -> "راحت عليك 3 ساعات يوتيوب."
            st.missed >= 5 -> "راحت عليك ساعتين يوتيوب."
            st.missed >= 3 -> "راحت عليك ساعة يوتيوب."
            else -> "تنبيه بس، لسا ما في عقوبة. مهمة فايتة ثالثة تنقّص ساعة يوتيوب."
        }
        st.lock -> "الجوال مقفول لحد ما تخلص كل المهام الفايتة."
        st.block -> "الإنترنت مقطوع عن الجوال لحد ${Rules.fmt(st.blockUntil)}."
        else -> "خلصت مدة القطع. أي مهمة فايتة ثانية تزيد العقوبة."
    }
}

/** حالة مشتركة بين خدمة الحراسة وشاشة القفل. */
object Guard {
    val lock = mutableStateOf(false)
    val overdue = mutableStateOf<List<Task>>(emptyList())
    /** مهام فايتة أشّر عليها العضو وتنتظر موافقة القائد. */
    val pending = mutableStateOf<Set<String>>(emptySet())
    val member = mutableStateOf<Member?>(null)
    @Volatile var code: String? = null
    @Volatile var grace: Int = 30
    /** وقت مسموح فيه الخروج من شاشة القفل لإجراء اتصال. */
    @Volatile var passUntil: Long = 0L

    fun vpnReady(c: Context) = VpnService.prepare(c) == null
    fun overlayReady(c: Context) = Settings.canDrawOverlays(c)
    private fun admin(c: Context) = android.content.ComponentName(c, AdminReceiver::class.java)
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    /** لما تكون مفعّلة، النظام ما يسمح بحذف التطبيق. */
    fun adminReady(c: Context) = dpm(c).isAdminActive(admin(c))
    fun removeAdmin(c: Context) {
        try { dpm(c).removeActiveAdmin(admin(c)) } catch (e: Exception) { }
    }
    fun start(c: Context) {
        try { ContextCompat.startForegroundService(c, Intent(c, EnforcerService::class.java)) } catch (e: Exception) { }
    }
    fun stop(c: Context) {
        try { c.stopService(Intent(c, EnforcerService::class.java)) } catch (e: Exception) { }
    }
}

/** خدمة شغالة بالخلفية: تتابع المهام وتنفذ قطع الإنترنت والقفل وقت العقوبة. */
class EnforcerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = Repo()
    private var house: House? = null
    private var me: Member? = null
    private var tasks: List<Task>? = null
    private var done: List<Done>? = null
    private var doneJob: Job? = null
    private var doneDate = ""
    private var lastBeat = 0L
    private var snapDate = ""
    /** المهام اللي انرسل تذكيرها اليوم، عشان ما يتكرر. */
    private val reminded = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        foreground()
        val prefs = getSharedPreferences("session", Context.MODE_PRIVATE)
        val code = prefs.getString("code", null)
        val meId = prefs.getString("me", null)
        if (code == null || meId == null) { stopSelf(); return }
        Guard.code = code
        scope.launch {
            try { repo.signIn() } catch (e: Exception) { }
            launch { repo.houseFlow(code).collect { house = it } }
            launch { repo.membersFlow(code).collect { list -> me = list.firstOrNull { it.id == meId } } }
            launch { repo.tasksFlow(code).collect { tasks = it } }
            while (isActive) {
                try { tick(code, meId) } catch (e: Exception) { }
                delay(2000)
            }
        }
    }

    private fun foreground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("guard", "متابعة المهام", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("remind", "تذكير المهام", NotificationManager.IMPORTANCE_HIGH))
        val n: Notification = Notification.Builder(this, "guard")
            .setContentTitle("مهام البيت")
            .setContentText("متابعة المهام شغالة")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    private fun inCall(): Boolean {
        val mode = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    private fun tick(code: String, meId: String) {
        val now = LocalDateTime.now()
        val date = now.toLocalDate()
        if (date.toString() != doneDate) {
            doneDate = date.toString()
            doneJob?.cancel()
            done = null
            doneJob = scope.launch { repo.doneFlow(code, doneDate).collect { done = it } }
        }
        val h = house
        val m = me
        val t = tasks
        val d = done
        // لو ما حد فتح التطبيق اليوم، الخدمة تحفظ قائمة مهام اليوم كدليل
        if (t != null && snapDate != doneDate) {
            snapDate = doneDate
            val todays = t.filter { it.day == Rules.dayIndex(date) }
            if (todays.isNotEmpty()) scope.launch { try { repo.snapshotDay(code, doneDate, todays, false) } catch (e: Exception) { } }
        }
        // ما ننفذ شي قبل ما توصل كل البيانات، عشان ما ينقفل الجوال بالغلط
        val st = if (h != null && m != null && t != null && d != null && Penalty.enforcedOnPhone(m)) {
            Penalty.forMember(h, m, t, d, date, now.hour * 60 + now.minute)
        } else PenaltyState.NONE
        if (h != null && m != null && t != null && d != null) remind(h, m, t, d, date, now.hour * 60 + now.minute)

        if (st.block && Guard.vpnReady(this)) BlockVpnService.start(this) else if (BlockVpnService.running) BlockVpnService.stop(this)

        Guard.member.value = m
        Guard.grace = h?.grace ?: 30
        Guard.overdue.value = st.overdue
        Guard.pending.value = if (d != null && m != null) d.filter { !it.ok && it.memberId == m.id && it.date == doneDate }.map { it.taskId }.toSet() else emptySet()
        Guard.lock.value = st.lock
        if (st.lock && !LockActivity.visible && System.currentTimeMillis() > Guard.passUntil && !inCall() && Guard.overlayReady(this)) {
            startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }

        val ms = System.currentTimeMillis()
        if (m != null && ms - lastBeat > 5 * 60_000) {
            lastBeat = ms
            val ok = Guard.vpnReady(this) && Guard.overlayReady(this) && Guard.adminReady(this)
            scope.launch { try { repo.beat(code, meId, ok) } catch (e: Exception) { } }
        }
    }

    /** إشعار قبل 10 دقايق من الوقت اللي تبدأ فيه نقاط المهمة تنقص (آخر موعد + المهلة). مرة وحدة لكل مهمة باليوم. */
    private fun remind(h: House, m: Member, tasks: List<Task>, done: List<Done>, date: LocalDate, nowMin: Int) {
        val doneIds = done.filter { it.date == doneDate && it.memberId == m.id }.map { it.taskId }.toSet()
        for (task in tasks) {
            if (task.memberId != m.id || task.day != Rules.dayIndex(date) || task.allDay || task.id in doneIds) continue
            val limit = task.start + task.dur + h.grace
            if (nowMin < limit - 10 || nowMin >= limit || !reminded.add(doneDate + task.id)) continue
            val open = android.app.PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(this, "remind")
                .setContentTitle(task.title)
                .setContentText("باقي ${limit - nowMin} دقايق وتبدأ نقاطها تنقص (${task.points} نقطة)")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(task.id.hashCode(), n)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (BlockVpnService.running) BlockVpnService.stop(this)
        Guard.lock.value = false
        super.onDestroy()
    }
}

/** VPN محلي ما يوصل لأي سيرفر: كل اتصالات الجوال تدخله وتوقف عنده. تطبيقنا مستثنى عشان يقدر يحدّث المهام. */
class BlockVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { close(); stopSelf(); return START_NOT_STICKY }
        if (tun == null) {
            try {
                val b = Builder().setSession("مهام البيت")
                    .addAddress("10.111.0.2", 32).addRoute("0.0.0.0", 0)
                    .addAddress("fd00:1:fd00:1::2", 128).addRoute("::", 0)
                b.addDisallowedApplication(packageName)
                tun = b.establish()
            } catch (e: Exception) { tun = null }
        }
        running = tun != null
        if (tun == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun close() {
        try { tun?.close() } catch (e: Exception) { }
        tun = null
        running = false
    }

    override fun onRevoke() { close(); super.onRevoke() }
    override fun onDestroy() { close(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "stop"
        @Volatile var running = false
        fun start(c: Context) {
            if (running) return
            try { c.startService(Intent(c, BlockVpnService::class.java)) } catch (e: Exception) { }
        }
        fun stop(c: Context) {
            try { c.startService(Intent(c, BlockVpnService::class.java).setAction(ACTION_STOP)) } catch (e: Exception) { }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Guard.start(context)
    }
}

/** حماية الحذف: طول ما هي مفعّلة النظام يرفض حذف التطبيق. إيقافها من إعدادات الجوال يبلّغ القائد فوراً. */
class AdminReceiver : android.app.admin.DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "إيقاف هذي الحماية يوصل تنبيه للقائد فوراً."

    override fun onDisabled(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        val code = prefs.getString("code", null) ?: return
        val me = prefs.getString("me", null) ?: return
        CoroutineScope(Dispatchers.IO).launch { try { Repo().beat(code, me, false) } catch (e: Exception) { } }
    }
}
