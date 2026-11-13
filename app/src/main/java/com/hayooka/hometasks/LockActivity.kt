package com.hayooka.hometasks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** شاشة القفل وقت العقوبة الثقيلة: الشخصية، عدّاد لآخر اليوم، والمهام الفايتة. */
class LockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LockScreen(onClose = { finish() }) }
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    companion object { @Volatile var visible = false }
}

@Composable
private fun LockScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repo() }
    val locked by Guard.lock
    val overdue by Guard.overdue
    val pending by Guard.pending
    val member by Guard.member
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = LocalDateTime.now() } }
    LaunchedEffect(locked) { if (!locked) onClose() }
    BackHandler(enabled = true) { }

    val left = 24 * 3600 - (now.hour * 3600 + now.minute * 60 + now.second)
    val clock = "%02d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60)
    val m = member

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF5CC4BF), onPrimary = Color(0xFF05302E))) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF11161C)).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (m != null && m.character >= 0) CharacterView(m.character, Rules.level(m.total), Modifier.size(180.dp))
                Text("الجوال في إجازة يا ${m?.name ?: ""}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                Text("يرجع لك أول ما تخلص المهام الفايتة والليدر توافق عليها.", color = Color(0xFFB8C4D0), textAlign = TextAlign.Center)
                Text(clock, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5CC4BF))
                Text("وإذا ما خلصتها، يفتح لحاله آخر اليوم.", fontSize = 12.sp, color = Color(0xFFB8C4D0))

                for (t in overdue) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2129))) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, fontWeight = FontWeight.Medium, color = Color.White)
                                Text("كان آخر موعد ${Rules.fmt(t.start + t.dur)}", fontSize = 12.sp, color = Color(0xFFB8C4D0))
                            }
                            if (t.id in pending) {
                                Text("بانتظار موافقة الليدر", fontSize = 12.sp, color = Color(0xFF5CC4BF))
                            } else Button(onClick = {
                                val code = Guard.code ?: return@Button
                                val nowMin = now.hour * 60 + now.minute
                                scope.launch {
                                    try {
                                        repo.complete(code, t, now.toLocalDate().toString(), Rules.pointsNow(t, nowMin, Guard.grace).toDouble(), nowMin, false)
                                    } catch (e: Exception) { ctx.toast("ما انحفظ، جرب مرة ثانية") }
                                }
                            }) { Text("خلصتها") }
                        }
                    }
                }

                OutlinedButton(onClick = {
                    // ثلاث دقايق مسموح فيها الخروج لإجراء اتصال
                    Guard.passUntil = System.currentTimeMillis() + 3 * 60_000
                    try { ctx.startActivity(Intent(Intent.ACTION_DIAL)) } catch (e: Exception) { }
                }) { Text("اتصال") }
            }
        }
    }
}
