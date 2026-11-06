package com.jollydoddger.liningup

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Night = Color(0xFF12061F)
private val Plum = Color(0xFF2B0B33)
private val Wine = Color(0xFF3D0E2E)
private val Rose = Color(0xFFE0457B)
private val Violet = Color(0xFF8B5CF6)
private val Glass = Color(0x14FFFFFF)
private val Faint = Color(0x99FFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        // While the app is on screen, ask the relay every 20 seconds, so an
        // answer of theirs shows up while you are looking.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { Sync.run(this@MainActivity) }
                    delay(20_000)
                }
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Rose, secondary = Violet)) { Root() }
        }
    }

    override fun onStart() {
        super.onStart()
        Store.foreground = true
        Store.refresh() // the day may have rolled over at 2am while closed
    }

    override fun onStop() {
        Store.foreground = false
        super.onStop()
    }
}

private enum class Screen { MAIN, SETTINGS }

@Composable
private fun Root() {
    val s by Store.state.collectAsState()
    val snap = s ?: return
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, Plum, Wine)))
            .safeDrawingPadding(),
    ) {
        when {
            !snap.paired -> PairScreen(context)
            screen == Screen.SETTINGS -> {
                BackHandler { screen = Screen.MAIN }
                SettingsScreen(snap, context) { screen = Screen.MAIN }
            }
            else -> MainScreen(snap, context) { screen = Screen.SETTINGS }
        }
    }
}

private fun answer(context: Context, ok: Boolean?, what: Set<Choice>) {
    Store.answer(ok, what)
    Notify.clearPrompt(context)
    Sync.soon(context)
}

@Composable
private fun MainScreen(snap: Snapshot, context: Context, openSettings: () -> Unit) {
    val mine = snap.mine
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (snap.lastError != null) Text("📡", fontSize = 18.sp, modifier = Modifier.alpha(0.5f))
            Spacer(Modifier.weight(1f))
            Text(
                "⚙️", fontSize = 24.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = openSettings).padding(10.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        PartnerCard(snap.theirs, snap.match)

        Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) { Heart(snap.match) }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(Glass).padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                EmojiButton("✅", mine?.ok == true, 124.dp) {
                    answer(context, if (mine?.ok == true) null else true, emptySet())
                }
                EmojiButton("❌", mine?.ok == false, 124.dp) {
                    answer(context, if (mine?.ok == false) null else false, emptySet())
                }
            }
            AnimatedVisibility(
                visible = mine?.ok == true,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                // Each one switches on and off by itself: either, both, or neither.
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    val picked = mine?.what.orEmpty()
                    for (c in Choice.entries) {
                        EmojiButton(c.emoji, c in picked, 104.dp) {
                            answer(context, true, if (c in picked) picked - c else picked + c)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(snap.avatar, fontSize = 18.sp)
                Spacer(Modifier.size(6.dp))
                Text(
                    mine?.takeIf { it.ok != null }?.let { Days.clock(it.at) } ?: "—",
                    color = Faint, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                )
                if (snap.pending && mine != null) Text("  ⏳", fontSize = 12.sp, modifier = Modifier.alpha(0.6f))
            }
        }

        Spacer(Modifier.height(16.dp))
        MyNote(mine?.note.orEmpty(), context)
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Today's note, folded away behind 📝. It opens only when tapped, so a phone
 * left on the table shows the emoji and nothing written.
 */
@Composable
private fun MyNote(saved: String, context: Context) {
    var open by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Glass),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open; text = saved }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("📝", fontSize = 28.sp, modifier = Modifier.alpha(if (saved.isBlank()) 0.55f else 1f))
            if (saved.isNotBlank() && !open) Text("  •", color = Rose, fontSize = 20.sp)
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(Status.NOTE_MAX) },
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Rose),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SmallEmoji("🗑️") { text = "" }
                    Spacer(Modifier.weight(1f))
                    SmallEmoji("✖️") { text = saved; open = false }
                    SmallEmoji("✔️") {
                        Store.setNote(text)
                        Sync.soon(context)
                        open = false
                    }
                }
            }
        }
    }
}

