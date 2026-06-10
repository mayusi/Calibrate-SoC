package io.github.mayusi.calibratesoc.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mayusi.calibratesoc.BuildConfig
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Full-screen in-app changelog. Reads `assets/changelog.md` at runtime
 * and renders it with a lightweight markdown-ish parser:
 *   - `## ` → version header (titleMedium SemiBold, primary colour)
 *   - `### ` → sub-header (bodyMedium SemiBold, onSurface)
 *   - `- ` / `* ` → bullet (bodySmall, onSurfaceVariant, "• " prefix)
 *   - `---` → visual separator (skipped from display)
 *   - blank lines → small vertical spacer
 *   - anything else → bodySmall
 *
 * No external markdown library needed — the asset is controlled by us
 * and only uses these constructs.
 */
@Composable
fun WhatsNewScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val lines = remember {
        runCatching {
            context.assets.open("changelog.md")
                .bufferedReader()
                .readLines()
        }.getOrDefault(listOf("(changelog unavailable)"))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column {
                    Text(
                        "What's New",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Calibrate SoC ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.item))
        }

        items(lines) { line ->
            when {
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        line.removePrefix("## "),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                line.startsWith("### ") -> {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        line.removePrefix("### "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    Text(
                        "• " + line.drop(2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp),
                    )
                }
                line == "---" || line.isBlank() -> {
                    Spacer(Modifier.height(4.dp))
                }
                else -> {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
