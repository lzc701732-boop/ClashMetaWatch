package com.github.kr328.clash

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
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
            MaterialTheme {
                when (val s = screen) {
                    Screen.Main -> MainScreen()
                    Screen.Profiles -> ProfilesScreen()
                    Screen.Groups -> GroupsScreen()
                    is Screen.Group -> GroupScreen(s.name)
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

        ScreenContent {
            item {
                Text(
                    text = profileName
                        ?: stringResource(if (profiles.isEmpty()) R.string.watch_no_profile else R.string.watch_ready),
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                Text(
                    text = stringResource(if (running) R.string.watch_status_running else R.string.watch_status_stopped),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.title2
                )
            }
            item {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = { toggle() },
                        colors = ButtonDefaults.primaryButtonColors()
                    ) {
                        Text(
                            text = stringResource(
                                if (running) R.string.watch_toggle_stop else R.string.watch_toggle_start
                            )
                        )
                    }

                    if (running && trafficTotal > 0) {
                        Text(
                            text = formatBytes(trafficTotal),
                            style = MaterialTheme.typography.caption2
                        )
                    }
                }
            }
            item {
                Chip(
                    onClick = { screen = Screen.Profiles },
                    colors = ChipDefaults.primaryChipColors(),
                    label = { Text(stringResource(R.string.watch_menu_profiles)) }
                )
            }
            item {
                Chip(
                    onClick = {
                        screen = Screen.Groups
                        loadGroups()
                    },
                    enabled = running,
                    colors = ChipDefaults.primaryChipColors(),
                    label = { Text(stringResource(R.string.watch_menu_proxy)) },
                    secondaryLabel = {
                        if (!running) {
                            Text(stringResource(R.string.watch_proxy_need_running))
                        }
                    }
                )
            }
            item {
                Chip(
                    onClick = { updateActiveProfile() },
                    enabled = !busy && profiles.any { it.active && it.type == Profile.Type.Url },
                    colors = ChipDefaults.primaryChipColors(),
                    label = { Text(stringResource(R.string.watch_menu_profiles_update)) }
                )
            }
            item {
                Chip(
                    onClick = { startActivity(MainActivity::class.intent) },
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text(stringResource(R.string.watch_menu_full_ui)) }
                )
            }
            item {
                Chip(
                    onClick = { screen = Screen.CrashLog },
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text(stringResource(R.string.watch_menu_crash_log)) }
                )
            }
            if (busy) {
                item { CircularProgressIndicator() }
            }
            message?.let { msg ->
                item {
                    Text(
                        text = msg,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption1
                    )
                }
            }
        }
    }

    @Composable
    private fun ProfilesScreen() {
        BackHandler {
            screen = Screen.Main
        }

        ScreenContent {
            item {
                Text(
                    text = stringResource(R.string.watch_menu_profiles),
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.watch_profiles_empty),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption1
                    )
                }
            }
            items(profiles.size) { index ->
                val p = profiles[index]

                Chip(
                    onClick = { selectProfile(p) },
                    colors = if (p.active) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
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
            item {
                Text(
                    text = stringResource(R.string.watch_menu_proxy),
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
            items(groups.size) { index ->
                val name = groups[index]

                Chip(
                    onClick = {
                        screen = Screen.Group(name)
                        loadGroup(name)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
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
            item {
                Text(
                    text = name,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
            group?.let { g ->
                items(g.proxies.size) { index ->
                    val proxy = g.proxies[index]

                    Chip(
                        onClick = { patchSelector(name, proxy.name) },
                        colors = if (proxy.name == g.now) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                        label = { Text(if (proxy.name == g.now) "✓ ${proxy.name}" else proxy.name) },
                        secondaryLabel = {
                            if (proxy.delay > 0) {
                                Text("${proxy.delay}ms")
                            }
                        }
                    )
                }
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
            item {
                Text(
                    text = stringResource(R.string.watch_menu_crash_log),
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
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
                    colors = ChipDefaults.primaryChipColors(),
                    label = { Text("刷新 / Refresh") }
                )
            }
            item {
                Text(
                    text = log ?: stringResource(R.string.watch_crash_log_hint),
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
