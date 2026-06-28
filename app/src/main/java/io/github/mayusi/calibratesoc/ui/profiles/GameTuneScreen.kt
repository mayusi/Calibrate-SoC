package io.github.mayusi.calibratesoc.ui.profiles

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.share.ShareableGameTune
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.SectionCard

// ─── Accent colour for this screen ────────────────────────────────────────────

private val TuneAccent = Color(0xFF6D5BF6)   // Arsenal indigo — consistent with app identity
private val ErrorRed   = Color(0xFFFF4D6D)   // AccentBar.Red equivalent

// ─── Screen colours (mirrors the rest of the dark Arsenal theme) ───────────────

private val SurfaceColor = Color(0xFF141419)
private val TileColor    = Color(0xFF0C0C10)
private val LabelGray    = Color(0xFF999999)

// ──────────────────────────────────────────────────────────────────────────────
// Public entry point
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen Game Tune hub with three sections reachable via a tab row:
 *
 *   0 · Share   — generate a CSOC2 code for this game's current tune
 *   1 · Import  — paste a CSOC2 code, preview settings, confirm import
 *   2 · Community — browse OTA-fetched community tunes filtered to this device
 *
 * The screen takes the *current* game context (packageName, bundle, profile) from
 * the caller; the ViewModel manages encode/decode/fetch without holding them.
 */
