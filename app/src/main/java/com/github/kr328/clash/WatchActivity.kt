package com.github.kr328.clash

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberScalingLazyListState
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.log.SystemLogcat
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

// Design tokens: OLED-black base, brand blue for actions only, green strictly
// for the connected state, red for stop/error. All screens share these.
private object W {
    val blue = Color(0xFF4E7CFF)
    val blueDim = Color(0xFF2A3A66)
    val green = Color(0xFF2ECC71)
    val amber = Color(0xFFF5B93E)
    val red = Color(0xFFFF5D5D)
    val redDim = Color(0xFF38202A)
    val text = Color(0xFFE8EDF7)
    val muted = Color(0xFF9AA6BF)
    val slate = Color(0xFF76829B)
    val chip = Color(0xFF1B2236)

    fun delayColor(ms: Int): Color = when {
        ms <= 0 -> red
        ms < 300 -> green
        ms < 800 -> amber
        else -> red
    }
}

// Watch-first UI. Opens as the launcher on Wear OS devices (see WatchLauncher
// activity-alias in AndroidManifest.xml); on phones the original UI stays default.
class WatchActivity : ComponentActivity(), Broadcasts.Observer {

    private val scope = MainScope()

    private var running by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var profileName by mutableStateOf<String?>(null)
    private var profiles by mutableStateOf<List<Profile>>(emptyList())
    private var trafficTotal by mutableStateOf(0L)
    private var message by mutableStateOf<String?>(null)
    private var screen by mutableStateOf<Screen>(Screen.Main)
    private var groups by mutableStateOf<List<String>>(emptyList())
    private var group by mutableStateOf<ProxyGroup?>(null)
    private var crashLog by mutableStateOf<String?>(null)

