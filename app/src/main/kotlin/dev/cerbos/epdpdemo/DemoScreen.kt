package dev.cerbos.epdpdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cerbos.epdp.BundleSource
import dev.cerbos.epdp.CerbosEmbeddedPDP
import dev.cerbos.epdp.CheckResult
import dev.cerbos.epdp.Effect
import dev.cerbos.epdp.ResourceIdentifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.DurationUnit

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun Instant.formatTime(): String = timeFormatter.format(atZone(ZoneId.systemDefault()))

/** Connects [DemoScreen] to the view model. */
@Composable
fun DemoRoute(viewModel: DemoViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.checkHealth() }
    DemoScreen(
        state = state,
        onScenarioChange = viewModel::updateScenario,
        onRunCheck = viewModel::runCheck,
        onApplySettings = viewModel::applySettings,
        onRetry = viewModel::retry,
        onRestart = viewModel::restart,
        onClearOfflineCache = viewModel::clearOfflineCache,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(
    state: DemoUiState,
    onScenarioChange: (DemoScenario) -> Unit,
    onRunCheck: () -> Unit,
    onApplySettings: (HubSettings) -> Unit,
    onRetry: () -> Unit,
    onRestart: () -> Unit,
    onClearOfflineCache: () -> Unit,
) {
    val pdp = state.pdp ?: CerbosEmbeddedPDP.State()
    val started = state.pdp != null
    var showsHubSettings by rememberSaveable { mutableStateOf(false) }
    var showsMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cerbos ePDP")
                        val server = pdp.server
                        val rule = "rule ${state.settings.ruleId}"
                        Text(
                            if (server != null) "Cerbos ${server.version} · $rule" else rule,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showsHubSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Cerbos Hub settings")
                    }
                    IconButton(onClick = { showsMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = showsMenu, onDismissRequest = { showsMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Restart engine") },
                            leadingIcon = {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            },
                            enabled = started && pdp.status != CerbosEmbeddedPDP.Status.Loading,
                            onClick = {
                                showsMenu = false
                                onRestart()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear offline cache") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            enabled = started,
                            onClick = {
                                showsMenu = false
                                onClearOfflineCache()
                            },
                        )
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StatusSection(started, pdp, settingsError = state.settingsError, onRetry = onRetry)
            }
            item {
                CheckSection(
                    scenario = state.scenario,
                    check = state.check,
                    isReady = pdp.isReady,
                    onScenarioChange = onScenarioChange,
                    onRunCheck = onRunCheck,
                )
            }
            if (pdp.logs.isNotEmpty()) {
                item { LogsSection(pdp.logs) }
            }
        }
    }

    if (showsHubSettings) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showsHubSettings = false }, sheetState = sheetState) {
            HubSettingsSheet(
                initial = state.settings,
                isLoading = pdp.status == CerbosEmbeddedPDP.Status.Loading,
                onCancel = { showsHubSettings = false },
                onApply = { settings ->
                    showsHubSettings = false
                    onApplySettings(settings)
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun LabeledContent(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun StatusSection(
    started: Boolean,
    state: CerbosEmbeddedPDP.State,
    settingsError: String?,
    onRetry: () -> Unit,
) {
    Column {
        SectionHeader("Embedded PDP")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (!started) {
                    StatusBanner(CerbosEmbeddedPDP.Status.Loading)
                } else {
                    StatusBanner(state.status)
                    val bundle = state.bundle
                    if (bundle != null) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        LabeledContent("Bundle", bundle.bundleId)
                        LabeledContent("Rule revision", bundle.ruleRevision)
                        LabeledContent(
                            "Loaded from",
                            if (bundle.source == BundleSource.CACHE) "Offline cache"
                            else "Cerbos Hub",
                        )
                        LabeledContent("Received", bundle.receivedAt.formatTime())
                    }
                    val update = state.lastPolicyUpdate
                    if (update != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Last update check", style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (update.succeeded) Icons.Default.CheckCircle
                                    else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (update.succeeded) Green else Orange,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    update.date.formatTime(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (settingsError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            settingsError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.status is CerbosEmbeddedPDP.Status.Failed) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRetry) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Try again")
                        }
                    }
                }
            }
        }
        SectionFooter(
            "Policies are downloaded from Cerbos Hub and evaluated on this device. The last bundle is cached so the app starts offline."
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckSection(
    scenario: DemoScenario,
    check: CheckUiState,
    isReady: Boolean,
    onScenarioChange: (DemoScenario) -> Unit,
    onRunCheck: () -> Unit,
) {
    Column {
        SectionHeader("Check")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = scenario.principalId,
                    onValueChange = { onScenarioChange(scenario.copy(principalId = it)) },
                    label = { Text("Principal ID") },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DemoScenario.ROLES.forEachIndexed { index, role ->
                        SegmentedButton(
                            selected = scenario.role == role,
                            onClick = { onScenarioChange(scenario.copy(role = role)) },
                            shape =
                                SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = DemoScenario.ROLES.size,
                                ),
                        ) {
                            Text(role)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Principal owns the resource", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = scenario.ownsResource,
                        onCheckedChange = { onScenarioChange(scenario.copy(ownsResource = it)) },
                    )
                }
                Button(
                    onClick = onRunCheck,
                    enabled = isReady && !check.isChecking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Check access to ${DemoScenario.RESOURCE_KIND} #1")
                    if (check.isChecking) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                val checkError = check.error
                if (checkError != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            checkError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                val lastResult = check.result
                if (lastResult != null) {
                    HorizontalDivider()
                    DemoScenario.ACTIONS.forEach { action -> EffectRow(action, lastResult) }
                    val duration = check.duration
                    if (duration != null) {
                        Text(
                            "Evaluated locally in ${duration.format()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        SectionFooter(
            "Runs checkResources against the loaded bundle. No network request is made for the decision."
        )
    }
}

private fun Duration.format(): String = "%.1f ms".format(toDouble(DurationUnit.MILLISECONDS))

@Composable
private fun LogsSection(logs: List<CerbosEmbeddedPDP.LogLine>) {
    Column {
        SectionHeader("Recent activity")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                logs.takeLast(15).asReversed().forEach { line ->
                    Column {
                        Text(
                            line.message,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                            color =
                                when (line.level) {
                                    CerbosEmbeddedPDP.LogLevel.ERROR ->
                                        MaterialTheme.colorScheme.error
                                    CerbosEmbeddedPDP.LogLevel.WARN -> Orange
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                        )
                        Text(
                            line.date.formatTime(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HubSettingsSheet(
    initial: HubSettings,
    isLoading: Boolean,
    onCancel: () -> Unit,
    onApply: (HubSettings) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var urlError by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cerbos Hub", style = MaterialTheme.typography.titleLarge)

        SectionHeader("Embedded PDP rule")
        OutlinedTextField(
            value = draft.ruleId,
            onValueChange = { draft = draft.copy(ruleId = it) },
            label = { Text("Rule ID") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.hubBaseUrl,
            onValueChange = {
                draft = draft.copy(hubBaseUrl = it)
                urlError = null
            },
            label = { Text("Hub URL") },
            isError = urlError != null,
            placeholder = { Text("https://api.cerbos.cloud") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        SectionFooter(
            "Copy the rule ID from the deployment's Embedded PDP rules tab in Cerbos Hub."
        )
        val urlProblem = urlError
        if (urlProblem != null) {
            Text(
                urlProblem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (isLoading) {
            Text(
                "Wait for the current load to finish before applying changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader("Client credentials (optional)")
        OutlinedTextField(
            value = draft.clientId,
            onValueChange = { draft = draft.copy(clientId = it) },
            label = { Text("Client ID") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.clientSecret,
            onValueChange = { draft = draft.copy(clientSecret = it) },
            label = { Text("Client secret") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        SectionFooter(
            "Only needed for rules with client-credential authentication. The secret is stored in the Android Keystore."
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val problem = HubSettings.hubUrlProblem(draft.hubBaseUrl)
                    if (problem != null) {
                        urlError = problem
                    } else {
                        onApply(draft)
                    }
                },
                enabled = draft.ruleId.isNotBlank() && !isLoading,
            ) {
                Text("Apply")
            }
        }
    }
}

private val Green = Color(0xFF2E7D32)
private val Orange = Color(0xFFEF6C00)

@Composable
private fun StatusBanner(status: CerbosEmbeddedPDP.Status) {
    val (icon, tint, title, detail) =
        when (status) {
            CerbosEmbeddedPDP.Status.Idle ->
                Banner(Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant, "Idle", null)
            CerbosEmbeddedPDP.Status.Loading ->
                Banner(
                    Icons.Default.Refresh,
                    MaterialTheme.colorScheme.primary,
                    "Loading policies…",
                    "Starting the engine and downloading the policy bundle",
                )
            CerbosEmbeddedPDP.Status.Ready ->
                Banner(
                    Icons.Default.CheckCircle,
                    Green,
                    "Ready",
                    "Decisions are evaluated on this device",
                )
            is CerbosEmbeddedPDP.Status.Failed ->
                Banner(
                    Icons.Default.Warning,
                    MaterialTheme.colorScheme.error,
                    "Failed",
                    status.error.message,
                )
        }
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (status == CerbosEmbeddedPDP.Status.Loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private data class Banner(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color,
    val title: String,
    val detail: String?,
)

@Composable
private fun EffectRow(action: String, result: CheckResult) {
    val effect = result.actions[action]
    val matchedPolicy = result.metadata?.actions?.get(action)?.matchedPolicy
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(action, style = MaterialTheme.typography.bodyMedium)
            if (matchedPolicy != null) {
                Text(
                    matchedPolicy,
                    style =
                        MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        EffectBadge(effect)
    }
}

@Composable
private fun EffectBadge(effect: Effect?) {
    val (icon, tint, title) =
        when (effect) {
            Effect.ALLOW -> Triple(Icons.Default.Check, Green, "Allow")
            Effect.DENY -> Triple(Icons.Default.Clear, MaterialTheme.colorScheme.error, "Deny")
            null ->
                Triple(Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant, "No result")
        }
    Surface(color = tint.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraLarge) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DemoScreenPreview() {
    val result =
        CheckResult(
            resource = ResourceIdentifier(DemoScenario.RESOURCE_KIND, "1"),
            actions =
                DemoScenario.ACTIONS.associateWith { Effect.ALLOW } + ("publish" to Effect.DENY),
        )
    DemoTheme {
        DemoScreen(
            state =
                DemoUiState(
                    pdp = CerbosEmbeddedPDP.State(status = CerbosEmbeddedPDP.Status.Ready),
                    check = CheckUiState(result = result, duration = 850.microseconds),
                ),
            onScenarioChange = {},
            onRunCheck = {},
            onApplySettings = {},
            onRetry = {},
            onRestart = {},
            onClearOfflineCache = {},
        )
    }
}