@Composable
fun GameTuneScreen(
    packageName: String,
    gameDisplayName: String,
    bundle: PerAppBundle?,
    profile: UserProfile?,
    currentDeviceKey: String?,
    viewModel: GameTuneViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val shareCode     by viewModel.shareCode.collectAsStateWithLifecycle()
    val importState   by viewModel.importState.collectAsStateWithLifecycle()
    val communityTunes by viewModel.communityTunes.collectAsStateWithLifecycle()
    val communityLoading by viewModel.communityLoading.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var importCode  by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Load community tunes on first composition; network refresh happens
    // concurrently so the list appears instantly from cache if available.
    LaunchedEffect(Unit) {
        viewModel.loadCommunityTunes(currentDeviceKey)
        viewModel.refreshCommunityTunes(currentDeviceKey)
    }

    // When "Preview & import" is tapped on a community tune, switch to Import tab.
    LaunchedEffect(importState) {
        if (importState is GameTuneImportState.Preview) {
            selectedTab = 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceColor)
            .padding(horizontal = 16.dp),
    ) {

        Spacer(Modifier.height(12.dp))

        // ── Screen title ──────────────────────────────────────────────────────
        Text(
            text = "Game Tunes",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = Color.White,
            ),
        )
        Text(
            text = gameDisplayName,
            style = MaterialTheme.typography.bodySmall,
            color = LabelGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(12.dp))

        // ── Tab row ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Share", "Import", "Community").forEachIndexed { index, label ->
                val active = selectedTab == index
                TextButton(
                    onClick = { selectedTab = index },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) TuneAccent else LabelGray,
                            letterSpacing = 0.8.sp,
                        ),
                    )
                }
            }
        }

        // Active-tab underline
        Row(modifier = Modifier.fillMaxWidth()) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (selectedTab == index) TuneAccent
                            else Color.White.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(1.dp),
                        ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Tab content ───────────────────────────────────────────────────────
        when (selectedTab) {
            0 -> ShareTab(
                packageName = packageName,
                gameDisplayName = gameDisplayName,
                bundle = bundle,
                profile = profile,
                shareCode = shareCode,
                viewModel = viewModel,
                onCopy = { code ->
                    clipboardManager.setText(AnnotatedString(code))
                },
                onSystemShare = { code ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, code)
                        putExtra(
                            Intent.EXTRA_SUBJECT,
                            "Calibrate SoC tune for $gameDisplayName",
                        )
                    }
                    context.startActivity(Intent.createChooser(intent, "Share game tune"))
                },
            )

            1 -> ImportTab(
                importCode = importCode,
                onImportCodeChange = { newCode ->
                    importCode = newCode
                    viewModel.decodeImportCode(newCode)
                },
                importState = importState,
                currentDeviceKey = currentDeviceKey,
                viewModel = viewModel,
                onImportSuccess = {
                    importCode = ""
                    viewModel.dismissImport()
                    onDone()
                },
                onImportError = { msg -> importError = msg },
            )

            2 -> CommunityTab(
                tunes = communityTunes,
                isLoading = communityLoading,
                onPreviewAndImport = { tune ->
                    viewModel.decodeImportCode(tune.tuneCode)
                    // LaunchedEffect on importState switches tab to Import once Preview lands.
                },
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Tab 0 — Share
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShareTab(
    packageName: String,
    gameDisplayName: String,
    bundle: PerAppBundle?,
    profile: UserProfile?,
    shareCode: String?,
    viewModel: GameTuneViewModel,
    onCopy: (String) -> Unit,
    onSystemShare: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ArsenalPanel(accent = TuneAccent, title = "Share this game's tune") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Generate a compact code that encodes all tuning settings for " +
                            "$gameDisplayName — AutoTDP goal, refresh rate, fan mode, game boost, " +
                            "and any CPU/GPU clock caps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabelGray,
                    )

                    Spacer(Modifier.height(4.dp))

                    if (bundle == null) {
                        AlertCard(
                            type = AlertType.WARNING,
                            title = "No tune configured",
                            message = "Configure settings for this game first — there's nothing to share yet.",
                        )
                    } else {
                        ArsenalButton(
                            label = "Generate tune code",
                            accent = TuneAccent,
                            style = ArsenalButtonStyle.Primary,
                            onClick = {
                                viewModel.generateShareCode(
                                    packageName = packageName,
                                    gameDisplayName = gameDisplayName,
                                    bundle = bundle,
                                    profile = profile,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (shareCode != null) {
            item {
                SectionCard(title = "Your tune code") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = shareCode,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Color.White,
                            ),
                            label = { Text("CSOC2 tune code") },
                            minLines = 3,
                            maxLines = 6,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ArsenalButton(
                                label = "Copy",
                                accent = TuneAccent,
                                style = ArsenalButtonStyle.Secondary,
                                onClick = { onCopy(shareCode) },
                                modifier = Modifier.weight(1f),
                            )
                            ArsenalButton(
                                label = "Share",
                                accent = AccentBar.Blue,
                                style = ArsenalButtonStyle.Secondary,
                                onClick = { onSystemShare(shareCode) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Text(
                            text = "Post this code on Discord or Reddit so others can apply the " +
                                "exact same settings for $gameDisplayName.",
                            style = MaterialTheme.typography.labelSmall,
                            color = LabelGray,
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Tab 1 — Import
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ImportTab(
    importCode: String,
    onImportCodeChange: (String) -> Unit,
    importState: GameTuneImportState,
    currentDeviceKey: String?,
    viewModel: GameTuneViewModel,
    onImportSuccess: () -> Unit,
    onImportError: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ArsenalPanel(accent = TuneAccent, title = "Import a tune code") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Paste a CSOC2 game tune code below. " +
                            "If a sysfs path fails validation we refuse the import and tell you exactly why.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabelGray,
                    )

                    OutlinedTextField(
                        value = importCode,
                        onValueChange = onImportCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Paste tune code here") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color.White,
                        ),
                        placeholder = { Text("CSOC2:…", color = LabelGray) },
                        minLines = 3,
                        maxLines = 6,
                        isError = importState is GameTuneImportState.Error ||
                            importState is GameTuneImportState.ValidationError,
                    )
                }
            }
        }

        // State-driven feedback cards
        when (val state = importState) {
            is GameTuneImportState.Error -> item {
                AlertCard(
                    type = AlertType.ERROR,
                    title = "Invalid code",
                    message = state.reason,
                )
            }

            is GameTuneImportState.ValidationError -> item {
                AlertCard(
                    type = AlertType.ERROR,
                    title = "Blocked: unsafe sysfs path",
                    message = "Path: ${state.path}\n${state.reason}",
                )
            }

            is GameTuneImportState.Preview -> {
                if (state.deviceMismatch) {
                    item {
                        AlertCard(
                            type = AlertType.WARNING,
                            title = "Device mismatch",
                            message = "This tune targets a different device family. " +
                                "CPU/GPU clock caps may not be valid on this hardware.",
                        )
                    }
                }

                item {
                    TunePreviewCard(tune = state.tune)
                }

                item {
                    ArsenalButton(
                        label = "Import this tune",
                        accent = TuneAccent,
                        style = ArsenalButtonStyle.Primary,
                        onClick = {
                            viewModel.confirmImport(
                                tune = state.tune,
                                currentDeviceKey = currentDeviceKey,
                                onResult = { error ->
                                    if (error == null) onImportSuccess()
                                    else onImportError(error)
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            GameTuneImportState.Idle -> { /* nothing to show */ }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Tab 2 — Community tunes
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun CommunityTab(
    tunes: List<CommunityTuneUiModel>,
    isLoading: Boolean,
    onPreviewAndImport: (CommunityTuneUiModel) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading && tunes.isEmpty() -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.Center),
                    color = TuneAccent,
                    strokeWidth = 3.dp,
                )
            }

            tunes.isEmpty() -> {
                EmptyState(
                    icon = Icons.Outlined.Groups,
                    title = "No community tunes yet",
                    body = "No community tunes for your device yet — be the first to share one!",
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isLoading) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = TuneAccent,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "Refreshing…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LabelGray,
                                )
                            }
                        }
                    }

                    items(tunes, key = { it.tuneCode }) { tune ->
                        CommunityTuneCard(
                            tune = tune,
                            onPreviewAndImport = { onPreviewAndImport(tune) },
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Reusable sub-composables
// ──────────────────────────────────────────────────────────────────────────────

/**
 * A detail card showing the decoded [ShareableGameTune] fields so the user
 * can inspect what they are about to import before confirming.
 */
@Composable
private fun TunePreviewCard(tune: ShareableGameTune) {
    SectionCard(title = tune.name) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (tune.description.isNotBlank()) {
                Text(
                    text = tune.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabelGray,
                )
                Spacer(Modifier.height(4.dp))
            }

            KvRow(label = "Game", value = tune.gameDisplayName)
            KvRow(label = "Package", value = tune.packageName)

            tune.autoTdpGoal?.let { goal ->
                KvRow(label = "AutoTDP goal", value = goal.name)
            }
            tune.refreshRateHz?.let { hz ->
                KvRow(label = "Refresh rate", value = "${hz.toInt()} Hz")
            }
            tune.fanMode?.let { mode ->
                KvRow(label = "Fan mode", value = mode.toString())
            }
            if (tune.gameBoostOnLaunch) {
                KvRow(label = "Game Boost on launch", value = "Enabled")
            }

            // Clock caps
            if (tune.cpuPolicyMaxKhz.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.06f),
                )
                Text(
                    text = "CPU max caps",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = LabelGray,
                        letterSpacing = 0.8.sp,
                    ),
                )
                tune.cpuPolicyMaxKhz.forEach { (policy, khz) ->
                    KvRow(label = "Policy $policy", value = "${khz / 1000} MHz")
                }
            }
            if (tune.cpuPolicyMinKhz.isNotEmpty()) {
                Text(
                    text = "CPU min caps",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = LabelGray,
                        letterSpacing = 0.8.sp,
                    ),
                )
                tune.cpuPolicyMinKhz.forEach { (policy, khz) ->
                    KvRow(label = "Policy $policy", value = "${khz / 1000} MHz")
                }
            }
            tune.gpuMaxHz?.let { hz ->
                KvRow(label = "GPU max", value = "${hz / 1_000_000} MHz")
            }
            tune.gpuMinHz?.let { hz ->
                KvRow(label = "GPU min", value = "${hz / 1_000_000} MHz")
            }
            tune.gpuGovernor?.let { gov ->
                KvRow(label = "GPU governor", value = gov)
            }

            // Extra sysfs knobs — shown for transparency so user sees what
            // paths are being imported (same honesty principle as preset import).
            if (tune.extraSysfs.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.06f),
                )
                Text(
                    text = "Additional knobs",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = LabelGray,
                        letterSpacing = 0.8.sp,
                    ),
                )
                tune.extraSysfs.forEach { (path, value) ->
                    KvRow(label = path.substringAfterLast('/'), value = value, explainer = path)
                }
            }

            // Device targeting hint
            tune.targetHandheldKeys?.takeIf { it.isNotEmpty() }?.let { keys ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.06f),
                )
                KvRow(label = "Targets", value = keys.joinToString(", "))
            }
        }
    }
}

/**
 * A single community tune list entry.
 */
@Composable
private fun CommunityTuneCard(
    tune: CommunityTuneUiModel,
    onPreviewAndImport: () -> Unit,
) {
    ArsenalPanel(accent = TuneAccent) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tune.gameDisplayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = tune.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = LabelGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onPreviewAndImport) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = "Preview & import",
                        tint = TuneAccent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (tune.authorHandle.isNotBlank()) {
                Text(
                    text = "by ${tune.authorHandle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LabelGray.copy(alpha = 0.7f),
                )
            }

            if (tune.notes.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tune.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(4.dp))

            ArsenalButton(
                label = "Preview & import",
                accent = TuneAccent,
                style = ArsenalButtonStyle.Secondary,
                onClick = onPreviewAndImport,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
