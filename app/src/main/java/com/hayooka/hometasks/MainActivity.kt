@file:OptIn(ExperimentalMaterial3Api::class)

package com.hayooka.hometasks

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LightMode.value = getSharedPreferences("session", Context.MODE_PRIVATE).getBoolean("light", true)
        setContent {
            // أيقونات شريط الحالة تتبع الوضع عشان تبقى واضحة
            val light = LightMode.value
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
            App()
        }
    }
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("session", Context.MODE_PRIVATE) }
    val repo = remember { Repo() }
    var code by remember { mutableStateOf(prefs.getString("code", null)) }
    var me by remember { mutableStateOf(prefs.getString("me", null)) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        failed = false
        try { repo.signIn(); ready = true } catch (e: Exception) { failed = true }
    }
    fun save(c: String?, m: String?) {
        code = c; me = m
        prefs.edit().putString("code", c).putString("me", m).apply()
        if (c == null || m == null) Guard.stop(ctx)
    }

    GameTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val c = code
                val m = me
                when {
                    failed -> Centered {
                        Text("ما قدرنا نتصل. تأكدي من الإنترنت.", textAlign = TextAlign.Center)
                        Button(onClick = { attempt++ }) { Text("حاولي مرة ثانية") }
                    }
                    !ready -> Centered { CircularProgressIndicator() }
                    c == null -> Welcome(repo, onCreated = { newCode -> save(newCode, "F") }, onJoined = { joined -> save(joined, null) })
                    m == null -> PickMember(repo, c, onPicked = { id -> save(c, id) }, onBack = { save(null, null) })
                    else -> Home(repo, c, m, onLogout = { save(null, null) })
                }
            }
        }
    }
}

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally, content = content,
    )
}

@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
}

/** صف خيارات يتمرر بالعرض. */
@Composable
fun Chips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((key, label) in options) {
            FilterChip(selected = key == selected, onClick = { onSelect(key) }, label = { Text(label) })
        }
    }
}

@Composable
fun Welcome(repo: Repo, onCreated: (String) -> Unit, onJoined: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var houseName by remember { mutableStateOf("بيتنا") }
    var pin by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in 0..5) CharacterView(k, 1, Modifier.size(48.dp))
        }
        Text("مهام البيت", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("كل واحد يعرف مهامه، ويجمع نقاط لما يخلصها بوقتها.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        when (mode) {
            "" -> {
                Button(onClick = { mode = "join" }, modifier = Modifier.fillMaxWidth()) { Text("دخول بكود البيت") }
                OutlinedButton(onClick = { mode = "create" }, modifier = Modifier.fillMaxWidth()) { Text("إنشاء بيت جديد (للقائد)") }
            }
            "join" -> {
                OutlinedTextField(
                    value = joinCode, onValueChange = { joinCode = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) },
                    label = { Text("كود البيت") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !busy && joinCode.length == 6, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                if (repo.houseExists(joinCode)) onJoined(joinCode) else ctx.toast("ما لقينا بيت بهذا الكود")
                            } catch (e: Exception) { ctx.toast("صار خطأ بالاتصال") }
                            busy = false
                        }
                    },
                ) { Text("دخول") }
                TextButton(onClick = { mode = "" }) { Text("رجوع") }
            }
            else -> {
                OutlinedTextField(houseName, { houseName = it.take(30) }, label = { Text("اسم البيت") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PinField(pin, { pin = it }, "رقمك السري (4 أرقام)")
                Text("ينشأ البيت جاهز بأعضاء العائلة وجدول الأسبوع، وأنتِ اللقائد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    enabled = !busy && pin.length == 4 && houseName.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try { onCreated(repo.createHouse(houseName.trim(), pin)) } catch (e: Exception) { ctx.toast("ما قدرنا ننشئ البيت") }
                            busy = false
                        }
                    },
                ) { Text("إنشاء البيت") }
                TextButton(onClick = { mode = "" }) { Text("رجوع") }
            }
        }
    }
}

@Composable
fun PickMember(repo: Repo, code: String, onPicked: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val members by remember(code) { repo.membersFlow(code) }.collectAsState(initial = null)
    var chosen by remember { mutableStateOf<Member?>(null) }
    var pin by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("مين أنت؟", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        val list = members
        val pick = chosen
        when {
            list == null -> CircularProgressIndicator()
            pick == null -> {
                for (m in list.sortedBy { it.name }) {
                    Card(Modifier.fillMaxWidth().clickable { chosen = m; pin = "" }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (m.character >= 0) CharacterView(m.character, Rules.level(m.total), Modifier.size(40.dp))
                            Text(m.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                TextButton(onClick = onBack) { Text("تغيير كود البيت") }
            }
            else -> {
                val first = pick.pinHash.isEmpty()
                Text(if (first) "أهلاً ${pick.name}! اختار رقم سري من 4 أرقام وتذكّره." else "أهلاً ${pick.name}! اكتب رقمك السري.")
                PinField(pin, { pin = it }, "الرقم السري")
                Button(
                    enabled = pin.length == 4, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val hash = repo.pinHash(code, pick.id, pin)
                        if (first) {
                            scope.launch {
                                try { repo.setPin(code, pick.id, hash); onPicked(pick.id) } catch (e: Exception) { ctx.toast("صار خطأ، حاول مرة ثانية") }
                            }
                        } else if (hash == pick.pinHash) onPicked(pick.id) else ctx.toast("الرقم السري غلط")
                    },
                ) { Text("دخول") }
                TextButton(onClick = { chosen = null }) { Text("رجوع") }
            }
        }
    }
}

@Composable
fun ChooseCharacter(onChoose: (Int) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("اختار شخصيتك", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("تكبر وتتطور معك كل ما جمعت نقاط.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (row in listOf(listOf(0, 1, 2), listOf(3, 4, 5))) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (k in row) {
                    Card(Modifier.weight(1f).clickable { onChoose(k) }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CharacterView(k, 1, Modifier.size(72.dp))
                            Text(CHARACTER_NAMES[k], fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