    private var trafficJob: Job? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startClashChecked()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180))) togetherWith
                        fadeOut(tween(120))
                },
                label = "screen"
            ) { target ->
                when (target) {
                    Screen.Main -> MainScreen()
                    Screen.Profiles -> ProfilesScreen()
                    Screen.Groups -> GroupsScreen()
                    is Screen.Group -> GroupScreen(target.name)
                    Screen.CrashLog -> CrashLogScreen()
                }
            }
        }

        // Auto-show crash log once per new crash, so the user only has to
        // reopen the app and photograph the screen after a crash.
        scope.launch(Dispatchers.IO) {
            val log = SystemLogcat.dumpCrash()

            if (log.contains("FATAL") || log.contains("panic")) {
                val prefs = getSharedPreferences("watch_diag", MODE_PRIVATE)
                val fingerprint = log.hashCode().toString()

                if (prefs.getString("last_crash", null) != fingerprint) {
                    prefs.edit().putString("last_crash", fingerprint).apply()

                    crashLog = log
                    screen = Screen.CrashLog
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        Remote.broadcasts.addObserver(this)

        refresh()
    }

    override fun onStop() {
        super.onStop()

        Remote.broadcasts.removeObserver(this)

        trafficJob?.cancel()
        trafficJob = null
    }

    override fun onDestroy() {
        scope.cancel()

        super.onDestroy()
    }

    // Broadcasts.Observer

    override fun onStarted() = refresh()

    override fun onStopped(cause: String?) {
        refresh()

        if (cause != null) {
            message = cause
        }
    }

    override fun onServiceRecreated() = refresh()

    override fun onProfileLoaded() = refresh()

    override fun onProfileChanged() = refresh()

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        busy = false
        message = getString(R.string.watch_update_done)
        refresh()
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        busy = false
        message = reason ?: getString(R.string.watch_update_failed)
        refresh()
    }

    // Actions

    private fun refresh() {
        running = Remote.broadcasts.clashRunning
        profileName = StatusClient(this).currentProfile()

        scope.launch(Dispatchers.IO) {
            runCatching {
                profiles = withProfile { queryAll() }
            }
        }

        if (running) {
            startTrafficLoop()
        } else {
            trafficJob?.cancel()
            trafficJob = null
            trafficTotal = 0
        }
    }

    private fun startTrafficLoop() {
        if (trafficJob?.isActive == true) {
            return
        }

        trafficJob = scope.launch {
            while (true) {
                delay(2000)

                runCatching {
                    trafficTotal = withClash { queryTrafficTotal() }
                }
            }
        }
    }

    private fun startClashChecked() {
        try {
            val vpnRequest = startClashService()

            if (vpnRequest != null) {
                runCatching {
                    vpnPermissionLauncher.launch(vpnRequest)
                }.onFailure {
                    message = "VPN授权: ${it.message}"
                }
            }
        } catch (e: Throwable) {
            message = "启动失败: ${e.message}"
        }
    }

    private fun toggle() {
        if (running) {
            stopClashService()
        } else {
            startClashChecked()
        }
    }

    private fun selectProfile(p: Profile) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                withProfile { setActive(p) }
            }.onSuccess {
                message = getString(R.string.watch_profile_selected, p.name)
            }.onFailure {
                message = it.message
            }

            refresh()
        }
    }

    private fun updateActiveProfile() {
        val active = profiles.firstOrNull { it.active } ?: return

        busy = true
        message = getString(R.string.watch_updating)

        scope.launch(Dispatchers.IO) {
            runCatching {
                withProfile { update(active.uuid) }
            }.onFailure {
                busy = false
                message = it.message
            }
        }
    }

    private fun loadGroups() {
        busy = true

        scope.launch(Dispatchers.IO) {
            runCatching {
                groups = withClash { queryProxyGroupNames(false) }
            }.onFailure {
                message = it.message
            }

            busy = false
        }
    }

    private fun loadGroup(name: String) {
        busy = true

        scope.launch(Dispatchers.IO) {
            runCatching {
                group = withClash { queryProxyGroup(name, ProxySort.Default) }
            }.onFailure {
                message = it.message
            }

            busy = false
        }
    }

    private fun patchSelector(groupName: String, proxyName: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                withClash { patchSelector(groupName, proxyName) }
            }

            loadGroup(groupName)
        }
    }

    // Screens

    private sealed interface Screen {
        data object Main : Screen
        data object Profiles : Screen
        data object Groups : Screen
        data class Group(val name: String) : Screen
        data object CrashLog : Screen
    }

    @Composable
    private fun MainScreen() {
        BackHandler {
            finish()
        }

        message?.let { msg ->
            androidx.compose.runtime.LaunchedEffect(msg) {
                delay(4000)
                message = null
            }
        }

        ScreenContent {
            item { StatusHero() }
            item { ToggleButton() }
            item {
                MenuChip(
                    label = stringResource(R.string.watch_menu_profiles),
                    onClick = { screen = Screen.Profiles }
                )
            }
            item {
                MenuChip(
                    label = stringResource(R.string.watch_menu_proxy),
                    enabled = running,
                    hint = if (!running) stringResource(R.string.watch_proxy_need_running) else null,
                    onClick = {
                        screen = Screen.Groups
                        loadGroups()
                    }
                )
            }
            item {
                MenuChip(
                    label = stringResource(R.string.watch_menu_profiles_update),
                    enabled = !busy && profiles.any { it.active && it.type == Profile.Type.Url },
                    onClick = { updateActiveProfile() }
                )
            }
            item {
                MenuChip(
                    label = stringResource(R.string.watch_menu_full_ui),
                    onClick = { startActivity(MainActivity::class.intent) }
                )
            }
            item {
                MenuChip(
                    label = stringResource(R.string.watch_menu_crash_log),
                    onClick = { screen = Screen.CrashLog }
                )
            }
            if (busy) {
                item { CircularProgressIndicator(strokeWidth = 3.dp) }
            }
            message?.let { msg ->
                item {
                    Text(
                        text = msg,
                        color = W.red,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption1
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusHero() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = profileName
                    ?: stringResource(if (profiles.isEmpty()) R.string.watch_no_profile else R.string.watch_ready),
                color = W.muted,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(running = running)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (running) R.string.watch_status_running else R.string.watch_status_stopped
                    ),
                    color = if (running) W.green else W.slate,
                    style = MaterialTheme.typography.title1,
                    fontWeight = FontWeight.Bold
                )
            }
            AnimatedVisibility(visible = running && trafficTotal > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatBytes(trafficTotal),
                        color = W.text,
                        style = MaterialTheme.typography.title3.copy(fontFeatureSettings = "tnum")
                    )
                    Text(
                        text = stringResource(R.string.watch_traffic_used),
                        color = W.muted,
                        style = MaterialTheme.typography.caption2
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusDot(running: Boolean) {
        if (running) {
            val transition = rememberInfiniteTransition(label = "pulse")
            val alpha by transition.animateFloat(
                initialValue = 0.15f,
                targetValue = 0.9f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )
            val scale by transition.animateFloat(
                initialValue = 0.8f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )

            Box(
                Modifier
                    .size(10.dp)
                    .scale(scale)
                    .alpha(alpha)
                    .background(W.green, CircleShape)
            )
        } else {
            Box(
                Modifier
                    .size(10.dp)
                    .alpha(0.45f)
                    .background(W.slate, CircleShape)
            )
        }
    }

    @Composable
    private fun ToggleButton() {
        if (running) {
            Button(
                onClick = { toggle() },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = W.redDim,
                    contentColor = W.red
                )
            ) {
                Text(stringResource(R.string.watch_toggle_stop))
            }
        } else {
            Button(
                onClick = { toggle() },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = W.blue,
                    contentColor = Color.White
                )
            ) {
                Text(stringResource(R.string.watch_toggle_start))
            }
        }
    }

    @Composable
    private fun MenuChip(
        label: String,
        enabled: Boolean = true,
        hint: String? = null,
        onClick: () -> Unit
    ) {
        Chip(
            onClick = onClick,
            enabled = enabled,
            colors = ChipDefaults.secondaryChipColors(
                backgroundColor = W.chip,
                contentColor = W.text,
                secondaryContentColor = W.muted
            ),
            label = { Text(label) },
            secondaryLabel = hint?.let { h -> { Text(h) } }
        )
    }

    @Composable
    private fun ScreenHeader(title: String) {
        Text(
            text = title,
            color = W.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.caption1
        )
    }

    @Composable
    private fun ProfilesScreen() {
        BackHandler {
            screen = Screen.Main
        }

        ScreenContent {
            item { ScreenHeader(stringResource(R.string.watch_menu_profiles)) }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.watch_profiles_empty),
                        textAlign = TextAlign.Center,
                        color = W.muted,
                        style = MaterialTheme.typography.caption1
                    )
                }
            }
            items(profiles.size) { index ->
                val p = profiles[index]

                Chip(
                    onClick = { selectProfile(p) },
                    colors = if (p.active)
                        ChipDefaults.primaryChipColors(
                            backgroundColor = W.blue,
                            contentColor = Color.White,
                            secondaryContentColor = Color(0xFFD6E2FF)
                        )
                    else
                        ChipDefaults.secondaryChipColors(
                            backgroundColor = W.chip,
                            contentColor = W.text,
                            secondaryContentColor = W.muted
                        ),
                    label = { Text(p.name) },
                    secondaryLabel = {
                        if (p.active) {
                            Text(stringResource(R.string.watch_profile_active))
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun GroupsScreen() {
        BackHandler {
            screen = Screen.Main
        }

        ScreenContent {
            item { ScreenHeader(stringResource(R.string.watch_menu_proxy)) }
            items(groups.size) { index ->
                val name = groups[index]

                Chip(
                    onClick = {
                        screen = Screen.Group(name)
                        loadGroup(name)
                    },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = W.chip,
                        contentColor = W.text,
                        secondaryContentColor = W.muted
                    ),
                    label = { Text(name) }
                )
            }
        }
    }

    @Composable
    private fun GroupScreen(name: String) {
        BackHandler {
            screen = Screen.Groups
        }

        ScreenContent {
            item { ScreenHeader(name) }
            group?.let { g ->
                items(g.proxies.size) { index ->
                    val proxy = g.proxies[index]
                    val selected = proxy.name == g.now

                    Chip(
                        onClick = { patchSelector(name, proxy.name) },
                        colors = if (selected)
                            ChipDefaults.primaryChipColors(
                                backgroundColor = W.blue,
                                contentColor = Color.White,
                                secondaryContentColor = Color(0xFFD6E2FF)
                            )
                        else
                            ChipDefaults.secondaryChipColors(
                                backgroundColor = W.chip,
                                contentColor = W.text,
                                secondaryContentColor = W.muted
                            ),
                        label = { Text(if (selected) "✓ ${proxy.name}" else proxy.name) },
                        secondaryLabel = {
                            // 65535 is mihomo's health-check timeout sentinel
                            if (proxy.delay <= 0) {
                                if (!selected) Text(text = "--", color = W.slate)
                            } else if (proxy.delay >= 65530) {
                                Text(
                                    text = stringResource(R.string.watch_proxy_timeout),
                                    color = if (selected) Color(0xFFD6E2FF) else W.red
                                )
                            } else {
                                Text(
                                    text = "${proxy.delay}ms",
                                    color = if (selected) Color(0xFFD6E2FF) else W.delayColor(proxy.delay)
                                )
                            }
                        }
                    )
                }
            }
            if (busy) {
                item { CircularProgressIndicator(strokeWidth = 3.dp) }
            }
        }
    }

    @Composable
    private fun CrashLogScreen() {
        BackHandler {
            screen = Screen.Main
        }

        val log = crashLog

        ScreenContent {
            item { ScreenHeader(stringResource(R.string.watch_menu_crash_log)) }
            item {
                Chip(
                    onClick = {
                        crashLog = "..."
                        scope.launch(Dispatchers.IO) {
                            crashLog = SystemLogcat.dumpCrash().ifBlank {
                                "(empty)"
                            }
                        }
                    },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = W.chip,
                        contentColor = W.text,
                        secondaryContentColor = W.muted
                    ),
                    label = { Text("刷新 / Refresh") }
                )
            }
            item {
                Text(
                    text = log ?: stringResource(R.string.watch_crash_log_hint),
                    color = W.muted,
                    style = MaterialTheme.typography.caption2
                )
            }
        }
    }

    // androidx.wear.compose.material.LazyColumn with defaults adjusted for round screens
    @Composable
    private fun ScreenContent(content: androidx.wear.compose.material.ScalingLazyListScope.() -> Unit) {
        val listState = rememberScalingLazyListState()

        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024f)
        if (bytes < 1024 * 1024 * 1024) return "%.1f MB".format(bytes / 1024f / 1024f)
        return "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
    }
}