@Composable
private fun PartnerCard(theirs: Status?, match: Boolean) {
    val glow by animateFloatAsState(if (match) 1f else 0f, tween(600), label = "glow")
    // Their note stays shut until tapped, and shuts again when they change it.
    var noteOpen by remember(theirs?.note) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(36.dp))
            .background(
                Brush.linearGradient(
                    listOf(Glass, Rose.copy(alpha = 0.10f + 0.25f * glow), Violet.copy(alpha = 0.08f + 0.2f * glow)),
                ),
            )
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f + 0.25f * glow)), RoundedCornerShape(36.dp))
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = theirs?.takeIf { it.shown },
            transitionSpec = { (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn()) togetherWith fadeOut() },
            label = "theirs",
        ) { t ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(theirs?.avatar?.ifBlank { null } ?: "💭", fontSize = 44.sp)
                    Spacer(Modifier.size(14.dp))
                    if (t == null || t.ok == null) Text("⏳", fontSize = 64.sp, modifier = Modifier.alpha(0.35f))
                    else Text(t.emojis, fontSize = 64.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    t?.takeIf { it.ok != null }?.let { Days.clock(it.at) } ?: "—",
                    color = Faint, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        val note = theirs?.note.orEmpty()
        if (note.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "📝", fontSize = 28.sp,
                modifier = Modifier.clip(CircleShape).clickable { noteOpen = !noteOpen }.padding(8.dp),
            )
            AnimatedVisibility(visible = noteOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(
                    note, color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Heart(match: Boolean) {
    AnimatedVisibility(visible = match, enter = scaleIn(spring(Spring.DampingRatioHighBouncy)) + fadeIn(), exit = scaleOut() + fadeOut()) {
        val pulse = rememberInfiniteTransition(label = "pulse")
        val k by pulse.animateFloat(
            1f, 1.15f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "k",
        )
        Text("💞", fontSize = 72.sp, modifier = Modifier.scale(k))
    }
}

@Composable
private fun EmojiButton(emoji: String, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val k by animateFloatAsState(if (selected) 1.08f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "k")
    val dim by animateFloatAsState(if (selected) 1f else 0.85f, label = "dim")
    Box(
        Modifier
            .size(size)
            .scale(k)
            .clip(CircleShape)
            .background(
                if (selected) Brush.radialGradient(listOf(Rose.copy(alpha = 0.85f), Violet.copy(alpha = 0.55f)))
                else Brush.radialGradient(listOf(Color(0x22FFFFFF), Color(0x0AFFFFFF))),
            )
            .border(BorderStroke(2.dp, Color.White.copy(alpha = if (selected) 0.45f else 0.10f)), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.46f).sp, modifier = Modifier.alpha(dim))
    }
}

private fun share(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "🌙 " + Pairing.pretty(code))
    context.startActivity(Intent.createChooser(send, null))
}

private fun clipboard(context: Context): String =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""

/** First run: start a pairing and send the code, or paste the one you were sent. */
@Composable
private fun PairScreen(context: Context) {
    var typed by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔗", fontSize = 72.sp)
        Spacer(Modifier.height(32.dp))
        Labelled("✨", "Start") {
            val code = Pairing.newCode()
            Store.setCode(code)
            share(context, code)
            Sync.soon(context)
        }
        Spacer(Modifier.height(36.dp))
        Text("—", color = Faint)
        Spacer(Modifier.height(36.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX", fontFamily = FontFamily.Monospace) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Rose),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Labelled("📋", "Paste") { typed = clipboard(context) }
            Labelled("💞", "Join", enabled = Pairing.isValid(typed)) {
                Store.setCode(typed)
                Sync.soon(context)
            }
        }
    }
}

@Composable
private fun Labelled(emoji: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.35f)) {
        Box(
            Modifier.size(96.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Rose.copy(alpha = 0.6f), Violet.copy(alpha = 0.35f))))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 44.sp) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Faint, fontSize = 13.sp)
    }
}

@Composable
private fun SettingsScreen(snap: Snapshot, context: Context, back: () -> Unit) {
    var confirmUnpair by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(snap.reminderHour) }
    var minute by remember { mutableIntStateOf(snap.reminderMinute) }
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 28.sp, color = Color.White,
                modifier = Modifier.clip(CircleShape).clickable(onClick = back).padding(8.dp))
        }

        Panel {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Store.AVATARS) { a ->
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(if (a == snap.avatar) Rose.copy(alpha = 0.5f) else Glass)
                            .clickable { Store.setAvatar(a); Sync.soon(context) },
                        contentAlignment = Alignment.Center,
                    ) { Text(a, fontSize = 30.sp) }
                }
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔔", fontSize = 30.sp)
                Spacer(Modifier.size(16.dp))
                Text(
                    "%02d:%02d".format(hour, minute),
                    fontSize = 30.sp, color = Color.White, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        TimePickerDialog(context, { _, h, m ->
                            hour = h; minute = m
                            Store.setReminder(snap.reminderOn, h, m)
                            Reminder.schedule(context)
                        }, hour, minute, true).show()
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = snap.reminderOn,
                    onCheckedChange = { Store.setReminder(it); Reminder.schedule(context) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Rose),
                )
            }
        }

        Panel {
            Text(Pairing.pretty(snap.code), color = Faint, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallEmoji("📤") { share(context, snap.code) }
                Spacer(Modifier.weight(1f))
                if (confirmUnpair) {
                    SmallEmoji("✖️") { confirmUnpair = false }
                    SmallEmoji("💔") { Store.unpair() }
                } else {
                    SmallEmoji("🔓") { confirmUnpair = true }
                }
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (snap.lastError == null) "📡" else "⚠️", fontSize = 22.sp)
                Spacer(Modifier.size(12.dp))
                Text(
                    if (snap.lastHeardAt > 0) Days.clock(snap.lastHeardAt) else "—",
                    color = Faint, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                SmallEmoji("🔄") { Sync.soon(context) }
            }
            snap.lastError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Faint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Glass).padding(20.dp)) { content() }
}

@Composable
private fun SmallEmoji(emoji: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Glass).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = 24.sp) }
}
